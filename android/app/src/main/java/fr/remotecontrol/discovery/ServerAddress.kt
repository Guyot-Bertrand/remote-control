package fr.remotecontrol.discovery

import okhttp3.HttpUrl

const val DEFAULT_PORT = 8765

data class ServerAddress(val host: String, val port: Int = DEFAULT_PORT) {
    val baseUrl: HttpUrl
        get() = HttpUrl.Builder().scheme("http").host(host).port(port).build()

    val display: String
        get() = if (':' in host) "[$host]:$port" else "$host:$port"
}

fun parseManualAddress(input: String): ServerAddress? {
    var s = input.trim()
    s = s.removePrefix("http://")
    s = s.substringBefore('/')
    if (s.isEmpty()) return null
    val host: String
    val portText: String?
    if (s.startsWith("[")) {
        val end = s.indexOf(']')
        if (end < 0) return null
        host = s.substring(1, end)
        val rest = s.substring(end + 1)
        portText = when {
            rest.isEmpty() -> null
            rest.startsWith(":") -> rest.substring(1)
            else -> return null
        }
    } else if (s.count { it == ':' } > 1) {
        host = s
        portText = null
    } else {
        host = s.substringBefore(':')
        portText = if (':' in s) s.substringAfter(':') else null
    }
    if (host.isEmpty() || host.any { it.isWhitespace() }) return null
    val port = if (portText == null) DEFAULT_PORT else portText.toIntOrNull() ?: return null
    if (port !in 1..65535) return null
    return ServerAddress(host, port)
}
