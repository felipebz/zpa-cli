package br.com.felipezorzo.zpa.cli.sonarqube

import br.com.felipezorzo.zpa.cli.InputFile
import br.com.felipezorzo.zpa.cli.tracker.Trackable
import org.sonar.plsqlopen.squid.ZpaIssue

class LocalIssueAdapter(override val ruleKey: String, private val localIssue: ZpaIssue) : Trackable {
    override val message: String
        get() = localIssue.primaryLocation.message()
    override val line: Int
        get() = localIssue.primaryLocation.startLine()
    override val lineHash: String
        get() = (localIssue.file as InputFile).getLineHash(localIssue.primaryLocation.startLine())
    override val textRangeHash: Int
        get() = localIssue.primaryLocation.startLine().hashCode()
    override val serverIssueKey: String
        get() = localIssue.toString()
    override val path: String
        get() = localIssue.file.toString()
    val startOffset: Int
        get() = localIssue.primaryLocation.startLineOffset()
    val endLine: Int
        get() = localIssue.primaryLocation.endLine()
    val endOffset: Int
        get() = localIssue.primaryLocation.endLineOffset()
    val severity: String
        get() = localIssue.check.activeRule.severity
}