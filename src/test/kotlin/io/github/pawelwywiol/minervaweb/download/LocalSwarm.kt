package io.github.pawelwywiol.minervaweb.download

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.TcpEndpoint
import org.libtorrent4j.TorrentBuilder
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.swig.settings_pack
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeBytes
import kotlin.random.Random

// one seeder on localhost, no DHT/LSD/port mapping, so tests never touch the network
internal class LocalSwarm(
    dir: Path,
    files: Map<String, Int>,
) : AutoCloseable {
    val content: Map<String, ByteArray> = files.mapValues { (name, size) -> Random(name.hashCode()).nextBytes(size) }
    val torrent: Path
    val info: TorrentInfo
    val peer: TcpEndpoint
    private val seeder = SessionManager()

    init {
        val pack = Files.createDirectories(dir.resolve("seed/pack"))
        content.forEach { (name, bytes) -> pack.resolve(name).writeBytes(bytes) }
        val bencoded =
            TorrentBuilder()
                .path(pack.toFile())
                .generate()
                .entry()
                .bencode()
        torrent = dir.resolve("pack.torrent").also { it.writeBytes(bencoded) }
        info = TorrentInfo.bdecode(bencoded)

        val port = ServerSocket(0).use { it.localPort }
        seeder.start(SessionParams(isolated().apply { listenInterfaces("127.0.0.1:$port") }))
        seeder.download(info, pack.parent.toFile())
        peer = TcpEndpoint("127.0.0.1", port)
        runBlocking {
            withTimeout(SEED_TIMEOUT_MS) {
                while (seeder.find(info.infoHash())?.status()?.isSeeding != true) delay(POLL_MS)
            }
        }
    }

    fun index(name: String): Int = (0 until info.numFiles()).first { info.files().fileName(it) == name }

    override fun close() = seeder.stop()

    companion object {
        private const val SEED_TIMEOUT_MS = 20_000L
        private const val POLL_MS = 100L

        fun isolated(): SettingsPack = SettingsPack().apply { isolate() }

        fun SettingsPack.isolate() {
            setBoolean(settings_pack.bool_types.enable_dht.swigValue(), false)
            setBoolean(settings_pack.bool_types.enable_lsd.swigValue(), false)
            setBoolean(settings_pack.bool_types.enable_upnp.swigValue(), false)
            setBoolean(settings_pack.bool_types.enable_natpmp.swigValue(), false)
        }
    }
}
