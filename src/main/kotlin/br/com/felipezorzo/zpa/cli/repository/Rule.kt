package br.com.felipezorzo.zpa.cli.repository

import org.sonar.plsqlopen.rules.RuleStatus
import org.sonar.plsqlopen.rules.ZpaRule
import org.sonar.plsqlopen.rules.ZpaRuleParam
import org.sonar.plugins.plsqlopen.api.annotations.RuleInfo

class Rule(override val key: String) : ZpaRule {

    override var htmlDescription: String = ""

    override var name: String = ""

    override val params = mutableListOf<ZpaRuleParam>()

    override var remediationConstant: String = ""

    override var scope: RuleInfo.Scope = RuleInfo.Scope.ALL

    override var severity: String = ""

    override var status: RuleStatus = RuleStatus.READY

    override var tags: Array<String> = emptyArray()

    override var template: Boolean = false

    override fun createParam(fieldKey: String): ZpaRuleParam {
        val param = RuleParam(fieldKey)
        params.add(param)
        return param
    }
}
