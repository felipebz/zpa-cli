package br.com.felipezorzo.zpa.cli

import br.com.felipezorzo.zpa.cli.repository.ActiveRules
import br.com.felipezorzo.zpa.cli.repository.Repository
import br.com.felipezorzo.zpa.cli.sqissue.GenericIssueData
import br.com.felipezorzo.zpa.cli.sqissue.PrimaryLocation
import br.com.felipezorzo.zpa.cli.sqissue.SecondaryLocation
import br.com.felipezorzo.zpa.cli.sqissue.TextRange
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.google.common.base.Stopwatch
import com.google.gson.Gson
import com.sonar.sslr.api.RecognitionException
import org.sonar.plsqlopen.CustomAnnotationBasedRulesDefinition
import org.sonar.plsqlopen.checks.CheckList
import org.sonar.plsqlopen.getSemanticNode
import org.sonar.plsqlopen.metadata.FormsMetadata
import org.sonar.plsqlopen.parser.PlSqlParser
import org.sonar.plsqlopen.rules.RuleMetadataLoader
import org.sonar.plsqlopen.rules.ZpaChecks
import org.sonar.plsqlopen.rules.ZpaRule
import org.sonar.plsqlopen.rules.ZpaRuleKey
import org.sonar.plsqlopen.squid.PlSqlAstWalker
import org.sonar.plsqlopen.squid.PlSqlConfiguration
import org.sonar.plsqlopen.symbols.DefaultTypeSolver
import org.sonar.plsqlopen.symbols.SymbolVisitor
import org.sonar.plugins.plsqlopen.api.PlSqlVisitorContext
import org.sonar.plugins.plsqlopen.api.checks.PlSqlCheck
import org.sonar.plugins.plsqlopen.api.checks.PlSqlVisitor
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Paths
import br.com.felipezorzo.zpa.cli.sqissue.Issue as GenericIssue

class Main : CliktCommand(name = "zpa-cli") {
    private val sources by option(help = "Folder with files").required()
    private val formsMetadata by option(help = "Oracle Forms metadata file").default("")
    private val extensions by option(help = "Extensions to analyze").default("sql,pkg,pks,pkb,fun,pcd,tgg,prc,tpb,trg,typ,tab,tps")
    private val output by option(help = "Output filename").default("zpa-issues.json")

    override fun run() {
        val extensions = extensions.split(',')

        val stopwatch = Stopwatch()
        stopwatch.start()

        val baseDir = File(sources).absoluteFile
        val baseDirPath = baseDir.toPath()

        val repository = Repository()
        val ruleMetadataLoader = RuleMetadataLoader()
        CustomAnnotationBasedRulesDefinition.load(repository, "zpa", CheckList.checks, ruleMetadataLoader)

        val activeRules = ActiveRules(repository)

        val checks = ZpaChecks<PlSqlVisitor>(activeRules, "zpa", ruleMetadataLoader)
                .addAnnotatedChecks(CheckList.checks)

        val files = baseDir
                .walkTopDown()
                .filter { it.isFile && extensions.contains(it.extension.toLowerCase()) }
                .map { InputFile(it, StandardCharsets.UTF_8) }
                .toList()

        val parser = PlSqlParser.create(PlSqlConfiguration(StandardCharsets.UTF_8))
        val metadata = FormsMetadata.loadFromFile(formsMetadata)

        println("Analyzing ${files.size} files...")

        val genericIssues = mutableListOf<GenericIssue>()

        for (file in files) {
            val relativeFilePath = baseDirPath.relativize(Paths.get(file.absolutePath))

            val relativeFilePathStr = relativeFilePath.toString().replace('\\', '/')

            val visitorContext = try {
                val tree = getSemanticNode(parser.parse(file.contents()))
                PlSqlVisitorContext(tree, file, metadata)
            } catch (e: RecognitionException) {
                PlSqlVisitorContext(file, e, metadata)
            }

            /*val defaultChecks = CheckList.checks
                    .map { it.getConstructor().newInstance() as PlSqlCheck }
                    .filter { metadata != null || it !is FormsMetadataAwareCheck }
                    .toTypedArray()*/

            val visitors = listOf(
                    SymbolVisitor(DefaultTypeSolver()),
                    *checks.all().toTypedArray())

            val walker = PlSqlAstWalker(visitors)
            walker.walk(visitorContext)

            for (visitor in visitors) {
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

                    genericIssues += GenericIssue(
                            ruleId = rule.key,
                            severity = rule.severity,
                            type = "BUG", // TODO load from the rule metadata
                            primaryLocation = primaryLocation,
                            effortMinutes = 1, // TODO load from the rule metadata'
                            secondaryLocations = secondaryLocations
                    )
                }
            }
        }

        val genericReport = GenericIssueData(genericIssues)

        val gson = Gson()

        val json = gson.toJson(genericReport)
        File(output).writeText(json)

        println("Time elapsed: ${stopwatch.elapsedMillis()} ms")
    }

    private fun createTextRange(startLine: Int, endLine: Int, startLineOffset: Int, endLineOffset: Int): TextRange {
        return TextRange(
                startLine = startLine,
                endLine = if (endLine > -1) endLine else null,
                startColumn = if (startLineOffset > -1) startLineOffset else null,
                endColumn = if (endLineOffset > -1) endLineOffset else null)
    }
}

fun main(args: Array<String>) = Main().main(args)
