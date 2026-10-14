package br.com.felipezorzo.zpa.cli.oracle

import br.com.felipezorzo.zpa.cli.execute
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.sql.*
import java.util.*
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A driver whose connections always fail with a message carrying the URL and the password, as some drivers do. */
class FailingTestDriver : Driver {
    override fun connect(url: String, info: Properties): Connection? {
        if (!acceptsURL(url)) return null
        throw SQLException("Listener refused $url for ${info["user"]} with password ${info["password"]}", "08006", 12514)
    }

    override fun acceptsURL(url: String) = url.startsWith("jdbc:zpatest:")
    override fun getPropertyInfo(url: String?, info: Properties?): Array<DriverPropertyInfo> = emptyArray()
    override fun getMajorVersion() = 1
    override fun getMinorVersion() = 0
    override fun jdbcCompliant() = false
    override fun getParentLogger(): Logger = throw SQLFeatureNotSupportedException()
}

/** A driver that fails while checking its own URLs, with the URL in the message. */
class ThrowingLookupTestDriver : Driver {
    override fun connect(url: String, info: Properties): Connection? = null
    override fun acceptsURL(url: String): Boolean {
        if (url.startsWith("jdbc:zpathrow:")) throw SQLException("Malformed URL $url", "08001", 17067)
        return false
    }
    override fun getPropertyInfo(url: String?, info: Properties?): Array<DriverPropertyInfo> = emptyArray()
    override fun getMajorVersion() = 1
    override fun getMinorVersion() = 0
    override fun jdbcCompliant() = false
    override fun getParentLogger(): Logger = throw SQLFeatureNotSupportedException()
}

class OracleCliFailureTest {
    private val secret = "s3cr3t-Pa55"

    private class Run(val exitCode: Int, val stderr: String)

    private fun run(root: File, vararg args: String): Run {
        val sources = root.resolve("src").apply {
            mkdirs()
            resolve("p.pks").writeText("CREATE PACKAGE p AS v employees.salary%TYPE; END p;")
        }
        val err = ByteArrayOutputStream()
        val original = System.err
        System.setErr(PrintStream(err, true, StandardCharsets.UTF_8))
        val exitCode = try {
            execute(arrayOf("--sources", sources.absolutePath, *args), mapOf("ZPA_ORACLE_PASSWORD" to secret)::get)
        } finally {
            System.setErr(original)
        }
        return Run(exitCode, err.toString(StandardCharsets.UTF_8))
    }

    private fun withRoot(block: (File) -> Unit) {
        val root = Files.createTempDirectory("zpa-cli-oracle-failure").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun connectionFailureAbortsBeforeAnyOutputWithoutLeakingSecrets() = withRoot { root ->
        val output = root.resolve("out.json")
        val report = root.resolve("types.json")
        val url = "jdbc:zpatest://db.internal:1521/SVC"
        val run = run(
            root, "--oracle-url", url, "--oracle-user", "app", "--oracle-schemas", "HR",
            "--output-format", "json", "--output-file", output.absolutePath, "--type-report", report.absolutePath
        )
        assertEquals(3, run.exitCode)
        assertTrue(run.stderr.contains("Oracle metadata acquisition failed (error 12514)"), run.stderr)
        assertFalse(run.stderr.contains(secret), run.stderr)
        assertFalse(run.stderr.contains(url), run.stderr)
        assertFalse(run.stderr.contains("\tat "), "no stack trace: ${run.stderr}")
        assertFalse(output.exists())
        assertFalse(report.exists())
    }

    @Test
    fun driverLookupFailureIsSanitized() = withRoot { root ->
        val output = root.resolve("out.json")
        val url = "jdbc:zpathrow://db.internal:1521/SVC?token=$secret"
        val run = run(root, "--oracle-url", url, "--oracle-user", "app", "--oracle-schemas", "HR", "--output-format", "json", "--output-file", output.absolutePath)
        assertEquals(3, run.exitCode)
        assertTrue(run.stderr.contains("JDBC driver lookup failed (error 17067)"), run.stderr)
        assertFalse(run.stderr.contains(url), run.stderr)
        assertFalse(run.stderr.contains(secret), run.stderr)
        assertFalse(run.stderr.contains("\tat "), "no stack trace: ${run.stderr}")
        assertFalse(output.exists())
    }

    @Test
    fun missingDriverIsReportedWithTheDriverDirectoryAndWithoutTheUrl() = withRoot { root ->
        val url = "jdbc:oracle:thin:@//db.internal:1521/SVC"
        val run = run(root, "--oracle-url", url, "--oracle-user", "app", "--oracle-schemas", "HR")
        assertEquals(3, run.exitCode)
        assertTrue(run.stderr.contains("No JDBC driver accepts the configured --oracle-url"), run.stderr)
        assertTrue(run.stderr.contains("drivers"), run.stderr)
        // The registered provider whose class does not exist is skipped and reported, not fatal.
        assertTrue(run.stderr.contains("1 JDBC driver provider(s) could not be loaded"), run.stderr)
        assertFalse(run.stderr.contains(url), run.stderr)
    }

    @Test
    fun invalidConfigurationFailsBeforeAnyConnection() = withRoot { root ->
        val run = run(root, "--oracle-url", "jdbc:zpatest:x", "--oracle-user", "app")
        assertEquals(2, run.exitCode)
        assertFalse(run.stderr.contains("Listener refused"), run.stderr)
        val syntaxOnly = run(root, "--syntax-only", "--oracle-url", "jdbc:zpatest:x", "--oracle-user", "app", "--oracle-schemas", "HR")
        assertEquals(2, syntaxOnly.exitCode)
        assertFalse(syntaxOnly.stderr.contains("Listener refused"), syntaxOnly.stderr)
    }
}
