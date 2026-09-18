package br.com.felipezorzo.zpa.cli.exporters

import br.com.felipezorzo.zpa.cli.InputFile
import br.com.felipezorzo.zpa.cli.json.*
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.felipebz.zpa.checks.ParsingErrorCheck
import com.felipebz.zpa.squid.ZpaIssue
import java.io.File
import java.io.PrintStream
import java.nio.charset.StandardCharsets

class JsonExporter(
    private val outputFile: String = "",
    private val validationFailed: Boolean = false,
    private val threshold: String = "none",
    private val out: PrintStream = System.out
) : IssueExporter {

    override fun export(issues: List<ZpaIssue>) {
        val diagnostics = issues.map { issue ->
            val relativePath = (issue.file as InputFile).pathRelativeToBase
            val loc = issue.primaryLocation
            val startLine = loc.startLine()
            val startColumn = loc.startLineOffset()
            val endLine = loc.endLine()
            val endColumn = loc.endLineOffset()

            val range = if (startLine <= 0 && startColumn < 0 && endLine <= 0 && endColumn < 0) {
                null
            } else {
                DiagnosticRange(
                    startLine = if (startLine > 0) startLine else null,
                    startColumn = if (startColumn >= 0) startColumn else null,
                    endLine = if (endLine > 0) endLine else null,
                    endColumn = if (endColumn >= 0) endColumn else null
                )
            }

            val kind = if (issue.check is ParsingErrorCheck) {
                DiagnosticKind.SYNTAX.name
            } else {
                DiagnosticKind.RULE.name
            }

            Diagnostic(
                kind = kind,
                file = relativePath,
                range = range,
                rule = issue.check.activeRule.ruleKey.toString(),
                severity = issue.check.activeRule.severity,
                message = loc.message()
            )
        }

        val report = AnalysisReport(
            schemaVersion = 1,
            validation = ValidationSummary(
                passed = !validationFailed,
                threshold = threshold
            ),
            diagnostics = diagnostics
        )

        val mapper = ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
        val jsonString = mapper.writeValueAsString(report)

        if (outputFile.isNotEmpty()) {
            val file = File(outputFile)
            file.parentFile?.mkdirs()
            file.writeText(jsonString, StandardCharsets.UTF_8)
        } else {
            out.println(jsonString)
        }
    }
}
