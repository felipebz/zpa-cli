package br.com.felipezorzo.zpa.cli

import br.com.felipezorzo.zpa.cli.config.BaseRuleCategory
import br.com.felipezorzo.zpa.cli.config.ConfigFile
import br.com.felipezorzo.zpa.cli.config.RuleConfiguration
import br.com.felipezorzo.zpa.cli.config.RuleLevel
import br.com.felipezorzo.zpa.cli.exporters.ConsoleExporter
import br.com.felipezorzo.zpa.cli.exporters.GenericIssueFormatExporter
import br.com.felipezorzo.zpa.cli.exporters.IssueExporter
import br.com.felipezorzo.zpa.cli.exporters.JsonExporter
import br.com.felipezorzo.zpa.cli.plugin.PluginManager
import br.com.felipezorzo.zpa.cli.rules.CliActiveRules
import com.beust.jcommander.JCommander
import com.beust.jcommander.ParameterException
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.felipebz.zpa.CustomAnnotationBasedRulesDefinition
import com.felipebz.zpa.api.PlSqlFile
import com.felipebz.zpa.api.ZpaRulesDefinition
import com.felipebz.zpa.api.checks.PlSqlVisitor
import com.felipebz.zpa.checks.ParsingErrorCheck
import com.felipebz.zpa.metadata.FormsMetadata
import com.felipebz.zpa.project.FileId
import com.felipebz.zpa.project.ProjectAnalysisContext
import com.felipebz.zpa.project.ProjectIndexPreparation
import com.felipebz.zpa.project.ProjectSource
import com.felipebz.zpa.project.ProjectSourceReader
import com.felipebz.zpa.rules.Repository
import com.felipebz.zpa.rules.RuleMetadataLoader
import com.felipebz.zpa.rules.ZpaChecks
import com.felipebz.zpa.squid.AstScanner
import com.felipebz.zpa.squid.ProgressReport
import com.felipebz.zpa.squid.ZpaIssue
import com.felipebz.zpa.utils.log.Loggers
import me.lucko.jarrelocator.JarRelocator
import me.lucko.jarrelocator.Relocation
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.logging.LogManager
import java.util.stream.Collectors
import kotlin.io.path.absolute
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.system.measureTimeMillis

const val CONSOLE = "console"
const val GENERIC_ISSUE_FORMAT = "sq-generic-issue-import"
const val JSON = "json"
class Main(private val args: Arguments) {

    val mapper = jacksonObjectMapper()

