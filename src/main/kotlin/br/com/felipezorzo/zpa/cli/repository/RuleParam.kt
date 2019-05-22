package br.com.felipezorzo.zpa.cli.repository

import org.sonar.plsqlopen.rules.ZpaRuleParam

class RuleParam(override val key: String) : ZpaRuleParam {

    override var defaultValue: String = ""

    override var description: String = ""

}
