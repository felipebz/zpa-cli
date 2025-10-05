package br.com.felipezorzo.zpa.cli.exporters

import br.com.felipezorzo.zpa.cli.InputFile
import com.felipebz.zpa.squid.ZpaIssue

class ConsoleExporter : IssueExporter {
    override fun export(issues: List<ZpaIssue>) {
        for ((file, fileIssues) in issues.groupBy { (it.file as InputFile).pathRelativeToBase }.toSortedMap()) {
            println("File: $file")

            for (issue in fileIssues.sortedWith(
                compareBy(
                    { it.primaryLocation.startLine() },
                    { it.primaryLocation.startLineOffset() })
            )) {
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
}
