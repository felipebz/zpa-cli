package br.com.felipezorzo.zpa.cli.exporters

import br.com.felipezorzo.zpa.cli.InputFile
import br.com.felipezorzo.zpa.cli.sqissue.*
import com.fasterxml.jackson.databind.ObjectMapper
import org.sonar.plsqlopen.squid.ZpaIssue
import java.io.File

class GenericIssueFormatExporter(private val outputFile: String) : IssueExporter {
    override fun export(issues: List<ZpaIssue>) {
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

            val activeRule = issue.check.activeRule

            val type = when {
                activeRule.tags.contains("vulnerability") -> "VULNERABILITY"
                activeRule.tags.contains("bug") -> "BUG"
                else -> "CODE_SMELL"
            }

            genericIssues += Issue(
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
        val generatedOutput = mapper.writeValueAsString(genericReport)
        val file = File(outputFile)
        file.parentFile?.mkdirs()
        file.writeText(generatedOutput)
    }

    private fun createTextRange(startLine: Int, endLine: Int, startLineOffset: Int, endLineOffset: Int): TextRange {
        return TextRange(
            startLine = startLine,
            endLine = if (endLine > -1) endLine else null,
            startColumn = if (startLineOffset > -1) startLineOffset else null,
            endColumn = if (endLineOffset > -1) endLineOffset else null
        )
    }
}
