package io.github.pawelwywiol.minervaweb.download

import io.github.pawelwywiol.minervaweb.catalog.CatalogReader
import io.github.pawelwywiol.minervaweb.placement.FilePlacement
import io.github.pawelwywiol.minervaweb.placement.needsUnpacking
import io.github.pawelwywiol.minervaweb.placement.playableName
import io.github.pawelwywiol.minervaweb.queue.Active
import io.github.pawelwywiol.minervaweb.queue.DownloadQueue
import io.github.pawelwywiol.minervaweb.queue.DownloadState
import io.github.pawelwywiol.minervaweb.queue.QueueItem
import io.github.pawelwywiol.minervaweb.settings.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.ConnectException
import java.net.UnknownHostException
import java.net.http.HttpTimeoutException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile

private const val PERCENT = 100

class DownloadRunner(
    private val queue: DownloadQueue,
    private val settings: Settings,
    private val torrents: TorrentCache,
    private val placement: FilePlacement,
    private val stagingRoot: Path,
    scope: CoroutineScope,
    private val downloaderFactory: (Int) -> SelectiveDownloader = { SelectiveDownloader(it) },
) {
    private val log = LoggerFactory.getLogger(DownloadRunner::class.java)
    private val kicks = Channel<Unit>(Channel.CONFLATED)
    private var current: Job? = null

    @Volatile
    var enabled: Boolean = true
        private set

    val working: Boolean get() = current?.isActive == true

    init {
        queue.resetInterrupted()
        scope.launch {
            for (kick in kicks) {
                if (!enabled) continue
                val job = launch { runOnce() }
                synchronized(this@DownloadRunner) { current = job }
                job.join()
            }
        }
        kick()
    }

    fun kick() {
        kicks.trySend(Unit)
    }

    fun setRunning(running: Boolean) {
        enabled = running
        if (running) kick() else synchronized(this) { current?.cancel() }
    }

    // a file already in its folder is recorded as downloaded instead of fetched again
    fun toggle(rom: CatalogReader.Rom): Boolean {
        val added =
            when {
                queue.contains(rom) -> {
                    queue.toggle(rom)
                    false
                }

                queue.isDownloaded(rom) -> {
                    queue.toggle(rom)
                }

                else -> {
                    val folder = settings.folderFor(rom.collection, rom.platformKey)
                    if (placement.exists(folder, rom.name, rom.size)) {
                        queue.recordDownloaded(rom, folder)
                        false
                    } else {
                        queue.toggle(rom)
                    }
                }
            }
        if (added) kick()
        return added
    }

    private suspend fun runOnce() {
        if (pendingGroups().isEmpty()) return
        try {
            val limit = settings.concurrentDownloads
            downloaderFactory(limit).use { downloader ->
                coroutineScope {
                    // re-read between batches so a game added mid-run is picked up; failed/paused are not pending
                    while (true) {
                        val pending = pendingGroups()
                        if (pending.isEmpty()) break
                        val slots = Semaphore(limit)
                        pending
                            .map { (path, items) ->
                                async { slots.withPermit { downloadGroup(downloader, path, items) } }
                            }.awaitAll()
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("queue run failed", e)
        } finally {
            queue.active.keys.forEach { queue.setActive(it, null) }
        }
    }

    private fun pendingGroups(): Map<String, List<QueueItem>> =
        queue
            .byTorrent()
            .mapValues { (_, items) -> items.filter { it.state == DownloadState.QUEUED } }
            .filterValues { it.isNotEmpty() }

    private suspend fun downloadGroup(
        downloader: SelectiveDownloader,
        torrentPath: String,
        items: List<QueueItem>,
    ) {
        items.forEach { queue.setState(it.id, DownloadState.DOWNLOADING) }
        // one staging dir per torrent: groups run side by side and clean up independently
        val staging = stagingRoot.resolve(torrentPath.replace(Regex("[^A-Za-z0-9.-]"), "_"))
        val label = torrentPath.substringAfterLast('/').removeSuffix(".torrent")
        try {
            Files.createDirectories(staging)
            val needed = items.sumOf { it.size }
            val free = minOf(Files.getFileStore(staging).usableSpace, placement.freeBytes())
            if (needed > free) throw OutOfSpace(needed, free)

            val torrent = torrents.fetch(torrentPath)
            val result =
                downloader.download(torrent, staging, { stillWanted(items) }) { p ->
                    val percent = if (p.total > 0) (p.done * PERCENT / p.total).toInt() else 0
                    queue.setActive(torrentPath, Active(label, percent, p.rate, p.peers))
                }
            // the hard rule is checked, not assumed: protocol overhead is unavoidable, file content must be 0
            check(result.payloadUploaded == 0L) { "shared ${result.payloadUploaded} bytes of file content" }

            val files = loadTorrentInfo(torrent).files()
            val wanted = stillWanted(items)
            val finished = items.filter { it.soId in wanted }
            finished.forEach { verifyAndPlace(it, staging.resolve(files.filePath(it.soId))) }
            // partial data is kept on failure so the next run resumes
            if (finished.size == items.size) staging.toFile().deleteRecursively()
        } catch (_: SelectiveDownloader.AllPaused) {
            // every file was paused; states are already set
        } catch (e: CancellationException) {
            items.forEach { queue.setState(it.id, DownloadState.QUEUED) }
            throw e
        } catch (e: Exception) {
            log.error("torrent failed: $torrentPath", e)
            items.forEach { queue.setState(it.id, DownloadState.FAILED, reason(e)) }
        } finally {
            queue.setActive(torrentPath, null)
        }
    }

    private fun stillWanted(items: List<QueueItem>): Set<Int> {
        val paused = queue.pausedIds()
        return items.filterNot { it.id in paused }.mapTo(mutableSetOf()) { it.soId }
    }

    private fun verifyAndPlace(
        item: QueueItem,
        file: Path,
    ) {
        queue.setState(item.id, DownloadState.VERIFYING)
        val size = if (file.isRegularFile()) file.fileSize() else -1
        val md5 = if (size == item.size && item.md5 != null) file.md5() else null
        if (size != item.size || md5 != item.md5) {
            log.warn(
                "verification failed for {}: size {} of {}, md5 {} expected {}",
                file,
                size,
                item.size,
                md5,
                item.md5,
            )
            // a damaged file is deleted, left in staging it would look finished on the next run
            file.deleteIfExists()
            queue.setState(item.id, DownloadState.FAILED, "The downloaded file was damaged — remove it and try again")
            return
        }
        val folder = settings.folderFor(item.collection, item.platformKey)
        runCatching {
            if (settings.unpackArchives && needsUnpacking(item.name, file)) {
                val written = placement.unpack(folder, file)
                check(written.isNotEmpty()) { "the archive was empty" }
                playableName(written)
            } else {
                placement.place(folder, item.name, file)
            }
        }.fold(
            onSuccess = { queue.markDownloaded(item, folder, it) },
            onFailure = {
                log.error("cannot place ${item.name} in $folder", it)
                queue.setState(item.id, DownloadState.FAILED, "Could not save into $folder — ${it.message.orEmpty()}")
            },
        )
    }

    private fun reason(error: Throwable): String =
        when (error) {
            is OutOfSpace -> {
                "Not enough space — needs ${error.needed} bytes, ${error.free} free"
            }

            is SelectiveDownloader.Stalled -> {
                if (error.peers == 0) {
                    "No one is sharing this file right now — try again later"
                } else {
                    "The download stopped making progress — try again later"
                }
            }

            is UnknownHostException, is HttpTimeoutException, is ConnectException -> {
                "No connection — try again"
            }

            is IOException -> {
                "Connection problem — ${error.message.orEmpty().ifEmpty { "try again" }}"
            }

            else -> {
                error.message?.takeIf { it.isNotBlank() } ?: "Something went wrong"
            }
        }

    private class OutOfSpace(
        val needed: Long,
        val free: Long,
    ) : Exception("needs $needed bytes, $free free")
}
