package io.github.pawelwywiol.minervaweb.metadata

import io.github.pawelwywiol.minervaweb.catalog.CatalogReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText

private const val HTTP_OK = 200
private val TIMEOUT = Duration.ofSeconds(10)

class GameInfo(
    val title: String?,
    val system: String?,
    val cover: ByteArray?,
)

/**
 * Best effort and keyless: title and system from hasheous.org by md5, box art from libretro thumbnails by name.
 * Results, including "nothing found", are cached once per ROM; a network failure caches nothing and returns empty.
 */
class GameInfoLookup(
    private val dir: Path,
    private val http: HttpClient,
    private val hasheous: URI = URI("https://hasheous.org/"),
    private val thumbnails: URI = URI("https://thumbnails.libretro.com/"),
) {
    suspend fun lookup(
        rom: CatalogReader.Rom,
        slug: String,
    ): GameInfo =
        withContext(Dispatchers.IO) {
            Files.createDirectories(dir)
            val key = rom.md5 ?: "n${rom.name.hashCode().toUInt().toString(16)}-${rom.size}"
            val meta = dir.resolve("$key.json")
            val coverFile = dir.resolve("$key.png")
            if (meta.exists()) return@withContext cached(meta, coverFile)
            try {
                val found = rom.md5?.let { get("api/v1/Lookup/ByHash/md5/$it", hasheous) }?.let { parse(it) }
                val title = found?.string("name")
                val system = (found?.get("platform") as? JsonObject)?.string("name")
                val cover = fetchCover(rom, slug, title)
                cover?.let(coverFile::writeBytes)
                meta.writeText(
                    buildJsonObject {
                        put("title", title)
                        put("system", system)
                    }.toString(),
                )
                GameInfo(title, system, cover)
            } catch (_: IOException) {
                GameInfo(null, null, null)
            }
        }

    private fun cached(
        meta: Path,
        coverFile: Path,
    ): GameInfo {
        val json = parse(meta.readText()) ?: return GameInfo(null, null, null)
        return GameInfo(json.string("title"), json.string("system"), coverFile.takeIf { it.exists() }?.readBytes())
    }

    private fun fetchCover(
        rom: CatalogReader.Rom,
        slug: String,
        canonical: String?,
    ): ByteArray? {
        val systemDir = LIBRETRO_SYSTEMS[slug] ?: return null
        val base = rom.name.substringBeforeLast('.')
        val candidates =
            listOfNotNull(base, canonical, regionVariant(base), base.substringBefore(" (").trim())
                .map(::sanitize)
                .filter { it.isNotBlank() }
                .distinct()
        return candidates.firstNotNullOfOrNull {
            getBytes(
                "${encode(systemDir)}/Named_Boxarts/${encode(it)}.png",
                thumbnails,
            )
        }
    }

    private fun get(
        path: String,
        base: URI,
    ): String? = getBytes(path, base)?.decodeToString()

    private fun getBytes(
        path: String,
        base: URI,
    ): ByteArray? {
        val request = HttpRequest.newBuilder(base.resolve(path)).timeout(TIMEOUT).build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofByteArray())
        return response.body().takeIf { response.statusCode() == HTTP_OK }
    }

    private fun parse(body: String): JsonObject? = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull?.ifBlank { null }

    // "Title (1992)(Ocean)(EU)" -> "Title (Europe)": thumbnail names use No-Intro regions
    private fun regionVariant(name: String): String? {
        val title = name.substringBefore(" (").trim()
        if (title == name) return null
        val region = REGIONS.entries.firstOrNull { (tag, _) -> name.contains("($tag)", ignoreCase = true) }?.value
        return region?.let { "$title ($it)" }
    }

    // the characters the thumbnail server itself replaces in file names
    private fun sanitize(name: String): String = name.replace(Regex("[&*/:`<>?\\\\|]"), "_")

    private fun encode(segment: String): String = URLEncoder.encode(segment, Charsets.UTF_8).replace("+", "%20")
}

private val REGIONS =
    mapOf(
        "EU" to "Europe",
        "Europe" to "Europe",
        "US" to "USA",
        "USA" to "USA",
        "JP" to "Japan",
        "Japan" to "Japan",
        "World" to "World",
    )

// ES-DE folder -> libretro thumbnail system directory
private val LIBRETRO_SYSTEMS =
    mapOf(
        "arcade" to "MAME",
        "mame" to "MAME",
        "fbneo" to "FBNeo - Arcade Games",
        "amiga" to "Commodore - Amiga",
        "amiga600" to "Commodore - Amiga",
        "amiga1200" to "Commodore - Amiga",
        "amigacd32" to "Commodore - Amiga",
        "c64" to "Commodore - 64",
        "amstradcpc" to "Amstrad - CPC",
        "zxspectrum" to "Sinclair - ZX Spectrum",
        "atari2600" to "Atari - 2600",
        "atari5200" to "Atari - 5200",
        "atari7800" to "Atari - 7800",
        "atari800" to "Atari - 8-bit",
        "atarilynx" to "Atari - Lynx",
        "atarist" to "Atari - ST",
        "atarijaguar" to "Atari - Jaguar",
        "nes" to "Nintendo - Nintendo Entertainment System",
        "famicom" to "Nintendo - Nintendo Entertainment System",
        "snes" to "Nintendo - Super Nintendo Entertainment System",
        "n64" to "Nintendo - Nintendo 64",
        "gb" to "Nintendo - Game Boy",
        "gbc" to "Nintendo - Game Boy Color",
        "gba" to "Nintendo - Game Boy Advance",
        "nds" to "Nintendo - Nintendo DS",
        "n3ds" to "Nintendo - Nintendo 3DS",
        "gc" to "Nintendo - GameCube",
        "wii" to "Nintendo - Wii",
        "virtualboy" to "Nintendo - Virtual Boy",
        "gamegear" to "Sega - Game Gear",
        "mastersystem" to "Sega - Master System - Mark III",
        "megadrive" to "Sega - Mega Drive - Genesis",
        "genesis" to "Sega - Mega Drive - Genesis",
        "megacd" to "Sega - Mega-CD - Sega CD",
        "saturn" to "Sega - Saturn",
        "dreamcast" to "Sega - Dreamcast",
        "psx" to "Sony - PlayStation",
        "ps2" to "Sony - PlayStation 2",
        "psp" to "Sony - PlayStation Portable",
        "pcengine" to "NEC - PC Engine - TurboGrafx 16",
        "neogeo" to "SNK - Neo Geo",
        "ngp" to "SNK - Neo Geo Pocket",
        "ngpc" to "SNK - Neo Geo Pocket Color",
        "wonderswan" to "Bandai - WonderSwan",
        "colecovision" to "Coleco - ColecoVision",
        "intellivision" to "Mattel - Intellivision",
        "vectrex" to "GCE - Vectrex",
        "3do" to "The 3DO Company - 3DO",
        "msx" to "Microsoft - MSX",
        "msx2" to "Microsoft - MSX2",
        "dos" to "DOS",
    )
