package br.com.felipezorzo.zpa.cli.exporters

import br.com.felipezorzo.zpa.cli.InputFile
import com.felipebz.zpa.squid.ZpaIssue
import java.io.PrintStream

class ConsoleExporter(private val out: PrintStream = System.out) : IssueExporter {
    override fun export(issues: List<ZpaIssue>) {
        for ((file, fileIssues) in issues.groupBy { (it.file as? InputFile)?.pathRelativeToBase ?: it.file.fileName() }) {
            out.println("File: $file")

            for (issue in fileIssues) {
                val startLine = issue.primaryLocation.startLine()
                val startColumn = issue.primaryLocation.startLineOffset()
                val activeRule = issue.check.activeRule
                val severity = activeRule.severity
                val ruleKey = activeRule.ruleKey.toString()

                var positionFormatted = "$startLine"
                if (startColumn != -1) {
                    positionFormatted += ":$startColumn"
                }
                val ruleCol = if (ruleKey.length < 24) ruleKey.padEnd(24) else "$ruleKey  "
                out.println("${positionFormatted.padEnd(10)}${severity.padEnd(10)}$ruleCol${issue.primaryLocation.message()}")
            }

            out.println("")
        }
    }
}
