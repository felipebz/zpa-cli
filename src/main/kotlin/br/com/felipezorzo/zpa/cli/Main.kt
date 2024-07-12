package br.com.felipezorzo.zpa.cli

import br.com.felipezorzo.zpa.cli.config.ConfigFile
import br.com.felipezorzo.zpa.cli.exporters.ConsoleExporter
import br.com.felipezorzo.zpa.cli.exporters.GenericIssueFormatExporter
import br.com.felipezorzo.zpa.cli.plugin.PluginManager
import com.beust.jcommander.JCommander
import com.beust.jcommander.ParameterException
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import me.lucko.jarrelocator.JarRelocator
import me.lucko.jarrelocator.Relocation
import org.sonar.plsqlopen.CustomAnnotationBasedRulesDefinition
import org.sonar.plsqlopen.metadata.FormsMetadata
import org.sonar.plsqlopen.rules.*
import org.sonar.plsqlopen.squid.AstScanner
import org.sonar.plsqlopen.squid.ProgressReport
import org.sonar.plsqlopen.utils.log.Loggers
import org.sonar.plugins.plsqlopen.api.PlSqlFile
import org.sonar.plugins.plsqlopen.api.ZpaRulesDefinition
import org.sonar.plugins.plsqlopen.api.checks.PlSqlVisitor
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
import kotlin.io.path.extension
import kotlin.io.path.listDirectoryEntries
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

        val tempDir = Files.createTempDirectory("zpa-cli")
        tempDir.toFile().deleteOnExit()

        val pluginRoot = appHome.resolve("plugins")
        pluginRoot.listDirectoryEntries("*.jar").forEach {
            val input = it.toFile()
            val output = tempDir.resolve(it.fileName).toFile()
            output.deleteOnExit()

            val rules: MutableList<Relocation> = ArrayList<Relocation>()
            rules.add(Relocation("org.sonar.plugins.plsqlopen.api.sslr", "com.felipebz.flr.api"))

            val relocator = JarRelocator(input, output, rules)
            try {
                relocator.run()
            } catch (e: IOException) {
                throw RuntimeException("Unable to relocate", e)
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

            val mapper = jacksonObjectMapper()

            val activeRules = ActiveRules()
            if (args.configFile.isNotEmpty()) {
                val configFile = File(args.configFile)
                val config = mapper.readValue(configFile, ConfigFile::class.java)
                activeRules.configureRules(config.rules.map {
                    var repositoryKey = "zpa"
                    var ruleKey = it.key
                    if (it.key.contains(':')) {
                        val keys = it.key.split(':')
                        repositoryKey = keys[0]
                        ruleKey = keys[1]
                    }

                    ActiveRuleConfiguration(
                        repositoryKey,
                        ruleKey,
                        it.value.options.level.toString(),
                        it.value.options.parameters
                    )
                })
            }

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
