package io.github.pawelwywiol.minervaweb.placement

import java.nio.file.Path
import java.util.zip.ZipFile

// formats emulators cannot open from inside a zip: disc images, N64 raw ROMs, Wii U, PS3 packages
private val UNPACKED_EXTENSIONS =
    setOf(
        "iso",
        "chd",
        "cue",
        "gdi",
        "cdi",
        "rvz",
        "gcm",
        "wbfs",
        "nkit",
        "cso",
        "img",
        "mdf",
        "ccd",
        "z64",
        "n64",
        "v64",
        "ndd",
        "wux",
        "wud",
        "wua",
        "rpx",
        "pkg",
    )

private val PLAYABLE_EXTENSIONS =
    setOf(
        "m3u",
        "cue",
        "gdi",
        "ccd",
        "chd",
        "iso",
        "rvz",
        "gcm",
        "wbfs",
        "cdi",
        "cso",
        "z64",
        "n64",
        "v64",
        "ndd",
        "wua",
        "wux",
        "wud",
        "pkg",
    )

internal fun isJunkEntry(entryName: String): Boolean =
    entryName.startsWith("__MACOSX/") || entryName.substringAfterLast('/').startsWith("._")

private fun entryNames(archive: Path): List<String> =
    runCatching {
        ZipFile(archive.toFile()).use { zip ->
            zip
                .entries()
                .asSequence()
                .filterNot { it.isDirectory || isJunkEntry(it.name) }
                .map { it.name }
                .toList()
        }
    }.getOrDefault(emptyList())

// cartridge zips stay zipped (emulators read them directly), discs are unpacked
fun needsUnpacking(
    name: String,
    archive: Path,
): Boolean {
    if (!name.endsWith(".zip", ignoreCase = true)) return false
    return entryNames(archive).any { it.substringAfterLast('.', "").lowercase() in UNPACKED_EXTENSIONS }
}

// the file the emulator opens: a .cue or .m3u rather than its tracks
fun playableName(written: List<String>): String =
    written.firstOrNull { it.substringAfterLast('.', "").lowercase() in PLAYABLE_EXTENSIONS } ?: written.first()
