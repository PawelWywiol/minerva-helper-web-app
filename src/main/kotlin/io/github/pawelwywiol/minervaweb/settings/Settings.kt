package io.github.pawelwywiol.minervaweb.settings

import io.github.pawelwywiol.minervaweb.platform.PlatformFolders
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

const val MIN_CONCURRENT_DOWNLOADS = 1
const val MAX_CONCURRENT_DOWNLOADS = 4

class Settings(
    private val file: Path,
    private val platforms: PlatformFolders = PlatformFolders(),
) {
    @Volatile
    var concurrentDownloads: Int = 1
        private set

    @Volatile
    var unpackArchives: Boolean = true
        private set

    private var folders: Map<String, String> = emptyMap()

    init {
        if (file.exists()) {
            val json = Json.parseToJsonElement(file.readText()).jsonObject
            json["concurrentDownloads"]?.jsonPrimitive?.intOrNull?.let {
                concurrentDownloads =
                    it.coerceIn(MIN_CONCURRENT_DOWNLOADS, MAX_CONCURRENT_DOWNLOADS)
            }
            json["unpackArchives"]?.jsonPrimitive?.booleanOrNull?.let { unpackArchives = it }
            folders = json["folders"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content }.orEmpty()
        }
    }

    // where the user put a platform; slugFor stays the archive's mapping so labels do not follow renames
    @Synchronized
    fun folderFor(
        collection: String,
        platformKey: String,
    ): String = folders[key(collection, platformKey)] ?: platforms.folderFor(collection, platformKey)

    fun slugFor(
        collection: String,
        platformKey: String,
    ): String = platforms.folderFor(collection, platformKey)

    fun isMapped(
        collection: String,
        platformKey: String,
    ): Boolean = platforms.isMapped(collection, platformKey)

    @Synchronized
    fun update(
        concurrent: Int? = null,
        unpack: Boolean? = null,
        folder: Triple<String, String, String>? = null,
    ) {
        concurrent?.let { concurrentDownloads = it.coerceIn(MIN_CONCURRENT_DOWNLOADS, MAX_CONCURRENT_DOWNLOADS) }
        unpack?.let { unpackArchives = it }
        folder?.let { (collection, platformKey, name) ->
            folders =
                folders + (key(collection, platformKey) to name.trim())
        }
        save()
    }

    private fun save() {
        val json: JsonObject =
            buildJsonObject {
                put("concurrentDownloads", concurrentDownloads)
                put("unpackArchives", unpackArchives)
                put("folders", buildJsonObject { folders.forEach { (k, v) -> put(k, v) } })
            }
        val tmp = file.resolveSibling("${file.fileName}.tmp")
        Files.createDirectories(file.toAbsolutePath().parent)
        tmp.writeText(json.toString())
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun key(
        collection: String,
        platformKey: String,
    ) = "$collection/$platformKey"
}
