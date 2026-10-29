package io.github.pawelwywiol.minervaweb.metadata

import com.sun.net.httpserver.HttpServer
import io.github.pawelwywiol.minervaweb.catalog.CatalogReader
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.net.http.HttpClient
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GameInfoTest {
    private val png = byteArrayOf(1, 2, 3)
    private val requests = mutableListOf<String>()
    private val responses =
        mapOf(
            "/api/v1/Lookup/ByHash/md5/abc" to
                """{"name":"Sonic","platform":{"name":"Sega Mega Drive"}}""".toByteArray(),
            "/Sega - Mega Drive - Genesis/Named_Boxarts/Sonic the Hedgehog (Europe).png" to png,
        )
    private val server =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                val path = URLDecoder.decode(exchange.requestURI.rawPath, Charsets.UTF_8)
                requests += path
                val body = responses[path]
                if (body == null) {
                    exchange.sendResponseHeaders(404, -1)
                } else {
                    exchange.sendResponseHeaders(200, body.size.toLong())
                    exchange.responseBody.write(body)
                }
                exchange.close()
            }
            start()
        }
    private val base = URI("http://127.0.0.1:${server.address.port}/")
    private val dir: Path = Files.createTempDirectory("gameinfo")
    private val rom =
        CatalogReader.Rom(
            1,
            "Sonic the Hedgehog (1991)(Sega)(EU).zip",
            1,
            "TOSEC",
            "Sega/Mega Drive",
            "t",
            0,
            "abc",
            null,
        )

    @AfterTest
    fun cleanup() {
        server.stop(0)
        dir.toFile().deleteRecursively()
    }

    @Test
    fun `finds title by hash and cover by region variant, then serves from cache`() {
        val lookup = GameInfoLookup(dir, HttpClient.newHttpClient(), base, base)
        val info = runBlocking { lookup.lookup(rom, "megadrive") }
        assertEquals("Sonic", info.title)
        assertEquals("Sega Mega Drive", info.system)
        assertContentEquals(png, info.cover)

        val before = requests.size
        val again = runBlocking { lookup.lookup(rom, "megadrive") }
        assertEquals(before, requests.size)
        assertEquals("Sonic", again.title)
        assertContentEquals(png, again.cover)
    }

    @Test
    fun `a network failure returns nothing and caches nothing`() {
        val offline = URI("http://127.0.0.1:9/")
        val info =
            runBlocking { GameInfoLookup(dir, HttpClient.newHttpClient(), offline, offline).lookup(rom, "megadrive") }
        assertNull(info.title)
        assertNull(info.cover)
        assertEquals(0, Files.list(dir).use { it.count() })
    }
}
