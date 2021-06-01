package br.com.felipezorzo.zpa.cli.sonarqube

import br.com.felipezorzo.zpa.cli.InputFile
import br.com.felipezorzo.zpa.cli.SonarQubeOptions
import br.com.felipezorzo.zpa.cli.sonarreport.Issue
import br.com.felipezorzo.zpa.cli.sonarreport.SonarPreviewReport
import br.com.felipezorzo.zpa.cli.tracker.Tracker
import com.google.protobuf.InvalidProtocolBufferException
import com.google.protobuf.Parser
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.sonar.plsqlopen.rules.Repository
import org.sonar.plsqlopen.rules.ZpaChecks
import org.sonar.plsqlopen.rules.ZpaRule
import org.sonar.plsqlopen.rules.ZpaRuleKey
import org.sonar.plsqlopen.squid.ZpaIssue
import org.sonar.plugins.plsqlopen.api.checks.PlSqlVisitor
import org.sonar.scanner.protocol.input.ScannerInput
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.*


class SonarQubeLoader(private val sonarQubeOptions: SonarQubeOptions) {
    fun updateIssues(repository: Repository, checks: ZpaChecks<PlSqlVisitor>, issues: List<ZpaIssue>): SonarPreviewReport {
        val serverIssues = downloadIssues()

        val analyzedFiles = issues.map { (it.file as InputFile).pathRelativeToBase }
        val filteredIssues = serverIssues.filter { analyzedFiles.contains(it.path) }

        val localIssues = issues.map {
            val ruleKey = checks.ruleKey(it.check) as ZpaRuleKey
            val rule = repository.rule(ruleKey.rule()) as ZpaRule

            LocalIssueAdapter(rule.key, it)
        }

        val trackerResult = Tracker<ServerIssueAdapter, LocalIssueAdapter>().track(
            filteredIssues.map { ServerIssueAdapter(it) },
            localIssues
        )

        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ")

        val issuesToExport = mutableListOf<Issue>();
        issuesToExport.addAll(trackerResult.matchedRaws.map {
            Issue(
                assignee = "",
                component = "",
                creationDate = dateFormat.format(Date(it.key.creationDate)),
                endLine = it.value.endLine,
                endOffset = it.value.endOffset,
                isNew = false,
                key = "",
                line = it.value.line,
                message = it.value.message,
                rule = it.value.ruleKey,
                severity = it.key.severity,
                startLine = it.value.line,
                startOffset = it.value.startOffset,
                status = "OPEN",
            )
        })
        issuesToExport.addAll(trackerResult.unmatchedBases.map {
            Issue(
                assignee = "",
                component = "",
                creationDate = dateFormat.format(Date()),
                endLine = it.endLine,
                endOffset = it.endOffset,
                isNew = true,
                key = "",
                line = it.line,
                message = it.message,
                rule = it.ruleKey,
                severity = "",//it.severity,
                startLine = it.line,
                startOffset = it.startOffset,
                status = "OPEN",
            )
        })

        return SonarPreviewReport(listOf(), issuesToExport, listOf(), "")
    }

    private fun downloadIssues(): List<ScannerInput.ServerIssue> {
        val client = OkHttpClient()
        val credential = Credentials.basic(sonarQubeOptions.sonarqubeToken, "")

        val url = sonarQubeOptions.sonarqubeUrl.toHttpUrl().newBuilder()
            .addPathSegments("batch/issues")
            .addQueryParameter("key", sonarQubeOptions.sonarqubeKey)
            .build()

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", credential)
            .build()
        val response = client.newCall(request).execute()
        return readMessages(response.body?.byteStream(), ScannerInput.ServerIssue.parser())
    }

    private fun <T> readMessages(input: InputStream?, parser: Parser<T>): List<T> {
        val list = mutableListOf<T>()
        while (true) {
            val message = try {
                parser.parseDelimitedFrom(input)
            } catch (e: InvalidProtocolBufferException) {
                throw IllegalStateException("failed to parse protobuf message", e)
            } ?: break
            list.add(message)
        }
        return list
    }

}
