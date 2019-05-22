package br.com.felipezorzo.zpa.cli.repository

import org.sonar.plsqlopen.rules.ZpaRuleKey

class RuleKey(private val key: String) : ZpaRuleKey {

    override fun repository(): String = "zpa"

    override fun rule(): String = key

}
