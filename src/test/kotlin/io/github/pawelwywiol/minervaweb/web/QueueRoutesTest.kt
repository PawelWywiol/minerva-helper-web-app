package io.github.pawelwywiol.minervaweb.web

import io.github.pawelwywiol.minervaweb.MUTATION_HEADER
import io.github.pawelwywiol.minervaweb.catalog.TestCatalog
import io.github.pawelwywiol.minervaweb.module
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

private fun JsonObject.str(key: String): String = getValue(key).jsonPrimitive.content

private fun JsonObject.list(key: String): List<JsonObject> = getValue(key).jsonArray.map { it.jsonObject }

private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

class QueueRoutesTest {
    private val dir: Path = Files.createTempDirectory("queue-routes")
    private val nes = TestCatalog.NES

    @AfterTest
    fun cleanup() {
        dir.toFile().deleteRecursively()
    }

    private fun app(block: suspend ApplicationTestBuilder.() -> Unit) =
        testApplication {
            TestCatalog.catalog(dir)
            val services = testServices(dir)
            application { module(emptySet(), services) }
            block()
        }

    private suspend fun ApplicationTestBuilder.get(path: String): JsonObject =
        client.get(path) { header(HttpHeaders.Host, "localhost") }.json()

    private suspend fun ApplicationTestBuilder.post(
        path: String,
        body: String,
    ) = client.post(path) {
        header(HttpHeaders.Host, "localhost")
        header(MUTATION_HEADER, "1")
        setBody(body)
    }

    private suspend fun ApplicationTestBuilder.mario(): JsonObject = get("/api/search?q=mario").list("roms").single()

    private suspend fun ApplicationTestBuilder.queueItem(): JsonObject =
        get("/api/queue")
            .list("groups")
            .single()
            .list("items")
            .single()

    @Test
    fun `queueing marks the rom and groups it by torrent`() =
        app {
            val id = mario().str("id")
            assertEquals("true", post("/api/queue", """{"romId":$id}""").json().str("queued"))
            assertEquals("true", mario().str("queued"))

            assertEquals("No-Intro/nes.torrent", get("/api/queue").list("groups").single().str("torrentPath"))
            val item = queueItem()
            assertEquals("nes", item.str("folder"))
            assertEquals("queued", item.str("state"))

            assertEquals(
                HttpStatusCode.OK,
                post("/api/queue/pause", """{"id":${item.str("id")},"paused":true}""").status,
            )
            assertEquals("paused", queueItem().str("state"))

            client.delete("/api/queue/${item.str("id")}") {
                header(HttpHeaders.Host, "localhost")
                header(MUTATION_HEADER, "1")
            }
            assertEquals("0", get("/api/queue").str("count"))
        }

    @Test
    fun `a file already in its folder counts as downloaded`() =
        app {
            val folder = Files.createDirectories(dir.resolve("library/roms/nes"))
            Files.write(folder.resolve("Super Mario Bros. (World).zip"), ByteArray(1000))
            val body = post("/api/queue", """{"romId":${mario().str("id")}}""").json()
            assertEquals("false", body.str("queued"))
            assertEquals("true", body.str("downloaded"))
        }

    @Test
    fun `settings reject folders that leave the library`() =
        app {
            val escape = """{"collection":"No-Intro","platformKey":"$nes","folder":"../etc"}"""
            assertEquals(HttpStatusCode.BadRequest, post("/api/settings", escape).status)

            val change = """{"collection":"No-Intro","platformKey":"$nes","folder":"famicom","concurrentDownloads":9}"""
            assertEquals(HttpStatusCode.OK, post("/api/settings", change).status)
            assertEquals("4", get("/api/settings").str("concurrentDownloads"))
            assertEquals("famicom", get("/api/collections/No-Intro/platforms").list("platforms").single().str("folder"))
        }
}
