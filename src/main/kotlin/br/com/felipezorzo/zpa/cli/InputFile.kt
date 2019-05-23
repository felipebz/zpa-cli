package br.com.felipezorzo.zpa.cli

import org.sonar.plugins.plsqlopen.api.PlSqlFile
import java.io.File
import java.nio.charset.Charset
import java.nio.file.Path
import java.nio.file.Paths

class InputFile(private val baseDirPath: Path, private val file: File, private val charset: Charset) : PlSqlFile {

    override fun contents(): String =
        file.inputStream().use {
            return it.bufferedReader(charset).use { r -> r.readText() }
        }

    override fun fileName(): String  = file.name

    val pathRelativeToBase: String = baseDirPath.relativize(Paths.get(file.absolutePath)).toString()

}