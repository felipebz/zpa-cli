package br.com.felipezorzo.zpa.cli.json

enum class DiagnosticKind {
    SYNTAX,
    RULE
}

data class ValidationSummary(
    val passed: Boolean,
    val threshold: String
)

data class AnalysisReport(
    val schemaVersion: Int = 1,
    val validation: ValidationSummary,
    val diagnostics: List<Diagnostic>
)

data class Diagnostic(
    val kind: String,
    val file: String,
    val range: DiagnosticRange?,
    val rule: String,
    val severity: String,
    val message: String
)

data class DiagnosticRange(
    val startLine: Int? = null,
    val startColumn: Int? = null,
    val endLine: Int? = null,
    val endColumn: Int? = null
)
