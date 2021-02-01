package br.com.felipezorzo.zpa.cli

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
import com.google.common.base.Stopwatch
import com.google.gson.Gson
import org.sonar.plsqlopen.CustomAnnotationBasedRulesDefinition
import org.sonar.plsqlopen.checks.CheckList
import org.sonar.plsqlopen.metadata.FormsMetadata
import org.sonar.plsqlopen.rules.*
import org.sonar.plsqlopen.squid.AstScanner
import org.sonar.plsqlopen.squid.ProgressReport
import org.sonar.plsqlopen.utils.log.Loggers
import org.sonar.plugins.plsqlopen.api.PlSqlFile
import org.sonar.plugins.plsqlopen.api.checks.PlSqlCheck
import org.sonar.plugins.plsqlopen.api.checks.PlSqlVisitor
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.logging.LogManager
import br.com.felipezorzo.zpa.cli.sqissue.Issue as GenericIssue

class SonarQubeOptions : OptionGroup() {
    val sonarqubeUrl by option(help = "SonarQube server URL").required()
    val sonarqubeToken by option(help = "The authentication token of a SonarQube user with Execute Analysis permission on the project.").default("")
    val sonarqubeKey by option(help = "The project's unique key on the SonarQube Server.").default("")
}

class Main : CliktCommand(name = "zpa-cli") {
    private val sources by option(help = "Folder with files").required()
    private val formsMetadata by option(help = "Oracle Forms metadata file").default("")
    private val extensions by option(help = "Extensions to analyze").default("sql,pkg,pks,pkb,fun,pcd,tgg,prc,tpb,trg,typ,tab,tps")
    private val output by option(help = "Output filename").default("zpa-issues.json")
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
        CustomAnnotationBasedRulesDefinition.load(repository, "zpa", CheckList.checks, ruleMetadataLoader)

        val activeRules = ActiveRules().addRepository(repository)

        val checks = ZpaChecks<PlSqlVisitor>(activeRules, "zpa", ruleMetadataLoader)
                .addAnnotatedChecks(CheckList.checks)

        val files = baseDir
                .walkTopDown()
                .filter { it.isFile && extensions.contains(it.extension.toLowerCase()) }
                .map { InputFile(PlSqlFile.Type.MAIN, baseDirPath, it, StandardCharsets.UTF_8) }
                .toList()

        val metadata = FormsMetadata.loadFromFile(formsMetadata)

        val genericIssues = mutableListOf<GenericIssue>()

        val progressReport = ProgressReport("Report about progress of code analyzer", TimeUnit.SECONDS.toMillis(10))
        progressReport.start(files.map { it.pathRelativeToBase }.toList())

        val scanner = AstScanner(checks.all(), metadata, true, StandardCharsets.UTF_8)
        for (file in files) {
            val relativeFilePathStr = file.pathRelativeToBase.replace('\\', '/')

            val result = scanner.scanFile(file)

            for (visitor in result.executedChecks) {
                for (issue in (visitor as PlSqlCheck).issues()) {
                    val issuePrimaryLocation = issue.primaryLocation()

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

                    for (secondary in issue.secondaryLocations()) {
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

                    val ruleKey = checks.ruleKey(visitor) as ZpaRuleKey
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
            }
            progressReport.nextFile()
        }
        progressReport.stop()

        val genericReport = GenericIssueData(genericIssues)

        val gson = Gson()

        val json = gson.toJson(genericReport)
        File(output).writeText(json)

        LOG.info("Time elapsed: ${stopwatch.elapsed().toMillis()} ms")
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
