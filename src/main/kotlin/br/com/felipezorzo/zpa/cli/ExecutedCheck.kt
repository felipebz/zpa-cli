package br.com.felipezorzo.zpa.cli

import org.sonar.plugins.plsqlopen.api.checks.PlSqlCheck
import org.sonar.plugins.plsqlopen.api.checks.PlSqlVisitor

data class ExecutedCheck(val check: PlSqlVisitor, val issues: List<PlSqlCheck.PreciseIssue>)
