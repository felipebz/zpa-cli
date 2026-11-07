package br.com.felipezorzo.zpa.cli.typereport

import br.com.felipezorzo.zpa.cli.Main
import br.com.felipezorzo.zpa.cli.SourceSelection
import br.com.felipezorzo.zpa.cli.execute
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.felipebz.zpa.api.annotations.ZpaExperimentalApi
import com.felipebz.zpa.api.project.ProjectIdentifier
import com.felipebz.zpa.api.project.ProjectNamedTypeResolution
import com.felipebz.zpa.api.project.ProjectQualifiedName
import com.felipebz.zpa.api.project.ProjectSchemaPaths
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The schemas units compile in, from `--schema-paths` and `--compilation-schema`, reach the project lookups. */
@OptIn(ZpaExperimentalApi::class)
class SchemaPathsTest {
    private val root: File = Files.createTempDirectory("zpa-cli-schema-paths").toFile()
    private val sources = root.resolve("src").apply { mkdirs() }
    private val mapper = jacksonObjectMapper()

    init {
        sources.resolve("lib").mkdirs()
        sources.resolve("lib/t.sql").writeText("CREATE TYPE t AS OBJECT (a NUMBER);\n/\n")
        sources.resolve("app").mkdirs()
        sources.resolve("app/billing.pks").writeText("CREATE OR REPLACE PACKAGE billing AS v_amount accounts.amount%TYPE; END billing;\n/\n")
        sources.resolve("shared.pks").writeText("CREATE OR REPLACE PACKAGE shared AS v_amount accounts.amount%TYPE; END shared;\n/\n")
    }

    @AfterTest
    fun cleanup() {
        root.deleteRecursively()
    }

    private fun context(schema: String?, paths: String) = Main.prepareProjectAnalysisContext(
        SourceSelection(sources.toPath(), listOf("sql", "pks")).discoverProjectSources(), null,
        schema?.let { ProjectIdentifier.fromSource("\"$it\"") }, schemaPaths = ProjectSchemaPaths.parse(sources.toPath(), paths)
    )

    private fun appT(schema: String?, paths: String) =
        context(schema, paths).createSnapshot().resolveNamedType(ProjectQualifiedName.of("app", "t"), null).status

    @Test
    fun aSchemaQualifiedNameResolvesOnlyWithTheMapping() {
        assertEquals(ProjectNamedTypeResolution.Status.RESOLVED, appT(null, "lib/**=APP"))
        assertEquals(ProjectNamedTypeResolution.Status.NOT_FOUND_IN_PROJECT, appT(null, ""))
        assertEquals(ProjectNamedTypeResolution.Status.NOT_FOUND_IN_PROJECT, appT(null, "lib/**=OTHER"))
    }

    @Test
    fun anUnmappedFileCompilesInTheDefaultSchema() {
        assertEquals(ProjectNamedTypeResolution.Status.RESOLVED, appT("APP", "app/**=BILLING"))
        assertEquals(ProjectNamedTypeResolution.Status.NOT_FOUND_IN_PROJECT, appT("OTHER", "app/**=BILLING"))
    }

    @Test
    fun theTypeReportGivesEachFileItsSchema() {
        val context = context("HR", "app/**=APP")
        val schemas = TypeReport.build(context.createSnapshot(), context.externalContext, null).references
            .associate { it.file to it.compilationSchema }
        assertEquals(mapOf("app/billing.pks" to "APP", "shared.pks" to "HR"), schemas)
    }

    @Test
    fun theOptionReachesTheCommandLineReport() {
        val report = root.resolve("types.json")
        val output = root.resolve("out.json")
        assertEquals(
            0, execute(
                arrayOf(
                    "--sources", sources.absolutePath, "--extensions", "sql,pks", "--output-format", "json", "--output-file", output.absolutePath,
                    "--schema-paths", "app/**=APP", "--type-report", report.absolutePath
                )
            )
        )
        val schemas = mapper.readTree(report).get("references").associate { it.get("file").asText() to it.get("compilationSchema")?.takeUnless { n -> n.isNull }?.asText() }
        assertEquals(mapOf("app/billing.pks" to "APP", "shared.pks" to null), schemas)
        assertEquals(2, execute(arrayOf("--sources", sources.absolutePath, "--schema-paths", "app/**")))
    }
}
