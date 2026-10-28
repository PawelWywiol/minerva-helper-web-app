package io.github.pawelwywiol.minervaweb

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.request.httpMethod
import io.ktor.server.response.respond

const val MUTATION_HEADER = "X-Minerva"

class RequestGuardConfig {
    var allowedHosts: Set<String> = emptySet()
}

private val SAFE_METHODS = setOf(HttpMethod.Get, HttpMethod.Head, HttpMethod.Options)
private val IPV4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

// No login: block DNS rebinding (hostnames outside ALLOWED_HOSTS) and CSRF (state changes need a custom header)
val RequestGuard =
    createApplicationPlugin("RequestGuard", ::RequestGuardConfig) {
        val allowedHosts = pluginConfig.allowedHosts
        onCall { call ->
            val trustedHost = isTrustedHost(hostName(call.request.headers[HttpHeaders.Host].orEmpty()), allowedHosts)
            val safe = call.request.httpMethod in SAFE_METHODS || call.request.headers[MUTATION_HEADER] == "1"
            if (!trustedHost || !safe) call.respond(HttpStatusCode.Forbidden)
        }
    }

internal fun hostName(header: String): String =
    if (header.startsWith('[')) {
        header.substringBefore(']') + ']'
    } else {
        header.substringBefore(':')
    }.lowercase()

internal fun isTrustedHost(
    host: String,
    allowedHosts: Set<String>,
): Boolean = host == "localhost" || IPV4.matches(host) || host.startsWith('[') || host in allowedHosts
