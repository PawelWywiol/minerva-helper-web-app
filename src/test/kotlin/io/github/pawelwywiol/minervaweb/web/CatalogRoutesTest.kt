package io.github.pawelwywiol.minervaweb.web

import io.github.pawelwywiol.minervaweb.catalog.CatalogService
import io.github.pawelwywiol.minervaweb.catalog.CatalogSync
import io.github.pawelwywiol.minervaweb.catalog.HashesDownloader
import io.github.pawelwywiol.minervaweb.catalog.TestCatalog
import io.github.pawelwywiol.minervaweb.module
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class CatalogRoutesTest {
    private val dir: Path = Files.createTempDirectory("routes")

    @AfterTest
    fun cleanup() {
        dir.toFile().deleteRecursively()
    }

    private fun app(
        withCatalog: Boolean = true,
        block: suspend ApplicationTestBuilder.() -> Unit,
    ) = testApplication {
        if (withCatalog) TestCatalog.catalog(dir)
        val downloader = HashesDownloader(HttpClient.newHttpClient(), URI("http://127.0.0.1:9/none"))
        val catalog = CatalogService(CatalogSync(dir, downloader), CoroutineScope(Dispatchers.Default))
        application { module(emptySet(), catalog) }
        block()
    }

    private suspend fun ApplicationTestBuilder.get(path: String) =
        client.get(path) {
            header(HttpHeaders.Host, "localhost")
        }

    private suspend fun ApplicationTestBuilder.json(path: String): JsonObject =
        Json.parseToJsonElement(get(path).bodyAsText()).jsonObject

    @Test
    fun `reports a ready catalog`() =
        app {
            assertEquals("ready", json("/api/catalog")["state"]?.jsonPrimitive?.content)
            assertEquals(4, json("/api/collections")["collections"]?.jsonArray?.size)
        }

    @Test
    fun `platforms carry the es-de folder and a readable label`() =
        app {
            val platform = json("/api/collections/No-Intro/platforms")["platforms"]!!.jsonArray.single().jsonObject
            assertEquals("nes", platform["folder"]?.jsonPrimitive?.content)
            assertEquals("Nintendo Entertainment System", platform["label"]?.jsonPrimitive?.content)

            val path = "/api/platforms/${platform["id"]!!.jsonPrimitive.long}/roms?limit=1"
            val roms = json(path)
            assertEquals(true, roms["more"]?.jsonPrimitive?.content?.toBoolean())
            val last = roms["last"]!!.jsonObject
            val afterName = URLEncoder.encode(last["name"]!!.jsonPrimitive.content, Charsets.UTF_8)
            val next = json("$path&afterName=$afterName&afterId=${last["id"]!!.jsonPrimitive.long}")
            val names = next["roms"]!!.jsonArray.map { it.jsonObject["name"]?.jsonPrimitive?.content }
            assertEquals(listOf("Zelda (USA).zip"), names)
        }

    @Test
    fun `search and detail`() =
        app {
            val hit = json("/api/search?q=mario")["roms"]!!.jsonArray.single().jsonObject
            val detail = json("/api/roms/${hit["id"]!!.jsonPrimitive.long}")
            assertEquals("No-Intro/nes.torrent", detail["torrentPath"]?.jsonPrimitive?.content)
            assertEquals(TestCatalog.MD5, detail["md5"]?.jsonPrimitive?.content)
            assertEquals("nes", detail["folder"]?.jsonPrimitive?.content)
            assertEquals(HttpStatusCode.NotFound, get("/api/roms/12345").status)
        }

    @Test
    fun `missing catalog answers 503`() =
        app(withCatalog = false) {
            assertEquals("missing", json("/api/catalog")["state"]?.jsonPrimitive?.content)
            assertEquals(HttpStatusCode.ServiceUnavailable, get("/api/collections").status)
            assertEquals(HttpStatusCode.ServiceUnavailable, get("/api/roms/1").status)
        }
}
