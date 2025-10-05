package br.com.felipezorzo.zpa.cli

import com.felipebz.zpa.api.ZpaRulesDefinition
import com.felipebz.zpa.checks.CheckList

class DefaultRulesDefinition : ZpaRulesDefinition {
    override fun checkClasses(): Array<Class<*>> {
        return CheckList.checks.toTypedArray()
    }

    override fun repositoryKey(): String {
        return "zpa"
    }

    override fun repositoryName(): String {
        return "Default"
    }
}
