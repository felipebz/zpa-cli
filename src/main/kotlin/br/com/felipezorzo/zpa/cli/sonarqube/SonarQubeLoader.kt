package br.com.felipezorzo.zpa.cli.sonarqube

import br.com.felipezorzo.zpa.cli.InputFile
import br.com.felipezorzo.zpa.cli.SonarQubeOptions
import br.com.felipezorzo.zpa.cli.sonarreport.Issue
import br.com.felipezorzo.zpa.cli.sonarreport.Rule
import br.com.felipezorzo.zpa.cli.sonarreport.SonarPreviewReport
import br.com.felipezorzo.zpa.cli.tracker.Tracker
import com.google.protobuf.InvalidProtocolBufferException
import com.google.protobuf.Parser
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.sonar.plsqlopen.rules.*
import org.sonar.plsqlopen.squid.ZpaIssue
import org.sonar.scanner.protocol.input.ScannerInput
import org.sonarqube.ws.client.HttpConnector
import org.sonarqube.ws.client.WsClientFactories
import org.sonarqube.ws.client.qualityprofiles.SearchRequest as QualityProfilesSearchRequest
import org.sonarqube.ws.client.rules.SearchRequest as RulesSearchRequest
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.*


class SonarQubeLoader(private val sonarQubeOptions: SonarQubeOptions) {
    fun updateIssues(repository: Repository, checks: ZpaChecks, activeRules: ActiveRules, issues: List<ZpaIssue>): SonarPreviewReport {
        val serverIssues = downloadIssues()

        val analyzedFiles = issues.map { (it.file as InputFile).pathRelativeToBase }
        val filteredIssues = serverIssues.filter { analyzedFiles.contains(it.path) }

        val localIssues = issues.map {
            val ruleKey = checks.ruleKey(it.check) as ZpaRuleKey
            val rule = repository.rule(ruleKey.rule) as ZpaRule

            LocalIssueAdapter(ruleKey.toString(), rule, it)
        }

        val trackerResult = Tracker<ServerIssueAdapter, LocalIssueAdapter>().track(
            filteredIssues.map { ServerIssueAdapter(it) },
            localIssues
        )

        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ")

        val issuesToExport = trackerResult.matchedRaws.map {
            Issue(
                assignee = "",
                component = it.value.path,
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
        } + trackerResult.unmatchedBases.map {
            Issue(
                assignee = "",
                component = it.path,
                creationDate = dateFormat.format(Date()),
                endLine = it.endLine,
                endOffset = it.endOffset,
                isNew = true,
                key = "",
                line = it.line,
                message = it.message,
                rule = it.ruleKey,
                severity = it.severity,
                startLine = it.line,
                startOffset = it.startOffset,
                status = "OPEN",
            )
        }

        val rules = activeRules.findByRepository("zpa")
            .filterIsInstance<ActiveRule>()
            .filter {
                issuesToExport.any { issue -> issue.rule == it.ruleKey.toString() }
            }
            .map {
                val rule = repository.rule(it.ruleKey.rule) as ZpaRule
                Rule(it.ruleKey.toString(), rule.name, it.ruleKey.repository, it.ruleKey.rule)
            }

        return SonarPreviewReport(listOf(), issuesToExport, rules, "")
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

    fun downloadQualityProfile(): List<ActiveRuleConfiguration> {
        val client = WsClientFactories.getDefault()
            .newClient(HttpConnector.newBuilder()
                .url(sonarQubeOptions.sonarqubeUrl)
                .credentials(sonarQubeOptions.sonarqubeToken, "")
                .build())

        val qualityProfiles = client.qualityprofiles().search(QualityProfilesSearchRequest()
            .setLanguage("plsqlopen")
            .setProject(sonarQubeOptions.sonarqubeKey))

        val rules = client.rules().search(RulesSearchRequest()
            .setActivation("true")
            .setQprofile(qualityProfiles.profilesList[0].key)
            .setF(listOf("repo", "params", "severity")))

        return rules.rulesList.map { rule ->
            var (repo, key) = rule.key.split(":")
            repo = if (repo == "plsql") "zpa" else repo

            ActiveRuleConfiguration(repo, key, rule.severity,
                rule.params.paramsList.associate { it.key to it.defaultValue })
        }
    }
}
