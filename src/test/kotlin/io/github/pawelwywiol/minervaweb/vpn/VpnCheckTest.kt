package io.github.pawelwywiol.minervaweb.vpn

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import kotlin.test.Test
import kotlin.test.assertEquals

class VpnCheckTest {
    private val http = HttpClient.newHttpClient()

    @Test
    fun `reports the tunnel and the address gluetun sees`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        mapOf(
            "/v1/vpn/status" to """{"status":"running"}""",
            "/v1/publicip/ip" to
                """{"public_ip":"192.0.2.7","city":"Prague","country":"Czech Republic","organization":"M247"}""",
        ).forEach { (path, body) ->
            server.createContext(path) { exchange ->
                val bytes = body.toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
        }
        server.start()
        try {
            val result = VpnCheck(http, URI("http://127.0.0.1:${server.address.port}/")).check()
            assertEquals("true", result.getValue("reachable").jsonPrimitive.content)
            assertEquals("running", result.getValue("status").jsonPrimitive.content)
            assertEquals("192.0.2.7", result.getValue("public_ip").jsonPrimitive.content)
            assertEquals("Prague", result.getValue("city").jsonPrimitive.content)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `no gluetun means no vpn`() {
        val result = VpnCheck(http, URI("http://127.0.0.1:9/")).check()
        assertEquals("false", result.getValue("reachable").jsonPrimitive.content)
    }
}
