package io.github.pawelwywiol.minervaweb.download

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
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
import org.libtorrent4j.swig.add_torrent_params
import org.libtorrent4j.swig.error_code
import org.libtorrent4j.swig.load_torrent_limits
import org.libtorrent4j.swig.session_params
import org.libtorrent4j.swig.settings_pack
import org.libtorrent4j.swig.torrent_flags_t
import org.libtorrent4j.swig.torrent_info
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile

private const val STALL_LIMIT_SECONDS = 300
private const val TICK_MS = 1000L
private const val RECHECK_AFTER_TICKS = 5
private const val MAX_RECHECKS = 2
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
    private val log = LoggerFactory.getLogger(SelectiveDownloader::class.java)
    private val session by lazy {
        SessionManager().also {
            it.addListener(problems)
            // plain pread/pwrite: libtorrent's mmap backend mixes mmap and write calls, which its own docs
            // warn corrupts data on Btrfs/ZFS, and in CI left verified pieces missing from the file
            val params = session_params(noSharingSettings().swig()).apply { set_posix_disk_io_constructor() }
            it.start(SessionParams(params))
        }
    }

    // disk and hash problems are otherwise silent: libtorrent reports them only as alerts
    private val problems =
        object : AlertListener {
            override fun types() =
                intArrayOf(AlertType.FILE_ERROR.swig(), AlertType.TORRENT_ERROR.swig(), AlertType.HASH_FAILED.swig())

            override fun alert(alert: Alert<*>) = log.warn("libtorrent: {}", alert.message())
        }

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

    /**
     * wantedNow is re-read every tick, so pausing one file takes effect within a second; empty cancels.
     * Returns only once every wanted file is intact on disk: a verified piece can still be waiting in
     * another disk thread when the torrent finishes, and removing the torrent then loses that write.
     */
    suspend fun download(
        torrent: Path,
        targetDir: Path,
        wantedNow: () -> Set<Int>,
        intact: ((index: Int, file: Path) -> Boolean)? = null,
        onProgress: suspend (Progress) -> Unit,
    ): Result =
        withContext(Dispatchers.IO) {
            val info = loadTorrentInfo(torrent)
            var wanted = info.validate(wantedNow())
            require(wanted.isNotEmpty()) { "no valid file indexes for ${torrent.fileName}" }

            val isIntact: (Int, Path) -> Boolean =
                intact ?: { index, file -> file.isRegularFile() && file.fileSize() == info.files().fileSize(index) }
            Files.createDirectories(targetDir)
            session.download(info, targetDir.toFile(), null, info.priorities(wanted), peers, torrent_flags_t())
            val handle = checkNotNull(session.find(info.infoHash())) { "no torrent handle" }
            try {
                var total = wanted.sumOf { info.files().fileSize(it) }
                var stalledFor = 0
                var unverifiedFor = 0
                var rechecks = 0
                var lastDone = -1L
                val result: Result
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val status = handle.status()
                    // progress of the wanted files only, counting complete hash-checked pieces
                    val verified = handle.fileProgress(TorrentHandle.PIECE_GRANULARITY)
                    val done = wanted.sumOf { verified[it] }
                    if (done >= total) {
                        // after rechecks the bytes are the torrent's own; a remaining mismatch is the caller's to report
                        if (rechecks >= MAX_RECHECKS ||
                            wanted.all { isIntact(it, targetDir.resolve(info.files().filePath(it))) }
                        ) {
                            result = Result(status.totalPayloadUpload(), status.totalUpload())
                            break
                        }
                        // still not on disk after a while: let libtorrent re-read and fetch what is missing
                        if (++unverifiedFor >= RECHECK_AFTER_TICKS) {
                            log.warn(
                                "{}: wanted files not intact, state {}, error '{}', rechecking",
                                torrent.fileName,
                                status.state(),
                                status.errorCode().message,
                            )
                            handle.forceRecheck()
                            unverifiedFor = 0
                            rechecks++
                        }
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
                    onProgress(Progress(minOf(done, total), total, status.downloadRate(), status.numPeers()))
                    delay(TICK_MS)
                }
                onProgress(Progress(total, total, 0, 0))
                result
            } finally {
                session.remove(handle)
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

fun Path.md5(): String = digest("MD5")

fun Path.sha1(): String = digest("SHA-1")

private fun Path.digest(algorithm: String): String {
    val digest = MessageDigest.getInstance(algorithm)
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
