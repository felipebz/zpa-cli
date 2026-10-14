package br.com.felipezorzo.zpa.cli.oracle

import br.com.felipezorzo.zpa.cli.Arguments
import br.com.felipezorzo.zpa.cli.CliValidationException
import com.beust.jcommander.JCommander
import com.felipebz.zpa.api.annotations.ZpaExperimentalApi
import kotlin.test.*

@OptIn(ZpaExperimentalApi::class)
class OracleSettingsTest {
    private val secret = "s3cr3t-Pa55"

    private fun args(vararg values: String): Arguments {
        val arguments = Arguments()
        JCommander.newBuilder().addObject(arguments).build().parse("--sources", ".", *values)
        return arguments
    }

    private fun settings(vararg values: String, environment: Map<String, String> = mapOf("ZPA_ORACLE_PASSWORD" to secret)) =
        OracleSettings.from(args(*values), environment::get)

    private fun failure(vararg values: String, environment: Map<String, String> = mapOf("ZPA_ORACLE_PASSWORD" to secret)) =
        assertFailsWith<CliValidationException> { settings(*values, environment = environment) }.message!!

    @Test
    fun noOracleOptionMeansNoDatabaseAccess() {
        assertNull(settings())
        assertNull(settings("--compilation-schema", "HR"))
    }

    @Test
    fun completeSettingsReadThePasswordFromTheEnvironment() {
        val settings = settings(
            "--oracle-url", "jdbc:oracle:thin:@//db:1521/FREEPDB1", "--oracle-user", "app",
            "--oracle-schemas", "HR, SALES,HR", "--oracle-relations", "REF.COUNTRIES"
        )!!
        assertEquals(secret, settings.password)
        assertEquals(listOf("HR", "SALES"), settings.schemas)
        assertEquals(listOf("REF" to "COUNTRIES"), settings.relations)
        assertFalse(settings.toString().contains(secret))
        assertFalse(settings.toString().contains("db:1521"))

        val custom = settings(
            "--oracle-url", "jdbc:oracle:thin:@db", "--oracle-user", "app", "--oracle-schemas", "HR",
            "--oracle-password-env", "MY_DB_PASSWORD", environment = mapOf("MY_DB_PASSWORD" to "other")
        )!!
        assertEquals("other", custom.password)
    }

    @Test
    fun incompleteOrConflictingSettingsAreRejected() {
        assertTrue(failure("--oracle-schemas", "HR").contains("require --oracle-url"))
        assertTrue(failure("--oracle-url", "jdbc:oracle:thin:@db", "--oracle-schemas", "HR").contains("--oracle-user"))
        assertTrue(failure("--oracle-url", "jdbc:oracle:thin:@db", "--oracle-user", "app").contains("--oracle-schemas or --oracle-relations"))
        assertTrue(failure("--oracle-url", "jdbc:oracle:thin:@db", "--oracle-user", "app", "--oracle-relations", "COUNTRIES").contains("SCHEMA.RELATION"))
        assertTrue(failure("--oracle-url", "jdbc:oracle:thin:@db", "--oracle-user", "app", "--oracle-schemas", "\"hr\"").contains("exact dictionary names"))
        assertTrue(failure("--syntax-only", "--oracle-url", "jdbc:oracle:thin:@db").contains("--syntax-only"))
        assertTrue(failure("--syntax-only", "--compilation-schema", "HR").contains("--syntax-only"))
        val missing = failure("--oracle-url", "jdbc:oracle:thin:@db", "--oracle-user", "app", "--oracle-schemas", "HR", environment = emptyMap())
        assertTrue(missing.contains("'ZPA_ORACLE_PASSWORD' is not set"))
        assertFailsWith<CliValidationException> { OracleSettings.compilationSchema(args("--compilation-schema", "\"HR\"")) }
    }

    @Test
    fun urlsCarryingCredentialsAreRejectedWithoutEchoingThem() {
        for (url in listOf("jdbc:oracle:thin:scott/$secret@//db:1521/FREEPDB1", "jdbc:oracle:thin:@db?oracle.jdbc.password=$secret")) {
            val message = failure("--oracle-url", url, "--oracle-user", "app", "--oracle-schemas", "HR")
            assertTrue(message.contains("must not contain credentials"))
            assertFalse(message.contains(secret))
        }
    }

    @Test
    fun driverMessagesAreRedacted() {
        val settings = OracleSettings("jdbc:oracle:thin:@//db.internal:1521/SVC", "app", secret, listOf("HR"), emptyList())
        val redacted = OracleMetadataLoader.redact(
            "IO Error for jdbc:oracle:thin:@//db.internal:1521/SVC (password $secret, via scott/tiger@db)", settings
        )
        assertFalse(redacted.contains(secret))
        assertFalse(redacted.contains("jdbc:oracle:thin:@//db.internal:1521/SVC"))
        assertFalse(redacted.contains("tiger"))
        assertTrue(redacted.contains("<oracle-url>"))
    }
}
