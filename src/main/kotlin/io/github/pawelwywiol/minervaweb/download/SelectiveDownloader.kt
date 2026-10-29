package io.github.pawelwywiol.minervaweb.download

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.libtorrent4j.AlertListener
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.TcpEndpoint
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.AlertType
import org.libtorrent4j.alerts.CacheFlushedAlert
import org.libtorrent4j.swig.add_torrent_params
import org.libtorrent4j.swig.error_code
import org.libtorrent4j.swig.load_torrent_limits
import org.libtorrent4j.swig.settings_pack
import org.libtorrent4j.swig.torrent_flags_t
import org.libtorrent4j.swig.torrent_info
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

private const val STALL_LIMIT_SECONDS = 300
private const val TICK_MS = 1000L
private const val FLUSH_TIMEOUT_MS = 60_000L
private const val MAX_TORRENT_BUFFER = 256 * 1024 * 1024
private const val MAX_DECODE_TOKENS = 50_000_000
private const val MD5_BUFFER = 64 * 1024

/**
 * Downloads only the requested files of a torrent and never shares them:
 * 1. incoming TCP and uTP connections are refused;
 * 2. the unchoke limit is zero, so no peer receives content on connections we opened;
 * 3. the torrent is removed on every exit path, it never becomes a seed;
 * 4. [Result.payloadUploaded] must be 0 (checked by the caller).
 * Upload is not throttled to zero: that also blocks requests and stops downloading.
 * DHT stays on: the archive's trackers are dead and discovery is not sharing.
 */
class SelectiveDownloader(
    private val maxConcurrent: Int = 1,
    private val peers: List<TcpEndpoint> = emptyList(),
    private val extraSettings: SettingsPack.() -> Unit = {},
) : AutoCloseable {
    private val session by lazy { SessionManager().also { it.start(SessionParams(noSharingSettings())) } }

    class Stalled(
        val peers: Int,
    ) : Exception("no progress for ${STALL_LIMIT_SECONDS}s (peers=$peers)")

    class AllPaused : Exception("all files paused")

    data class Result(
        val payloadUploaded: Long,
        val protocolOverhead: Long,
    )

    data class Progress(
        val done: Long,
        val total: Long,
        val rate: Int,
        val peers: Int,
    )

    // wantedNow is re-read every tick, so pausing one file takes effect within a second; empty cancels
    suspend fun download(
        torrent: Path,
        targetDir: Path,
        wantedNow: () -> Set<Int>,
        onProgress: suspend (Progress) -> Unit,
    ): Result =
        withContext(Dispatchers.IO) {
            val info = loadTorrentInfo(torrent)
            var wanted = info.validate(wantedNow())
            require(wanted.isNotEmpty()) { "no valid file indexes for ${torrent.fileName}" }

            Files.createDirectories(targetDir)
            session.download(info, targetDir.toFile(), null, info.priorities(wanted), peers, torrent_flags_t())
            val handle = checkNotNull(session.find(info.infoHash())) { "no torrent handle" }
            try {
                var total = wanted.sumOf { info.files().fileSize(it) }
                var stalledFor = 0
                var lastDone = -1L
                val result: Result
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val status = handle.status()
                    // only complete, hash-checked pieces count; is_finished can be true before the files are
                    val verified = handle.fileProgress(TorrentHandle.PIECE_GRANULARITY)
                    val done = wanted.sumOf { verified[it] }
                    if (done >= total) {
                        result = Result(status.totalPayloadUpload(), status.totalUpload())
                        break
                    }
                    val requested = info.validate(wantedNow())
                    if (requested != wanted) {
                        if (requested.isEmpty()) throw AllPaused()
                        wanted = requested
                        handle.prioritizeFiles(info.priorities(wanted))
                        total = wanted.sumOf { info.files().fileSize(it) }
                        stalledFor = 0
                    }
                    stalledFor = if (done == lastDone) stalledFor + 1 else 0
                    lastDone = done
                    if (stalledFor >= STALL_LIMIT_SECONDS) throw Stalled(status.numPeers())
                    onProgress(Progress(done, total, status.downloadRate(), status.numPeers()))
                    delay(TICK_MS)
                }
                flush(handle)
                onProgress(Progress(total, total, 0, 0))
                result
            } finally {
                session.remove(handle)
            }
        }

    // verified pieces can still sit in libtorrent's write buffer; reading the file before this sees stale bytes
    private suspend fun flush(handle: TorrentHandle) {
        val hash = handle.infoHash()
        val flushed = CompletableDeferred<Unit>()
        val listener =
            object : AlertListener {
                override fun types() = intArrayOf(AlertType.CACHE_FLUSHED.swig())

                override fun alert(alert: Alert<*>) {
                    if ((alert as CacheFlushedAlert).handle().infoHash() == hash) flushed.complete(Unit)
                }
            }
        session.addListener(listener)
        try {
            handle.flushCache()
            withTimeout(FLUSH_TIMEOUT_MS) { flushed.await() }
        } finally {
            session.removeListener(listener)
        }
    }

    override fun close() {
        session.stop()
    }

    private fun TorrentInfo.validate(soIds: Set<Int>): Set<Int> =
        soIds.filterTo(mutableSetOf()) {
            it in
                0 until numFiles()
        }

    private fun TorrentInfo.priorities(wanted: Set<Int>): Array<Priority> =
        Array(numFiles()) { if (it in wanted) Priority.DEFAULT else Priority.IGNORE }

    internal fun noSharingSettings() =
        SettingsPack().apply {
            downloadRateLimit(0)
            uploadRateLimit(0)
            activeDownloads(maxConcurrent)
            activeSeeds(0)
            setBoolean(settings_pack.bool_types.enable_incoming_tcp.swigValue(), false)
            setBoolean(settings_pack.bool_types.enable_incoming_utp.swigValue(), false)
            setInteger(settings_pack.int_types.unchoke_slots_limit.swigValue(), 0)
            extraSettings()
        }
}

// the archive's largest torrents (27 MB, 305k files) exceed libtorrent's default bencode limits
fun loadTorrentInfo(torrent: Path): TorrentInfo {
    val limits =
        load_torrent_limits().apply {
            max_buffer_size = MAX_TORRENT_BUFFER
            max_decode_tokens = MAX_DECODE_TOKENS
        }
    val ec = error_code()
    val params = add_torrent_params.load_torrent_file(torrent.toString(), ec, limits)
    check(ec.value() == 0) { "cannot parse ${torrent.fileName}: ${ec.message()}" }
    // copied: add_torrent_params owns the parsed info and frees it when collected
    return TorrentInfo(torrent_info(params.ti_ptr()))
}

fun Path.md5(): String {
    val digest = MessageDigest.getInstance("MD5")
    Files.newInputStream(this).use { input ->
        val buffer = ByteArray(MD5_BUFFER)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().toHexString()
}
