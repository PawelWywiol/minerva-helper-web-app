package io.github.pawelwywiol.minervaweb.web

import io.github.pawelwywiol.minervaweb.MUTATION_HEADER
import io.github.pawelwywiol.minervaweb.catalog.TestCatalog
import io.github.pawelwywiol.minervaweb.catalog.find
import io.github.pawelwywiol.minervaweb.module
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WebRoutesTest {
    private val dir: Path = Files.createTempDirectory("web")

    @AfterTest
    fun cleanup() {
        dir.toFile().deleteRecursively()
    }

    private fun app(block: suspend ApplicationTestBuilder.(Services) -> Unit) =
        testApplication {
            TestCatalog.catalog(dir)
            val services = testServices(dir)
            application { module(emptySet(), services) }
            block(services)
        }

    private suspend fun ApplicationTestBuilder.get(path: String) =
        client.get(path) {
            header(HttpHeaders.Host, "localhost")
        }

    private suspend fun ApplicationTestBuilder.post(
        path: String,
        body: String,
    ) = client.post(path) {
        header(HttpHeaders.Host, "localhost")
        header(MUTATION_HEADER, "1")
        setBody(body)
    }

    private suspend fun ApplicationTestBuilder.field(
        path: String,
        key: String,
    ) = Json
        .parseToJsonElement(get(path).bodyAsText())
        .jsonObject
        .getValue(key)

    @Test
    fun `serves the ui with a strict content security policy`() =
        app {
            val index = get("/")
            assertEquals(HttpStatusCode.OK, index.status)
            assertTrue(index.headers["Content-Security-Policy"]!!.startsWith("default-src 'self'"))
            assertTrue(index.bodyAsText().contains("/app.js"))
            assertEquals(
                "text/javascript",
                get("/views/queue.js").headers[HttpHeaders.ContentType]?.substringBefore(';'),
            )
            assertEquals("nosniff", get("/style.css").headers["X-Content-Type-Options"])
        }

    @Test
    fun `nothing outside the bundled ui is served`() =
        app {
            for (path in listOf(
                "/../build.gradle.kts",
                "/%2e%2e/%2e%2e/etc/passwd",
                "/web/app.js",
                "/data/systems.csv",
                "/x.kt",
            )) {
                assertEquals(HttpStatusCode.NotFound, get(path).status, path)
            }
        }

    @Test
    fun `favourites toggle and list`() =
        app {
            val id =
                field("/api/search?q=zelda", "roms")
                    .jsonArray
                    .single()
                    .jsonObject["id"]!!
                    .jsonPrimitive.content
            assertEquals(
                "true",
                Json
                    .parseToJsonElement(
                        post("/api/favourites", """{"romId":$id}""").bodyAsText(),
                    ).jsonObject["favourite"]
                    .toString(),
            )
            assertEquals(
                "Zelda (USA).zip",
                field("/api/favourites", "roms")
                    .jsonArray
                    .single()
                    .jsonObject["name"]!!
                    .jsonPrimitive.content,
            )
            assertEquals("true", field("/api/roms/$id", "favourite").toString())
        }

    @Test
    fun `changing a folder can move downloaded files`() =
        app { services ->
            val rom = services.catalog.read { it.find("zelda").single() }!!
            services.queue.recordDownloaded(rom, "nes")
            val folder = Files.createDirectories(dir.resolve("library/roms/nes"))
            Files.write(folder.resolve(rom.name), ByteArray(1))

            val nes = TestCatalog.NES
            val change = """{"collection":"No-Intro","platformKey":"$nes","folder":"famicom","moveFiles":true}"""
            assertEquals(HttpStatusCode.OK, post("/api/settings", change).status)
            assertTrue(dir.resolve("library/roms/famicom/${rom.name}").exists())
            assertEquals(
                "famicom",
                services.queue
                    .downloaded()
                    .single()
                    .folder,
            )
        }
}
