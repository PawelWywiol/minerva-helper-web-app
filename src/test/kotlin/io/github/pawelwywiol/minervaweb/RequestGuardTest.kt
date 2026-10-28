package io.github.pawelwywiol.minervaweb

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class RequestGuardTest {
    private fun guarded(block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit) =
        testApplication {
            application {
                module(allowedHosts = setOf("retro.lan"))
                routing { post("/api/echo") { call.respond(HttpStatusCode.OK) } }
            }
            block()
        }

    @Test
    fun `health answers on an ip host`() =
        guarded {
            val response = client.get("/api/health") { header(HttpHeaders.Host, "192.0.2.10:8090") }
            assertEquals(HttpStatusCode.OK, response.status)
        }

    @Test
    fun `allowed and local hosts pass`() =
        guarded {
            for (host in listOf("localhost", "retro.lan:8090", "[::1]:8080")) {
                val response = client.get("/api/health") { header(HttpHeaders.Host, host) }
                assertEquals(HttpStatusCode.OK, response.status, host)
            }
        }

    @Test
    fun `unknown hostname is rejected`() =
        guarded {
            val response = client.get("/api/health") { header(HttpHeaders.Host, "attacker.example:8090") }
            assertEquals(HttpStatusCode.Forbidden, response.status)
        }

    @Test
    fun `mutation without header is rejected`() =
        guarded {
            val response = client.post("/api/echo") { header(HttpHeaders.Host, "localhost") }
            assertEquals(HttpStatusCode.Forbidden, response.status)
        }

    @Test
    fun `mutation with header passes`() =
        guarded {
            val response =
                client.post("/api/echo") {
                    header(HttpHeaders.Host, "localhost")
                    header(MUTATION_HEADER, "1")
                }
            assertEquals(HttpStatusCode.OK, response.status)
        }
}
