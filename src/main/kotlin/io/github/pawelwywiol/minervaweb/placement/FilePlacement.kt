package io.github.pawelwywiol.minervaweb.placement

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipFile
import kotlin.io.path.deleteIfExists
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile

private val FOLDER = Regex("^[A-Za-z0-9][A-Za-z0-9._+ -]*$")

fun isValidFolder(name: String): Boolean = FOLDER.matches(name)

// writes into <root>/<folder>/<name>; folder and names come from config and archives, so both are confined
class FilePlacement(
    root: Path,
) {
    private val root: Path = Files.createDirectories(root.toAbsolutePath().normalize())

    // replaces an existing file: a re-download is a repair, numbered duplicates would be worse
    fun place(
        folder: String,
        name: String,
        source: Path,
    ): String {
        val target = target(folder, name)
        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
        return target.fileName.toString()
    }

    fun unpack(
        folder: String,
        source: Path,
    ): List<String> {
        val placed = mutableListOf<String>()
        ZipFile(source.toFile()).use { zip ->
            for (entry in zip.entries()) {
                if (entry.isDirectory || isJunkEntry(entry.name)) continue
                val target = target(folder, entry.name.substringAfterLast('/').substringAfterLast('\\'))
                zip.getInputStream(entry).use { Files.copy(it, target, StandardCopyOption.REPLACE_EXISTING) }
                placed += target.fileName.toString()
            }
        }
        source.deleteIfExists()
        return placed
    }

    fun exists(
        folder: String,
        name: String,
        size: Long,
    ): Boolean =
        runCatching {
            dir(folder).resolve(name).let { it.isRegularFile() && it.fileSize() == size }
        }.getOrDefault(false)

    // an unpacked disc is a .cue and its tracks named after the game, so the whole set moves together
    fun move(
        fromFolder: String,
        toFolder: String,
        name: String,
    ): Boolean {
        val origin = dir(fromFolder)
        if (!Files.isDirectory(origin)) return false
        val stem = name.substringBeforeLast('.')
        val sources =
            Files.list(origin).use { files ->
                files
                    .filter {
                        it.isRegularFile() &&
                            (it.fileName.toString() == name || it.fileName.toString().startsWith(stem))
                    }.toList()
            }
        sources.forEach {
            Files.move(
                it,
                target(toFolder, it.fileName.toString()),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
        return sources.isNotEmpty()
    }

    fun freeBytes(): Long = Files.getFileStore(root).usableSpace

    private fun dir(folder: String): Path {
        require(isValidFolder(folder)) { "invalid folder name: $folder" }
        return root.resolve(folder)
    }

    private fun target(
        folder: String,
        name: String,
    ): Path {
        require(name.isNotBlank() && name != "." && name != ".." && '/' !in name && '\\' !in name) {
            "invalid file name: $name"
        }
        val dir = Files.createDirectories(dir(folder))
        val target = dir.resolve(name).normalize()
        check(target.parent == dir.normalize()) { "file escapes its folder: $name" }
        return target
    }
}
