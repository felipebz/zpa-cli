package br.com.felipezorzo.zpa.cli

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FocusedValidationTest {

    private val mapper = jacksonObjectMapper()

    @Test
    fun sourceTargetSeparationPreservesProjectSemanticContext() {
        val root = Files.createTempDirectory("zpa-cli-context-test").toFile()
        try {
            val sourcesDir = root.resolve("src").apply { mkdirs() }
            val specFile = sourcesDir.resolve("types.pks").apply {
                writeText(
                    """
                    CREATE OR REPLACE PACKAGE types AS
                      PROCEDURE work(payload IN OUT NOCOPY CLOB);
                    END types;
                    """.trimIndent()
                )
            }
            val bodyFile = sourcesDir.resolve("consumer.pkb").apply {
                writeText(
                    """
                    CREATE OR REPLACE PACKAGE BODY types AS
                      PROCEDURE work(payload IN OUT CLOB) IS
                      BEGIN
                        NULL;
                      END work;
                    END types;
                    """.trimIndent()
                )
            }

            // 1. Run with --files consumer.pkb: types.pks is in project context, only consumer.pkb is scanned
            val outputFile = root.resolve("output-target-only.json")
            val exitCodeTargetOnly = execute(
                arrayOf(
                    "--sources", sourcesDir.absolutePath,
                    "--files", "consumer.pkb",
                    "--output-format", "json",
                    "--output-file", outputFile.absolutePath
                )
            )

            assertEquals(0, exitCodeTargetOnly)
            val jsonTargetOnly = mapper.readTree(outputFile)
            assertEquals(1, jsonTargetOnly.get("schemaVersion").asInt())
            val diagnosticsTargetOnly = jsonTargetOnly.get("diagnostics")
            assertTrue(diagnosticsTargetOnly.size() > 0)
            // types.pks was not analyzed
            assertTrue(diagnosticsTargetOnly.elements().asSequence().none { it.get("file").asText() == "types.pks" })
            // consumer.pkb was analyzed and detected the NOCOPY mismatch with types.pks
            val nocopyDiag = diagnosticsTargetOnly.elements().asSequence()
                .firstOrNull { it.get("rule").asText() == "zpa:PackageBodyParameterNocopy" }
            assertNotNull(nocopyDiag, "PackageBodyParameterNocopyCheck must be reported on consumer.pkb")
            assertEquals("consumer.pkb", nocopyDiag.get("file").asText())
            assertEquals("RULE", nocopyDiag.get("kind").asText())
            assertTrue(nocopyDiag.get("message").asText().contains("NOCOPY"))
            val outputAll = root.resolve("output-all.json")
            val exitCodeAll = execute(
                arrayOf(
                    "--sources", sourcesDir.absolutePath,
                    "--output-format", "json",
                    "--output-file", outputAll.absolutePath
                )
            )

            assertEquals(0, exitCodeAll)
            val jsonAll = mapper.readTree(outputAll)
            val filesAnalyzed = jsonAll.get("diagnostics").elements().asSequence()
                .map { it.get("file").asText() }
                .toSet()
            // types.pks has no violations, consumer.pkb has the violation
            assertTrue(filesAnalyzed.contains("consumer.pkb"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun targetResolutionHandlesRelativeAbsoluteAndDeduplication() {
        val root = Files.createTempDirectory("zpa-cli-resolution-test").toFile()
        try {
            val sourcesDir = root.resolve("sources").apply { mkdirs() }
            val fileA = sourcesDir.resolve("a.pkb").apply {
                writeText("CREATE PACKAGE BODY p AS PROCEDURE run IS BEGIN NULL; END; END;")
            }
            val fileB = sourcesDir.resolve("b.pkb").apply {
                writeText("CREATE PACKAGE BODY q AS PROCEDURE run IS BEGIN NULL; END; END;")
            }

            // Deduplication: pass a.pkb twice (relative and absolute)
            val outputFile = root.resolve("out.json")
            val exitCode = execute(
                arrayOf(
                    "--sources", sourcesDir.absolutePath,
                    "--files", fileA.absolutePath, "a.pkb", "b.pkb",
                    "--output-format", "json",
                    "--output-file", outputFile.absolutePath
                )
            )

            assertEquals(0, exitCode)
            val json = mapper.readTree(outputFile)
            assertTrue(json.get("validation").get("passed").asBoolean())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun targetValidationRejectsNonexistentFile() {
        val root = Files.createTempDirectory("zpa-cli-invalid-file").toFile()
        try {
            val sourcesDir = root.resolve("sources").apply { mkdirs() }
            sourcesDir.resolve("valid.sql").writeText("SELECT 1 FROM DUAL;")

            val exitCode = execute(
                arrayOf(
                    "--sources", sourcesDir.absolutePath,
                    "--files", "nonexistent.sql"
                )
            )
            assertEquals(2, exitCode)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun targetValidationRejectsFileOutsideSources() {
        val root = Files.createTempDirectory("zpa-cli-outside-file").toFile()
        try {
            val sourcesDir = root.resolve("sources").apply { mkdirs() }
            val outsideDir = root.resolve("outside").apply { mkdirs() }
            val outsideFile = outsideDir.resolve("outside.sql").apply {
                writeText("SELECT 1 FROM DUAL;")
            }

            val exitCode = execute(
                arrayOf(
                    "--sources", sourcesDir.absolutePath,
                    "--files", outsideFile.absolutePath
                )
            )
            assertEquals(2, exitCode)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun targetValidationRejectsUnsupportedExtension() {
        val root = Files.createTempDirectory("zpa-cli-unsupported-ext").toFile()
        try {
            val sourcesDir = root.resolve("sources").apply { mkdirs() }
            val txtFile = sourcesDir.resolve("readme.txt").apply {
                writeText("Not a PL/SQL file")
            }

            val exitCode = execute(
                arrayOf(
                    "--sources", sourcesDir.absolutePath,
                    "--files", txtFile.absolutePath
                )
            )
            assertEquals(2, exitCode)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun syntaxOnlyValidAndInvalidFiles() {
        val root = Files.createTempDirectory("zpa-cli-syntax-only").toFile()
        try {
            val sourcesDir = root.resolve("sources").apply { mkdirs() }
            // Valid PL/SQL with coding rule violation (e.g. empty block)
            val validFile = sourcesDir.resolve("valid.sql").apply {
                writeText("BEGIN NULL; END;")
            }
            // Invalid PL/SQL syntax
            val invalidFile = sourcesDir.resolve("invalid.sql").apply {
                writeText("CREATE OR REPLACE PROCEDURE broken IS BEGIN IF THEN END;")
            }

            // Valid file in syntax-only mode: 0 diagnostics
            val validOutput = root.resolve("valid.json")
            val validExit = execute(
                arrayOf(
                    "--sources", sourcesDir.absolutePath,
                    "--files", "valid.sql",
                    "--syntax-only",
                    "--output-format", "json",
                    "--output-file", validOutput.absolutePath
                )
            )
            assertEquals(0, validExit)
            val validJson = mapper.readTree(validOutput)
            assertTrue(validJson.get("validation").get("passed").asBoolean())
            assertEquals(0, validJson.get("diagnostics").size())

            // Invalid file in syntax-only mode: emits SYNTAX diagnostic
            val invalidOutput = root.resolve("invalid.json")
            val invalidExit = execute(
                arrayOf(
                    "--sources", sourcesDir.absolutePath,
                    "--files", "invalid.sql",
                    "--syntax-only",
                    "--fail-on", "syntax",
                    "--output-format", "json",
                    "--output-file", invalidOutput.absolutePath
                )
            )
            assertEquals(1, invalidExit)
            val invalidJson = mapper.readTree(invalidOutput)
            assertEquals(false, invalidJson.get("validation").get("passed").asBoolean())

            val diagnostics = invalidJson.get("diagnostics")
            assertTrue(diagnostics.size() > 0)
            val syntaxDiag = diagnostics.get(0)
            assertEquals("SYNTAX", syntaxDiag.get("kind").asText())
            assertEquals("zpa:ParsingError", syntaxDiag.get("rule").asText())
            assertEquals("invalid.sql", syntaxDiag.get("file").asText())
            assertNotNull(syntaxDiag.get("range").get("startLine"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun stdinValidationWithVirtualFilename() {
        val root = Files.createTempDirectory("zpa-cli-stdin-test").toFile()
        val originalIn = System.`in`
        try {
            val sourcesDir = root.resolve("sources").apply { mkdirs() }
            val sqlContent = "CREATE OR REPLACE PROCEDURE p IS BEGIN NULL; END;"
            System.setIn(ByteArrayInputStream(sqlContent.toByteArray(StandardCharsets.UTF_8)))

            val outputFile = root.resolve("stdin-out.json")
            val exitCode = execute(
                arrayOf(
                    "--sources", sourcesDir.absolutePath,
                    "--files", "-",
                    "--syntax-only",
                    "--stdin-filename", "virtual/pkg.pkb",
                    "--output-format", "json",
                    "--output-file", outputFile.absolutePath
                )
            )

            assertEquals(0, exitCode)
            val json = mapper.readTree(outputFile)
            assertTrue(json.get("validation").get("passed").asBoolean())
            assertEquals(1, json.get("schemaVersion").asInt())
        } finally {
            System.setIn(originalIn)
            root.deleteRecursively()
        }
    }

    @Test
    fun stdinDefaultSyntheticNameWhenOmitted() {
        val root = Files.createTempDirectory("zpa-cli-stdin-default").toFile()
        val originalIn = System.`in`
        try {
            val sourcesDir = root.resolve("sources").apply { mkdirs() }
            val invalidSql = "BROKEN SYNTAX"
            System.setIn(ByteArrayInputStream(invalidSql.toByteArray(StandardCharsets.UTF_8)))

            val outputFile = root.resolve("stdin-default-out.json")
            val exitCode = execute(
                arrayOf(
                    "--sources", sourcesDir.absolutePath,
                    "--files", "-",
                    "--syntax-only",
                    "--fail-on", "none",
                    "--output-format", "json",
                    "--output-file", outputFile.absolutePath
                )
            )

            assertEquals(0, exitCode) // --fail-on none by default
            val json = mapper.readTree(outputFile)
            val diagnostics = json.get("diagnostics")
            assertTrue(diagnostics.size() > 0)
            assertEquals("stdin.sql", diagnostics.get(0).get("file").asText())
            assertEquals("SYNTAX", diagnostics.get(0).get("kind").asText())
        } finally {
            System.setIn(originalIn)
            root.deleteRecursively()
        }
    }

    @Test
    fun deterministicJsonOutputAndSorting() {
        val root = Files.createTempDirectory("zpa-cli-deterministic-json").toFile()
        try {
            val sourcesDir = root.resolve("sources").apply { mkdirs() }
            sourcesDir.resolve("b_file.sql").apply {
                writeText("CREATE OR REPLACE PROCEDURE broken_b IS BEGIN IF THEN END;")
            }
            sourcesDir.resolve("a_file.sql").apply {
                writeText("CREATE OR REPLACE PROCEDURE broken_a IS BEGIN IF THEN END;")
            }

            // Run 1: pass b_file.sql first
            val out1 = root.resolve("out1.json")
            execute(
                arrayOf(
                    "--sources", sourcesDir.absolutePath,
                    "--files", "b_file.sql", "a_file.sql",
                    "--syntax-only",
                    "--output-format", "json",
                    "--output-file", out1.absolutePath
                )
            )

            // Run 2: pass a_file.sql first
            val out2 = root.resolve("out2.json")
            execute(
                arrayOf(
                    "--sources", sourcesDir.absolutePath,
                    "--files", "a_file.sql", "b_file.sql",
                    "--syntax-only",
                    "--output-format", "json",
                    "--output-file", out2.absolutePath
                )
            )

            val json1 = mapper.readTree(out1)
            val json2 = mapper.readTree(out2)

            // Both runs must have a_file.sql diagnostics before b_file.sql diagnostics
            val files1 = json1.get("diagnostics").elements().asSequence().map { it.get("file").asText() }.toList()
            val files2 = json2.get("diagnostics").elements().asSequence().map { it.get("file").asText() }.toList()

            assertEquals(files1, files2)
            assertEquals("a_file.sql", files1.first())
            assertEquals("b_file.sql", files1.last())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun jsonOutputPipedToStdoutIsSafe() {
        val root = Files.createTempDirectory("zpa-cli-stdout-test").toFile()
        val originalOut = System.out
        try {
            val sourcesDir = root.resolve("sources").apply { mkdirs() }
            sourcesDir.resolve("test.sql").writeText("SELECT 1 FROM DUAL;")

            val capturedOut = ByteArrayOutputStream()
            System.setOut(PrintStream(capturedOut, true, StandardCharsets.UTF_8))

            val exitCode = execute(
                arrayOf(
                    "--sources", sourcesDir.absolutePath,
                    "--output-format", "json"
                )
            )

            assertEquals(0, exitCode)
            val stdoutString = capturedOut.toString(StandardCharsets.UTF_8).trim()
            // Must parse cleanly as JSON with no extra logging or progress text
            val json = mapper.readTree(stdoutString)
            assertEquals(1, json.get("schemaVersion").asInt())
            assertTrue(json.get("validation").get("passed").asBoolean())
            assertTrue(json.has("diagnostics"))
        } finally {
            System.setOut(originalOut)
            root.deleteRecursively()
        }
    }

    @Test
    fun exitCodeThresholds() {
        val root = Files.createTempDirectory("zpa-cli-exit-codes").toFile()
        try {
            val sourcesDir = root.resolve("sources").apply { mkdirs() }
            sourcesDir.resolve("bad_syntax.sql").apply {
                writeText("BROKEN SYNTAX")
            }

            // 1. --fail-on none: exits 0
            val codeNone = execute(
                arrayOf(
                    "--sources", sourcesDir.absolutePath,
                    "--files", "bad_syntax.sql",
                    "--fail-on", "none",
                    "--output-format", "json",
                    "--output-file", root.resolve("none.json").absolutePath
                )
            )
            assertEquals(0, codeNone)

            // 2. --fail-on syntax: exits 1
            val codeSyntax = execute(
                arrayOf(
                    "--sources", sourcesDir.absolutePath,
                    "--files", "bad_syntax.sql",
                    "--fail-on", "syntax",
                    "--output-format", "json",
                    "--output-file", root.resolve("syntax.json").absolutePath
                )
            )
            assertEquals(1, codeSyntax)

            // 3. --fail-on any: exits 1
            val codeAny = execute(
                arrayOf(
                    "--sources", sourcesDir.absolutePath,
                    "--files", "bad_syntax.sql",
                    "--fail-on", "any",
                    "--output-format", "json",
                    "--output-file", root.resolve("any.json").absolutePath
                )
            )
            assertEquals(1, codeAny)

            // 4. Invalid --fail-on value: exits 2
            val codeInvalid = execute(
                arrayOf(
                    "--sources", sourcesDir.absolutePath,
                    "--fail-on", "invalid_threshold"
                )
            )
            assertEquals(2, codeInvalid)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun existingExportersCompatibility() {
        val root = Files.createTempDirectory("zpa-cli-compat-test").toFile()
        val originalOut = System.out
        try {
            val sourcesDir = root.resolve("sources").apply { mkdirs() }
            sourcesDir.resolve("test.sql").writeText("SELECT 1 FROM DUAL;")

            // 1. Console exporter
            val capturedOut = ByteArrayOutputStream()
            System.setOut(PrintStream(capturedOut, true, StandardCharsets.UTF_8))
            val consoleExit = execute(
                arrayOf(
                    "--sources", sourcesDir.absolutePath,
                    "--output-format", "console"
                )
            )
            assertEquals(0, consoleExit)

            // 2. Generic issue exporter
            val genericOutputFile = root.resolve("generic-issues.json")
            val genericExit = execute(
                arrayOf(
                    "--sources", sourcesDir.absolutePath,
                    "--output-format", "sq-generic-issue-import",
                    "--output-file", genericOutputFile.absolutePath
                )
            )
            assertEquals(0, genericExit)
            assertTrue(genericOutputFile.exists())
            val genericJson = mapper.readTree(genericOutputFile)
            assertTrue(genericJson.has("issues"))
        } finally {
            System.setOut(originalOut)
            root.deleteRecursively()
        }
    }

    @Test
    fun stdinRejectedInNormalAnalysis() {
        val root = Files.createTempDirectory("zpa-cli-stdin-rejected").toFile()
        val originalIn = System.`in`
        try {
            val sourcesDir = root.resolve("sources").apply { mkdirs() }
            System.setIn(ByteArrayInputStream("SELECT 1 FROM DUAL;".toByteArray(StandardCharsets.UTF_8)))

            val exitCode = execute(
                arrayOf(
                    "--sources", sourcesDir.absolutePath,
                    "--files", "-"
                )
            )
            assertEquals(2, exitCode)
        } finally {
            System.setIn(originalIn)
            root.deleteRecursively()
        }
    }

    @Test
    fun stdinFilenameValidations() {
        val root = Files.createTempDirectory("zpa-cli-stdin-fn-val").toFile()
        val originalIn = System.`in`
        try {
            val sourcesDir = root.resolve("sources").apply { mkdirs() }
            System.setIn(ByteArrayInputStream("SELECT 1 FROM DUAL;".toByteArray(StandardCharsets.UTF_8)))

            // 1. --stdin-filename without --files - is rejected
            val codeWithoutStdin = execute(
                arrayOf(
                    "--sources", sourcesDir.absolutePath,
                    "--stdin-filename", "test.sql"
                )
            )
            assertEquals(2, codeWithoutStdin)

            // 2. --stdin-filename escaping sources is rejected
            val codeEscaped = execute(
                arrayOf(
                    "--sources", sourcesDir.absolutePath,
                    "--files", "-",
                    "--syntax-only",
                    "--stdin-filename", "../escaped.sql"
                )
            )
            assertEquals(2, codeEscaped)

            // 3. --stdin-filename with unsupported extension is rejected
            val codeUnsupported = execute(
                arrayOf(
                    "--sources", sourcesDir.absolutePath,
                    "--files", "-",
                    "--syntax-only",
                    "--stdin-filename", "test.txt"
                )
            )
            assertEquals(2, codeUnsupported)
        } finally {
            System.setIn(originalIn)
            root.deleteRecursively()
        }
    }

    @Test
    fun relativeFilesResolvedRelativeToSourcesNotCwd() {
        val root = Files.createTempDirectory("zpa-cli-rel-sources").toFile()
        try {
            val sourcesDir = root.resolve("project_sources").apply { mkdirs() }
            val subDir = sourcesDir.resolve("sub").apply { mkdirs() }
            subDir.resolve("target.sql").writeText("SELECT 1 FROM DUAL;")

            val outputFile = root.resolve("out.json")
            // sub/target.sql is relative to sourcesDir
            val exitCode = execute(
                arrayOf(
                    "--sources", sourcesDir.absolutePath,
                    "--files", "sub/target.sql",
                    "--output-format", "json",
                    "--output-file", outputFile.absolutePath
                )
            )

            assertEquals(0, exitCode)
            val json = mapper.readTree(outputFile)
            assertTrue(json.get("validation").get("passed").asBoolean())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun strictMembershipInvariantInNormalAnalysis() {
        val root = Files.createTempDirectory("zpa-cli-membership").toFile()
        try {
            val sourcesDir = root.resolve("sources").apply { mkdirs() }
            sourcesDir.resolve("valid.sql").writeText("SELECT 1 FROM DUAL;")

            // Target with unsupported extension exists under sources but was not discovered as a PL/SQL source
            val txtFile = sourcesDir.resolve("note.txt").apply { writeText("hello") }
            val exitCodeUnsupported = execute(
                arrayOf(
                    "--sources", sourcesDir.absolutePath,
                    "--files", txtFile.name
                )
            )
            assertEquals(2, exitCodeUnsupported)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun syntaxOnlyWithExplicitFilesIsLightweight() {
        val root = Files.createTempDirectory("zpa-cli-lightweight").toFile()
        try {
            val sourcesDir = root.resolve("sources").apply { mkdirs() }
            // Target file is valid
            val validTarget = sourcesDir.resolve("target.sql").apply {
                writeText("CREATE PROCEDURE p IS BEGIN NULL; END;")
            }
            // An unparseable file in sources that would emit syntax errors if walked
            sourcesDir.resolve("broken.sql").apply {
                writeText("BROKEN SYNTAX ERROR")
            }

            val outputFile = root.resolve("lightweight.json")
            val exitCode = execute(
                arrayOf(
                    "--sources", sourcesDir.absolutePath,
                    "--files", validTarget.name,
                    "--syntax-only",
                    "--fail-on", "syntax",
                    "--output-format", "json",
                    "--output-file", outputFile.absolutePath
                )
            )

            // Only target.sql was parsed; broken.sql was never touched!
            assertEquals(0, exitCode)
            val json = mapper.readTree(outputFile)
            assertTrue(json.get("validation").get("passed").asBoolean())
            assertEquals(0, json.get("diagnostics").size())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun jsonValidationSummaryAndRangeCoordinateContract() {
        val root = Files.createTempDirectory("zpa-cli-coords-test").toFile()
        try {
            val sourcesDir = root.resolve("sources").apply { mkdirs() }
            // A file with known syntax error to verify coordinates
            sourcesDir.resolve("err.sql").apply {
                writeText("CREATE OR REPLACE PROCEDURE broken IS BEGIN IF THEN END;")
            }

            val outputFile = root.resolve("coords.json")
            val exitCode = execute(
                arrayOf(
                    "--sources", sourcesDir.absolutePath,
                    "--files", "err.sql",
                    "--syntax-only",
                    "--fail-on", "syntax",
                    "--output-format", "json",
                    "--output-file", outputFile.absolutePath
                )
            )

            assertEquals(1, exitCode)
            val json = mapper.readTree(outputFile)
            assertEquals(1, json.get("schemaVersion").asInt())
            assertFalse(json.has("status"))

            // Validation summary contract
            val validation = json.get("validation")
            assertEquals(false, validation.get("passed").asBoolean())
            assertEquals("syntax", validation.get("threshold").asText())

            // Range coordinate contract: 1-based startLine, 0-based startColumn
            val diagnostics = json.get("diagnostics")
            assertTrue(diagnostics.size() > 0)
            val diag = diagnostics.get(0)
            val range = diag.get("range")
            assertEquals(1, range.get("startLine").asInt())
            assertTrue(range.get("startColumn").asInt() >= 0)
            assertEquals(1, range.get("endLine").asInt())
            assertTrue(range.get("endColumn").asInt() > range.get("startColumn").asInt())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun jsonEscapingAndUnicode() {
        val root = Files.createTempDirectory("zpa-cli-unicode-test").toFile()
        val originalIn = System.`in`
        try {
            val sourcesDir = root.resolve("sources").apply { mkdirs() }
            val unicodeFilename = "café_ação_🚀.sql"
            val sql = "BROKEN SYNTAX -- café 🚀 \"quotes\" \n"
            System.setIn(ByteArrayInputStream(sql.toByteArray(StandardCharsets.UTF_8)))

            val outputFile = root.resolve("unicode.json")
            val exitCode = execute(
                arrayOf(
                    "--sources", sourcesDir.absolutePath,
                    "--files", "-",
                    "--stdin-filename", unicodeFilename,
                    "--syntax-only",
                    "--fail-on", "none",
                    "--output-format", "json",
                    "--output-file", outputFile.absolutePath
                )
            )

            assertEquals(0, exitCode)
            val rawJson = outputFile.readText(StandardCharsets.UTF_8)
            // Verify that Unicode string is preserved in the JSON output
            assertTrue(rawJson.contains("café_ação_🚀.sql"))

            // Verify JSON round-trip deserialization preserves exact Unicode characters
            val json = mapper.readTree(rawJson)
            assertTrue(json.get("validation").get("passed").asBoolean())
            val diag = json.get("diagnostics").get(0)
            assertEquals(unicodeFilename, diag.get("file").asText())
        } finally {
            System.setIn(originalIn)
            root.deleteRecursively()
        }
    }

    @Test
    fun exitCodeSeverityBoundaries() {
        val root = Files.createTempDirectory("zpa-cli-severity-boundaries").toFile()
        try {
            val sourcesDir = root.resolve("sources").apply { mkdirs() }
            sourcesDir.resolve("types.pks").writeText(
                "CREATE OR REPLACE PACKAGE types AS PROCEDURE work(payload IN OUT NOCOPY CLOB); END types;"
            )
            sourcesDir.resolve("consumer.pkb").writeText(
                "CREATE OR REPLACE PACKAGE BODY types AS PROCEDURE work(payload IN OUT CLOB) IS BEGIN NULL; END work; END types;"
            )

            // 1. --fail-on blocker: should NOT fail on MAJOR (returns 0)
            val codeBlocker = execute(
                arrayOf(
                    "--sources", sourcesDir.absolutePath,
                    "--files", "consumer.pkb",
                    "--fail-on", "blocker",
                    "--output-format", "json",
                    "--output-file", root.resolve("blocker.json").absolutePath
                )
            )
            assertEquals(0, codeBlocker)

            // 2. --fail-on major: SHOULD fail on MAJOR (returns 1)
            val codeMajor = execute(
                arrayOf(
                    "--sources", sourcesDir.absolutePath,
                    "--files", "consumer.pkb",
                    "--fail-on", "major",
                    "--output-format", "json",
                    "--output-file", root.resolve("major.json").absolutePath
                )
            )
            assertEquals(1, codeMajor)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun validationRejectsNonexistentSources() {
        val exitCode = execute(
            arrayOf(
                "--sources", "nonexistent_sources_dir_12345"
            )
        )
        assertEquals(2, exitCode)
    }

    @Test
    fun jsonSyntaxDiagnosticWithFailOnNoneHasValidationPassedTrue() {
        val root = Files.createTempDirectory("zpa-cli-syntax-fail-on-none").toFile()
        try {
            val sourcesDir = root.resolve("sources").apply { mkdirs() }
            sourcesDir.resolve("syntax_err.sql").writeText("BROKEN SYNTAX")

            val outputFile = root.resolve("out.json")
            val exitCode = execute(
                arrayOf(
                    "--sources", sourcesDir.absolutePath,
                    "--files", "syntax_err.sql",
                    "--syntax-only",
                    "--fail-on", "none",
                    "--output-format", "json",
                    "--output-file", outputFile.absolutePath
                )
            )

            // Exit code 0 because threshold is none
            assertEquals(0, exitCode)
            val json = mapper.readTree(outputFile)
            assertEquals(1, json.get("schemaVersion").asInt())
            assertFalse(json.has("status"), "Redundant root status must not be present")

            val validation = json.get("validation")
            assertTrue(validation.get("passed").asBoolean())
            assertEquals("none", validation.get("threshold").asText())

            val diagnostics = json.get("diagnostics")
            assertEquals(1, diagnostics.size())
            assertEquals("SYNTAX", diagnostics.get(0).get("kind").asText())
            assertEquals("syntax_err.sql", diagnostics.get(0).get("file").asText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun syntaxOnlyDefaultThresholdFailsOnSyntaxErrorWithoutFailOn() {
        val root = Files.createTempDirectory("zpa-syntax-default-fail").toFile()
        try {
            val sourcesDir = root.resolve("sources").apply { mkdirs() }
            sourcesDir.resolve("syntax_error.sql").writeText("INVALID SQL SYNTAX;")

            val outputFile = root.resolve("out.json")
            val exitCode = execute(
                arrayOf(
                    "--sources", sourcesDir.absolutePath,
                    "--files", "syntax_error.sql",
                    "--syntax-only",
                    "--output-format", "json",
                    "--output-file", outputFile.absolutePath
                )
            )

            // Defaults to --fail-on syntax => exit 1
            assertEquals(1, exitCode)
            val json = mapper.readTree(outputFile)
            val validation = json.get("validation")
            assertEquals(false, validation.get("passed").asBoolean())
            assertEquals("syntax", validation.get("threshold").asText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun syntaxOnlyExplicitFailOnNoneOverridesDefault() {
        val root = Files.createTempDirectory("zpa-syntax-override-none").toFile()
        try {
            val sourcesDir = root.resolve("sources").apply { mkdirs() }
            sourcesDir.resolve("syntax_error.sql").writeText("INVALID SQL SYNTAX;")

            val outputFile = root.resolve("out.json")
            val exitCode = execute(
                arrayOf(
                    "--sources", sourcesDir.absolutePath,
                    "--files", "syntax_error.sql",
                    "--syntax-only",
                    "--fail-on", "none",
                    "--output-format", "json",
                    "--output-file", outputFile.absolutePath
                )
            )

            // Explicit --fail-on none overrides default => exit 0
            assertEquals(0, exitCode)
            val json = mapper.readTree(outputFile)
            val validation = json.get("validation")
            assertTrue(validation.get("passed").asBoolean())
            assertEquals("none", validation.get("threshold").asText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun consoleOutputIncludesRuleKey() {
        val root = Files.createTempDirectory("zpa-console-rule-key").toFile()
        val originalOut = System.out
        try {
            val sourcesDir = root.resolve("sources").apply { mkdirs() }
            sourcesDir.resolve("broken.sql").writeText("INVALID SQL SYNTAX;")

            val capturedOut = ByteArrayOutputStream()
            System.setOut(PrintStream(capturedOut, true, StandardCharsets.UTF_8))

            val exitCode = execute(
                arrayOf(
                    "--sources", sourcesDir.absolutePath,
                    "--files", "broken.sql",
                    "--syntax-only",
                    "--fail-on", "none",
                    "--output-format", "console"
                )
            )

            assertEquals(0, exitCode)
            val consoleText = capturedOut.toString(StandardCharsets.UTF_8)
            assertTrue(consoleText.contains("zpa:ParsingError"), "Console output must include the rule key 'zpa:ParsingError'")
            assertTrue(consoleText.contains("File: broken.sql"))
        } finally {
            System.setOut(originalOut)
            root.deleteRecursively()
        }
    }

    @Test
    fun configurationErrorsReturnExitCode2() {
        val root = Files.createTempDirectory("zpa-config-error").toFile()
        try {
            val sourcesDir = root.resolve("sources").apply { mkdirs() }
            sourcesDir.resolve("test.sql").writeText("SELECT 1 FROM DUAL;")
            val invalidConfigFile = root.resolve("invalid-config.json").apply {
                writeText("{ invalid json not parseable }")
            }

            // 1. Malformed JSON config file
            val exitCodeMalformed = execute(
                arrayOf(
                    "--sources", sourcesDir.absolutePath,
                    "--config", invalidConfigFile.absolutePath
                )
            )
            assertEquals(2, exitCodeMalformed, "Malformed configuration JSON must result in exit code 2")

            // 2. Nonexistent config file
            val exitCodeNonexistent = execute(
                arrayOf(
                    "--sources", sourcesDir.absolutePath,
                    "--config", root.resolve("nonexistent.json").absolutePath
                )
            )
            assertEquals(2, exitCodeNonexistent, "Nonexistent configuration file must result in exit code 2")

            // 3. Syntactically valid JSON but semantically invalid config (unresolved templateRuleKey / unknown repository)
            val semanticErrorConfigFile = root.resolve("semantic-error-config.json").apply {
                writeText(
                    """
                    {
                      "base": "none",
                      "rules": {
                        "CustomRule": {
                          "level": "major",
                          "templateRuleKey": "nonexistent_repo:UnknownTemplateRule"
                        }
                      }
                    }
                    """.trimIndent()
                )
            }
            val exitCodeSemantic = execute(
                arrayOf(
                    "--sources", sourcesDir.absolutePath,
                    "--config", semanticErrorConfigFile.absolutePath
                )
            )
            assertEquals(2, exitCodeSemantic, "Semantically invalid configuration must result in exit code 2")

            // 4. Syntactically valid config with instance key conflicting with an existing rule
            val conflictingConfigFile = root.resolve("conflicting-rule-config.json").apply {
                writeText(
                    """
                    {
                      "base": "none",
                      "rules": {
                        "SelectAllColumns": {
                          "level": "major",
                          "templateRuleKey": "XPath"
                        }
                      }
                    }
                    """.trimIndent()
                )
            }
            val exitCodeConflict = execute(
                arrayOf(
                    "--sources", sourcesDir.absolutePath,
                    "--config", conflictingConfigFile.absolutePath
                )
            )
            assertEquals(2, exitCodeConflict, "Conflicting template rule instance must result in exit code 2")
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun genericIssueExportExplicitUtf8() {
        val root = Files.createTempDirectory("zpa-generic-utf8").toFile()
        try {
            val sourcesDir = root.resolve("sources").apply { mkdirs() }
            // Non-ASCII characters in syntax error context
            sourcesDir.resolve("café.sql").writeText("CREATE PROCEDURE broken_café IS BEGIN IF THEN END;")

            val genericOutputFile = root.resolve("generic-issues-utf8.json")
            val exitCode = execute(
                arrayOf(
                    "--sources", sourcesDir.absolutePath,
                    "--files", "café.sql",
                    "--syntax-only",
                    "--fail-on", "none",
                    "--output-format", "sq-generic-issue-import",
                    "--output-file", genericOutputFile.absolutePath
                )
            )

            assertEquals(0, exitCode)
            assertTrue(genericOutputFile.exists())
            val rawContent = genericOutputFile.readText(StandardCharsets.UTF_8)
            assertTrue(rawContent.contains("café.sql"))
            val genericJson = mapper.readTree(rawContent)
            assertTrue(genericJson.has("issues"))
            val issuesArray = genericJson.get("issues")
            assertTrue(issuesArray.size() > 0)
            assertEquals("café.sql", issuesArray.get(0).get("primaryLocation").get("filePath").asText())
        } finally {
            root.deleteRecursively()
        }
    }
}