    fun run(): Int {
        javaClass.getResourceAsStream("/logging.properties").use {
            LogManager.getLogManager().readConfiguration(it)
        }

        val format = args.outputFormat.lowercase(Locale.ROOT)
        if (format != CONSOLE && format != GENERIC_ISSUE_FORMAT && format != JSON) {
            throw CliValidationException("Invalid output format: '${args.outputFormat}'. Supported formats: $CONSOLE, $GENERIC_ISSUE_FORMAT, $JSON")
        }

        val failOnThreshold = if (args.failOn != null) {
            FailOnThreshold.fromString(args.failOn!!)
        } else if (args.syntaxOnly) {
            FailOnThreshold.SYNTAX
        } else {
            FailOnThreshold.NONE
        }
        val config = loadConfigFile()

        val baseDir = File(args.sources).absoluteFile
        if (!baseDir.exists() || !baseDir.isDirectory) {
            throw CliValidationException("Sources folder does not exist or is not a directory: ${args.sources}")
        }
        val baseDirPath = baseDir.toPath().normalize()

        val extensions = args.extensions.split(',').map { it.trim().lowercase(Locale.ROOT) }
        val sourceSelection = SourceSelection(baseDirPath, extensions, StandardCharsets.UTF_8)

        if (args.stdinFilename.isNotEmpty() && !args.files.contains("-")) {
            throw CliValidationException("--stdin-filename can only be used when reading from standard input ('--files -')")
        }
        if (args.files.contains("-") && !args.syntaxOnly) {
            throw CliValidationException("Standard input ('--files -') is currently supported only with --syntax-only. Project-aware semantic analysis requires project-overlay support which is deferred.")
        }

        var pluginManager: PluginManager? = null

        if (!args.syntaxOnly) {
            val codePath = Path.of(Main::class.java.protectionDomain.codeSource.location.toURI())
            val appHome = if (codePath.extension == "jar" && (codePath.parent.name == "lib" || codePath.parent.name == "jars")) {
                codePath.parent.parent.absolute()
            } else {
                Path.of(".")
            }

            val tempDir = Files.createTempDirectory("zpa-cli")
            tempDir.toFile().deleteOnExit()

            val pluginRoot = appHome.resolve("plugins")
            if (pluginRoot.exists()) {
                pluginRoot.listDirectoryEntries("*.jar").forEach {
                    val input = it.toFile()
                    val output = tempDir.resolve(it.fileName).toFile()
                    output.deleteOnExit()

                    val rules: MutableList<Relocation> = ArrayList<Relocation>()
                    rules.add(Relocation("org.sonar.plugins.plsqlopen.api.sslr", "com.felipebz.flr.api"))
                    rules.add(Relocation("org.sonar.plugins.plsqlopen.api", "com.felipebz.zpa.api"))

                    val relocator = JarRelocator(input, output, rules)
                    try {
                        relocator.run()
                    } catch (e: IOException) {
                        throw RuntimeException("Unable to relocate", e)
                    }
                }
            }

            pluginManager = PluginManager(tempDir)
        }

        var validationFailed = false

        try {
            if (pluginManager != null) {
                pluginManager.loadPlugins()
                pluginManager.startPlugins()

                for (plugin in pluginManager.startedPlugins) {
                    LOG.info("Plugin '${plugin.descriptor.pluginId}@${plugin.descriptor.version}' loaded")
                }
            }

            val ellapsedTime = measureTimeMillis {
                val ruleMetadataLoader = RuleMetadataLoader()

                val checkList = mutableListOf<PlSqlVisitor>()

                if (args.syntaxOnly) {
                    val repository = Repository("zpa")
                    CustomAnnotationBasedRulesDefinition.load(
                        repository, "plsqlopen",
                        listOf(ParsingErrorCheck::class.java), ruleMetadataLoader
                    )
                    val activeRules = CliActiveRules(ConfigFile(base = BaseRuleCategory.DEFAULT))
                    activeRules.addRepository(repository)
                    val checks = ZpaChecks(activeRules, repository.key, ruleMetadataLoader)
                        .addAnnotatedChecks(listOf(ParsingErrorCheck::class.java))
                    checkList.addAll(checks.all())
                } else {
                    val activeRules = getActiveRules(config)

                    val rulesDefinitions = listOf(
                        DefaultRulesDefinition(),
                        *pluginManager!!.getExtensions(ZpaRulesDefinition::class.java).toTypedArray()
                    )

                    val repositories = rulesDefinitions.map { rulesDefinition ->
                        val repository = Repository(rulesDefinition.repositoryKey())
                        CustomAnnotationBasedRulesDefinition.load(
                            repository, "plsqlopen",
                            rulesDefinition.checkClasses().toList(), ruleMetadataLoader
                        )

                        activeRules.addRepository(repository)
                        repository
                    }

                    try {
                        activeRules.validateConfiguration()
                    } catch (e: IllegalArgumentException) {
                        throw CliValidationException("Invalid configuration: ${e.message}", e)
                    }

                    for ((rulesDefinition, repository) in rulesDefinitions.zip(repositories)) {
                        val checks = ZpaChecks(activeRules, repository.key, ruleMetadataLoader)
                            .addAnnotatedChecks(rulesDefinition.checkClasses().toList())

                        checkList.addAll(checks.all())
                    }
                }

                val metadata = if (args.syntaxOnly) null else FormsMetadata.loadFromFile(args.formsMetadata)

                val targetFiles: List<InputFile>
                val projectAnalysisContext: ProjectAnalysisContext

                if (args.syntaxOnly) {
                    projectAnalysisContext = ProjectAnalysisContext.NOT_PREPARED
                    targetFiles = sourceSelection.resolveSyntaxOnlyTargets(
                        requestedFiles = args.files,
                        stdinFilename = args.stdinFilename
                    )
                } else {
                    val projectSources = sourceSelection.discoverProjectSources()
                    targetFiles = sourceSelection.resolveProjectTargets(
                        projectSources = projectSources,
                        requestedFiles = args.files
                    )
                    projectAnalysisContext = prepareProjectAnalysisContext(projectSources)
                }

                val progressReport = ProgressReport("Report about progress of code analyzer", TimeUnit.SECONDS.toMillis(10))
                progressReport.start(targetFiles.map { it.pathRelativeToBase }.toList())

                val scanner = AstScanner(
                    checkList,
                    metadata,
                    true,
                    StandardCharsets.UTF_8,
                    projectAnalysisContext
                )

                val rawIssues: List<ZpaIssue>
                var scanSucceeded = false
                try {
                    rawIssues = targetFiles.parallelStream().flatMap { file ->
                        val scannerResult = scanner.scanFile(file, fileId = FileId(file.pathRelativeToBase))
                        progressReport.nextFile()
                        scannerResult.issues.stream()
                    }.collect(Collectors.toList())
                    scanSucceeded = true
                } finally {
                    if (scanSucceeded) {
                        progressReport.stop()
                    } else {
                        progressReport.cancel()
                    }
                }

                val issues = IssueOrdering.sort(rawIssues)

                validationFailed = failOnThreshold.hasFailure(issues)

                val issueExporter: IssueExporter = when (format) {
                    CONSOLE -> ConsoleExporter()
                    GENERIC_ISSUE_FORMAT -> GenericIssueFormatExporter(args.outputFile)
                    JSON -> JsonExporter(
                        outputFile = args.outputFile,
                        validationFailed = validationFailed,
                        threshold = failOnThreshold.cliName
                    )
                    else -> throw CliValidationException("Invalid output format: '${args.outputFormat}'")
                }

                issueExporter.export(issues)
            }

            LOG.info("Time elapsed: $ellapsedTime ms")
        } finally {
            if (pluginManager != null) {
                try {
                    pluginManager.stopPlugins()
                } catch (e: Exception) {
                    LOG.warn("Failed to stop plugins: ${e.message}")
                }
                try {
                    pluginManager.unloadPlugins()
                } catch (e: Exception) {
                    LOG.warn("Failed to unload plugins: ${e.message}")
                }
            }
        }

        return if (validationFailed) 1 else 0
    }

