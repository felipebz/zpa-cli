package br.com.felipezorzo.zpa.cli

import com.felipebz.zpa.api.PlSqlFile
import java.io.File
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.name

class InputFile(
    private val type: PlSqlFile.Type,
    private val baseDirPath: Path,
    private val file: File?,
    private val charset: Charset = StandardCharsets.UTF_8,
    private val inMemoryContent: String? = null,
    private val virtualPath: String? = null
) : PlSqlFile {

    constructor(
        type: PlSqlFile.Type,
        baseDirPath: Path,
        file: File,
        charset: Charset
    ) : this(type, baseDirPath, file, charset, null, null)

    init {
        require(file != null || inMemoryContent != null) {
            "Either file or inMemoryContent must be provided"
        }
    }

    override fun contents(): String =
        inMemoryContent ?: file!!.inputStream().use {
            it.bufferedReader(charset).use { r -> r.readText() }
        }

    override fun fileName(): String =
        virtualPath?.let { Paths.get(it).name.ifEmpty { it } } ?: file!!.name

    override fun type(): PlSqlFile.Type = type

    override fun path(): Path =
        virtualPath?.let { baseDirPath.resolve(it).normalize() } ?: file!!.toPath()

    val pathRelativeToBase: String =
        virtualPath?.let {
            val p = Paths.get(it)
            if (p.isAbsolute && p.startsWith(baseDirPath)) {
                baseDirPath.relativize(p).invariantSeparatorsPathString
            } else {
                p.invariantSeparatorsPathString
            }
        } ?: baseDirPath.relativize(Paths.get(file!!.absolutePath)).invariantSeparatorsPathString

    override fun hashCode(): Int {
        return file?.hashCode() ?: (inMemoryContent.hashCode() * 31 + (virtualPath?.hashCode() ?: 0))
    }

    override fun equals(other: Any?): Boolean {
        if (other == null || other !is InputFile) return false
        if (file != null && other.file != null) return file == other.file
        return file == null && other.file == null &&
            inMemoryContent == other.inMemoryContent &&
            virtualPath == other.virtualPath
    }

    override fun toString(): String {
        return pathRelativeToBase
    }

    companion object {
        fun fromStdin(
            baseDirPath: Path,
            content: String,
            virtualPath: String = "stdin.sql",
            type: PlSqlFile.Type = PlSqlFile.Type.MAIN
        ): InputFile = InputFile(
            type = type,
            baseDirPath = baseDirPath,
            file = null,
            charset = StandardCharsets.UTF_8,
            inMemoryContent = content,
            virtualPath = virtualPath
        )
    }
}
