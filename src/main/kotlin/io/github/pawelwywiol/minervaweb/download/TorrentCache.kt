package io.github.pawelwywiol.minervaweb.download

import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.exists
import kotlin.io.path.fileSize

private const val HTTP_OK = 200

// keeps .torrent files (up to ~27 MB of metadata) so a retry does not fetch them again
class TorrentCache(
    private val dir: Path,
    private val http: HttpClient,
    private val base: URI = URI("https://minerva-archive.org/assets/"),
) {
    fun fetch(torrentPath: String): Path {
        Files.createDirectories(dir)
        val out = dir.resolve(torrentPath.replace(Regex("[^A-Za-z0-9.-]"), "_"))
        if (out.exists() && out.fileSize() > 0) return out

        val encoded = torrentPath.split("/").joinToString("/", transform = ::encodeSegment)
        val partial = out.resolveSibling("${out.fileName}.part")
        val response =
            http.send(
                HttpRequest.newBuilder(base.resolve(encoded)).build(),
                HttpResponse.BodyHandlers.ofFile(partial),
            )
        if (response.statusCode() != HTTP_OK) {
            Files.deleteIfExists(partial)
            throw IOException("HTTP ${response.statusCode()} for $torrentPath")
        }
        Files.move(partial, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        return out
    }

    private fun encodeSegment(segment: String) = URLEncoder.encode(segment, Charsets.UTF_8).replace("+", "%20")
}
