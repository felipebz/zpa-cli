package br.com.felipezorzo.zpa.cli

import org.sonar.plsqlopen.checks.CheckList
import org.sonar.plugins.plsqlopen.api.ZpaRulesDefinition

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