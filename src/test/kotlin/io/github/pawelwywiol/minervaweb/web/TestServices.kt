package io.github.pawelwywiol.minervaweb.web

import io.github.pawelwywiol.minervaweb.catalog.CatalogService
import io.github.pawelwywiol.minervaweb.catalog.CatalogSync
import io.github.pawelwywiol.minervaweb.catalog.HashesDownloader
import io.github.pawelwywiol.minervaweb.download.DownloadRunner
import io.github.pawelwywiol.minervaweb.download.TorrentCache
import io.github.pawelwywiol.minervaweb.metadata.GameInfoLookup
import io.github.pawelwywiol.minervaweb.placement.FilePlacement
import io.github.pawelwywiol.minervaweb.platform.Systems
import io.github.pawelwywiol.minervaweb.queue.DownloadQueue
import io.github.pawelwywiol.minervaweb.settings.Settings
import io.github.pawelwywiol.minervaweb.vpn.VpnCheck
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.net.URI
import java.net.http.HttpClient
import java.nio.file.Path

private val UNREACHABLE = URI("http://127.0.0.1:9/")

internal fun testServices(dir: Path): Services {
    val http = HttpClient.newHttpClient()
    val scope = CoroutineScope(Dispatchers.Default)
    val queue = DownloadQueue(dir.resolve("queue.db"))
    val settings = Settings(dir.resolve("settings.json"))
    val placement = FilePlacement(dir.resolve("library/roms"))
    val runner =
        DownloadRunner(
            queue,
            settings,
            TorrentCache(dir.resolve("torrents"), http, UNREACHABLE),
            placement,
            dir.resolve("staging"),
            scope,
        )
    runner.setRunning(false)
    return Services(
        catalog = CatalogService(CatalogSync(dir, HashesDownloader(http, UNREACHABLE.resolve("hashes.db"))), scope),
        queue = queue,
        runner = runner,
        settings = settings,
        placement = placement,
        systems = Systems(),
        gameInfo = GameInfoLookup(dir.resolve("gameinfo"), http, UNREACHABLE, UNREACHABLE),
        vpn = VpnCheck(http, UNREACHABLE),
        version = "test",
    )
}
