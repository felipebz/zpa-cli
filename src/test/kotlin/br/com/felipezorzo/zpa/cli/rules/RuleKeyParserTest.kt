package br.com.felipezorzo.zpa.cli.rules

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RuleKeyParserTest {

    @Test
    fun parsesUnqualifiedRuleKeys() {
        val key = RuleKeyParser.parse("XPath")

        assertEquals("", key.repository)
        assertEquals("XPath", key.rule)
    }

    @Test
    fun parsesQualifiedRuleKeys() {
        val key = RuleKeyParser.parse("zpa:XPath")

        assertEquals("zpa", key.repository)
        assertEquals("XPath", key.rule)
        assertEquals("zpa:XPath", key.toString())
    }

    @Test
    fun rejectsMalformedRuleKeys() {
        for (malformed in listOf("", ":XPath", "zpa:", "zpa:XPath:extra", " zpa:XPath")) {
            val exception = assertFailsWith<IllegalArgumentException> {
                RuleKeyParser.parse(malformed)
            }
            assertTrue(exception.message.orEmpty().contains("Expected 'rule' or 'repository:rule'"))
        }
    }
}
