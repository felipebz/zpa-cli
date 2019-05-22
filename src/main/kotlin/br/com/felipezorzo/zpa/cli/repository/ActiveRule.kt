package br.com.felipezorzo.zpa.cli.repository

import org.sonar.plsqlopen.rules.ZpaActiveRule
import org.sonar.plsqlopen.rules.ZpaRule
import org.sonar.plsqlopen.rules.ZpaRuleKey

class ActiveRule(private val rule: ZpaRule) : ZpaActiveRule {

    override fun internalKey(): String? = rule.key

    override fun language(): String = "plsqlopen"

    override fun param(key: String): String? = ""

    override fun params(): Map<String, String> = emptyMap()

    override fun ruleKey(): ZpaRuleKey = RuleKey(rule.key)

    override fun severity(): String = rule.severity

    override fun templateRuleKey(): String? = null

}
