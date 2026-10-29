package io.github.pawelwywiol.minervaweb.web

import io.github.pawelwywiol.minervaweb.catalog.CatalogReader
import io.github.pawelwywiol.minervaweb.catalog.CatalogService
import io.github.pawelwywiol.minervaweb.platform.PlatformFolders
import io.github.pawelwywiol.minervaweb.platform.Systems
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val DEFAULT_PAGE = 50
private const val MAX_PAGE = 200

fun Route.catalogRoutes(
    catalog: CatalogService,
    systems: Systems,
    folders: PlatformFolders,
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
                            val slug = folders.folderFor(collection, it.key)
                            add(
                                buildJsonObject {
                                    put("id", it.id)
                                    put("key", it.key)
                                    put("label", systems.labelFor(it.key, slug))
                                    put("folder", slug)
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
        call.withCatalog(catalog) { romsJson(it.browse(platformId, afterName, afterId, limit), limit) }
    }

    get("/api/search") {
        val query = call.request.queryParameters["q"].orEmpty()
        val limit = call.pageSize()
        val offset =
            call.request.queryParameters["offset"]
                ?.toIntOrNull()
                ?.coerceAtLeast(0) ?: 0
        call.withCatalog(
            catalog,
        ) { romsJson(if (query.isBlank()) emptyList() else it.search(query, limit, offset), limit) }
    }

    get("/api/roms/{id}") {
        val id = call.parameters["id"]?.toLongOrNull() ?: return@get call.badRequest()
        val found = catalog.read { listOfNotNull(it.rom(id)) }
        val rom = found?.firstOrNull()
        when {
            found == null -> {
                call.noCatalog()
            }

            rom == null -> {
                call.respondText("", status = HttpStatusCode.NotFound)
            }

            else -> {
                val slug = folders.folderFor(rom.collection, rom.platformKey)
                call.json(
                    buildJsonObject {
                        putRom(rom)
                        put("torrentPath", rom.torrentPath)
                        put("md5", rom.md5)
                        put("sha1", rom.sha1)
                        put("folder", slug)
                        put("platform", systems.labelFor(rom.platformKey, slug))
                    },
                )
            }
        }
    }
}

private fun romsJson(
    roms: List<CatalogReader.Rom>,
    limit: Int,
): JsonObject =
    buildJsonObject {
        put("roms", buildJsonArray { roms.forEach { add(buildJsonObject { putRom(it) }) } })
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

private fun JsonObjectBuilder.putRom(rom: CatalogReader.Rom) {
    put("id", rom.id)
    put("name", rom.name)
    put("size", rom.size)
    put("collection", rom.collection)
    put("platformKey", rom.platformKey)
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

private suspend fun ApplicationCall.noCatalog() =
    json(buildJsonObject { put("catalogReady", false) }, HttpStatusCode.ServiceUnavailable)

private suspend fun ApplicationCall.badRequest() = respondText("", status = HttpStatusCode.BadRequest)

internal suspend fun ApplicationCall.json(
    body: JsonObject,
    status: HttpStatusCode = HttpStatusCode.OK,
) {
    response.header(HttpHeaders.CacheControl, "no-store")
    response.header("X-Content-Type-Options", "nosniff")
    respondText(body.toString(), ContentType.Application.Json, status)
}
