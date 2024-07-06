package br.com.felipezorzo.zpa.cli.config

import org.sonar.plsqlopen.rules.ActiveRuleConfiguration

class ConfigFile {
    val rules: List<RuleConfiguration> = listOf()
}

class RuleConfiguration {
    var repositoryKey: String = "zpa"
    var key: String = ""
    var severity: String? = null
    var parameters: Map<String, String> = emptyMap()

    fun toActiveRuleConfiguration(): ActiveRuleConfiguration {
        return ActiveRuleConfiguration(repositoryKey, key, severity, parameters)
    }
}
