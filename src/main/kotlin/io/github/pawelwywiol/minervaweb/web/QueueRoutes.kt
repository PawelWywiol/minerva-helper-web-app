package io.github.pawelwywiol.minervaweb.web

import io.github.pawelwywiol.minervaweb.catalog.CatalogService
import io.github.pawelwywiol.minervaweb.download.DownloadRunner
import io.github.pawelwywiol.minervaweb.metadata.GameInfoLookup
import io.github.pawelwywiol.minervaweb.placement.FilePlacement
import io.github.pawelwywiol.minervaweb.placement.isValidFolder
import io.github.pawelwywiol.minervaweb.platform.Systems
import io.github.pawelwywiol.minervaweb.queue.DownloadQueue
import io.github.pawelwywiol.minervaweb.queue.DownloadState
import io.github.pawelwywiol.minervaweb.settings.MAX_CONCURRENT_DOWNLOADS
import io.github.pawelwywiol.minervaweb.settings.MIN_CONCURRENT_DOWNLOADS
import io.github.pawelwywiol.minervaweb.settings.Settings
import io.ktor.server.application.ApplicationCall
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

private const val RECENT_DOWNLOADS = 20

class Services(
    val catalog: CatalogService,
    val queue: DownloadQueue,
    val runner: DownloadRunner,
    val settings: Settings,
    val placement: FilePlacement,
    val systems: Systems,
    val gameInfo: GameInfoLookup,
    val version: String,
)

fun Route.queueRoutes(s: Services) {
    get("/api/state") {
        val romCount = s.catalog.read { it.count() }
        call.json(
            buildJsonObject {
                put("version", s.version)
                put("catalogReady", romCount != null)
                put("romCount", romCount ?: 0L)
                put("rootChosen", true)
                put("concurrentDownloads", s.settings.concurrentDownloads)
                put("queued", s.queue.all().size)
                put("downloaded", s.queue.downloaded().size)
                put(
                    "active",
                    buildJsonArray {
                        s.queue.active.values.forEach {
                            add(
                                buildJsonObject {
                                    put("label", it.label)
                                    put("percent", it.percent)
                                    put("rate", it.rate)
                                    put("peers", it.peers)
                                },
                            )
                        }
                    },
                )
            },
        )
    }

    get("/api/queue") { call.json(queueJson(s)) }

    post("/api/queue") {
        call.withRom(s.catalog) { rom ->
            s.runner.toggle(rom)
            call.json(
                buildJsonObject {
                    put("queued", s.queue.contains(rom))
                    put("downloaded", s.queue.isDownloaded(rom))
                },
            )
        }
    }

    post("/api/queue/pause") {
        val body = call.body() ?: return@post call.badRequest()
        val paused = body["paused"]?.jsonPrimitive?.booleanOrNull ?: return@post call.badRequest()
        val torrentPath = body["torrentPath"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }
        val id = body["id"]?.jsonPrimitive?.longOrNull
        when {
            torrentPath != null -> s.queue.setGroupPaused(torrentPath, paused)
            id != null -> s.queue.setPaused(id, paused)
            else -> return@post call.badRequest()
        }
        // only QUEUED rows are picked up, so resuming has to wake the runner
        if (!paused) s.runner.kick()
        call.ok()
    }

    post("/api/queue/move") {
        val body = call.body() ?: return@post call.badRequest()
        s.queue.moveGroup(body.int("from") ?: -1, body.int("to") ?: -1)
        call.ok()
    }

    post("/api/queue/run") {
        val running =
            call
                .body()
                ?.get("running")
                ?.jsonPrimitive
                ?.booleanOrNull ?: return@post call.badRequest()
        s.runner.setRunning(running)
        call.ok()
    }

    post("/api/queue/retry") {
        s.queue.retryFailed()
        s.runner.kick()
        call.ok()
    }

    delete("/api/queue/{id}") { call.byId { s.queue.remove(it) } }

    // forgets the record only; the file itself is never touched from here
    delete("/api/downloaded/{id}") { call.byId { s.queue.removeDownloaded(it) } }

    get("/api/favourites") {
        val roms = s.catalog.read { reader -> s.queue.favourites().mapNotNull { (c, p, n) -> reader.rom(c, p, n) } }
        if (roms == null) return@get call.noCatalog()
        val view = RomView(s)
        call.json(buildJsonObject { put("roms", buildJsonArray { roms.forEach { add(romJson(it, view)) } }) })
    }

    post("/api/favourites") {
        call.withRom(s.catalog) { rom -> call.json(buildJsonObject { put("favourite", s.queue.toggleFavourite(rom)) }) }
    }

    get("/api/settings") { call.json(settingsJson(s)) }

    post("/api/settings") {
        val body = call.body() ?: return@post call.badRequest()
        val collection = body.string("collection")
        val platformKey = body.string("platformKey")
        val folder = body.string("folder")?.trim()
        val change =
            if (!collection.isNullOrEmpty() && !platformKey.isNullOrEmpty() && !folder.isNullOrEmpty()) {
                if (!isValidFolder(folder)) return@post call.badRequest()
                Triple(collection, platformKey, folder)
            } else {
                null
            }
        val previous = change?.let { s.settings.folderFor(it.first, it.second) }
        s.settings.update(
            concurrent = body.int("concurrentDownloads")?.takeIf { it > 0 },
            unpack = body["unpackArchives"]?.jsonPrimitive?.booleanOrNull,
            folder = change,
        )
        if (change != null && previous != null && previous != change.third &&
            body["moveFiles"]?.jsonPrimitive?.booleanOrNull == true
        ) {
            moveDownloaded(s, change, previous)
        }
        call.json(settingsJson(s))
    }
}

