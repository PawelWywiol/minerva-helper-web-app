package io.github.pawelwywiol.minervaweb.catalog

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class HashesDownloaderTest {
    private val body = Random(1).nextBytes(700_000)
    private var etag = "\"v1\""
    private val ranges = mutableListOf<String?>()
    private val server =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/hashes.db") { exchange ->
                exchange.responseHeaders.add("ETag", etag)
                val range = exchange.requestHeaders.getFirst("Range")
                if (exchange.requestMethod == "GET") ranges += range
                val from = range?.removePrefix("bytes=")?.removeSuffix("-")?.toInt() ?: 0
                val part = body.copyOfRange(from, body.size)
                exchange.responseHeaders.add("Content-Length", body.size.toString())
                if (exchange.requestMethod == "HEAD") {
                    exchange.sendResponseHeaders(200, -1)
                } else {
                    exchange.responseHeaders.set("Content-Length", part.size.toString())
                    exchange.sendResponseHeaders(if (range != null) 206 else 200, part.size.toLong())
                    exchange.responseBody.use { it.write(part) }
                }
                exchange.close()
            }
            start()
        }
    private val dir: Path = Files.createTempDirectory("hashes")
    private val target = dir.resolve("hashes.db")
    private val downloader =
        HashesDownloader(HttpClient.newHttpClient(), URI("http://127.0.0.1:${server.address.port}/hashes.db"))

    @AfterTest
    fun cleanup() {
        server.stop(0)
        dir.toFile().deleteRecursively()
    }

    @Test
    fun `downloads the whole file`() {
        runBlocking { downloader.download(target) {} }
        assertContentEquals(body, target.readBytes())
        assertEquals(listOf<String?>(null), ranges)
        assertEquals("\"v1\":${body.size}", downloader.version())
    }

    @Test
    fun `resumes a partial file with the same etag`() {
        target.writeBytes(body.copyOfRange(0, 100_000))
        dir.resolve("hashes.db.etag").writeText(etag)
        runBlocking { downloader.download(target) {} }
        assertContentEquals(body, target.readBytes())
        assertEquals(listOf<String?>("bytes=100000-"), ranges)
    }

    @Test
    fun `starts over when the etag changed`() {
        target.writeBytes(ByteArray(100_000))
        dir.resolve("hashes.db.etag").writeText("\"old\"")
        etag = "\"v2\""
        runBlocking { downloader.download(target) {} }
        assertContentEquals(body, target.readBytes())
        assertEquals(listOf<String?>(null), ranges)
    }
}
