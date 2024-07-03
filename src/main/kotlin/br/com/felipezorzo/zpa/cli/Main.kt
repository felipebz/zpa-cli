package br.com.felipezorzo.zpa.cli

import br.com.felipezorzo.zpa.cli.plugin.PluginManager
import br.com.felipezorzo.zpa.cli.sqissue.GenericIssueData
import br.com.felipezorzo.zpa.cli.sqissue.PrimaryLocation
import br.com.felipezorzo.zpa.cli.sqissue.SecondaryLocation
import br.com.felipezorzo.zpa.cli.sqissue.TextRange
import com.beust.jcommander.JCommander
import com.beust.jcommander.ParameterException
import com.fasterxml.jackson.databind.ObjectMapper
import org.sonar.plsqlopen.CustomAnnotationBasedRulesDefinition
import org.sonar.plsqlopen.metadata.FormsMetadata
import org.sonar.plsqlopen.rules.ActiveRules
import org.sonar.plsqlopen.rules.Repository
import org.sonar.plsqlopen.rules.RuleMetadataLoader
import org.sonar.plsqlopen.rules.ZpaChecks
import org.sonar.plsqlopen.squid.AstScanner
import org.sonar.plsqlopen.squid.ProgressReport
import org.sonar.plsqlopen.squid.ZpaIssue
import org.sonar.plsqlopen.utils.log.Loggers
import org.sonar.plugins.plsqlopen.api.CustomPlSqlRulesDefinition
import org.sonar.plugins.plsqlopen.api.PlSqlFile
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
import br.com.felipezorzo.zpa.cli.sqissue.Issue as GenericIssue

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

        val ellapsedTime = measureTimeMillis {

            val baseDir = File(args.sources).absoluteFile
            val baseDirPath = baseDir.toPath()

            val activeRules = ActiveRules()
            // TODO: read the configuration from a file and call activeRules.configureRules with the list of rules

            val ruleMetadataLoader = RuleMetadataLoader()

            val checkList = mutableListOf<PlSqlVisitor>()

            val rulesDefinitions = listOf(
                DefaultRulesDefinition(),
                *pluginManager.getExtensions(CustomPlSqlRulesDefinition::class.java).toTypedArray()
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

            if (args.outputFormat == CONSOLE) {
                printIssues(issues)
            } else {
                val generatedOutput =
                    when (args.outputFormat) {
                        GENERIC_ISSUE_FORMAT -> {
                            exportToGenericIssueFormat(issues)
                        }
                        else -> {
                            ""
                        }
                    }

                val file = File(args.outputFile)
                file.parentFile?.mkdirs()
                file.writeText(generatedOutput)
            }
        }

        LOG.info("Time elapsed: $ellapsedTime ms")
        pluginManager.stopPlugins()
        pluginManager.unloadPlugins()
    }

    private fun printIssues(issues: List<ZpaIssue>) {
        for ((file, fileIssues) in issues.groupBy { (it.file as InputFile).pathRelativeToBase }.toSortedMap()) {
            println("File: $file")

            for (issue in fileIssues.sortedWith(compareBy({ it.primaryLocation.startLine() }, { it.primaryLocation.startLineOffset() }))) {
                val startLine = issue.primaryLocation.startLine()
                val startColumn = issue.primaryLocation.startLineOffset()
                val activeRule = issue.check.activeRule
                val severity = activeRule.severity

                var positionFormatted = "$startLine"
                if (startColumn != -1) {
                    positionFormatted += ":$startColumn"
                }
                println("${positionFormatted.padEnd(10)}${severity.padEnd(10)}${issue.primaryLocation.message()}")
            }

            println("")
        }
    }

    private fun exportToGenericIssueFormat(issues: List<ZpaIssue>): String {
        val genericIssues = mutableListOf<GenericIssue>()
        for (issue in issues) {
            val relativeFilePathStr = (issue.file as InputFile).pathRelativeToBase

            val issuePrimaryLocation = issue.primaryLocation

            val primaryLocation = PrimaryLocation(
                issuePrimaryLocation.message(),
                relativeFilePathStr,
                createTextRange(
                    issuePrimaryLocation.startLine(),
                    issuePrimaryLocation.endLine(),
                    issuePrimaryLocation.startLineOffset(),
                    issuePrimaryLocation.endLineOffset())
            )

            val secondaryLocations = mutableListOf<SecondaryLocation>()

            for (secondary in issue.secondaryLocations) {
                secondaryLocations += SecondaryLocation(
                    secondary.message(),
                    relativeFilePathStr,
                    createTextRange(
                        secondary.startLine(),
                        secondary.endLine(),
                        secondary.startLineOffset(),
                        secondary.endLineOffset())
                )
            }

            val activeRule = issue.check.activeRule

            val type = when {
                activeRule.tags.contains("vulnerability") -> "VULNERABILITY"
                activeRule.tags.contains("bug") -> "BUG"
                else -> "CODE_SMELL"
            }

            genericIssues += GenericIssue(
                ruleId = activeRule.ruleKey.toString(),
                severity = activeRule.severity,
                type = type,
                primaryLocation = primaryLocation,
                duration = activeRule.remediationConstant,
                secondaryLocations = secondaryLocations
            )
        }
        val genericReport = GenericIssueData(genericIssues)

        val mapper = ObjectMapper()
        return mapper.writeValueAsString(genericReport)
    }

    private fun createTextRange(startLine: Int, endLine: Int, startLineOffset: Int, endLineOffset: Int): TextRange {
        return TextRange(
                startLine = startLine,
                endLine = if (endLine > -1) endLine else null,
                startColumn = if (startLineOffset > -1) startLineOffset else null,
                endColumn = if (endLineOffset > -1) endLineOffset else null)
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
