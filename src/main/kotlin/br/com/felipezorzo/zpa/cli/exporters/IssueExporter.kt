package br.com.felipezorzo.zpa.cli.exporters

import org.sonar.plsqlopen.squid.ZpaIssue

fun interface IssueExporter {
    fun export(issues: List<ZpaIssue>)
}
