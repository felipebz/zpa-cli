package br.com.felipezorzo.zpa.cli.sonarreport

data class SonarPreviewReport(
    val components: List<Component>,
    val issues: List<Issue>,
    val rules: List<Rule>,
    val version: String
)

data class Component(
    val key: String,
    val path: String,
    val status: String
)

data class Issue(
    val assignee: String,
    val component: String,
    val creationDate: String,
    val endLine: Int,
    val endOffset: Int,
    val isNew: Boolean,
    val key: String,
    val line: Int,
    val message: String,
    val rule: String,
    val severity: String,
    val startLine: Int,
    val startOffset: Int,
    val status: String
)

data class Rule(
    val key: String,
    val name: String,
    val repository: String,
    val rule: String
)