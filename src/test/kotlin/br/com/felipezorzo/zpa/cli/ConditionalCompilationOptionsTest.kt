package br.com.felipezorzo.zpa.cli

import com.felipebz.zpa.api.annotations.ZpaExperimentalApi
import com.felipebz.zpa.squid.ConditionalCompilationSettings
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ZpaExperimentalApi::class)
class ConditionalCompilationOptionsTest {

    /** Parses only when `$$legacy` is evaluated (to NULL); unevaluated, the empty first branch is kept. */
    private val procedure = "CREATE PROCEDURE p IS BEGIN \$IF \$\$legacy \$THEN \$ELSE NULL; \$END END;"

    private lateinit var root: File
    private lateinit var sources: File

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("zpa-cli-cc-test").toFile()
        sources = root.resolve("src").apply { mkdirs() }
        sources.resolve("p.sql").writeText(procedure)
    }

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun run(vararg options: String): Int =
        execute(arrayOf("--sources", sources.absolutePath, "--output-format", "json", "--output-file", root.resolve("out.json").absolutePath, *options))

    private fun stderrOf(vararg options: String): Pair<Int, String> {
        val err = ByteArrayOutputStream()
        val original = System.err
        System.setErr(PrintStream(err, true, StandardCharsets.UTF_8))
        try {
            return run(*options) to err.toString(StandardCharsets.UTF_8)
        } finally {
            System.setErr(original)
        }
    }

    @Test
    fun syntaxOnlyParseDependsOnTheSettings() {
        assertEquals(1, run("--syntax-only"))
        assertEquals(0, run("--syntax-only", "--database-version", "19"))
        assertEquals(0, run("--syntax-only", "--ccflags", "x:1"))
    }

    @Test
    fun standardInputUsesTheSettings() {
        val originalIn = System.`in`
        try {
            System.setIn(ByteArrayInputStream(procedure.toByteArray(StandardCharsets.UTF_8)))
            assertEquals(0, run("--files", "-", "--syntax-only", "--database-version", "19"))
        } finally {
            System.setIn(originalIn)
        }
    }

    @Test
    fun invalidValuesAreRejectedNamingTheOption() {
        for ((option, value) in listOf("--ccflags" to "x", "--ccflags" to "a:maybe", "--database-version" to "abc")) {
            val (exitCode, stderr) = stderrOf("--syntax-only", option, value)
            assertEquals(2, exitCode, "$option $value")
            assertTrue(stderr.contains("Invalid $option"), "$option $value: $stderr")
        }
    }

    @Test
    fun normalAnalysisRunsWithAnOption() {
        assertEquals(0, run("--database-version", "19"))
    }

    @Test
    fun projectIndexSelectsTheSameBranches() {
        sources.resolve("p.sql").delete()
        sources.resolve("p.pks").writeText("CREATE PACKAGE p AS \$IF \$\$f \$THEN PROCEDURE a; \$ELSE PROCEDURE b; \$END END p;")
        val files = SourceSelection(sources.toPath(), listOf("sql", "pks", "pkb")).discoverProjectSources()
        fun declarations(settings: ConditionalCompilationSettings) =
            Main.prepareProjectAnalysisContext(files, null, null, settings).createSnapshot().declarations.map { it.qualifiedName.toString() }
        assertEquals(listOf("p", "p.a"), declarations(ConditionalCompilationSettings.NONE))
        assertEquals(listOf("p", "p.b"), declarations(ConditionalCompilationSettings.of("f:false", null)))
    }
}
