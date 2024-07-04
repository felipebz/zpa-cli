package br.com.felipezorzo.zpa.cli

import br.com.felipezorzo.zpa.cli.exporters.ConsoleExporter
import br.com.felipezorzo.zpa.cli.exporters.GenericIssueFormatExporter
import br.com.felipezorzo.zpa.cli.plugin.PluginManager
import com.beust.jcommander.JCommander
import com.beust.jcommander.ParameterException
import org.sonar.plsqlopen.CustomAnnotationBasedRulesDefinition
import org.sonar.plsqlopen.metadata.FormsMetadata
import org.sonar.plsqlopen.rules.ActiveRules
import org.sonar.plsqlopen.rules.Repository
import org.sonar.plsqlopen.rules.RuleMetadataLoader
import org.sonar.plsqlopen.rules.ZpaChecks
import org.sonar.plsqlopen.squid.AstScanner
import org.sonar.plsqlopen.squid.ProgressReport
import org.sonar.plsqlopen.utils.log.Loggers
import org.sonar.plugins.plsqlopen.api.PlSqlFile
import org.sonar.plugins.plsqlopen.api.ZpaRulesDefinition
import org.sonar.plugins.plsqlopen.api.checks.PlSqlVisitor
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.logging.LogManager
import java.util.stream.Collectors
import kotlin.io.path.absolute
import kotlin.io.path.extension
import kotlin.io.path.name
import kotlin.system.measureTimeMillis

const val CONSOLE = "console"
const val GENERIC_ISSUE_FORMAT = "sq-generic-issue-import"

class Main(private val args: Arguments) {

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

        val pluginManager = PluginManager(appHome.resolve("plugins"))
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

            val activeRules = ActiveRules()
            // TODO: read the configuration from a file and call activeRules.configureRules with the list of rules

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
