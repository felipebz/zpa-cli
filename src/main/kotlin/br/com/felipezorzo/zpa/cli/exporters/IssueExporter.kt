package br.com.felipezorzo.zpa.cli.exporters

import com.felipebz.zpa.squid.ZpaIssue

fun interface IssueExporter {
    fun export(issues: List<ZpaIssue>)
}
