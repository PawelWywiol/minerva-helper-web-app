package io.github.pawelwywiol.minervaweb.catalog

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.URI
import java.net.http.HttpClient
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class CatalogServiceTest {
    private val dir: Path = Files.createTempDirectory("service")

    @AfterTest
    fun cleanup() {
        dir.toFile().deleteRecursively()
    }

    @Test
    fun `a forced rebuild leaves ready at once and reports failure`() {
        TestCatalog.catalog(dir)
        val unreachable = HashesDownloader(HttpClient.newHttpClient(), URI("http://127.0.0.1:9/hashes.db"))
        val service = CatalogService(CatalogSync(dir, unreachable), CoroutineScope(Dispatchers.Default))
        assertEquals(CatalogService.State.READY, service.status.state)

        service.start(force = true)
        assertNotEquals(CatalogService.State.READY, service.status.state)

        runBlocking { withTimeout(10_000) { while (service.status.state != CatalogService.State.FAILED) delay(50) } }
        assertEquals(5, service.read { it.count() })
    }
}
