package br.com.felipezorzo.zpa.cli.exporters

import br.com.felipezorzo.zpa.cli.Arguments
import br.com.felipezorzo.zpa.cli.GENERIC_ISSUE_FORMAT
import br.com.felipezorzo.zpa.cli.Main
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class GenericIssueFormatExporterTest {

    private val root: File = Files.createTempDirectory("zpa-cli-generic-report").toFile()

    @AfterTest
    fun cleanup() {
        root.deleteRecursively()
    }

    private fun report(): JsonNode {
        val sources = root.resolve("sources").apply { mkdirs() }
        sources.resolve("stock.sql").writeText(
            """
            CREATE OR REPLACE PROCEDURE restock_shelf IS
              crates NUMBER;
            BEGIN
              IF crates = NULL THEN
                crates := 1;
              END IF;
              IF crates <> NULL THEN
                crates := 2;
              END IF;
              SELECT * INTO crates FROM pallet_counts;
            END;
            """.trimIndent()
        )
        val config = root.resolve("config.json").apply {
            writeText(
                """
                {
                  "base": "none",
                  "rules": {
                    "ComparisonWithNull": "on",
                    "SelectAllColumns": "critical",
                    "StarSearch": {
                      "level": "minor",
                      "templateRuleKey": "XPath",
                      "parameters": { "xpathQuery": "//SELECT_COLUMN/MULTIPLICATION", "message": "star found" }
                    }
                  }
                }
                """.trimIndent()
            )
        }
        val output = root.resolve("report.json")
        Main(
            Arguments().apply {
                this.sources = sources.absolutePath
                configFile = config.absolutePath
                outputFormat = GENERIC_ISSUE_FORMAT
                outputFile = output.absolutePath
                extensions = "sql"
            }
        ).run()
        return jacksonObjectMapper().readTree(output)
    }

    @Test
    fun rulesDescribeTheirIssuesAndIssuesPointToTheirRule() {
        val report = report()
        val rules = report.get("rules").associateBy { it.get("id").asText() }
        val issues = report.get("issues").toList()

        // Each rule appears once, even with two issues.
        assertEquals(listOf("zpa:ComparisonWithNull", "zpa:SelectAllColumns", "zpa:StarSearch"), rules.keys.sorted())
        assertEquals(3, report.get("rules").size())
        assertEquals(2, issues.count { it.get("ruleId").asText() == "zpa:ComparisonWithNull" })

        val bug = rules.getValue("zpa:ComparisonWithNull")
        assertEquals("zpa", bug.get("engineId").asText())
        assertEquals("LOGICAL", bug.get("cleanCodeAttribute").asText())
        assertEquals("BUG", bug.get("type").asText())
        assertEquals("BLOCKER", bug.get("severity").asText())
        assertEquals(impact("RELIABILITY", "BLOCKER"), bug.get("impacts").toList().map(::impactOf))
        assertTrue(bug.get("description").asText().contains("<"))
        assertTrue(bug.get("name").asText().isNotBlank())

        // The severity set in the configuration replaces the rule's default, and the impact follows it.
        val smell = rules.getValue("zpa:SelectAllColumns")
        assertEquals("EFFICIENT", smell.get("cleanCodeAttribute").asText())
        assertEquals("CODE_SMELL", smell.get("type").asText())
        assertEquals("CRITICAL", smell.get("severity").asText())
        assertEquals(impact("MAINTAINABILITY", "HIGH"), smell.get("impacts").toList().map(::impactOf))

        val instance = rules.getValue("zpa:StarSearch")
        assertFalse(instance.has("cleanCodeAttribute"))
        assertEquals("MINOR", instance.get("severity").asText())
        assertEquals(impact("MAINTAINABILITY", "LOW"), instance.get("impacts").toList().map(::impactOf))
        assertTrue(instance.get("description").asText().isNotBlank())

        for (issue in issues) {
            assertFalse(issue.has("severity"))
            assertFalse(issue.has("type"))
            assertFalse(issue.has("engineId"))
            assertTrue(issue.has("effortMinutes"))
            assertTrue(issue.has("primaryLocation"))
        }
    }

    private fun impact(quality: String, severity: String) = listOf(quality to severity)

    private fun impactOf(node: JsonNode) = node.get("softwareQuality").asText() to node.get("severity").asText()
}
