package br.com.felipezorzo.zpa.cli.sonarqube

import br.com.felipezorzo.zpa.cli.tracker.Trackable
import org.sonar.scanner.protocol.input.ScannerInput

class ServerIssueAdapter(private val serverIssue: ScannerInput.ServerIssue) : Trackable {
    override val ruleKey: String
        get() = serverIssue.ruleKey
    override val message: String
        get() = serverIssue.msg
    override val line: Int
        get() = serverIssue.line
    override val lineHash: Int
        get() = serverIssue.line.hashCode()
    override val textRangeHash: Int
        get() = serverIssue.line.hashCode()
    override val serverIssueKey: String
        get() = serverIssue.key
    val creationDate: Long
        get() = serverIssue.creationDate
    val severity: String
        get() = serverIssue.severity.toString()
}