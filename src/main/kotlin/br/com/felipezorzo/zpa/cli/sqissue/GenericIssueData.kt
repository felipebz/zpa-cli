package br.com.felipezorzo.zpa.cli.sqissue

import com.fasterxml.jackson.annotation.JsonInclude

data class GenericIssueData(
        val rules: List<Rule>,
        val issues: List<Issue>
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class Rule(
        val id: String,
        val name: String,
        val description: String,
        val engineId: String = "zpa",
        val cleanCodeAttribute: String?,
        val type: String,
        val severity: String,
        val impacts: List<Impact>
)

data class Impact(
        val softwareQuality: String,
        val severity: String
)

data class Issue(
        val ruleId: String,
        val primaryLocation: PrimaryLocation,
        private val duration: String,
        val secondaryLocations: List<SecondaryLocation>) {
    val effortMinutes: Int = Duration.toMinute(duration)
}

data class PrimaryLocation(
        val message: String,
        val filePath: String,
        val textRange: TextRange
)

data class SecondaryLocation(
        val message: String,
        val filePath: String,
        val textRange: TextRange
)

data class TextRange(
        val startLine: Int,
        val endLine: Int?,
        val startColumn: Int?,
        val endColumn: Int?
)
