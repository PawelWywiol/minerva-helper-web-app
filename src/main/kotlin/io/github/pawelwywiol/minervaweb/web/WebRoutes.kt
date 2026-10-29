package io.github.pawelwywiol.minervaweb.web

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

private val ASSET = Regex("^[a-z0-9][a-z0-9/_-]*\\.(html|js|css|svg)$")

// cover art is the only thing loaded from elsewhere, straight from libretro to the browser
private const val CSP =
    "default-src 'self'; img-src 'self' data: https://thumbnails.libretro.com; script-src 'self'; " +
        "style-src 'self'; connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'"

fun Route.webRoutes() {
    get("/") { call.asset("index.html") }
    get("/{path...}") {
        call.asset(
            call.parameters
                .getAll("path")
                ?.joinToString("/")
                .orEmpty(),
        )
    }
}

// served only from the jar by a strict name pattern, so nothing on disk is ever reachable
private suspend fun ApplicationCall.asset(name: String) {
    val bytes =
        name
            .takeIf { ASSET.matches(it) && ".." !in it }
            ?.let { WebRoutesAnchor::class.java.getResourceAsStream("/web/$it") }
            ?.use { it.readBytes() }
    if (bytes == null) return respondText("", status = HttpStatusCode.NotFound)
    response.header(HttpHeaders.CacheControl, "no-store")
    response.header("X-Content-Type-Options", "nosniff")
    response.header("Referrer-Policy", "no-referrer")
    if (name.endsWith(".html")) response.header("Content-Security-Policy", CSP)
    respondBytes(bytes, contentTypeOf(name))
}

private fun contentTypeOf(name: String): ContentType =
    when (name.substringAfterLast('.')) {
        "html" -> ContentType.Text.Html
        "css" -> ContentType.Text.CSS
        "js" -> ContentType.Text.JavaScript
        else -> ContentType.Image.SVG
    }

private object WebRoutesAnchor
