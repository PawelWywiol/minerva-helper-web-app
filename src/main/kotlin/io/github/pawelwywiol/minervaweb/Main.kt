package io.github.pawelwywiol.minervaweb

import io.github.pawelwywiol.minervaweb.catalog.CatalogService
import io.github.pawelwywiol.minervaweb.catalog.CatalogSync
import io.github.pawelwywiol.minervaweb.catalog.HashesDownloader
import io.github.pawelwywiol.minervaweb.platform.PlatformFolders
import io.github.pawelwywiol.minervaweb.platform.Systems
import io.github.pawelwywiol.minervaweb.web.catalogRoutes
import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.system.exitProcess

private const val DEFAULT_PORT = 8080
private const val HEALTH_PATH = "/api/health"
private val HEALTH_TIMEOUT = Duration.ofSeconds(3)

fun main(args: Array<String>) {
    val port = System.getenv("PORT")?.toIntOrNull() ?: DEFAULT_PORT
    if (args.firstOrNull() == "healthcheck") exitProcess(healthcheck(port))

    val allowedHosts =
        System
            .getenv("ALLOWED_HOSTS")
            .orEmpty()
            .split(',')
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()
    val dataDir = Files.createDirectories(Path.of(System.getenv("DATA_DIR") ?: "/data"))
    val http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val catalog = CatalogService(CatalogSync(dataDir, HashesDownloader(http)), scope)

    embeddedServer(CIO, port = port) { module(allowedHosts, catalog) }.start(wait = true)
}

fun Application.module(
    allowedHosts: Set<String>,
    catalog: CatalogService,
) {
    base(allowedHosts)
    routing { catalogRoutes(catalog, Systems(), PlatformFolders()) }
}

fun Application.base(allowedHosts: Set<String> = emptySet()) {
    install(RequestGuard) { this.allowedHosts = allowedHosts }
    routing {
        get(HEALTH_PATH) { call.respondText("""{"status":"ok"}""", ContentType.Application.Json) }
    }
}

private fun healthcheck(port: Int): Int {
    val request =
        HttpRequest
            .newBuilder(URI("http://127.0.0.1:$port$HEALTH_PATH"))
            .timeout(HEALTH_TIMEOUT)
            .build()
    val status =
        runCatching {
            HttpClient.newHttpClient().use { it.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() }
        }.getOrDefault(0)
    return if (status == 200) 0 else 1
}
