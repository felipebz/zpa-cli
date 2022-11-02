package br.com.felipezorzo.zpa.cli.sonarqube

import br.com.felipezorzo.zpa.cli.InputFile
import br.com.felipezorzo.zpa.cli.SonarQubeOptions
import br.com.felipezorzo.zpa.cli.sonarreport.Issue
import br.com.felipezorzo.zpa.cli.sonarreport.Rule
import br.com.felipezorzo.zpa.cli.sonarreport.SonarPreviewReport
import br.com.felipezorzo.zpa.cli.tracker.Tracker
import com.fasterxml.jackson.dataformat.xml.XmlMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.google.protobuf.InvalidProtocolBufferException
import com.google.protobuf.Parser
import org.sonar.plsqlopen.rules.*
import org.sonar.plsqlopen.squid.ZpaIssue
import org.sonar.scanner.protocol.input.ScannerInput
import org.sonarqube.ws.client.HttpConnector
import org.sonarqube.ws.client.WsClientFactories
import org.sonarqube.ws.client.batch.IssuesRequest
import org.sonarqube.ws.client.qualityprofiles.ExportRequest
import org.sonarqube.ws.client.qualityprofiles.SearchRequest
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.*


class SonarQubeLoader(private val sonarQubeOptions: SonarQubeOptions) {
    private val client = WsClientFactories.getDefault()
        .newClient(HttpConnector.newBuilder()
            .url(sonarQubeOptions.sonarqubeUrl)
            .credentials(sonarQubeOptions.sonarqubeToken, "")
            .build())

    fun updateIssues(repository: Repository, checks: ZpaChecks, activeRules: ActiveRules, issues: List<ZpaIssue>): SonarPreviewReport {
        val serverIssues = downloadIssues()

        val analyzedFiles = issues.map { (it.file as InputFile).pathRelativeToBase }.toSet()
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
        val response = CustomBatchService(client.wsConnector()).issuesStream(IssuesRequest()
            .setKey(sonarQubeOptions.sonarqubeKey))

        return readMessages(response, ScannerInput.ServerIssue.parser())
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
        val qualityProfiles = client.qualityprofiles().search(SearchRequest()
            .setLanguage("plsqlopen")
            .setProject(sonarQubeOptions.sonarqubeKey))

        val qualityProfile = client.qualityprofiles().export(ExportRequest()
            .setLanguage("plsqlopen")
            .setQualityProfile(qualityProfiles.profilesList[0].name))

        val xmlMapper = XmlMapper().registerKotlinModule()

        return xmlMapper.readValue(qualityProfile, QualityProfile::class.java)
            .rules.map {
                val repo = if (it.repositoryKey == "plsql") "zpa" else it.repositoryKey
                ActiveRuleConfiguration(repo, it.key, it.priority, it.parameters.associate { p -> p.key to p.value })
            }
    }
}
