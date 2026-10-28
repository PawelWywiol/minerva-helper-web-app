package io.github.pawelwywiol.minervaweb.catalog

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.RandomAccessFile
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.readText
import kotlin.io.path.writeText

private const val BUFFER_SIZE = 256 * 1024
private const val HTTP_OK_MIN = 200
private const val HTTP_OK_MAX = 299
private const val HTTP_PARTIAL = 206

class HashesDownloader(
    private val http: HttpClient,
    private val url: URI = DEFAULT_URL,
) {
    data class Progress(
        val downloaded: Long,
        val total: Long,
    )

    // resumes with Range while the upstream ETag is unchanged
    suspend fun download(
        target: Path,
        onProgress: suspend (Progress) -> Unit,
    ): Path =
        withContext(Dispatchers.IO) {
            val tagFile = target.resolveSibling("${target.fileName}.etag")
            val knownTag = if (tagFile.exists()) tagFile.readText() else null

            val remote = head()
            if (knownTag != null && knownTag != remote.etag) {
                target.deleteIfExists()
                tagFile.deleteIfExists()
            }
            remote.etag?.let(tagFile::writeText)

            var have = if (target.exists()) target.fileSize() else 0L
            if (have == remote.length) return@withContext target
            if (have > remote.length) {
                target.deleteIfExists()
                have = 0L
            }

            val request =
                HttpRequest
                    .newBuilder(url)
                    .apply { if (have > 0) header("Range", "bytes=$have-") }
                    .build()
            val response = http.send(request, HttpResponse.BodyHandlers.ofInputStream())
            if (response.statusCode() !in HTTP_OK_MIN..HTTP_OK_MAX) throw IOException("HTTP ${response.statusCode()}")
            if (have > 0 && response.statusCode() != HTTP_PARTIAL) {
                have = 0L
                target.deleteIfExists()
            }

            Files.createDirectories(target.toAbsolutePath().parent)
            RandomAccessFile(target.toFile(), "rw").use { out ->
                out.seek(have)
                val buf = ByteArray(BUFFER_SIZE)
                response.body().use { input ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        have += n
                        onProgress(Progress(have, remote.length))
                    }
                }
            }

            if (target.fileSize() != remote.length) {
                throw IOException("size mismatch: ${target.fileSize()} != ${remote.length}")
            }
            target
        }

    fun version(): String = head().let { "${it.etag.orEmpty()}:${it.length}" }

    private data class Remote(
        val length: Long,
        val etag: String?,
    )

    private fun head(): Remote {
        val request =
            HttpRequest
                .newBuilder(url)
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .build()
        val response = http.send(request, HttpResponse.BodyHandlers.discarding())
        if (response.statusCode() !in HTTP_OK_MIN..HTTP_OK_MAX) throw IOException("HTTP ${response.statusCode()}")
        val length =
            response
                .headers()
                .firstValue("Content-Length")
                .orElse(null)
                ?.toLongOrNull()
                ?: throw IOException("no Content-Length")
        return Remote(length, response.headers().firstValue("ETag").orElse(null))
    }

    companion object {
        val DEFAULT_URL: URI = URI("https://minerva-archive.org/assets/hashes.db")
    }
}
