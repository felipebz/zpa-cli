package br.com.felipezorzo.zpa.cli.sonarqube

import br.com.felipezorzo.zpa.cli.tracker.Trackable
import org.sonar.plugins.plsqlopen.api.checks.PlSqlCheck
import org.sonar.plugins.plsqlopen.api.checks.PlSqlVisitor
import org.sonar.scanner.protocol.input.ScannerInput

class LocalIssueAdapter(override val ruleKey: String, private val localIssue: PlSqlCheck.PreciseIssue) : Trackable {
    override val message: String
        get() = localIssue.primaryLocation().message()
    override val line: Int
        get() = localIssue.primaryLocation().startLine()
    override val lineHash: Int
        get() = localIssue.primaryLocation().startLine().hashCode()
    override val textRangeHash: Int
        get() = localIssue.primaryLocation().startLine().hashCode()
    override val serverIssueKey: String
        get() = localIssue.toString()
    val startOffset: Int
        get() = localIssue.primaryLocation().startLineOffset()
    val endLine: Int
        get() = localIssue.primaryLocation().endLine()
    val endOffset: Int
        get() = localIssue.primaryLocation().endLineOffset()
}