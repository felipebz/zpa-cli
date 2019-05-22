package br.com.felipezorzo.zpa.cli.repository

import org.sonar.plsqlopen.rules.ZpaRepository
import org.sonar.plsqlopen.rules.ZpaRule

class Repository : ZpaRepository {

    private val rules = mutableMapOf<String, ZpaRule>()

    override fun createRule(ruleKey: String): ZpaRule {
        val rule = Rule(ruleKey)
        rules[ruleKey] = rule
        return rule
    }

    override fun rule(ruleKey: String): ZpaRule? = rules[ruleKey]

    fun availableRules() = rules.values.toList()

}
