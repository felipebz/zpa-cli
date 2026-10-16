package br.com.felipezorzo.zpa.cli

import br.com.felipezorzo.zpa.cli.config.BaseRuleCategory
import br.com.felipezorzo.zpa.cli.config.ConfigFile
import br.com.felipezorzo.zpa.cli.config.RuleConfiguration
import br.com.felipezorzo.zpa.cli.config.RuleLevel
import br.com.felipezorzo.zpa.cli.exporters.ConsoleExporter
import br.com.felipezorzo.zpa.cli.exporters.GenericIssueFormatExporter
import br.com.felipezorzo.zpa.cli.exporters.IssueExporter
import br.com.felipezorzo.zpa.cli.exporters.JsonExporter
import br.com.felipezorzo.zpa.cli.oracle.OracleMetadataLoader
import br.com.felipezorzo.zpa.cli.oracle.OracleSettings
import br.com.felipezorzo.zpa.cli.plugin.PluginManager
import br.com.felipezorzo.zpa.cli.rules.CliActiveRules
import br.com.felipezorzo.zpa.cli.typereport.TypeReport
import com.beust.jcommander.JCommander
import com.beust.jcommander.ParameterException
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.felipebz.zpa.CustomAnnotationBasedRulesDefinition
import com.felipebz.zpa.api.ZpaRulesDefinition
import com.felipebz.zpa.api.annotations.ZpaExperimentalApi
import com.felipebz.zpa.api.checks.PlSqlVisitor
import com.felipebz.zpa.api.project.ProjectExternalContext
import com.felipebz.zpa.api.project.ProjectExternalMetadata
import com.felipebz.zpa.api.project.ProjectIdentifier
import com.felipebz.zpa.checks.ParsingErrorCheck
import com.felipebz.zpa.metadata.FormsMetadata
import com.felipebz.zpa.oracle.metadata.OracleMetadataResult
import com.felipebz.zpa.project.*
import com.felipebz.zpa.rules.Repository
import com.felipebz.zpa.rules.RuleMetadataLoader
import com.felipebz.zpa.rules.ZpaChecks
import com.felipebz.zpa.squid.AstScanner
import com.felipebz.zpa.squid.ConditionalCompilationSettings
import com.felipebz.zpa.squid.PlSqlConfiguration
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
import kotlin.io.path.*
import kotlin.system.measureTimeMillis

const val CONSOLE = "console"
const val GENERIC_ISSUE_FORMAT = "sq-generic-issue-import"
const val JSON = "json"
@OptIn(ZpaExperimentalApi::class)
class Main(private val args: Arguments, private val environment: (String) -> String? = System::getenv) {

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

        // Database settings are validated before anything runs; no connection is made unless --oracle-url is given.
        val oracleSettings = OracleSettings.from(args, environment)
        val compilationSchema = OracleSettings.compilationSchema(args)
        // Applies in every mode (it changes what parses); the project index and the AST get the same value.
        val conditionalCompilation = conditionalCompilationSettings(args)
        if (conditionalCompilation.isActive) LOG.info("Conditional compilation: $conditionalCompilation")
        if (args.typeReport.isNotEmpty() && args.syntaxOnly) {
            throw CliValidationException("--type-report cannot be used with --syntax-only")
        }
        if (args.typeReport.isNotEmpty() && args.outputFile.isNotEmpty() &&
            File(args.typeReport).absoluteFile.normalize() == File(args.outputFile).absoluteFile.normalize()) {
            throw CliValidationException("--type-report must be a different file than --output-file")
        }

        var pluginManager: PluginManager? = null
        val appHome = applicationHome()

