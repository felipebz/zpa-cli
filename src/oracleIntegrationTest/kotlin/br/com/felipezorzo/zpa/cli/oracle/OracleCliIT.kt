package br.com.felipezorzo.zpa.cli.oracle

import br.com.felipezorzo.zpa.cli.InputFile
import br.com.felipezorzo.zpa.cli.Main
import br.com.felipezorzo.zpa.cli.execute
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.felipebz.flr.api.AstNode
import com.felipebz.zpa.api.PlSqlFile
import com.felipebz.zpa.api.PlSqlGrammar
import com.felipebz.zpa.api.annotations.ZpaExperimentalApi
import com.felipebz.zpa.api.checks.PlSqlCheck
import com.felipebz.zpa.api.project.ProjectExternalTypeDescription
import com.felipebz.zpa.api.project.ProjectIdentifier
import com.felipebz.zpa.api.project.ProjectPackageValueView
import com.felipebz.zpa.project.FileId
import com.felipebz.zpa.squid.AstScanner
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The CLI against a real Oracle database: packages anchored to real table and view columns, analyzed offline and with
 * metadata acquired by the CLI. The Oracle JDBC driver comes from this suite's class path.
 */
@OptIn(ZpaExperimentalApi::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OracleCliIT {
    private lateinit var database: OracleDatabase
    private lateinit var owner: OracleDatabase.User
    private lateinit var reference: OracleDatabase.User
    private val root: File = Files.createTempDirectory("zpa-cli-oracle-it").toFile()
    private val sources = root.resolve("src")
    private val mapper = jacksonObjectMapper()

    @BeforeAll
    fun createFixture() {
        database = OracleDatabase.connect()
        owner = database.createUser("ZPACLI_HR", "CREATE TABLE", "CREATE VIEW", "CREATE PROCEDURE", "CREATE SYNONYM")
        reference = database.createUser("ZPACLI_REF", "CREATE TABLE")
        reference.execute(
            "CREATE TABLE countries (iso CHAR(2 BYTE), name VARCHAR2(60 CHAR))",
            "GRANT SELECT ON countries TO ${owner.name}"
        )
        owner.execute(
            "CREATE TABLE employees (id NUMBER(6) PRIMARY KEY, name VARCHAR2(40 CHAR) NOT NULL, code CHAR(3 BYTE), " +
                "salary NUMBER(10,2), hired DATE, secret VARCHAR2(10) INVISIBLE, note CLOB)",
            "CREATE VIEW emp_v AS SELECT id, name, salary * 12 AS annual FROM employees",
            "CREATE SYNONYM emp_syn FOR employees"
        )
        val hrApi = """
            CREATE OR REPLACE PACKAGE hr_api AS
              v_salary employees.salary%TYPE;
              v_name employees.name%TYPE;
              v_hired employees.hired%TYPE;
              r_employee employees%ROWTYPE;
              r_view emp_v%ROWTYPE;
              TYPE salaries IS TABLE OF employees.salary%TYPE;
              FUNCTION salary_of(p_id employees.id%TYPE) RETURN employees.salary%TYPE;
            END hr_api;
            """.trimIndent()
        // The anchors are real: Oracle compiles the specification.
        owner.execute(hrApi)
        assertEquals(listOf("VALID"), owner.query("SELECT status FROM user_objects WHERE object_name = 'HR_API'") { it.getString(1) })
        sources.mkdirs()
        sources.resolve("hr_api.pks").writeText(hrApi)
        sources.resolve("gaps.pks").writeText(
            """
            CREATE OR REPLACE PACKAGE gaps AS
              v_bonus employees.bonus%TYPE;
              v_synonym emp_syn.salary%TYPE;
              v_country ${reference.name}.countries.name%TYPE;
              v_city ${reference.name}.cities.name%TYPE;
            END gaps;
            """.trimIndent()
        )
        sources.resolve("billing.pks").writeText("CREATE OR REPLACE PACKAGE otherapp.billing AS v_salary employees.salary%TYPE; END billing;")
        sources.resolve("job.sql").writeText("BEGIN\n  IF 1 = 1 THEN\n    NULL;\n  END IF;\nEND;\n/\n")
    }

    @AfterAll
    fun dropFixture() {
        root.deleteRecursively()
        if (::database.isInitialized) database.close()
    }

    private class Run(val exitCode: Int, val stdout: String, val stderr: String)

    private fun cli(vararg args: String, password: String? = owner.password): Run {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val (originalOut, originalErr) = System.out to System.err
        System.setOut(PrintStream(out, true, StandardCharsets.UTF_8))
        System.setErr(PrintStream(err, true, StandardCharsets.UTF_8))
        val exitCode = try {
            execute(arrayOf("--sources", sources.absolutePath, *args), mapOf("ZPA_ORACLE_PASSWORD" to password)::get)
        } finally {
            System.setOut(originalOut)
            System.setErr(originalErr)
        }
        return Run(exitCode, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8))
    }

    private fun oracleOptions(user: String = owner.name) = arrayOf(
        "--oracle-url", database.jdbcUrl, "--oracle-user", user,
        "--oracle-schemas", owner.name, "--oracle-relations", "${reference.name}.COUNTRIES", "--compilation-schema", owner.name
    )

    private fun typeReport(): JsonNode {
        val report = root.resolve("types.json")
        val run = cli(*oracleOptions(), "--output-format", "json", "--output-file", root.resolve("issues.json").absolutePath, "--type-report", report.absolutePath)
        assertEquals(0, run.exitCode, run.stderr)
        return mapper.readTree(report)
    }

    private fun JsonNode.reference(declaration: String, element: String = "type"): JsonNode =
        get("references").single { it.get("declaration").asText() == declaration && it.get("element").asText() == element }

    @Test
    fun offlineAnalysisIsUnchangedByMetadata() {
        for (format in listOf("json", "sq-generic-issue-import")) {
            val offline = root.resolve("offline.$format")
            val online = root.resolve("online.$format")
            assertEquals(0, cli("--output-format", format, "--output-file", offline.absolutePath).exitCode)
            val run = cli(*oracleOptions(), "--output-format", format, "--output-file", online.absolutePath)
            assertEquals(0, run.exitCode, run.stderr)
            assertEquals(mapper.readTree(offline), mapper.readTree(online))
        }
        val console = cli("--fail-on", "any")
        val consoleWithMetadata = cli(*oracleOptions(), "--fail-on", "any")
        assertEquals(1, console.exitCode)
        assertEquals(console.exitCode, consoleWithMetadata.exitCode)
        assertEquals(console.stdout, consoleWithMetadata.stdout)
    }

    @Test
    fun acquisitionAndTypeResolution() {
        val report = typeReport()
        val metadata = report.get("metadata")
        assertEquals(owner.name, metadata.get("sessionUser").asText())
        assertEquals(listOf(owner.name), metadata.get("completeSchemas").map { it.asText() })

        // %TYPE: the datatype Oracle declared for the column.
        val columns = owner.query("SELECT column_name, data_type, data_precision, data_scale, char_length, char_used FROM user_tab_columns WHERE table_name = 'EMPLOYEES'") {
            it.getString(1) to listOf(it.getString(2), it.getString(3), it.getString(4), it.getString(5), it.getString(6))
        }.toMap()
        assertEquals(listOf("NUMBER", "10", "2", "0", null), columns["SALARY"])
        assertEquals(listOf("VARCHAR2", null, null, "40", "C"), columns["NAME"])
        assertEquals("NUMBER(10,2)", report.reference("hr_api.v_salary").get("type").get("display").asText())
        assertEquals("VARCHAR2(40 CHAR)", report.reference("hr_api.v_name").get("type").get("display").asText())
        assertEquals("DATE", report.reference("hr_api.v_hired").get("type").get("display").asText())
        assertEquals("NUMBER(10,2)", report.reference("hr_api.salaries", "element").get("type").get("display").asText())
        assertEquals("NUMBER(6,0)", report.reference("hr_api.salary_of", "parameter p_id").get("type").get("display").asText())
        assertEquals("RESOLVED", report.reference("hr_api.salary_of", "return").get("status").asText())

        // %ROWTYPE: Oracle's visible columns in column order; the invisible column is not a field.
        val visible = owner.query("SELECT column_name FROM user_tab_columns WHERE table_name = 'EMPLOYEES' AND column_id IS NOT NULL ORDER BY column_id") { it.getString(1) }
        val row = report.reference("hr_api.r_employee")
        assertEquals("RESOLVED", row.get("status").asText())
        assertEquals(visible, row.get("rowFields").map { it.get("name").asText() })
        assertEquals(listOf("ID", "NAME", "CODE", "SALARY", "HIRED", "NOTE"), visible)
        assertEquals("CHAR(3 BYTE)", row.get("rowFields")[2].get("type").get("display").asText())
        assertTrue(owner.accepts("DECLARE r employees%ROWTYPE; BEGIN r.note := 'x'; END;"))
        assertFalse(owner.accepts("DECLARE r employees%ROWTYPE; BEGIN r.secret := 'x'; END;"))
        assertEquals(listOf("ID", "NAME", "ANNUAL"), report.reference("hr_api.r_view").get("rowFields").map { it.get("name").asText() })
    }

    @Test
    fun uncoveredOrUnprovableReferencesStayInconclusive() {
        val report = typeReport()
        assertEquals("COLUMN_NOT_FOUND", report.reference("gaps.v_bonus").get("status").asText())
        // Oracle resolves the synonym, the metadata has no synonyms: not found, never a resolved guess.
        assertTrue(owner.accepts("DECLARE v emp_syn.salary%TYPE; BEGIN NULL; END;"))
        assertEquals("RELATION_NOT_FOUND", report.reference("gaps.v_synonym").get("status").asText())
        // The other schema was read for one relation only; its other relations are unknown.
        // A schema-qualified anchor: an object of that name the project cannot see could capture it.
        assertEquals("CAPTURE_NOT_EXCLUDED", report.reference("gaps.v_country").get("status").asText())
        assertEquals("INCOMPLETE", report.reference("gaps.v_city").get("status").asText())
        // A unit of another schema is never described in the default compilation schema.
        val billing = report.reference("otherapp.billing.v_salary")
        assertEquals("OTHERAPP", billing.get("compilationSchema").asText())
        assertEquals("INCOMPLETE", billing.get("status").asText())
        assertFalse(billing.has("type"))
    }

    /** A check uses the acquired metadata through the normal scanner lifecycle. */
    private class AnchorProbe : PlSqlCheck() {
        val descriptions = ConcurrentHashMap<String, ProjectExternalTypeDescription>()

        init {
            subscribeTo(PlSqlGrammar.CREATE_PACKAGE)
        }

        override fun visitNode(node: AstNode) {
            val analysis = projectAnalysis()
            val external = analysis.externalContext
            analysis.snapshot.declarations.filterIsInstance<ProjectPackageValueView>().forEach {
                descriptions[it.qualifiedName.toString()] = analysis.snapshot.describeExternalType(
                    it.typeRef, it.owner, external.metadata, external.compilationSchema(it.owner).orElse(null)
                )
            }
        }
    }

    @Test
    fun checksReachTheAcquiredMetadataThroughTheScanner() {
        val settings = OracleSettings(database.jdbcUrl, owner.name, owner.password, listOf(owner.name), emptyList())
        val acquisition = OracleMetadataLoader.load(settings, null)
        val files = sources.listFiles()!!.sorted().map { InputFile(PlSqlFile.Type.MAIN, sources.toPath(), it, StandardCharsets.UTF_8) }
        val context = Main.prepareProjectAnalysisContext(files, acquisition.metadata, ProjectIdentifier.fromSource("\"${owner.name}\""))
        val scanner = AstScanner(emptyList(), null, true, StandardCharsets.UTF_8, context)
        val probes = files.parallelStream().map { file ->
            AnchorProbe().also { scanner.scanFile(file, listOf(it), FileId(file.pathRelativeToBase)) }
        }.toList()

        // Every package file is scanned in parallel and sees the same project-wide descriptions.
        val packageProbes = probes.filterIndexed { i, _ -> files[i].pathRelativeToBase.endsWith(".pks") }
        assertEquals(3, packageProbes.size)
        val statuses = packageProbes.map { probe -> probe.descriptions.mapValues { it.value.status } }
        assertTrue(statuses.all { it == statuses.first() && it.size == 10 }, statuses.toString())
        assertTrue(probes.single { it !in packageProbes }.descriptions.isEmpty())
        val salary = packageProbes.first().descriptions.getValue("hr_api.v_salary")
        assertEquals(ProjectExternalTypeDescription.Status.RESOLVED, salary.status)
        val type = salary.lookup.get().column.get().datatype.get()
        assertEquals("NUMBER", type.name)
        assertEquals(10, type.precision.asInt)
        assertEquals(2, type.scale.asInt)
    }

    @Test
    fun failuresNeverLeakCredentials() {
        val output = root.resolve("failed.json")
        val wrongPassword = "Wrong-${owner.password}"
        val denied = cli(*oracleOptions(), "--output-format", "json", "--output-file", output.absolutePath, password = wrongPassword)
        assertEquals(3, denied.exitCode)
        assertTrue(denied.stderr.contains("ORA-01017") || denied.stderr.contains("error 1017"), denied.stderr)
        assertFalse(output.exists())

        val unreachableUrl = database.jdbcUrl.replace(Regex(":\\d+/"), ":1/")
        assertFalse(unreachableUrl == database.jdbcUrl, "the test needs a URL with a port")
        val unreachable = cli(
            "--oracle-url", unreachableUrl, "--oracle-user", owner.name, "--oracle-schemas", owner.name,
            "--output-format", "json", "--output-file", output.absolutePath
        )
        assertEquals(3, unreachable.exitCode)
        assertFalse(output.exists())

        val unset = cli(*oracleOptions(), password = null)
        assertEquals(2, unset.exitCode)

        for (run in listOf(denied, unreachable, unset)) {
            for (secret in listOf(owner.password, wrongPassword, database.jdbcUrl, unreachableUrl)) {
                assertFalse(run.stderr.contains(secret) || run.stdout.contains(secret), "secret-bearing value printed")
            }
            assertFalse(run.stderr.contains("\tat "), "no stack trace")
        }
    }
}
