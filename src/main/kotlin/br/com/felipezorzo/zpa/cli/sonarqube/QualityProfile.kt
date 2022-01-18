package br.com.felipezorzo.zpa.cli.sonarqube

data class QualityProfile(
    val name: String,
    val language: String,
    val rules: List<RulesItem>
)

data class RulesItem(
    val type: String? = null,
    val priority: String,
    val parameters: List<Parameter>,
    val key: String,
    val repositoryKey: String
)

data class Parameter(
    val value: String,
    val key: String
)
