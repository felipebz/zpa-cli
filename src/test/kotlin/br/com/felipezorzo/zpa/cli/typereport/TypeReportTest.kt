package br.com.felipezorzo.zpa.cli.typereport

import br.com.felipezorzo.zpa.cli.Main
import br.com.felipezorzo.zpa.cli.SourceSelection
import br.com.felipezorzo.zpa.cli.execute
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.felipebz.flr.api.AstNode
import com.felipebz.zpa.api.PlSqlGrammar
import com.felipebz.zpa.api.annotations.ZpaExperimentalApi
import com.felipebz.zpa.api.checks.PlSqlCheck
import com.felipebz.zpa.api.project.*
import com.felipebz.zpa.project.FileId
import com.felipebz.zpa.squid.AstScanner
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.*

@OptIn(ZpaExperimentalApi::class)
class TypeReportTest {
    private val root: File = Files.createTempDirectory("zpa-cli-type-report").toFile()
    private val sources = root.resolve("src").apply { mkdirs() }
    private val mapper = jacksonObjectMapper()
    private val hr = ProjectIdentifier.fromSource("\"HR\"")

    private val metadata = ProjectExternalMetadata.builder("test fixture").completeSchema("HR").relation(
        ProjectExternalRelation.of(
            "HR", "EMPLOYEES", true, listOf(
                ProjectExternalColumn.of("ID", 1, ProjectExternalDatatype.of("NUMBER", null, null, null, 6, 0)),
                ProjectExternalColumn.of("NAME", 2, ProjectExternalDatatype.of("VARCHAR2", null, 40, ProjectExternalDatatype.LengthSemantics.CHAR, null, null)),
                ProjectExternalColumn.of("SALARY", 3, ProjectExternalDatatype.of("NUMBER", null, null, null, 10, 2))
            )
        )
    ).build()

    init {
        sources.resolve("hr_api.pks").writeText(
            """
            CREATE OR REPLACE PACKAGE hr_api AS
              v_salary employees.salary%TYPE;
              r_employee employees%ROWTYPE;
              v_bonus employees.bonus%TYPE;
              v_department departments.name%TYPE;
              TYPE employee_rec IS RECORD (id employees.id%TYPE, note VARCHAR2(10));
              FUNCTION salary_of(p_id employees.id%TYPE) RETURN employees.salary%TYPE;
            END hr_api;
            """.trimIndent()
        )
        sources.resolve("billing.pks").writeText("CREATE OR REPLACE PACKAGE app.billing AS v_salary employees.salary%TYPE; END billing;")
        sources.resolve("unrelated.sql").writeText("BEGIN NULL; END;")
    }

    @AfterTest
    fun cleanup() {
        root.deleteRecursively()
    }

    private fun context(metadata: ProjectExternalMetadata?, schema: ProjectIdentifier?) =
        Main.prepareProjectAnalysisContext(SourceSelection(sources.toPath(), listOf("sql", "pks", "pkb")).discoverProjectSources(), metadata, schema)

    @Test
    fun reportsResolvedDatatypesRowFieldsAndInconclusiveStatuses() {
        val context = context(metadata, hr)
        val report = TypeReport.build(context.createSnapshot(), context.externalContext, null)
        val byKey = report.references.associateBy { "${it.declaration}|${it.element}" }

        val salary = byKey.getValue("hr_api.v_salary|type")
        assertEquals("RESOLVED", salary.status)
        assertEquals("HR", salary.compilationSchema)
        assertEquals("HR.EMPLOYEES", salary.relation)
        assertEquals("NUMBER(10,2)", salary.type!!.display)
        assertEquals(2, salary.startLine)

        val row = byKey.getValue("hr_api.r_employee|type")
        assertEquals("RESOLVED", row.status)
        assertEquals(listOf("ID" to "NUMBER(6,0)", "NAME" to "VARCHAR2(40 CHAR)", "SALARY" to "NUMBER(10,2)"), row.rowFields!!.map { it.name to it.type!!.display })
        assertEquals(listOf(1, 2, 3), row.rowFields!!.map { it.position })
        assertNull(row.type)

        assertEquals("COLUMN_NOT_FOUND", byKey.getValue("hr_api.v_bonus|type").status)
        assertEquals("RELATION_NOT_FOUND", byKey.getValue("hr_api.v_department|type").status)
        assertEquals("NUMBER(6,0)", byKey.getValue("hr_api.employee_rec|field id").type!!.display)
        assertEquals("RESOLVED", byKey.getValue("hr_api.salary_of|parameter p_id").status)
        assertEquals("NUMBER(10,2)", byKey.getValue("hr_api.salary_of|return").type!!.display)
        // An explicit owner wins over the default compilation schema; APP is not covered, so it stays inconclusive.
        val billing = byKey.getValue("app.billing.v_salary|type")
        assertEquals("APP", billing.compilationSchema)
        assertEquals("INCOMPLETE", billing.status)
        assertNull(billing.type)
        assertEquals(report.references.size, report.summary.values.sum())
    }

