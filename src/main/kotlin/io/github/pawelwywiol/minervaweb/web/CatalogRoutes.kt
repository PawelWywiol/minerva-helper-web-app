package io.github.pawelwywiol.minervaweb.web

import io.github.pawelwywiol.minervaweb.catalog.CatalogReader
import io.github.pawelwywiol.minervaweb.catalog.CatalogService
import io.github.pawelwywiol.minervaweb.metadata.GameInfoLookup
import io.github.pawelwywiol.minervaweb.platform.Systems
import io.github.pawelwywiol.minervaweb.queue.DownloadQueue
import io.github.pawelwywiol.minervaweb.settings.Settings
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.util.Base64

private const val DEFAULT_PAGE = 50
private const val MAX_PAGE = 200

// what is already picked or downloaded, read once per request rather than per row
internal class Marks(
    queue: DownloadQueue,
) {
    private val queued = queue.queuedKeys()
    private val downloaded = queue.downloadedKeys()

    fun put(
        target: JsonObjectBuilder,
        rom: CatalogReader.Rom,
    ) {
        val key = DownloadQueue.key(rom.torrentPath, rom.soId)
        target.put("queued", key in queued)
        target.put("downloaded", key in downloaded)
    }
}

fun Route.catalogRoutes(
    catalog: CatalogService,
    systems: Systems,
    settings: Settings,
    queue: DownloadQueue,
    gameInfo: GameInfoLookup,
) {
    get("/api/catalog") {
        val status = catalog.status
        call.json(
            buildJsonObject {
                put("state", status.state.name.lowercase())
                put("done", status.done)
                put("total", status.total)
                put("error", status.error)
            },
        )
    }

    get("/api/catalog/update") { call.json(buildJsonObject { put("available", catalog.hasUpdate()) }) }

    post("/api/catalog/sync") {
        val started = catalog.start(force = call.request.queryParameters["force"] == "true")
        call.json(buildJsonObject { put("started", started) })
    }

    get("/api/collections") {
        call.withCatalog(catalog) { reader ->
            buildJsonObject {
                put(
                    "collections",
                    buildJsonArray {
                        reader.collections().forEach {
                            add(
                                buildJsonObject {
                                    put("name", it.name)
                                    put("fileCount", it.fileCount)
                                    put("platformCount", it.platformCount)
                                },
                            )
                        }
                    },
                )
            }
        }
    }

    get("/api/collections/{name}/platforms") {
        val collection = call.parameters["name"].orEmpty()
        call.withCatalog(catalog) { reader ->
            buildJsonObject {
                put("collection", collection)
                put(
                    "platforms",
                    buildJsonArray {
                        reader.platforms(collection).forEach {
                            add(
                                buildJsonObject {
                                    put("id", it.id)
                                    put("key", it.key)
                                    put("label", systems.labelFor(it.key, settings.slugFor(collection, it.key)))
                                    put("folder", settings.folderFor(collection, it.key))
                                    put("fileCount", it.fileCount)
                                },
                            )
                        }
                    },
                )
            }
        }
    }

    get("/api/platforms/{id}/roms") {
        val platformId = call.parameters["id"]?.toLongOrNull() ?: return@get call.badRequest()
        val limit = call.pageSize()
        val afterName = call.request.queryParameters["afterName"]
        val afterId = call.request.queryParameters["afterId"]?.toLongOrNull()
        call.withCatalog(catalog) { romsJson(it.browse(platformId, afterName, afterId, limit), limit, Marks(queue)) }
    }

    // FTS returns rows by relevance with no key to seek to, so search pages by offset
    get("/api/search") {
        val query = call.request.queryParameters["q"].orEmpty()
        val limit = call.pageSize()
        val offset =
            call.request.queryParameters["offset"]
                ?.toIntOrNull()
                ?.coerceAtLeast(0) ?: 0
        call.withCatalog(catalog) { reader ->
            romsJson(if (query.isBlank()) emptyList() else reader.search(query, limit, offset), limit, Marks(queue))
        }
    }

    get("/api/roms/{id}") {
        call.withRom(catalog) { rom ->
            val slug = settings.slugFor(rom.collection, rom.platformKey)
            val info = gameInfo.lookup(rom, slug)
            call.json(
                buildJsonObject {
                    putRom(rom, Marks(queue))
                    put("torrentPath", rom.torrentPath)
                    put("md5", rom.md5)
                    put("sha1", rom.sha1)
                    put("favourite", queue.isFavourite(rom))
                    put("folder", settings.folderFor(rom.collection, rom.platformKey))
                    put("platform", systems.labelFor(rom.platformKey, slug))
                    put("title", info.title)
                    put("system", info.system)
                    // inlined so no route ever serves a file from disk
                    put("cover", info.cover?.let { "data:image/png;base64," + Base64.getEncoder().encodeToString(it) })
                },
            )
        }
    }
}

private fun romsJson(
    roms: List<CatalogReader.Rom>,
    limit: Int,
    marks: Marks,
): JsonObject =
    buildJsonObject {
        put("roms", buildJsonArray { roms.forEach { add(buildJsonObject { putRom(it, marks) }) } })
        put("more", roms.size >= limit)
        roms.lastOrNull()?.let { last ->
            put(
                "last",
                buildJsonObject {
                    put("name", last.name)
                    put("id", last.id)
                },
            )
        }
    }

internal fun romJson(
    rom: CatalogReader.Rom,
    marks: Marks,
): JsonObject = buildJsonObject { putRom(rom, marks) }

private fun JsonObjectBuilder.putRom(
    rom: CatalogReader.Rom,
    marks: Marks,
) {
    put("id", rom.id)
    put("name", rom.name)
    put("size", rom.size)
    put("collection", rom.collection)
    put("platformKey", rom.platformKey)
    marks.put(this, rom)
}

private fun ApplicationCall.pageSize(): Int =
    request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, MAX_PAGE) ?: DEFAULT_PAGE

private suspend fun ApplicationCall.withCatalog(
    catalog: CatalogService,
    block: (CatalogReader) -> JsonObject,
) {
    val body = catalog.read(block)
    if (body == null) noCatalog() else json(body)
}

// one ROM by id, taken from the path or from a mutation's {"romId": ...} body
internal suspend fun ApplicationCall.withRom(
    catalog: CatalogService,
    block: suspend (CatalogReader.Rom) -> Unit,
) {
    val id = parameters["id"]?.toLongOrNull() ?: body()?.get("romId")?.toString()?.toLongOrNull()
    if (id == null) return badRequest()
    val found = catalog.read { listOfNotNull(it.rom(id)) }
    when {
        found == null -> noCatalog()
        found.isEmpty() -> respondText("", status = HttpStatusCode.NotFound)
        else -> block(found.single())
    }
}

internal suspend fun ApplicationCall.body(): JsonObject? =
    runCatching {
        Json.parseToJsonElement(receiveText()).jsonObject
    }.getOrNull()

internal suspend fun ApplicationCall.noCatalog() =
    json(buildJsonObject { put("catalogReady", false) }, HttpStatusCode.ServiceUnavailable)

internal suspend fun ApplicationCall.badRequest() = respondText("", status = HttpStatusCode.BadRequest)

internal suspend fun ApplicationCall.json(
    body: JsonObject,
    status: HttpStatusCode = HttpStatusCode.OK,
) {
    response.header(HttpHeaders.CacheControl, "no-store")
    response.header("X-Content-Type-Options", "nosniff")
    respondText(body.toString(), ContentType.Application.Json, status)
}
