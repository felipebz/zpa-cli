package br.com.felipezorzo.zpa.cli

import br.com.felipezorzo.zpa.cli.config.BaseRuleCategory
import br.com.felipezorzo.zpa.cli.config.ConfigFile
import br.com.felipezorzo.zpa.cli.config.RuleConfiguration
import br.com.felipezorzo.zpa.cli.config.RuleLevel
import br.com.felipezorzo.zpa.cli.config.RuleOptions
import br.com.felipezorzo.zpa.cli.rules.CliActiveRules
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.felipebz.zpa.CustomAnnotationBasedRulesDefinition
import com.felipebz.zpa.checks.XPathCheck
import com.felipebz.zpa.rules.ActiveRuleConfiguration
import com.felipebz.zpa.rules.Repository
import com.felipebz.zpa.rules.RuleMetadataLoader
import com.felipebz.zpa.rules.ZpaChecks
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class TemplateRuleInstancesTest {

    @Test
    fun zpaChecksCreatesIndependentVisitorsForTwoInstancesOfOneTemplate() {
        val repository = xpathRepository()
        val config = templateConfig()
        val activeRules = configuredActiveRules(config, repository)

        val checks = ZpaChecks(activeRules, repository.key, RuleMetadataLoader())
            .addAnnotatedChecks(listOf(XPathCheck::class.java))
            .all()
            .filterIsInstance<XPathCheck>()
            .associateBy { it.activeRule.ruleKey.rule }

        assertEquals(setOf("FirstXPath", "SecondXPath"), checks.keys)

        val first = checks.getValue("FirstXPath")
        val second = checks.getValue("SecondXPath")
        assertNotSame(first, second)

        assertEquals("//SELECT_COLUMN/MULTIPLICATION", first.xpathQuery)
        assertEquals("message A", first.message)
        assertEquals("MINOR", first.activeRule.severity)
        assertEquals("zpa:FirstXPath", first.activeRule.ruleKey.toString())
        assertEquals("XPath", first.activeRule.templateRuleKey)

        assertEquals("//SELECT_COLUMN/MULTIPLICATION", second.xpathQuery)
        assertEquals("message B", second.message)
        assertEquals("MAJOR", second.activeRule.severity)
        assertEquals("zpa:SecondXPath", second.activeRule.ruleKey.toString())
        assertEquals("XPath", second.activeRule.templateRuleKey)
        assertTrue(first.activeRule !== second.activeRule)
    }

    @Test
    fun explicitTemplateReferenceOnTheOriginalRuleCreatesOnlyOneCheck() {
        val repository = xpathRepository()
        val config = ConfigFile(
            base = BaseRuleCategory.NONE,
            rules = mapOf(
                "XPath" to options(
                    level = RuleLevel.MINOR,
                    templateRuleKey = "XPath",
                    parameters = mapOf(
                        "xpathQuery" to "//SELECT_COLUMN/MULTIPLICATION",
                        "message" to "message"
                    )
                )
            )
        )

        val checks = ZpaChecks(configuredActiveRules(config, repository), repository.key, RuleMetadataLoader())
            .addAnnotatedChecks(listOf(XPathCheck::class.java))
            .all()
            .filterIsInstance<XPathCheck>()

        assertEquals(1, checks.size)
        assertEquals("zpa:XPath", checks.single().activeRule.ruleKey.toString())
    }

    @Test
    fun cliProducesTwoDistinctGenericIssuesWithIndependentConfiguration() {
        val root = Files.createTempDirectory("zpa-cli-template-instances").toFile()
        try {
            val sourceDirectory = root.resolve("sources").apply { mkdirs() }
            sourceDirectory.resolve("multiplication.sql").writeText(
                "SELECT * FROM table_name;\n"
            )

            val configPath = root.resolve("config.json").apply {
                writeText(
                    """
                    {
                      "base": "none",
                      "rules": {
                        "FirstXPath": {
                          "level": "minor",
                          "templateRuleKey": "XPath",
                          "parameters": {
                            "xpathQuery": "//SELECT_COLUMN/MULTIPLICATION",
                            "message": "message A"
                          }
                        },
                        "SecondXPath": {
                          "level": "major",
                          "templateRuleKey": "zpa:XPath",
                          "parameters": {
                            "xpathQuery": "//SELECT_COLUMN/MULTIPLICATION",
                            "message": "message B"
                          }
                        }
                      }
                    }
                    """.trimIndent()
                )
            }
            val issuesPath = root.resolve("issues.json")

            Main(
                Arguments().apply {
                    sources = sourceDirectory.absolutePath
                    configFile = configPath.absolutePath
                    outputFormat = GENERIC_ISSUE_FORMAT
                    outputFile = issuesPath.absolutePath
                    extensions = "sql"
                }
            ).run()

            val issues = jacksonObjectMapper()
                .readTree(issuesPath)
                .get("issues")
                .elements()
                .asSequence()
                .map { issue ->
                    Triple(
                        issue.get("ruleId").asText(),
                        issue.get("severity").asText(),
                        issue.get("primaryLocation").get("message").asText()
                    )
                }
                .toList()
                .sortedBy { it.first }

            assertEquals(
                listOf(
                    Triple("zpa:FirstXPath", "MINOR", "message A"),
                    Triple("zpa:SecondXPath", "MAJOR", "message B")
                ),
                issues
            )
        } finally {
            root.deleteRecursively()
        }
    }

    private fun xpathRepository(): Repository {
        val repository = Repository("zpa")
        CustomAnnotationBasedRulesDefinition.load(
            repository,
            "plsqlopen",
            listOf(XPathCheck::class.java),
            RuleMetadataLoader()
        )
        return repository
    }

    private fun templateConfig(): ConfigFile {
        return ConfigFile(
            base = BaseRuleCategory.NONE,
            rules = mapOf(
                "FirstXPath" to options(
                    level = RuleLevel.MINOR,
                    templateRuleKey = "XPath",
                    parameters = mapOf(
                        "xpathQuery" to "//SELECT_COLUMN/MULTIPLICATION",
                        "message" to "message A"
                    )
                ),
                "SecondXPath" to options(
                    level = RuleLevel.MAJOR,
                    templateRuleKey = "zpa:XPath",
                    parameters = mapOf(
                        "xpathQuery" to "//SELECT_COLUMN/MULTIPLICATION",
                        "message" to "message B"
                    )
                )
            )
        )
    }

    private fun configuredActiveRules(config: ConfigFile, repository: Repository): CliActiveRules {
        return CliActiveRules(config)
            .addRepository(repository)
            .addRuleConfigurer { repo, rule, configuration: ActiveRuleConfiguration ->
                var ruleConfig = config.rules["${repo.key}:${rule.key}"] ?: config.rules[rule.key]
                if (config.base == BaseRuleCategory.DEFAULT && rule.isActivatedByDefault) {
                    ruleConfig = ruleConfig ?: RuleConfiguration()
                }

                if (ruleConfig == null || ruleConfig.options.level == RuleLevel.OFF) {
                    return@addRuleConfigurer false
                }

                if (ruleConfig.options.level != RuleLevel.ON) {
                    configuration.severity = ruleConfig.options.level.toString()
                }
                configuration.parameters.putAll(ruleConfig.options.parameters)
                true
            }
    }

    private fun options(
        level: RuleLevel,
        templateRuleKey: String,
        parameters: Map<String, String>
    ): RuleConfiguration {
        return RuleConfiguration().apply {
            this.options = RuleOptions().apply {
                this.level = level
                this.templateRuleKey = templateRuleKey
                this.parameters = parameters
            }
        }
    }
}