    @Test
    fun withoutMetadataEveryReferenceIsUnavailable() {
        val context = context(null, null)
        val report = TypeReport.build(context.createSnapshot(), context.externalContext, null)
        assertEquals(false, report.metadata.available)
        assertEquals(mapOf("UNAVAILABLE" to report.references.size), report.summary)
    }

    /** A check sees the metadata through its normal lifecycle, over the same prepared project. */
    private class SalaryTypeProbe : PlSqlCheck() {
        val resolved = ConcurrentHashMap<String, ProjectExternalTypeDescription>()

        init {
            subscribeTo(PlSqlGrammar.CREATE_PACKAGE)
        }

        override fun visitNode(node: AstNode) {
            val analysis = projectAnalysis()
            val external = analysis.externalContext
            analysis.snapshot.declarations.filterIsInstance<ProjectPackageValueView>().forEach { value ->
                resolved[value.qualifiedName.toString()] = analysis.snapshot.describeExternalType(
                    value.typeRef, value.owner, external.metadata, external.compilationSchema(value.owner).orElse(null)
                )
            }
        }
    }

    @Test
    fun checksReachTheMetadataThroughTheScanner() {
        val files = SourceSelection(sources.toPath(), listOf("sql", "pks", "pkb")).discoverProjectSources()
        val context = Main.prepareProjectAnalysisContext(files, metadata, hr)
        val probe = SalaryTypeProbe()
        val scanner = AstScanner(emptyList(), null, true, StandardCharsets.UTF_8, context)
        scanner.scanFile(files.single { it.pathRelativeToBase == "hr_api.pks" }, listOf(probe), FileId("hr_api.pks"))

        val salary = probe.resolved.getValue("hr_api.v_salary")
        assertEquals(ProjectExternalTypeDescription.Status.RESOLVED, salary.status)
        assertEquals(10, salary.lookup.get().column.get().datatype.get().precision.asInt)
        assertEquals(ProjectExternalTypeDescription.Status.INCOMPLETE, probe.resolved.getValue("app.billing.v_salary").status)
    }

    @Test
    fun theTypeReportLeavesTheAnalysisOutputUnchanged() {
        val plain = root.resolve("plain.json")
        val withReport = root.resolve("with-report.json")
        val report = root.resolve("types.json")
        assertEquals(0, execute(arrayOf("--sources", sources.absolutePath, "--output-format", "json", "--output-file", plain.absolutePath)))
        assertEquals(
            0, execute(
                arrayOf(
                    "--sources", sources.absolutePath, "--files", "unrelated.sql", "--output-format", "json",
                    "--output-file", withReport.absolutePath, "--compilation-schema", "HR", "--type-report", report.absolutePath
                )
            )
        )
        val all = mapper.readTree(plain).get("diagnostics").filter { it.get("file").asText() == "unrelated.sql" }
        assertContentEquals(all, mapper.readTree(withReport).get("diagnostics").toList())

        // The report covers the whole project, not only the --files targets.
        val json = mapper.readTree(report)
        assertEquals(1, json.get("schemaVersion").asInt())
        assertEquals("HR", json.get("metadata").get("defaultCompilationSchema").asText())
        val files = json.get("references").map { it.get("file").asText() }.toSet()
        assertEquals(setOf("billing.pks", "hr_api.pks"), files)
        assertTrue(json.get("references").all { it.get("status").asText() == "UNAVAILABLE" })
    }
}
