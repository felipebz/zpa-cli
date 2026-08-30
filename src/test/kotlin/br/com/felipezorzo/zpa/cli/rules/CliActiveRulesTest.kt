package br.com.felipezorzo.zpa.cli.rules

import br.com.felipezorzo.zpa.cli.config.BaseRuleCategory
import br.com.felipezorzo.zpa.cli.config.ConfigFile
import br.com.felipezorzo.zpa.cli.config.RuleConfiguration
import br.com.felipezorzo.zpa.cli.config.RuleLevel
import br.com.felipezorzo.zpa.cli.config.RuleOptions
import com.felipebz.zpa.api.annotations.RuleInfo
import com.felipebz.zpa.rules.Repository
import com.felipebz.zpa.rules.RuleStatus
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class CliActiveRulesTest {

    @Test
    fun unqualifiedAndQualifiedTemplateKeysResolveInTheSameRepository() {
        val repository = repository("XPath")
        val config = ConfigFile(
            base = BaseRuleCategory.NONE,
            rules = mapOf(
                "FirstXPath" to options(templateRuleKey = "XPath"),
                "SecondXPath" to options(templateRuleKey = "zpa:XPath")
            )
        )

        val customRules = CliActiveRules(config)
            .addRepository(repository)
            .addCustomRulesByConfig(config, repository)

        assertEquals(setOf("FirstXPath", "SecondXPath"), customRules.map { it.key }.toSet())
        assertEquals(setOf("XPath"), customRules.map { (it as CliCustomRule).templateRuleKey }.toSet())
    }

    @Test
    fun qualifiedConfiguredInstanceKeysAreNormalizedBeforeActivation() {
        val repository = repository("XPath")
        val config = ConfigFile(
            base = BaseRuleCategory.NONE,
            rules = mapOf("zpa:FirstXPath" to options(templateRuleKey = "zpa:XPath"))
        )

        val activeRule = CliActiveRules(config)
            .addRepository(repository)
            .findByRepository("zpa")
            .single { it.templateRuleKey != null }

        assertEquals("zpa:FirstXPath", activeRule.ruleKey.toString())
        assertEquals("FirstXPath", activeRule.internalKey)
        assertEquals("XPath", activeRule.templateRuleKey)
    }

    @Test
    fun unresolvedTemplateFailsWithInstanceTemplateAndRepository() {
        val config = ConfigFile(
            base = BaseRuleCategory.NONE,
            rules = mapOf("MyRule" to options(templateRuleKey = "DoesNotExist"))
        )

        val exception = assertFailsWith<IllegalArgumentException> {
            CliActiveRules(config)
                .addRepository(repository("XPath"))
                .findByRepository("zpa")
        }

        assertTrue(exception.message.orEmpty().contains("MyRule"))
        assertTrue(exception.message.orEmpty().contains("DoesNotExist"))
        assertTrue(exception.message.orEmpty().contains("zpa"))
    }

    @Test
    fun explicitlyQualifiedTemplateRepositoryDoesNotFallBackToAnotherRepository() {
        val zpaRepository = repository("SomeTemplate")
        val customRepository = repository("SomeTemplate", key = "custom")
        val config = ConfigFile(
            base = BaseRuleCategory.NONE,
            rules = mapOf("custom:SomeInstance" to options(templateRuleKey = "custom:SomeTemplate"))
        )

        val activeRules = CliActiveRules(config)
            .addRepository(zpaRepository)
            .addRepository(customRepository)

        val zpaRules = activeRules.findByRepository("zpa")
        val customRules = activeRules.findByRepository("custom")

        assertTrue(zpaRules.none { it.internalKey == "SomeInstance" })
        assertTrue(customRules.any { it.ruleKey.toString() == "custom:SomeInstance" })
        assertEquals(
            "SomeTemplate",
            customRules.single { it.internalKey == "SomeInstance" }.templateRuleKey
        )
    }

    @Test
    fun unknownExplicitTemplateRepositoryFailsClearly() {
        val config = ConfigFile(
            base = BaseRuleCategory.NONE,
            rules = mapOf("MyRule" to options(templateRuleKey = "missing:DoesNotExist"))
        )

        val exception = assertFailsWith<IllegalArgumentException> {
            CliActiveRules(config)
                .addRepository(repository("XPath"))
                .findByRepository("zpa")
        }

        assertTrue(exception.message.orEmpty().contains("MyRule"))
        assertTrue(exception.message.orEmpty().contains("missing:DoesNotExist"))
        assertTrue(exception.message.orEmpty().contains("missing"))
    }

    @Test
    fun defaultBaseKeepsTheOriginalDefaultRuleAlongsideItsConfiguredInstance() {
        val repository = repository("SomeRule").apply {
            rule("SomeRule")!!.isActivatedByDefault = true
        }
        val config = ConfigFile(
            base = BaseRuleCategory.DEFAULT,
            rules = mapOf("CustomInstance" to options(templateRuleKey = "SomeRule"))
        )

        val activeRules = configuredActiveRules(config, repository)
        val active = activeRules.findByRepository("zpa")

        assertEquals(setOf("SomeRule", "CustomInstance"), active.map { it.internalKey }.toSet())
        assertEquals(null, active.single { it.internalKey == "SomeRule" }.templateRuleKey)
        assertEquals("SomeRule", active.single { it.internalKey == "CustomInstance" }.templateRuleKey)
    }

    @Test
    fun explicitlyDisabledTemplateCanBeReplacedByAnEnabledInstance() {
        val repository = repository("SomeRule").apply {
            rule("SomeRule")!!.isActivatedByDefault = true
        }
        val config = ConfigFile(
            base = BaseRuleCategory.DEFAULT,
            rules = mapOf(
                "SomeRule" to options(level = RuleLevel.OFF),
                "CustomInstance" to options(templateRuleKey = "SomeRule")
            )
        )

        val active = configuredActiveRules(config, repository).findByRepository("zpa")

        assertEquals(listOf("CustomInstance"), active.map { it.internalKey })
        assertEquals("SomeRule", active.single().templateRuleKey)
    }

    @Test
    fun offInstanceDoesNotInterfereWithAnotherInstance() {
        val repository = repository("XPath")
        val config = ConfigFile(
            base = BaseRuleCategory.NONE,
            rules = mapOf(
                "FirstXPath" to options(level = RuleLevel.OFF, templateRuleKey = "XPath"),
                "SecondXPath" to options(level = RuleLevel.MINOR, templateRuleKey = "XPath")
            )
        )

        val active = configuredActiveRules(config, repository).findByRepository("zpa")

        assertEquals(listOf("SecondXPath"), active.map { it.internalKey })
        assertEquals("MINOR", active.single().severity)
    }

    @Test
    fun disablingAnInstanceDoesNotDisableAnIndependentlyActiveOriginalRule() {
        val repository = repository("SomeRule").apply {
            rule("SomeRule")!!.isActivatedByDefault = true
        }
        val config = ConfigFile(
            base = BaseRuleCategory.DEFAULT,
            rules = mapOf("CustomInstance" to options(level = RuleLevel.OFF, templateRuleKey = "SomeRule"))
        )

        val active = configuredActiveRules(config, repository).findByRepository("zpa")

        assertEquals(listOf("SomeRule"), active.map { it.internalKey })
        assertEquals(null, active.single().templateRuleKey)
    }

    @Test
    fun explicitTemplateKeyOnTheOriginalRuleDoesNotDuplicateExecution() {
        val repository = repository("XPath")
        val config = ConfigFile(
            base = BaseRuleCategory.NONE,
            rules = mapOf("XPath" to options(level = RuleLevel.MINOR, templateRuleKey = "XPath"))
        )

        val active = configuredActiveRules(config, repository).findByRepository("zpa")

        assertEquals(1, active.size)
        assertEquals("XPath", active.single().ruleKey.rule)
        assertEquals("MINOR", active.single().severity)
        assertEquals(null, active.single().templateRuleKey)
    }

    @Test
    fun legacyStringConfigurationStillActivatesTheOriginalRule() {
        val repository = repository("XPath")
        val config = ConfigFile(
            base = BaseRuleCategory.NONE,
            rules = mapOf("XPath" to options(level = RuleLevel.MINOR))
        )

        val active = configuredActiveRules(config, repository).findByRepository("zpa")

        assertEquals(1, active.size)
        assertEquals("zpa:XPath", active.single().ruleKey.toString())
        assertEquals("MINOR", active.single().severity)
        assertEquals(null, active.single().templateRuleKey)
    }

    @Test
    fun legacyObjectConfigurationStillPassesParametersToTheOriginalRule() {
        val repository = repository("XPath")
        val config = ConfigFile(
            base = BaseRuleCategory.NONE,
            rules = mapOf(
                "XPath" to options(
                    level = RuleLevel.MINOR,
                    parameters = mapOf(
                        "xpathQuery" to "//SELECT_COLUMN/MULTIPLICATION",
                        "message" to "legacy message"
                    )
                )
            )
        )

        val active = configuredActiveRules(config, repository).findByRepository("zpa")

        assertEquals(mapOf("xpathQuery" to "//SELECT_COLUMN/MULTIPLICATION", "message" to "legacy message"), active.single().params)
        assertEquals(null, active.single().templateRuleKey)
    }

    @Test
    fun syntheticRuleCopiesExecutionMetadataAndParameterDefinitions() {
        val repository = repository("Template")
        val template = repository.rule("Template")!!.apply {
            name = "Template name"
            remediationConstant = "10min"
            scope = RuleInfo.Scope.MAIN
            severity = "MAJOR"
            status = RuleStatus.BETA
            tags = arrayOf("bug", "convention")
            htmlDescription = "Template description"
            isActivatedByDefault = true
            template = true
            createParam("message").apply {
                description = "Issue message"
                defaultValue = "default message"
            }
        }
        val config = ConfigFile(
            base = BaseRuleCategory.NONE,
            rules = mapOf("Instance" to options(templateRuleKey = "Template"))
        )

        val synthetic = CliActiveRules(config)
            .addRepository(repository)
            .addCustomRulesByConfig(config, repository)
            .single() as CliCustomRule

        assertEquals("Template name", synthetic.name)
        assertEquals("10min", synthetic.remediationConstant)
        assertEquals(RuleInfo.Scope.MAIN, synthetic.scope)
        assertEquals("MAJOR", synthetic.severity)
        assertEquals(RuleStatus.BETA, synthetic.status)
        assertContentEquals(arrayOf("bug", "convention"), synthetic.tags)
        assertNotSame(template.tags, synthetic.tags)
        assertEquals("Template description", synthetic.htmlDescription)
        assertTrue(synthetic.isActivatedByDefault)
        assertFalse(synthetic.template)
        assertEquals(1, synthetic.params.size)
        assertEquals("message", synthetic.params.single().key)
        assertEquals("Issue message", synthetic.params.single().description)
        assertEquals("default message", synthetic.params.single().defaultValue)
        assertNotSame(template.params.single(), synthetic.params.single())
    }

    private fun configuredActiveRules(config: ConfigFile, repository: Repository): CliActiveRules {
        return CliActiveRules(config)
            .addRepository(repository)
            .addRuleConfigurer { repo, rule, configuration ->
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
        level: RuleLevel = RuleLevel.ON,
        templateRuleKey: String? = null,
        parameters: Map<String, String> = emptyMap()
    ): RuleConfiguration {
        return RuleConfiguration().apply {
            this.options = RuleOptions().apply {
                this.level = level
                this.templateRuleKey = templateRuleKey
                this.parameters = parameters
            }
        }
    }

    private fun repository(ruleKey: String, key: String = "zpa"): Repository {
        return Repository(key).apply {
            createRule(ruleKey)
        }
    }
}
