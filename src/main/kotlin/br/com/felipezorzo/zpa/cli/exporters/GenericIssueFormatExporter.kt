package br.com.felipezorzo.zpa.cli.exporters

import br.com.felipezorzo.zpa.cli.InputFile
import br.com.felipezorzo.zpa.cli.rules.CliActiveRule
import br.com.felipezorzo.zpa.cli.sqissue.*
import com.fasterxml.jackson.databind.ObjectMapper
import com.felipebz.zpa.api.annotations.RuleCategory
import com.felipebz.zpa.squid.ZpaIssue
import java.io.File
import java.nio.charset.StandardCharsets

class GenericIssueFormatExporter(private val outputFile: String) : IssueExporter {
    override fun export(issues: List<ZpaIssue>) {
        val rules = LinkedHashMap<String, Rule>()
        val genericIssues = mutableListOf<Issue>()
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
                    issuePrimaryLocation.endLineOffset()
                )
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
                        secondary.endLineOffset()
                    )
                )
            }

            val activeRule = issue.check.activeRule as CliActiveRule
            val ruleId = activeRule.ruleKey.toString()
            rules.getOrPut(ruleId) { describe(ruleId, activeRule) }

            genericIssues += Issue(
                ruleId = ruleId,
                primaryLocation = primaryLocation,
                duration = activeRule.remediationConstant,
                secondaryLocations = secondaryLocations
            )
        }
        val genericReport = GenericIssueData(rules.values.toList(), genericIssues)

        val mapper = ObjectMapper()
        val generatedOutput = mapper.writeValueAsString(genericReport)
        val file = File(outputFile)
        file.parentFile?.mkdirs()
        file.writeText(generatedOutput, StandardCharsets.UTF_8)
    }

    private fun describe(ruleId: String, activeRule: CliActiveRule): Rule {
        val rule = activeRule.rule
        val type = when {
            activeRule.tags.contains("vulnerability") -> "VULNERABILITY"
            activeRule.tags.contains("bug") -> "BUG"
            else -> "CODE_SMELL"
        }
        val severity = activeRule.severity
        return Rule(
            id = ruleId,
            name = rule.name,
            description = rule.htmlDescription.ifBlank { rule.name },
            cleanCodeAttribute = cleanCodeAttribute(rule.category),
            type = type,
            severity = severity,
            impacts = listOf(Impact(softwareQuality(type), impactSeverity(severity)))
        )
    }

    private fun createTextRange(startLine: Int, endLine: Int, startLineOffset: Int, endLineOffset: Int): TextRange {
        return TextRange(
            startLine = startLine,
            endLine = if (endLine > -1) endLine else null,
            startColumn = if (startLineOffset > -1) startLineOffset else null,
            endColumn = if (endLineOffset > -1) endLineOffset else null
        )
    }

    private companion object {
        /** The clean code attribute of a rule of [category]; null when the rule declares none. */
        fun cleanCodeAttribute(category: RuleCategory): String? = when (category) {
            RuleCategory.UNSPECIFIED -> null
            RuleCategory.LOGIC_ERROR -> "LOGICAL"
            RuleCategory.INCOMPLETE -> "COMPLETE"
            RuleCategory.READABILITY -> "CLEAR"
            RuleCategory.CONVENTION -> "CONVENTIONAL"
            RuleCategory.DESIGN -> "MODULAR"
            RuleCategory.PERFORMANCE -> "EFFICIENT"
            RuleCategory.NAMING -> "IDENTIFIABLE"
            RuleCategory.TESTING -> "TESTED"
            RuleCategory.FORMATTING -> "FORMATTED"
            RuleCategory.DUPLICATION -> "DISTINCT"
            RuleCategory.COMPLEXITY -> "FOCUSED"
        }

        /** The software quality SonarQube derives from a rule's type. */
        fun softwareQuality(type: String): String = when (type) {
            "BUG" -> "RELIABILITY"
            "VULNERABILITY" -> "SECURITY"
            else -> "MAINTAINABILITY"
        }

        /** The impact severity SonarQube derives from a rule's severity. */
        fun impactSeverity(severity: String): String = when (severity) {
            "BLOCKER" -> "BLOCKER"
            "CRITICAL" -> "HIGH"
            "MAJOR" -> "MEDIUM"
            "MINOR" -> "LOW"
            "INFO" -> "INFO"
            else -> throw IllegalArgumentException("Unknown severity: $severity")
        }
    }
}