// files follow the new mapping; records whose files are gone keep their folder untouched
private fun moveDownloaded(
    s: Services,
    change: Triple<String, String, String>,
    previous: String,
) {
    val (collection, platformKey, folder) = change
    s.queue
        .downloaded()
        .filter { it.collection == collection && it.platformKey == platformKey && it.folder == previous }
        .forEach { if (s.placement.move(previous, folder, it.name)) s.queue.setDownloadedFolder(it.id, folder) }
}

private fun queueJson(s: Services): JsonObject {
    val byTorrent = s.queue.byTorrent()
    val items = byTorrent.values.flatten()
    val downloaded = s.queue.downloaded()
    return buildJsonObject {
        put(
            "groups",
            buildJsonArray {
                byTorrent.forEach { (torrentPath, group) ->
                    add(
                        buildJsonObject {
                            put("torrentPath", torrentPath)
                            put("label", torrentPath.substringAfterLast('/'))
                            put("paused", group.all { it.state == DownloadState.PAUSED })
                            put(
                                "items",
                                buildJsonArray {
                                    group.forEach { item ->
                                        add(
                                            buildJsonObject {
                                                put("id", item.id)
                                                put("name", item.name)
                                                put("size", item.size)
                                                put(
                                                    "platform",
                                                    s.systems.labelFor(
                                                        item.platformKey,
                                                        s.settings.slugFor(item.collection, item.platformKey),
                                                    ),
                                                )
                                                put("folder", s.settings.folderFor(item.collection, item.platformKey))
                                                put("state", item.state.name.lowercase())
                                                put("error", item.error)
                                            },
                                        )
                                    }
                                },
                            )
                            val active = s.queue.active[torrentPath]
                            if (active == null) {
                                put("active", JsonNull)
                            } else {
                                put(
                                    "active",
                                    buildJsonObject {
                                        put("percent", active.percent)
                                        put("rate", active.rate)
                                        put("peers", active.peers)
                                    },
                                )
                            }
                        },
                    )
                }
            },
        )
        put("count", items.size)
        put("bytes", items.sumOf { it.size })
        put("freeBytes", s.placement.freeBytes())
        put("working", s.runner.working)
        put("waiting", items.any { it.state == DownloadState.QUEUED })
        put("failed", items.any { it.state == DownloadState.FAILED })
        put("rootChosen", true)
        put(
            "downloaded",
            buildJsonArray {
                downloaded.take(RECENT_DOWNLOADS).forEach {
                    add(
                        buildJsonObject {
                            put("id", it.id)
                            put("name", it.name)
                            put("size", it.size)
                            put("folder", it.folder)
                        },
                    )
                }
            },
        )
        put("downloadedTotal", downloaded.size)
    }
}

private fun settingsJson(s: Services): JsonObject {
    val queued = s.queue.all().groupBy { it.collection to it.platformKey }
    val done = s.queue.downloaded().groupBy { it.collection to it.platformKey }
    return buildJsonObject {
        put("root", "library")
        put("concurrentDownloads", s.settings.concurrentDownloads)
        put("unpackArchives", s.settings.unpackArchives)
        put("minConcurrentDownloads", MIN_CONCURRENT_DOWNLOADS)
        put("maxConcurrentDownloads", MAX_CONCURRENT_DOWNLOADS)
        put(
            "platforms",
            buildJsonArray {
                (queued.keys + done.keys).sortedBy { it.second }.forEach { (collection, platformKey) ->
                    add(
                        buildJsonObject {
                            put("collection", collection)
                            put("platformKey", platformKey)
                            put("folder", s.settings.folderFor(collection, platformKey))
                            put("mapped", s.settings.isMapped(collection, platformKey))
                            put("queued", queued[collection to platformKey]?.size ?: 0)
                            put("downloaded", done[collection to platformKey]?.size ?: 0)
                        },
                    )
                }
            },
        )
    }
}

private fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull

private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

private suspend fun ApplicationCall.ok() = json(buildJsonObject { put("ok", true) })

private suspend fun ApplicationCall.byId(block: (Long) -> Unit) {
    val id = parameters["id"]?.toLongOrNull() ?: return badRequest()
    block(id)
    ok()
}
