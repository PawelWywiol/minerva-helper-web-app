package io.github.pawelwywiol.minervaweb.download

import io.github.pawelwywiol.minervaweb.catalog.CatalogReader
import io.github.pawelwywiol.minervaweb.download.LocalSwarm.Companion.isolate
import io.github.pawelwywiol.minervaweb.placement.FilePlacement
import io.github.pawelwywiol.minervaweb.queue.DownloadQueue
import io.github.pawelwywiol.minervaweb.queue.DownloadState
import io.github.pawelwywiol.minervaweb.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URI
import java.net.http.HttpClient
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.copyTo
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DownloadTest {
    private val dir: Path = Files.createTempDirectory("download")
    private val swarm = LocalSwarm(dir, mapOf("a.nes" to 300_000, "b.nes" to 200_000, "c.nes" to 100_000))

    @AfterTest
    fun cleanup() {
        swarm.close()
        dir.toFile().deleteRecursively()
    }

    private fun downloader() = SelectiveDownloader(peers = listOf(swarm.peer)) { isolate() }

    @Test
    fun `fetches only the wanted file and uploads no content`() {
        val target = dir.resolve("staging")
        val wanted = swarm.index("b.nes")
        val result = downloader().use { runBlocking { it.download(swarm.torrent, target, { setOf(wanted) }) {} } }

        assertEquals(0L, result.payloadUploaded)
        assertContentEquals(
            swarm.content.getValue("b.nes"),
            target.resolve(swarm.info.files().filePath(wanted)).readBytes(),
        )
        assertFalse(target.resolve(swarm.info.files().filePath(swarm.index("a.nes"))).exists())
    }

    @Test
    fun `every file arrives intact on its own`() {
        val md5 = { bytes: ByteArray -> MessageDigest.getInstance("MD5").digest(bytes).toHexString() }
        val known = swarm.content.mapValues { md5(it.value) }
        for (name in listOf("c.nes", "a.nes", "b.nes")) {
            val target = dir.resolve("each-$name")
            val wanted = swarm.index(name)
            downloader().use { runBlocking { it.download(swarm.torrent, target, { setOf(wanted) }) {} } }
            val got = md5(target.resolve(swarm.info.files().filePath(wanted)).readBytes())
            val files =
                (0 until swarm.info.numFiles()).map {
                    "${swarm.info.files().fileName(it)}@${swarm.info.files().fileOffset(it)}"
                }
            assertEquals(known.getValue(name), got, "$name index=$wanted files=$files known=$known")
        }
    }

    @Test
    fun `the runner downloads, verifies and files a queued rom`() {
        val torrentPath = "No-Intro/pack.torrent"
        val torrents = dir.resolve("torrents")
        Files.createDirectories(torrents)
        swarm.torrent.copyTo(torrents.resolve(torrentPath.replace(Regex("[^A-Za-z0-9.-]"), "_")))

        val queue = DownloadQueue(dir.resolve("queue.db"))
        val settings = Settings(dir.resolve("settings.json"))
        val placement = FilePlacement(dir.resolve("library/roms"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val cache = TorrentCache(torrents, HttpClient.newHttpClient(), URI("http://127.0.0.1:9/"))
        val runner = DownloadRunner(queue, settings, cache, placement, dir.resolve("staging"), scope) { downloader() }

        val bytes = swarm.content.getValue("c.nes")
        val md5 = MessageDigest.getInstance("MD5").digest(bytes).toHexString()
        val nes = "Nintendo - Nintendo Entertainment System (Headerless)"
        val rom =
            CatalogReader.Rom(
                1,
                "c.nes",
                bytes.size.toLong(),
                "No-Intro",
                nes,
                torrentPath,
                swarm.index("c.nes"),
                md5,
                null,
            )
        assertTrue(runner.toggle(rom))

        val done = runBlocking { withTimeoutOrNull(TIMEOUT_MS) { while (!queue.isDownloaded(rom)) delay(POLL_MS) } }
        assertNotNull(done, "not downloaded: queue=${queue.all()} active=${queue.active} working=${runner.working}")
        assertContentEquals(bytes, dir.resolve("library/roms/nes/c.nes").readBytes())
        assertEquals(emptyList(), queue.all())
        assertEquals("nes", queue.downloaded().single().folder)
        scope.cancel()
        queue.close()
    }

    @Test
    fun `a checksum mismatch fails the item and keeps nothing`() {
        val torrentPath = "No-Intro/pack.torrent"
        val torrents = Files.createDirectories(dir.resolve("torrents"))
        swarm.torrent.copyTo(torrents.resolve(torrentPath.replace(Regex("[^A-Za-z0-9.-]"), "_")))
        val queue = DownloadQueue(dir.resolve("queue.db"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val cache = TorrentCache(torrents, HttpClient.newHttpClient(), URI("http://127.0.0.1:9/"))
        val placement = FilePlacement(dir.resolve("library/roms"))
        val runner =
            DownloadRunner(
                queue,
                Settings(dir.resolve("s.json")),
                cache,
                placement,
                dir.resolve("staging"),
                scope,
            ) { downloader() }

        val size =
            swarm.content
                .getValue("a.nes")
                .size
                .toLong()
        val rom =
            CatalogReader.Rom(
                2,
                "a.nes",
                size,
                "No-Intro",
                "x",
                torrentPath,
                swarm.index("a.nes"),
                "0".repeat(32),
                null,
            )
        runner.toggle(rom)

        runBlocking {
            withTimeout(TIMEOUT_MS) {
                while (queue.all().single().state !=
                    DownloadState.FAILED
                ) {
                    delay(POLL_MS)
                }
            }
        }
        assertEquals("The downloaded file was damaged — remove it and try again", queue.all().single().error)
        assertFalse(queue.isDownloaded(rom))
        assertFalse(dir.resolve("library/roms/x/a.nes").exists())
        scope.cancel()
        queue.close()
    }

    private companion object {
        const val TIMEOUT_MS = 30_000L
        const val POLL_MS = 100L
    }
}