    private fun prepareProjectAnalysisContext(files: Collection<InputFile>): ProjectAnalysisContext =
        ProjectAnalysisContext.prepared(
            ProjectIndexPreparation().prepare(
                files.map { file ->
                    ProjectSource(FileId(file.pathRelativeToBase), ProjectSourceReader { file.contents() })
                }
            )
        )

    private fun loadConfigFile(): ConfigFile {
        if (args.configFile.isEmpty()) {
            return ConfigFile()
        }
        val configFile = File(args.configFile)
        if (!configFile.exists()) {
            throw CliValidationException(
                "Configuration file does not exist: ${args.configFile}",
                java.io.FileNotFoundException("Configuration file not found: ${configFile.absolutePath}")
            )
        }
        if (!configFile.isFile) {
            throw CliValidationException(
                "Configuration file is not a file: ${args.configFile}",
                IOException("Configuration path is not a regular file: ${configFile.absolutePath}")
            )
        }
        return try {
            mapper.readValue(configFile, ConfigFile::class.java)
        } catch (e: com.fasterxml.jackson.core.JsonProcessingException) {
            throw CliValidationException("Failed to parse configuration file '${args.configFile}': ${e.message}", e)
        } catch (e: IOException) {
            throw CliValidationException("Failed to read configuration file '${args.configFile}': ${e.message}", e)
        }
    }

    private fun getActiveRules(config: ConfigFile): CliActiveRules {
        val activeRules = CliActiveRules(config)

        if (config.rules.isNotEmpty()) {
            activeRules.addRuleConfigurer { repo, rule, configuration ->
                var ruleConfig = config.rules["${repo.key}:${rule.key}"] ?: config.rules[rule.key]
                if (config.base == BaseRuleCategory.DEFAULT && rule.isActivatedByDefault) {
                    ruleConfig = ruleConfig ?: RuleConfiguration()
                }

                if (ruleConfig == null || ruleConfig.options.level == RuleLevel.OFF) {
                    return@addRuleConfigurer false
                }

                if (ruleConfig.options.level != RuleLevel.ON) {
                    configuration.severity = ruleConfig.options.level.toString()
                }
                configuration.parameters.putAll(ruleConfig.options.parameters)
                true
            }
        }
        return activeRules
    }

    companion object {
        val LOG = Loggers.getLogger(Main::class.java)
    }
}

fun execute(args: Array<String>): Int {
    val arguments = Arguments()
    val cmd = JCommander.newBuilder()
        .addObject(arguments)
        .programName("zpa-cli")
        .build()
    return try {
        cmd.parse(*args)
        if (arguments.help) {
            val sb = StringBuilder()
            cmd.usage(sb)
            println(sb.toString())
            return 0
        }
        Main(arguments).run()
    } catch (exception: ParameterException) {
        System.err.println(exception.message)
        val sb = StringBuilder()
        cmd.usage(sb)
        System.err.println(sb.toString())
        2
    } catch (exception: CliValidationException) {
        System.err.println(exception.message)
        2
    } catch (exception: Exception) {
        System.err.println("Execution failed: ${exception.message}")
        exception.printStackTrace(System.err)
        3
    }
}

fun main(args: Array<String>) {
    val exitCode = execute(args)
    if (exitCode != 0) {
        kotlin.system.exitProcess(exitCode)
    }
}
