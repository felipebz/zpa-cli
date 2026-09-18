package br.com.felipezorzo.zpa.cli

import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.absolutePathString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SourceSelectionTest {

    private val defaultExtensions = listOf("sql", "pkg", "pks", "pkb")

    @Test
    fun noFilesResolvesAllDiscoveredProjectSources() {
        val root = Files.createTempDirectory("zpa-source-selection-test").toFile()
        try {
            val fileB = root.resolve("b.sql").apply { writeText("SELECT 2 FROM DUAL;") }
            val fileA = root.resolve("a.sql").apply { writeText("SELECT 1 FROM DUAL;") }
            val fileIgnored = root.resolve("ignored.txt").apply { writeText("ignore") }

            val selection = SourceSelection(root.toPath(), defaultExtensions)
            val projectSources = selection.discoverProjectSources()

            assertEquals(2, projectSources.size)
            assertEquals("a.sql", projectSources[0].pathRelativeToBase)
            assertEquals("b.sql", projectSources[1].pathRelativeToBase)

            val targets = selection.resolveProjectTargets(projectSources, emptyList())
            assertEquals(2, targets.size)
            assertEquals("a.sql", targets[0].pathRelativeToBase)
            assertEquals("b.sql", targets[1].pathRelativeToBase)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun relativePathsResolveFromSources() {
        val root = Files.createTempDirectory("zpa-source-selection-test").toFile()
        try {
            val sub = root.resolve("sub").apply { mkdirs() }
            val target = sub.resolve("pkg.pkb").apply { writeText("SELECT 1 FROM DUAL;") }

            val selection = SourceSelection(root.toPath(), defaultExtensions)
            val projectSources = selection.discoverProjectSources()
            val targets = selection.resolveProjectTargets(projectSources, listOf("sub/pkg.pkb"))

            assertEquals(1, targets.size)
            assertEquals("sub/pkg.pkb", targets[0].pathRelativeToBase)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun absolutePathsInsideSourcesWork() {
        val root = Files.createTempDirectory("zpa-source-selection-test").toFile()
        try {
            val target = root.resolve("test.sql").apply { writeText("SELECT 1 FROM DUAL;") }

            val selection = SourceSelection(root.toPath(), defaultExtensions)
            val projectSources = selection.discoverProjectSources()
            val targets = selection.resolveProjectTargets(projectSources, listOf(target.absolutePath))

            assertEquals(1, targets.size)
            assertEquals("test.sql", targets[0].pathRelativeToBase)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun outsidePathsAreRejected() {
        val root = Files.createTempDirectory("zpa-source-selection-test").toFile()
        val outside = Files.createTempDirectory("zpa-outside-test").toFile()
        try {
            val outsideFile = outside.resolve("outside.sql").apply { writeText("SELECT 1 FROM DUAL;") }

            val selection = SourceSelection(root.toPath(), defaultExtensions)
            val projectSources = selection.discoverProjectSources()

            val ex = assertFailsWith<CliValidationException> {
                selection.resolveProjectTargets(projectSources, listOf(outsideFile.absolutePath))
            }
            assertTrue(ex.message!!.contains("outside the sources directory"))
        } finally {
            root.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    @Test
    fun unsupportedExtensionsAreRejected() {
        val root = Files.createTempDirectory("zpa-source-selection-test").toFile()
        try {
            root.resolve("notes.txt").writeText("Notes")

            val selection = SourceSelection(root.toPath(), defaultExtensions)
            val projectSources = selection.discoverProjectSources()

            val ex = assertFailsWith<CliValidationException> {
                selection.resolveProjectTargets(projectSources, listOf("notes.txt"))
            }
            assertTrue(ex.message!!.contains("unsupported extension 'txt'"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun nonexistentFilesAreRejected() {
        val root = Files.createTempDirectory("zpa-source-selection-test").toFile()
        try {
            val selection = SourceSelection(root.toPath(), defaultExtensions)
            val projectSources = selection.discoverProjectSources()

            val ex = assertFailsWith<CliValidationException> {
                selection.resolveProjectTargets(projectSources, listOf("missing.sql"))
            }
            assertTrue(ex.message!!.contains("Target file does not exist"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun duplicateFilesystemTargetsAreDeduplicatedDeterministically() {
        val root = Files.createTempDirectory("zpa-source-selection-test").toFile()
        try {
            val fileA = root.resolve("a.sql").apply { writeText("SELECT 1 FROM DUAL;") }
            val fileB = root.resolve("b.sql").apply { writeText("SELECT 2 FROM DUAL;") }

            val selection = SourceSelection(root.toPath(), defaultExtensions)
            val projectSources = selection.discoverProjectSources()

            // Duplicates passed in different order and mix of relative / absolute
            val targets = selection.resolveProjectTargets(
                projectSources,
                listOf("b.sql", fileA.absolutePath, "a.sql", fileB.absolutePath)
            )

            assertEquals(2, targets.size)
            assertEquals("a.sql", targets[0].pathRelativeToBase)
            assertEquals("b.sql", targets[1].pathRelativeToBase)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun normalTargetsMustBeDiscoveredProjectSources() {
        val root = Files.createTempDirectory("zpa-source-selection-test").toFile()
        try {
            root.resolve("valid.sql").writeText("SELECT 1 FROM DUAL;")

            val selection = SourceSelection(root.toPath(), defaultExtensions)
            // Empty project sources (simulating file missing from project sources)
            val emptyProjectSources = emptyList<InputFile>()

            val ex = assertFailsWith<CliValidationException> {
                selection.resolveProjectTargets(emptyProjectSources, listOf("valid.sql"))
            }
            assertTrue(ex.message!!.contains("is not part of discovered project sources"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun syntaxOnlyExplicitTargetsDoesNotDiscoverProjectSources() {
        val root = Files.createTempDirectory("zpa-source-selection-test").toFile()
        try {
            val explicitFile = root.resolve("explicit.sql").apply { writeText("SELECT 1 FROM DUAL;") }
            // A non-PL/SQL or broken file in the sources directory
            root.resolve("other.sql").apply { writeText("BROKEN") }

            val selection = SourceSelection(root.toPath(), defaultExtensions)
            // Resolve syntax-only explicit targets WITHOUT calling discoverProjectSources
            val targets = selection.resolveSyntaxOnlyTargets(listOf("explicit.sql"))

            assertEquals(1, targets.size)
            assertEquals("explicit.sql", targets[0].pathRelativeToBase)
            // The unrequested other.sql is not in targets
            assertFalse(targets.any { it.pathRelativeToBase == "other.sql" })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun stdinAcceptedInSyntaxOnlyAndReadExactlyOnce() {
        val root = Files.createTempDirectory("zpa-source-selection-test").toFile()
        try {
            val selection = SourceSelection(root.toPath(), defaultExtensions)
            val readCount = AtomicInteger(0)
            val stdinSupplier: () -> String = {
                readCount.incrementAndGet()
                "SELECT 1 FROM DUAL;"
            }

            // Two stdin references in requestedFiles
            val targets = selection.resolveSyntaxOnlyTargets(
                requestedFiles = listOf("-", "-"),
                stdinFilename = "virtual/stdin_test.sql",
                stdinReader = stdinSupplier
            )

            // Deduplicated by pathRelativeToBase
            assertEquals(1, targets.size)
            assertEquals("virtual/stdin_test.sql", targets[0].pathRelativeToBase)
            assertEquals("SELECT 1 FROM DUAL;", targets[0].contents())
            // Read exactly once
            assertEquals(1, readCount.get())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun stdinRejectedInNormalAnalysis() {
        val root = Files.createTempDirectory("zpa-source-selection-test").toFile()
        try {
            val selection = SourceSelection(root.toPath(), defaultExtensions)
            val projectSources = selection.discoverProjectSources()

            val ex = assertFailsWith<CliValidationException> {
                selection.resolveProjectTargets(projectSources, listOf("-"))
            }
            assertTrue(ex.message!!.contains("Standard input ('--files -') is currently supported only with --syntax-only"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun stdinFilenameValidationRejectsEscapeAndUnsupportedExtension() {
        val root = Files.createTempDirectory("zpa-source-selection-test").toFile()
        try {
            val selection = SourceSelection(root.toPath(), defaultExtensions)

            // Escaping sources directory
            val exEscape = assertFailsWith<CliValidationException> {
                selection.resolveSyntaxOnlyTargets(listOf("-"), stdinFilename = "../outside.sql", stdinReader = { "" })
            }
            assertTrue(exEscape.message!!.contains("cannot escape the sources directory") || exEscape.message!!.contains("must be inside"))

            // Unsupported extension
            val exExt = assertFailsWith<CliValidationException> {
                selection.resolveSyntaxOnlyTargets(listOf("-"), stdinFilename = "virtual.txt", stdinReader = { "" })
            }
            assertTrue(exExt.message!!.contains("unsupported extension 'txt'"))
        } finally {
            root.deleteRecursively()
        }
    }
}
