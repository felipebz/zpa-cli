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
        val repo = this.repositories.firstOrNull { it.key == repository }
            ?: throw IllegalArgumentException("Unknown rule repository '$repository'.")
        validateConfiguration()
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

        val repositoriesToSearch = if (repositories.any { it.key == repo.key }) {
            repositories
        } else {
            repositories + repo
        }
        validateConfiguration(config, repositoriesToSearch)

        val customRules = mutableListOf<ZpaRule>()
        val customRuleKeys = mutableSetOf<String>()

        for ((configuredKey, ruleConfig) in config.rules) {
            val requestedTemplateKey = ruleConfig.options.templateRuleKey ?: continue
            val instanceRuleKey = RuleKeyParser.parse(configuredKey)
            val templateRuleKey = RuleKeyParser.parse(requestedTemplateKey)

            if (instanceRuleKey.repository.isNotEmpty() && instanceRuleKey.repository != repo.key) {
                continue
            }
            if (templateRuleKey.repository.isNotEmpty() && templateRuleKey.repository != repo.key) {
                continue
            }

            val templateRule = repo.rule(templateRuleKey.rule)
            if (templateRule == null) {
                if (templateRuleKey.repository.isEmpty()) {
                    continue
                }
                throw unresolvedTemplate(configuredKey, requestedTemplateKey, repo.key)
            }

            val normalizedInstanceKey = instanceRuleKey.rule

            // A configuration entry using the template's own key is the existing rule
            // configuration, not a second rule definition.
            if (normalizedInstanceKey == templateRule.key) {
                continue
            }

            if (repo.rule(normalizedInstanceKey) != null) {
                throw IllegalArgumentException(
                    "Configured template rule instance '$configuredKey' conflicts with " +
                        "existing rule '${repo.key}:$normalizedInstanceKey' in repository '${repo.key}'."
                )
            }

            if (!customRuleKeys.add(normalizedInstanceKey)) {
                throw IllegalArgumentException(
                    "Multiple template rule configurations resolve to '${repo.key}:$normalizedInstanceKey' " +
                        "in repository '${repo.key}'."
                )
            }

            customRules += createCustomRuleFromTemplateRule(normalizedInstanceKey, templateRule)
        }

        return customRules
    }

    fun validateConfiguration() {
        validateConfiguration(config, repositories)
    }

    private fun validateConfiguration(config: ConfigFile?, repositoriesToSearch: List<Repository>) {
        if (config == null) return

        val customRuleKeys = mutableMapOf<Pair<String, String>, String>()

        for ((configuredKey, ruleConfig) in config.rules) {
            val requestedTemplateKey = ruleConfig.options.templateRuleKey ?: continue
            val instanceRuleKey = RuleKeyParser.parse(configuredKey)
            val templateRuleKey = RuleKeyParser.parse(requestedTemplateKey)

            if (instanceRuleKey.repository.isNotEmpty() && repositoriesToSearch.none { it.key == instanceRuleKey.repository }) {
                throw IllegalArgumentException(
                    "Configured template rule instance '$configuredKey' refers to unknown repository " +
                        "'${instanceRuleKey.repository}'. Available repositories: ${availableRepositories(repositoriesToSearch)}."
                )
            }

            if (templateRuleKey.repository.isNotEmpty() && repositoriesToSearch.none { it.key == templateRuleKey.repository }) {
                throw unresolvedTemplateForRepository(
                    configuredKey,
                    requestedTemplateKey,
                    templateRuleKey.repository,
                    repositoriesToSearch
                )
            }

            val candidateRepositories = repositoriesToSearch.filter { repository ->
                (instanceRuleKey.repository.isEmpty() || instanceRuleKey.repository == repository.key) &&
                    (templateRuleKey.repository.isEmpty() || templateRuleKey.repository == repository.key)
            }
            val matchingRepositories = candidateRepositories.filter { it.rule(templateRuleKey.rule) != null }

            if (matchingRepositories.isEmpty()) {
                val searchedRepositories = candidateRepositories.map { it.key }
                throw unresolvedTemplate(
                    configuredKey,
                    requestedTemplateKey,
                    searchedRepositories.joinToString(", ").ifEmpty { "<none>" }
                )
            }

            for (repository in matchingRepositories) {
                val normalizedInstanceKey = instanceRuleKey.rule
                if (normalizedInstanceKey == templateRuleKey.rule) continue

                val identity = repository.key to normalizedInstanceKey
                val previousConfiguration = customRuleKeys.put(identity, configuredKey)
                if (previousConfiguration != null) {
                    throw IllegalArgumentException(
                        "Multiple template rule configurations '$previousConfiguration' and '$configuredKey' " +
                            "resolve to '${repository.key}:$normalizedInstanceKey'."
                    )
                }
            }
        }
    }

    private fun availableRepositories(repositoriesToSearch: List<Repository>): String {
        return repositoriesToSearch.map { it.key }.ifEmpty { listOf("<none>") }.joinToString(", ")
    }

    private fun unresolvedTemplate(
        configuredKey: String,
        requestedTemplateKey: String,
        repository: String
    ): IllegalArgumentException {
        return IllegalArgumentException(
            "Unable to resolve template rule '$requestedTemplateKey' for configured rule instance " +
                "'$configuredKey' in repository '$repository'."
        )
    }

    private fun unresolvedTemplateForRepository(
        configuredKey: String,
        requestedTemplateKey: String,
        repository: String,
        repositoriesToSearch: List<Repository>
    ): IllegalArgumentException {
        return IllegalArgumentException(
            "Unable to resolve template rule '$requestedTemplateKey' for configured rule instance " +
                "'$configuredKey': repository '$repository' is not available. " +
                "Available repositories: ${availableRepositories(repositoriesToSearch)}."
        )
    }

    private fun createCustomRuleFromTemplateRule(key: String, templateRule: ZpaRule): CliCustomRule {

        val rule = Rule(key).apply {
            name = templateRule.name
            remediationConstant = templateRule.remediationConstant
            scope = templateRule.scope
            severity = templateRule.severity
            status = templateRule.status
            tags = templateRule.tags.copyOf()
            htmlDescription = templateRule.htmlDescription
            isActivatedByDefault = templateRule.isActivatedByDefault
            // A synthetic rule is an instance of the template, not another template.
            template = false
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
