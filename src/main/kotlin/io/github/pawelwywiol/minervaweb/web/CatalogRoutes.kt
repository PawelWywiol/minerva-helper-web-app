package io.github.pawelwywiol.minervaweb.web

import io.github.pawelwywiol.minervaweb.catalog.CatalogReader
import io.github.pawelwywiol.minervaweb.catalog.CatalogService
import io.github.pawelwywiol.minervaweb.catalog.Regions
import io.github.pawelwywiol.minervaweb.catalog.titleOf
import io.github.pawelwywiol.minervaweb.platform.SystemMatcher
import io.github.pawelwywiol.minervaweb.queue.DownloadQueue
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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.util.Base64

private const val DEFAULT_PAGE = 50
private const val MAX_PAGE = 200

// what a list row shows besides the name: where it lands and whether it is picked, read once per request
internal class RomView(
    private val s: Services,
) {
    private val queued = s.queue.queuedKeys()
    private val downloaded = s.queue.downloadedKeys()
    private val favourites = s.queue.favourites().toSet()

    // another version of the same game in the same folder: a USA release next to a Japanese one already kept
    private val similar: Map<String, String> =
        s.queue.all().associate {
            similarKey(
                s.settings.folderFor(it.collection, it.platformKey),
                it.name,
            ) to "queue"
        } +
            s.queue.downloaded().associate { similarKey(it.folder, it.name) to "library" }

    private fun similarKey(
        folder: String,
        name: String,
    ) = "$folder/${titleOf(name)}"

    fun put(
        target: JsonObjectBuilder,
        rom: CatalogReader.Rom,
    ) {
        val key = DownloadQueue.key(rom.torrentPath, rom.soId)
        val slug = s.settings.slugFor(rom.collection, rom.platformKey)
        val folder = s.settings.folderFor(rom.collection, rom.platformKey)
        target.put("folder", folder)
        target.put("platform", s.systems.labelFor(rom.platformKey, slug))
        target.put("family", s.systems.familyFor(slug))
        target.put("queued", key in queued)
        target.put("downloaded", key in downloaded)
        target.put("favourite", Triple(rom.collection, rom.platformKey, rom.name) in favourites)
        target.put("similar", similar[similarKey(folder, rom.name)])
    }
}

private fun JsonObjectBuilder.putSystem(system: SystemMatcher.System) {
    put("folder", system.folder)
    put("label", system.label)
    put("family", system.family)
    put("games", system.games)
}

fun Route.catalogRoutes(s: Services) {
    val catalog = s.catalog
    val systems = s.systems
    val settings = s.settings
    val matcher = SystemMatcher(settings, systems)
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
                            val slug = settings.slugFor(collection, it.key)
                            add(
                                buildJsonObject {
                                    put("id", it.id)
                                    put("key", it.key)
                                    put("label", systems.labelFor(it.key, slug))
                                    put("family", systems.familyFor(slug))
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
        call.withCatalog(catalog) { romsJson(it.browse(platformId, afterName, afterId, limit), limit, RomView(s)) }
    }

    // ranked results have no key to seek to, so search pages by offset
    get("/api/search") {
        val query = call.request.queryParameters["q"].orEmpty()
        val limit = call.pageSize()
        val offset =
            call.request.queryParameters["offset"]
                ?.toIntOrNull()
                ?.coerceAtLeast(0) ?: 0
        val anywhere = call.request.queryParameters["anywhere"] == "true"
        val hidden = Regions.parse(call.request.queryParameters["hide"])
        call.withCatalog(catalog) { reader ->
            val parsed = matcher.parse(reader.platforms(), query, anywhere)
            val system = parsed.system
            // a query still being typed ends mid-word, so its last word may be the start of one
            val prefix = query.lastOrNull()?.isLetterOrDigit() == true
            val roms =
                if (system != null && parsed.terms.isEmpty()) {
                    reader.list(system.platformIds, limit, offset, hidden)
                } else {
                    reader.rank(parsed.terms, prefix, system?.platformIds, limit, offset, hidden)
                }
            buildJsonObject {
                putRoms(roms, limit, RomView(s))
                put("terms", buildJsonArray { parsed.terms.forEach { add(JsonPrimitive(it)) } })
                put("prefix", prefix)
                put("system", system?.let { buildJsonObject { putSystem(it) } } ?: JsonNull)
                put("systems", buildJsonArray { parsed.suggestions.forEach { add(buildJsonObject { putSystem(it) }) } })
            }
        }
    }

    // other files of the same game for the same folder: other regions, revisions, re-releases
    get("/api/roms/{id}/versions") {
        val limit = call.pageSize()
        val offset =
            call.request.queryParameters["offset"]
                ?.toIntOrNull()
                ?.coerceAtLeast(0) ?: 0
        val hidden = Regions.parse(call.request.queryParameters["hide"])
        call.withRom(catalog) { rom ->
            val folder = settings.folderFor(rom.collection, rom.platformKey)
            val title = titleOf(rom.name)
            val versions =
                catalog.read { reader ->
                    val platformIds =
                        reader.platforms().filter { settings.folderFor(it.collection, it.key) == folder }.map { it.id }
                    reader.rank(
                        title.split(' ').filter { it.isNotEmpty() },
                        false,
                        platformIds,
                        limit,
                        offset,
                        hidden,
                    ) {
                        it.id != rom.id && titleOf(it.name) == title
                    }
                }
            if (versions == null) call.noCatalog() else call.json(romsJson(versions, limit, RomView(s)))
        }
    }

    get("/api/roms/{id}") {
        call.withRom(catalog) { rom ->
            val info = s.gameInfo.lookup(rom, settings.slugFor(rom.collection, rom.platformKey))
            call.json(
                buildJsonObject {
                    putRom(rom, RomView(s))
                    put("torrentPath", rom.torrentPath)
                    put("md5", rom.md5)
                    put("sha1", rom.sha1)
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
    view: RomView,
): JsonObject = buildJsonObject { putRoms(roms, limit, view) }

private fun JsonObjectBuilder.putRoms(
    roms: List<CatalogReader.Rom>,
    limit: Int,
    view: RomView,
) {
    put("roms", buildJsonArray { roms.forEach { add(buildJsonObject { putRom(it, view) }) } })
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
    view: RomView,
): JsonObject = buildJsonObject { putRom(rom, view) }

private fun JsonObjectBuilder.putRom(
    rom: CatalogReader.Rom,
    view: RomView,
) {
    put("id", rom.id)
    put("name", rom.name)
    put("size", rom.size)
    put("collection", rom.collection)
    put("platformKey", rom.platformKey)
    view.put(this, rom)
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
