package br.com.felipezorzo.zpa.cli.config

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConfigFileTest {

    private val mapper = jacksonObjectMapper()

    @Test
    fun templateRuleKeyIsOptionalAndDeserializesFromRuleOptions() {
        val config = mapper.readValue(
            """
            {
              "rules": {
                "FirstXPath": {
                  "level": "minor",
                  "parameters": {
                    "xpathQuery": "//SELECT_COLUMN/MULTIPLICATION",
                    "message": "message A"
                  },
                  "templateRuleKey": "zpa:XPath"
                }
              }
            }
            """.trimIndent(),
            ConfigFile::class.java
        )

        val options = config.rules.getValue("FirstXPath").options
        assertEquals(RuleLevel.MINOR, options.level)
        assertEquals(
            mapOf(
                "xpathQuery" to "//SELECT_COLUMN/MULTIPLICATION",
                "message" to "message A"
            ),
            options.parameters
        )
        assertEquals("zpa:XPath", options.templateRuleKey)

        val legacyOptions = mapper.readValue(
            """{"rules":{"XPath":"minor"}}""",
            ConfigFile::class.java
        ).rules.getValue("XPath").options
        assertEquals(RuleLevel.MINOR, legacyOptions.level)
        assertTrue(legacyOptions.parameters.isEmpty())
        assertEquals(null, legacyOptions.templateRuleKey)
    }

    @Test
    fun legacyObjectConfigurationRetainsLevelAndParameters() {
        val config = mapper.readValue(
            """
            {
              "rules": {
                "XPath": {
                  "level": "minor",
                  "parameters": {
                    "xpathQuery": "//STATEMENT",
                    "message": "Avoid statements"
                  }
                }
              }
            }
            """.trimIndent(),
            ConfigFile::class.java
        )

        val options = config.rules.getValue("XPath").options
        assertEquals(RuleLevel.MINOR, options.level)
        assertEquals("//STATEMENT", options.parameters.getValue("xpathQuery"))
        assertEquals("Avoid statements", options.parameters.getValue("message"))
        assertEquals(null, options.templateRuleKey)

        val serialized = mapper.writeValueAsString(config)
        assertFalse(serialized.contains("templateRuleKey"))
        assertEquals(
            "//STATEMENT",
            mapper.readTree(serialized).get("rules").get("XPath").get("parameters").get("xpathQuery").asText()
        )
    }

    @Test
    fun templateRuleKeySurvivesSerializationRoundTrip() {
        val config = ConfigFile(
            base = BaseRuleCategory.NONE,
            rules = mapOf(
                "FirstXPath" to RuleConfiguration().apply {
                    options = RuleOptions().apply {
                        level = RuleLevel.MAJOR
                        templateRuleKey = "zpa:XPath"
                    }
                }
            )
        )

        val serialized = mapper.writeValueAsString(config)
        val serializedRule = mapper.readTree(serialized).get("rules").get("FirstXPath")
        assertTrue(serializedRule.isObject)
        assertEquals("zpa:XPath", serializedRule.get("templateRuleKey").asText())

        val roundTrip = mapper.readValue(serialized, ConfigFile::class.java)
        assertEquals(RuleLevel.MAJOR, roundTrip.rules.getValue("FirstXPath").options.level)
        assertEquals("zpa:XPath", roundTrip.rules.getValue("FirstXPath").options.templateRuleKey)
    }

    @Test
    fun schemaDocumentsTemplateRuleKeyAsAnOptionalString() {
        val schema = mapper.readTree(File("schema.json"))
        val templateRuleKey = schema.get("\$defs")
            .get("RuleOptions")
            .get("properties")
            .get("templateRuleKey")

        assertEquals("string", templateRuleKey.get("type").asText())
        assertTrue(templateRuleKey.get("description").asText().contains("repository:rule"))
    }
}
