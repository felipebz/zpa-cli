package br.com.felipezorzo.zpa.cli

import br.com.felipezorzo.zpa.cli.sonarqube.SonarQubeLoader
import br.com.felipezorzo.zpa.cli.sqissue.GenericIssueData
import br.com.felipezorzo.zpa.cli.sqissue.PrimaryLocation
import br.com.felipezorzo.zpa.cli.sqissue.SecondaryLocation
import br.com.felipezorzo.zpa.cli.sqissue.TextRange
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.groups.OptionGroup
import com.github.ajalt.clikt.parameters.groups.cooccurring
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice
import com.google.common.base.Stopwatch
import com.google.gson.Gson
import org.sonar.plsqlopen.CustomAnnotationBasedRulesDefinition
import org.sonar.plsqlopen.checks.CheckList
import org.sonar.plsqlopen.metadata.FormsMetadata
import org.sonar.plsqlopen.rules.*
import org.sonar.plsqlopen.squid.AstScanner
import org.sonar.plsqlopen.squid.ProgressReport
import org.sonar.plsqlopen.squid.ZpaIssue
import org.sonar.plsqlopen.utils.log.Loggers
import org.sonar.plugins.plsqlopen.api.PlSqlFile
import org.sonar.plugins.plsqlopen.api.checks.PlSqlVisitor
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.logging.LogManager
import java.util.stream.Collectors
import br.com.felipezorzo.zpa.cli.sqissue.Issue as GenericIssue

const val CONSOLE = "console"
const val GENERIC_ISSUE_FORMAT = "sq-generic-issue-import"
const val SONAR_REPORT_FORMAT = "sq-issue-report"

class SonarQubeOptions : OptionGroup() {
    val sonarqubeUrl by option(help = "SonarQube server URL").required()
    val sonarqubeToken by option(help = "The authentication token of a SonarQube user with Execute Analysis permission on the project.").default("")
    val sonarqubeKey by option(help = "The project's unique key on the SonarQube Server.").default("")
}

class Main : CliktCommand(name = "zpa-cli") {
    private val sources by option(help = "Folder with files").required()
    private val formsMetadata by option(help = "Oracle Forms metadata file").default("")
    private val extensions by option(help = "Extensions to analyze").default("sql,pkg,pks,pkb,fun,pcd,tgg,prc,tpb,trg,typ,tab,tps")
    private val outputFormat by option(help = "Format of the output file").choice(CONSOLE, GENERIC_ISSUE_FORMAT, SONAR_REPORT_FORMAT).default(CONSOLE)
    private val outputFile by option(help = "Output filename").default("")
    private val sonarqubeOptions by SonarQubeOptions().cooccurring()

    override fun run() {
        javaClass.getResourceAsStream("/logging.properties").use {
            LogManager.getLogManager().readConfiguration(it)
        }

        val extensions = extensions.split(',')

        val stopwatch = Stopwatch.createStarted()

        val baseDir = File(sources).absoluteFile
        val baseDirPath = baseDir.toPath()

        val repository = Repository("zpa")
        val ruleMetadataLoader = RuleMetadataLoader()
        CustomAnnotationBasedRulesDefinition.load(repository, "plsqlopen", CheckList.checks, ruleMetadataLoader)

        val activeRules = ActiveRules().addRepository(repository)

        val checks = ZpaChecks<PlSqlVisitor>(activeRules, "zpa", ruleMetadataLoader)
                .addAnnotatedChecks(CheckList.checks)

        val files = baseDir
                .walkTopDown()
                .filter { it.isFile && extensions.contains(it.extension.lowercase(Locale.getDefault())) }
                .map { InputFile(PlSqlFile.Type.MAIN, baseDirPath, it, StandardCharsets.UTF_8) }
                .toList()

        val metadata = FormsMetadata.loadFromFile(formsMetadata)

        val progressReport = ProgressReport("Report about progress of code analyzer", TimeUnit.SECONDS.toMillis(10))
        progressReport.start(files.map { it.pathRelativeToBase }.toList())

        val scanner = AstScanner(checks.all(), metadata, true, StandardCharsets.UTF_8)

        val issues = files.parallelStream().flatMap { file ->
            val scannerResult = scanner.scanFile(file)
            progressReport.nextFile()
            scannerResult.issues.stream()
        }.collect(Collectors.toList())

        progressReport.stop()

        if (outputFormat == CONSOLE) {
            printIssues(issues)
        } else {
            val generatedOutput =
                when (outputFormat) {
                    GENERIC_ISSUE_FORMAT -> {
                        exportToGenericIssueFormat(repository, checks, issues)
                    }
                    SONAR_REPORT_FORMAT -> {
                        sonarqubeOptions?.let {
                            if (it.sonarqubeUrl.isNotEmpty()) {
                                val issuesToExport = SonarQubeLoader(it).updateIssues(repository, checks, issues)
                                val gson = Gson()
                                gson.toJson(issuesToExport)
                            } else {
                                ""
                            }
                        }.orEmpty()
                    }
                    else -> {
                        ""
                    }
                }

            File(outputFile).writeText(generatedOutput)
        }

        LOG.info("Time elapsed: ${stopwatch.elapsed().toMillis()} ms")
    }

    private fun printIssues(issues: List<ZpaIssue>) {
        for ((file, fileIssues) in issues.groupBy { (it.file as InputFile).pathRelativeToBase }.toSortedMap()) {
            println("File: $file")

            for (issue in fileIssues.sortedWith(compareBy({ it.primaryLocation.startLine() }, { it.primaryLocation.startLineOffset() }))) {
                val startLine = issue.primaryLocation.startLine()
                val startColumn = issue.primaryLocation.startLineOffset()

                var positionFormatted = "$startLine"
                if (startColumn != -1) {
                    positionFormatted += ":$startColumn"
                }
                println("${positionFormatted.padEnd(10)}${issue.primaryLocation.message()}")
            }

            println("")
        }
    }

    private fun exportToGenericIssueFormat(
        repository: Repository,
        checks: ZpaChecks<PlSqlVisitor>,
        issues: List<ZpaIssue>
    ): String {
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

            val ruleKey = checks.ruleKey(issue.check) as ZpaRuleKey
            val rule = repository.rule(ruleKey.rule()) as ZpaRule

            val type = when {
                rule.tags.contains("vulnerability") -> "VULNERABILITY"
                rule.tags.contains("bug") -> "BUG"
                else -> "CODE_SMELL"
            }

            genericIssues += GenericIssue(
                ruleId = rule.key,
                severity = rule.severity,
                type = type,
                primaryLocation = primaryLocation,
                duration = rule.remediationConstant,
                secondaryLocations = secondaryLocations
            )
        }
        val genericReport = GenericIssueData(genericIssues)

        val gson = Gson()
        return gson.toJson(genericReport)
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

fun main(args: Array<String>) = Main().main(args)
