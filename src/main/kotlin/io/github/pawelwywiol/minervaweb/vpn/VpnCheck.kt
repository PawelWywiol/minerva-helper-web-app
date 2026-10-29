package io.github.pawelwywiol.minervaweb.vpn

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

private val TIMEOUT = Duration.ofSeconds(5)
private val PUBLIC_FIELDS = listOf("public_ip", "city", "region", "country", "organization")

/**
 * Asks gluetun, whose network this container shares, whether the tunnel is up and which address
 * the world sees. No answer means no gluetun: downloads would then leave through the host's own line.
 */
class VpnCheck(
    private val http: HttpClient,
    private val control: URI = URI("http://127.0.0.1:8000/"),
) {
    fun check(): JsonObject {
        val status = get("v1/vpn/status")
        val public = status?.let { get("v1/publicip/ip") }
        return buildJsonObject {
            put("reachable", status != null)
            put("status", status?.text("status"))
            PUBLIC_FIELDS.forEach { put(it, public?.text(it)) }
        }
    }

    private fun get(path: String): JsonObject? =
        try {
            val request =
                HttpRequest
                    .newBuilder(control.resolve(path))
                    .timeout(TIMEOUT)
                    .GET()
                    .build()
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() == 200) Json.parseToJsonElement(response.body()).jsonObject else null
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

    private fun JsonObject.text(key: String): String? = get(key)?.jsonPrimitive?.contentOrNull?.ifEmpty { null }
}
