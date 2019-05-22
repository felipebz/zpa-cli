package br.com.felipezorzo.zpa.cli.repository

import org.sonar.plsqlopen.rules.ZpaActiveRule
import org.sonar.plsqlopen.rules.ZpaActiveRules

class ActiveRules(private val repository: Repository) : ZpaActiveRules {

    override fun findByRepository(repositoryKey: String): Collection<ZpaActiveRule> =
            repository.availableRules().map { ActiveRule(it) }

}