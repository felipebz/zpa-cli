package br.com.felipezorzo.zpa.cli

import br.com.felipezorzo.zpa.cli.config.BaseRuleCategory
import br.com.felipezorzo.zpa.cli.config.ConfigFile
import br.com.felipezorzo.zpa.cli.rules.CliActiveRules
import com.felipebz.flr.api.AstNode
import com.felipebz.zpa.CustomAnnotationBasedRulesDefinition
import com.felipebz.zpa.api.PlSqlFile
import com.felipebz.zpa.api.annotations.ActivatedByDefault
import com.felipebz.zpa.api.annotations.Rule
import com.felipebz.zpa.api.checks.PlSqlCheck
import com.felipebz.zpa.project.FileId
import com.felipebz.zpa.project.ProjectAnalysisContext
import com.felipebz.zpa.project.ProjectIndexPreparation
import com.felipebz.zpa.project.ProjectSource
import com.felipebz.zpa.project.ProjectSourceReader
import com.felipebz.zpa.rules.Repository
import com.felipebz.zpa.rules.RuleMetadataLoader
import com.felipebz.zpa.rules.ZpaChecks
import com.felipebz.zpa.squid.AstScanner
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IssueOrderingTest {

    @Rule(key = "RuleAlpha")
    @ActivatedByDefault
    class AlphaCheck : PlSqlCheck() {
        override fun visitFile(node: AstNode) {
            addIssue(node, "shared message")
        }
    }

    @Rule(key = "RuleBeta")
    @ActivatedByDefault
    class BetaCheck : PlSqlCheck() {
        override fun visitFile(node: AstNode) {
            addIssue(node, "shared message")
        }
    }

    @Rule(key = "RuleMessage")
    @ActivatedByDefault
    class MessageCheck : PlSqlCheck() {
        override fun visitFile(node: AstNode) {
            addIssue(node, "zebra message")
            addIssue(node, "apple message")
        }
    }

    @Rule(key = "RuleEndLine")
    @ActivatedByDefault
    class EndLineCheck : PlSqlCheck() {
        override fun visitFile(node: AstNode) {
            addIssue(node, "multi-line statement")
            addIssue(node.token, "single-token statement head")
        }
    }

    @Test
    fun exercisesFileStartLineStartColumnAndEndColumnTieBreakers() {
        val root = Files.createTempDirectory("zpa-issue-ordering-test").toFile()
        try {
            val specFile = root.resolve("types.pks").apply {
                writeText(
                    """
                    CREATE OR REPLACE PACKAGE types AS
                      PROCEDURE run(p1 IN OUT NOCOPY CLOB, p2 IN NUMBER);
                    END types;
                    """.trimIndent()
                )
            }
            val bodyFile = root.resolve("types.pkb").apply {
                writeText(
                    """
                    CREATE OR REPLACE PACKAGE BODY types AS
                      PROCEDURE run(p1 IN OUT CLOB, p2 IN NUMBER) IS
                      BEGIN
                        SELECT * FROM dual;
                        SELECT * FROM dual;
                        NULL;
                      END run;
                    END types;
                    """.trimIndent()
                )
            }
            val otherFile = root.resolve("other.sql").apply {
                writeText(
                    """
                    CREATE OR REPLACE PROCEDURE other_proc IS
                    BEGIN
                      SELECT * FROM dual;
                    END other_proc;
                    """.trimIndent()
                )
            }

            val ruleMetadataLoader = RuleMetadataLoader()
            val rulesDefinition = DefaultRulesDefinition()
            val repository = Repository(rulesDefinition.repositoryKey())
            CustomAnnotationBasedRulesDefinition.load(
                repository, "plsqlopen",
                rulesDefinition.checkClasses().toList(), ruleMetadataLoader
            )
            val activeRules = CliActiveRules(ConfigFile(base = BaseRuleCategory.DEFAULT))
            activeRules.addRepository(repository)
            val checks = ZpaChecks(activeRules, repository.key, ruleMetadataLoader)
                .addAnnotatedChecks(rulesDefinition.checkClasses().toList())

            val inputSpec = InputFile(PlSqlFile.Type.MAIN, root.toPath(), specFile, StandardCharsets.UTF_8)
            val inputBody = InputFile(PlSqlFile.Type.MAIN, root.toPath(), bodyFile, StandardCharsets.UTF_8)
            val inputOther = InputFile(PlSqlFile.Type.MAIN, root.toPath(), otherFile, StandardCharsets.UTF_8)

            val context = ProjectAnalysisContext.prepared(
                ProjectIndexPreparation().prepare(
                    listOf(inputSpec, inputBody, inputOther).map { file ->
                        ProjectSource(FileId(file.pathRelativeToBase), ProjectSourceReader { file.contents() })
                    }
                )
            )

            val scanner = AstScanner(
                checks.all(),
                null,
                true,
                StandardCharsets.UTF_8,
                context
            )

            val bodyIssues = scanner.scanFile(inputBody, fileId = FileId("types.pkb")).issues
            val otherIssues = scanner.scanFile(inputOther, fileId = FileId("other.sql")).issues
            val allIssues = bodyIssues + otherIssues

            val sorted = IssueOrdering.sort(allIssues)

            // 1. FILE dimension: "other.sql" strictly precedes "types.pkb"
            val otherIssuesSorted = sorted.filter { (it.file as InputFile).pathRelativeToBase == "other.sql" }
            val bodyIssuesSorted = sorted.filter { (it.file as InputFile).pathRelativeToBase == "types.pkb" }
            assertEquals(2, otherIssuesSorted.size)
            assertEquals(8, bodyIssuesSorted.size)
            assertTrue(
                sorted.indexOf(otherIssuesSorted.last()) < sorted.indexOf(bodyIssuesSorted.first()),
                "File tie-breaker: 'other.sql' must strictly precede 'types.pkb'"
            )

            // 2. START LINE dimension: line 2 issues strictly precede line 4 issues in types.pkb
            val line2Issues = bodyIssuesSorted.filter { it.primaryLocation.startLine() == 2 }
            val line4Issues = bodyIssuesSorted.filter { it.primaryLocation.startLine() == 4 }
            assertEquals(3, line2Issues.size)
            assertEquals(2, line4Issues.size)
            assertTrue(
                sorted.indexOf(line2Issues.last()) < sorted.indexOf(line4Issues.first()),
                "Start line tie-breaker: line 2 issues must precede line 4 issues"
            )

            // 3. START COLUMN dimension: p1 at column 16 strictly precedes p2 at column 32 on line 2
            val col16Issues = line2Issues.filter { it.primaryLocation.startLineOffset() == 16 }
            val col32Issues = line2Issues.filter { it.primaryLocation.startLineOffset() == 32 }
            assertEquals(2, col16Issues.size, "Must have exactly 2 issues at column 16 (Nocopy + UnusedParameter)")
            assertEquals(1, col32Issues.size, "Must have exactly 1 issue at column 32 (UnusedParameter for p2)")
            assertTrue(
                sorted.indexOf(col16Issues.last()) < sorted.indexOf(col32Issues.first()),
                "Start column tie-breaker: column 16 (p1) must precede column 32 (p2)"
            )

            // 4. END COLUMN dimension: PackageBodyParameterNocopy (endCol 18) strictly precedes UnusedParameter (endCol 30) at line 2, col 16
            val nocopyIssue = col16Issues.first { it.check.activeRule.ruleKey.rule == "PackageBodyParameterNocopy" }
            val unusedIssue = col16Issues.first { it.check.activeRule.ruleKey.rule == "UnusedParameter" }
            assertEquals(2, nocopyIssue.primaryLocation.startLine())
            assertEquals(2, unusedIssue.primaryLocation.startLine())
            assertEquals(16, nocopyIssue.primaryLocation.startLineOffset())
            assertEquals(16, unusedIssue.primaryLocation.startLineOffset())
            assertEquals(2, nocopyIssue.primaryLocation.endLine())
            assertEquals(2, unusedIssue.primaryLocation.endLine())
            assertEquals(18, nocopyIssue.primaryLocation.endLineOffset())
            assertEquals(30, unusedIssue.primaryLocation.endLineOffset())
            assertTrue(
                sorted.indexOf(nocopyIssue) < sorted.indexOf(unusedIssue),
                "End column tie-breaker: endCol 18 must precede endCol 30 on same file, startLine, startCol, endLine"
            )

            // 5. Permutation stability: 10 random permutations sort into the identical canonical list
            for (seed in 1..10) {
                val shuffled = ArrayList(allIssues)
                Collections.shuffle(shuffled, java.util.Random(seed.toLong()))
                assertEquals(sorted, IssueOrdering.sort(shuffled))
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun exercisesEndLineRuleKeyAndMessageTieBreakers() {
        val root = Files.createTempDirectory("zpa-same-location-test").toFile()
        try {
            val sqlFile = root.resolve("query.sql").apply {
                writeText(
                    """
                    SELECT
                      *
                    FROM
                      table_name;
                    """.trimIndent()
                )
            }

            val ruleMetadataLoader = RuleMetadataLoader()
            val repository = Repository("zpa")
            CustomAnnotationBasedRulesDefinition.load(
                repository,
                "plsqlopen",
                listOf(AlphaCheck::class.java, BetaCheck::class.java, MessageCheck::class.java, EndLineCheck::class.java),
                ruleMetadataLoader
            )
            val activeRules = CliActiveRules(ConfigFile(base = BaseRuleCategory.DEFAULT))
            activeRules.addRepository(repository)
            val checks = ZpaChecks(activeRules, repository.key, ruleMetadataLoader)
                .addAnnotatedChecks(listOf(AlphaCheck::class.java, BetaCheck::class.java, MessageCheck::class.java, EndLineCheck::class.java))

            val scanner = AstScanner(
                checks.all(),
                null,
                true,
                StandardCharsets.UTF_8,
                ProjectAnalysisContext.NOT_PREPARED
            )

            val inputFile = InputFile(PlSqlFile.Type.MAIN, root.toPath(), sqlFile, StandardCharsets.UTF_8)
            val issues = scanner.scanFile(inputFile, fileId = FileId("query.sql")).issues
            val sorted = IssueOrdering.sort(issues)

            // 1. END LINE tie-breaker:
            // EndLineCheck emits two issues starting at line 1, col 0:
            // - Token issue ends at line 1 (col 6)
            // - Statement issue ends at line 4 (col 13)
            val endLineIssues = sorted.filter { it.check is EndLineCheck }
            assertEquals(2, endLineIssues.size, "EndLineCheck must emit exactly 2 issues")
            val shortEndIssue = endLineIssues.first { it.primaryLocation.message() == "single-token statement head" }
            val longEndIssue = endLineIssues.first { it.primaryLocation.message() == "multi-line statement" }
            assertEquals(1, shortEndIssue.primaryLocation.startLine())
            assertEquals(1, longEndIssue.primaryLocation.startLine())
            assertEquals(0, shortEndIssue.primaryLocation.startLineOffset())
            assertEquals(0, longEndIssue.primaryLocation.startLineOffset())
            assertEquals(1, shortEndIssue.primaryLocation.endLine())
            assertEquals(4, longEndIssue.primaryLocation.endLine())
            assertTrue(
                sorted.indexOf(shortEndIssue) < sorted.indexOf(longEndIssue),
                "End line tie-breaker: issue ending on line 1 must strictly precede issue ending on line 4"
            )

            // 2. RULE KEY tie-breaker:
            // AlphaCheck and BetaCheck both emit on the multi-line statement (line 1, col 0, endLine 4, endCol 13)
            // with identical message "shared message". RuleKey "zpa:RuleAlpha" must precede "zpa:RuleBeta".
            val alphaIssue = sorted.first { it.check is AlphaCheck }
            val betaIssue = sorted.first { it.check is BetaCheck }
            assertEquals(alphaIssue.primaryLocation.startLine(), betaIssue.primaryLocation.startLine())
            assertEquals(alphaIssue.primaryLocation.startLineOffset(), betaIssue.primaryLocation.startLineOffset())
            assertEquals(alphaIssue.primaryLocation.endLine(), betaIssue.primaryLocation.endLine())
            assertEquals(alphaIssue.primaryLocation.endLineOffset(), betaIssue.primaryLocation.endLineOffset())
            assertEquals(alphaIssue.primaryLocation.message(), betaIssue.primaryLocation.message())
            assertEquals("zpa:RuleAlpha", alphaIssue.check.activeRule.ruleKey.toString())
            assertEquals("zpa:RuleBeta", betaIssue.check.activeRule.ruleKey.toString())
            assertTrue(
                sorted.indexOf(alphaIssue) < sorted.indexOf(betaIssue),
                "Rule key tie-breaker: zpa:RuleAlpha must strictly precede zpa:RuleBeta on identical coordinates and message"
            )

            // 3. MESSAGE tie-breaker:
            // MessageCheck emits "zebra message" and "apple message" on the exact same node and ruleKey.
            // "apple message" must strictly precede "zebra message".
            val msgIssues = sorted.filter { it.check is MessageCheck }
            assertEquals(2, msgIssues.size, "MessageCheck must emit exactly 2 issues")
            val appleIssue = msgIssues.first { it.primaryLocation.message() == "apple message" }
            val zebraIssue = msgIssues.first { it.primaryLocation.message() == "zebra message" }
            assertEquals(appleIssue.primaryLocation.startLine(), zebraIssue.primaryLocation.startLine())
            assertEquals(appleIssue.primaryLocation.startLineOffset(), zebraIssue.primaryLocation.startLineOffset())
            assertEquals(appleIssue.primaryLocation.endLine(), zebraIssue.primaryLocation.endLine())
            assertEquals(appleIssue.primaryLocation.endLineOffset(), zebraIssue.primaryLocation.endLineOffset())
            assertEquals(appleIssue.check.activeRule.ruleKey.toString(), zebraIssue.check.activeRule.ruleKey.toString())
            assertTrue(
                sorted.indexOf(appleIssue) < sorted.indexOf(zebraIssue),
                "Message tie-breaker: 'apple message' must strictly precede 'zebra message' on identical coordinates and ruleKey"
            )

            // 4. Monotonicity across the entire sorted list
            for (i in 0 until sorted.size - 1) {
                assertTrue(IssueOrdering.COMPARATOR.compare(sorted[i], sorted[i + 1]) <= 0)
            }
        } finally {
            root.deleteRecursively()
        }
    }
}
