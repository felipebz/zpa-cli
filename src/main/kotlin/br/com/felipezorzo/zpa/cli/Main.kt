package br.com.felipezorzo.zpa.cli

import br.com.felipezorzo.zpa.cli.config.BaseRuleCategory
import br.com.felipezorzo.zpa.cli.config.ConfigFile
import br.com.felipezorzo.zpa.cli.config.RuleConfiguration
import br.com.felipezorzo.zpa.cli.config.RuleLevel
import br.com.felipezorzo.zpa.cli.exporters.ConsoleExporter
import br.com.felipezorzo.zpa.cli.exporters.GenericIssueFormatExporter
import br.com.felipezorzo.zpa.cli.plugin.PluginManager
import com.beust.jcommander.JCommander
import com.beust.jcommander.ParameterException
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.felipebz.zpa.CustomAnnotationBasedRulesDefinition
import com.felipebz.zpa.api.PlSqlFile
import com.felipebz.zpa.api.ZpaRulesDefinition
import com.felipebz.zpa.api.checks.PlSqlVisitor
import com.felipebz.zpa.metadata.FormsMetadata
import com.felipebz.zpa.rules.ActiveRules
import com.felipebz.zpa.rules.Repository
import com.felipebz.zpa.rules.RuleMetadataLoader
import com.felipebz.zpa.rules.ZpaChecks
import com.felipebz.zpa.squid.AstScanner
import com.felipebz.zpa.squid.ProgressReport
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
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.system.measureTimeMillis

const val CONSOLE = "console"
const val GENERIC_ISSUE_FORMAT = "sq-generic-issue-import"

class Main(private val args: Arguments) {

    val mapper = jacksonObjectMapper()

    fun run() {
        javaClass.getResourceAsStream("/logging.properties").use {
            LogManager.getLogManager().readConfiguration(it)
        }

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

        val pluginManager = PluginManager(tempDir)
        pluginManager.loadPlugins()
        pluginManager.startPlugins()

        // print loaded plugins
        for (plugin in pluginManager.startedPlugins) {
            LOG.info("Plugin '${plugin.descriptor.pluginId}@${plugin.descriptor.version}' loaded")
        }

        val extensions = args.extensions.split(',')

        val issueExporter = when (args.outputFormat) {
            CONSOLE -> ConsoleExporter()
            GENERIC_ISSUE_FORMAT -> GenericIssueFormatExporter(args.outputFile)
            else -> throw IllegalArgumentException("Invalid output format")
        }

        val ellapsedTime = measureTimeMillis {

            val baseDir = File(args.sources).absoluteFile
            val baseDirPath = baseDir.toPath()

            val activeRules = getActiveRules()

            val ruleMetadataLoader = RuleMetadataLoader()

            val checkList = mutableListOf<PlSqlVisitor>()

            val rulesDefinitions = listOf(
                DefaultRulesDefinition(),
                *pluginManager.getExtensions(ZpaRulesDefinition::class.java).toTypedArray()
            )

            for (rulesDefinition in rulesDefinitions) {
                val repository = Repository(rulesDefinition.repositoryKey())
                CustomAnnotationBasedRulesDefinition.load(
                    repository, "plsqlopen",
                    rulesDefinition.checkClasses().toList(), ruleMetadataLoader
                )

                activeRules.addRepository(repository)

                val checks = ZpaChecks(activeRules, repository.key, ruleMetadataLoader)
                    .addAnnotatedChecks(rulesDefinition.checkClasses().toList())

                checkList.addAll(checks.all())
            }

            val files = baseDir
                .walkTopDown()
                .filter { it.isFile && extensions.contains(it.extension.lowercase(Locale.getDefault())) }
                .map { InputFile(PlSqlFile.Type.MAIN, baseDirPath, it, StandardCharsets.UTF_8) }
                .toList()

            val metadata = FormsMetadata.loadFromFile(args.formsMetadata)

            val progressReport = ProgressReport("Report about progress of code analyzer", TimeUnit.SECONDS.toMillis(10))
            progressReport.start(files.map { it.pathRelativeToBase }.toList())

            val scanner = AstScanner(checkList, metadata, true, StandardCharsets.UTF_8)

            val issues = files.parallelStream().flatMap { file ->
                val scannerResult = scanner.scanFile(file)
                progressReport.nextFile()
                scannerResult.issues.stream()
            }.collect(Collectors.toList())

            progressReport.stop()

            issueExporter.export(issues)
        }

        LOG.info("Time elapsed: $ellapsedTime ms")
        pluginManager.stopPlugins()
        pluginManager.unloadPlugins()
    }

    private fun getActiveRules(): ActiveRules {
        val activeRules = ActiveRules()
        val config = if (args.configFile.isNotEmpty()) {
            val configFile = File(args.configFile)
            mapper.readValue(configFile, ConfigFile::class.java)
        } else {
            ConfigFile()
        }

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

fun main(args: Array<String>) {
    val arguments = Arguments()
    val cmd = JCommander.newBuilder()
        .addObject(arguments)
        .programName("map-generator")
        .build()
    try {
        cmd.parse(*args)
        Main(arguments).run()
    } catch (exception: ParameterException) {
        println(exception.message)
        cmd.usage()
    }
}