        if (!args.syntaxOnly) {

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

                LOG.info("Loading source files...")
                val metadata = if (args.syntaxOnly) null else FormsMetadata.loadFromFile(args.formsMetadata)

                val targetFiles: List<InputFile>
                val projectAnalysisContext: ProjectAnalysisContext
                val acquisition: OracleMetadataResult?

                if (args.syntaxOnly) {
                    projectAnalysisContext = ProjectAnalysisContext.NOT_PREPARED
                    acquisition = null
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
                    // Acquired once, before scanning; scanner workers only see the detached, immutable metadata.
                    acquisition = oracleSettings?.let { OracleMetadataLoader.load(it, appHome.resolve("drivers")) }
                    projectAnalysisContext = prepareProjectAnalysisContext(projectSources, acquisition?.metadata, compilationSchema, conditionalCompilation)
                }

                val progressReport = ProgressReport("Report about progress of code analyzer", TimeUnit.SECONDS.toMillis(10))
                progressReport.start(targetFiles.map { it.pathRelativeToBase }.toList())

                val scanner = AstScanner(
                    checkList,
                    metadata,
                    true,
                    StandardCharsets.UTF_8,
                    projectAnalysisContext,
                    conditionalCompilation
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

                if (args.typeReport.isNotEmpty()) {
                    val report = TypeReport.build(
                        projectAnalysisContext.createSnapshot(), projectAnalysisContext.externalContext, acquisition
                    )
                    TypeReport.write(report, File(args.typeReport))
                    LOG.info("Type report: ${report.references.size} references written to ${args.typeReport} ${report.summary}")
                }
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

    private fun applicationHome(): Path {
        val codePath = Path.of(Main::class.java.protectionDomain.codeSource.location.toURI())
        return if (codePath.extension == "jar" && (codePath.parent.name == "lib" || codePath.parent.name == "jars")) {
            codePath.parent.parent.absolute()
        } else {
            Path.of(".")
        }
    }


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

        /**
         * Parses `--ccflags` and `--database-version`; blank values mean not configured.
         *
         * @throws CliValidationException naming the invalid option
         */
        fun conditionalCompilationSettings(args: Arguments): ConditionalCompilationSettings {
            val flags = try {
                ConditionalCompilationSettings.parseFlags(args.ccflags)
            } catch (e: IllegalArgumentException) {
                throw CliValidationException("Invalid --ccflags: ${e.message}", e)
            }
            val version = try {
                args.databaseVersion.takeIf { it.isNotBlank() }?.let { ConditionalCompilationSettings.parseVersion(it) }
            } catch (e: IllegalArgumentException) {
                throw CliValidationException("Invalid --database-version: ${e.message}", e)
            }
            return ConditionalCompilationSettings(flags, version)
        }

        /**
         * Prepares the project index once from every project source and attaches the external context (Oracle
         * metadata and the default compilation schema) when either is configured; otherwise the context is the
         * plain prepared project, exactly as without Oracle support. Sources are lexed with [conditionalCompilation],
         * which must be the value given to the [AstScanner], so that the index and the AST select the same
         * conditional compilation branches.
         */
        fun prepareProjectAnalysisContext(
            files: Collection<InputFile>,
            metadata: ProjectExternalMetadata?,
            compilationSchema: ProjectIdentifier?,
            conditionalCompilation: ConditionalCompilationSettings = ConditionalCompilationSettings.NONE
        ): ProjectAnalysisContext {
            val prepared = ProjectAnalysisContext.prepared(
                ProjectIndexPreparation(
                    ProjectDeclarationExtractor(PlSqlConfiguration(StandardCharsets.UTF_8, true, conditionalCompilation))
                ).prepare(
                    files.map { file ->
                        ProjectSource(FileId(file.pathRelativeToBase), ProjectSourceReader { file.contents() })
                    }
                )
            )
            if (metadata == null && compilationSchema == null) return prepared
            return prepared.withExternalContext(
                ProjectExternalContext.of(metadata ?: ProjectExternalMetadata.none(), compilationSchema)
            )
        }
    }
}

fun execute(args: Array<String>, environment: (String) -> String? = System::getenv): Int {
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
        Main(arguments, environment).run()
    } catch (exception: ParameterException) {
        System.err.println(exception.message)
        val sb = StringBuilder()
        cmd.usage(sb)
        System.err.println(sb.toString())
        2
    } catch (exception: CliValidationException) {
        System.err.println(exception.message)
        2
    } catch (exception: CliExecutionException) {
        System.err.println(exception.message)
        3
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
