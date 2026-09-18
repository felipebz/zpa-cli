package br.com.felipezorzo.zpa.cli

import com.beust.jcommander.JCommander
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ArgumentsTest {

    @Test
    fun parseMultipleFilesSyntax() {
        val args = Arguments()
        val cmd = JCommander.newBuilder().addObject(args).build()
        cmd.parse("--sources", ".", "--files", "a.pks", "b.pkb", "--output-format", "json")

        assertEquals(".", args.sources)
        assertEquals(listOf("a.pks", "b.pkb"), args.files)
        assertEquals("json", args.outputFormat)
    }

    @Test
    fun parseRepeatedFilesOption() {
        val args = Arguments()
        val cmd = JCommander.newBuilder().addObject(args).build()
        cmd.parse("--sources", ".", "--files", "a.pks", "--files", "b.pkb")

        assertEquals(listOf("a.pks", "b.pkb"), args.files)
    }

    @Test
    fun parseSyntaxOnlyFlag() {
        val args = Arguments()
        val cmd = JCommander.newBuilder().addObject(args).build()
        cmd.parse("--sources", ".", "--files", "a.pks", "--syntax-only")

        assertEquals(listOf("a.pks"), args.files)
        assertTrue(args.syntaxOnly)
    }

    @Test
    fun parseStdinOptions() {
        val args = Arguments()
        val cmd = JCommander.newBuilder().addObject(args).build()
        cmd.parse("--sources", ".", "--files", "-", "--stdin-filename", "src/virtual.sql")

        assertEquals(listOf("-"), args.files)
        assertEquals("src/virtual.sql", args.stdinFilename)
    }

    @Test
    fun parseFailOnOption() {
        val args = Arguments()
        val cmd = JCommander.newBuilder().addObject(args).build()
        cmd.parse("--sources", ".", "--fail-on", "blocker")

        assertEquals("blocker", args.failOn)
    }

    @Test
    fun parseHelpOption() {
        val args = Arguments()
        val cmd = JCommander.newBuilder().addObject(args).build()
        cmd.parse("--help")

        assertTrue(args.help)
        assertEquals(0, execute(arrayOf("--help")))
    }
}
