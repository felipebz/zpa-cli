/**
 * Z PL/SQL Analyzer
 * Copyright (C) 2015-2026 Felipe Zorzo
 * mailto:felipe AT felipezorzo DOT com DOT br
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package br.com.felipezorzo.zpa.cli.rules

import br.com.felipezorzo.zpa.cli.config.ConfigFile
import com.felipebz.zpa.rules.*

class CliActiveRules(val config: ConfigFile?) : ZpaActiveRules {

    private val repositories = mutableListOf<Repository>()
    private val activeRuleConfigurers = mutableListOf<ActiveRuleConfigurer>()

    fun addRepository(repository: Repository): CliActiveRules = apply {
        repositories.add(repository)
    }

    fun addRuleConfigurer(filter: ActiveRuleConfigurer): CliActiveRules = apply {
        activeRuleConfigurers.add(filter)
    }

    override fun findByRepository(repository: String): Collection<ZpaActiveRule> {
        val repo = this.repositories.first { it.key == repository }
        val repoAvailableRules = repo.availableRules
        val customRules = addCustomRulesByConfig(config, repo)

        return (repoAvailableRules + customRules)
            .mapNotNull { rule ->
                val activeRuleConfiguration = ActiveRuleConfiguration(repo.key, rule.key)
                if (activeRuleConfigurers.all { it.apply(repo, rule, activeRuleConfiguration) }) {
                    CliActiveRule(repo, rule, activeRuleConfiguration)
                } else {
                    null
                }
            }
    }

    fun addCustomRulesByConfig(config: ConfigFile?, repo: Repository): List<ZpaRule> {
        if (config == null) return emptyList()

        return config.rules.entries
            .mapNotNull { (key, ruleConfig) ->
                val templateRuleKey = ruleConfig.options.templateRuleKey?.let { RuleKeyParser.parse(it) } ?: return@mapNotNull null
                if (templateRuleKey.repository.isNotEmpty() && templateRuleKey.repository != repo.key) return@mapNotNull null
                val templateRule = repo.rule(templateRuleKey.rule) ?: return@mapNotNull null

                createCustomRuleFromTemplateRule(key, templateRule)
            }
    }

    private fun createCustomRuleFromTemplateRule(key: String, templateRule: ZpaRule): CliCustomRule {

        val rule = Rule(key).apply {
            name = templateRule.name
            remediationConstant = templateRule.remediationConstant
            scope = templateRule.scope
            severity = templateRule.severity
            status = templateRule.status
            tags = templateRule.tags
            htmlDescription = templateRule.htmlDescription
            isActivatedByDefault = templateRule.isActivatedByDefault
            templateRule.params.forEach { param ->
                createParam(param.key).apply {
                    description = param.description
                    defaultValue = param.defaultValue
                }
            }
        }

        return CliCustomRule(rule).apply {
            templateRuleKey = templateRule.key
        }
    }

}
