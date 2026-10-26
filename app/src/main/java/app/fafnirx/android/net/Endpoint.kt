package app.fafnirx.android.net

import java.net.URI

/** Where the master listens: `192.168.1.10`, `nas.lan:7443`, `http://nas.lan:7443`, `https://fafnirx.example.com`. */
data class Endpoint(val host: String, val port: Int, val tls: Boolean) {
    /** Plain HTTP to something that looks like a public address: anyone on the path can read the token and the files. */
    val looksPublicOverHttp: Boolean
        get() {
            if (tls) return false
            val h = host.lowercase()
            if (h == "localhost" || !h.contains('.') && !h.contains(':')) return false
            if (listOf(".lan", ".local", ".home", ".internal", ".home.arpa", ".ts.net").any { h.endsWith(it) }) return false
            val ip = h.split('.').mapNotNull { it.toIntOrNull() }
            if (ip.size == 4 && h.split('.').size == 4) {
                return !(ip[0] == 10 || ip[0] == 127 || (ip[0] == 192 && ip[1] == 168) ||
                    (ip[0] == 172 && ip[1] in 16..31) || (ip[0] == 169 && ip[1] == 254) || (ip[0] == 100 && ip[1] in 64..127))
            }
            if (h.startsWith("fd") || h.startsWith("fe80") || h == "::1") return false
            return true
        }

    companion object {
        const val DEFAULT_PORT = 7443

        fun parse(text: String): Endpoint? {
            val t = text.trim()
            if (t.isEmpty()) return null
            val withScheme = if (t.contains("://")) t else "http://$t"
            return try {
                val uri = URI(withScheme)
                val tls = when (uri.scheme?.lowercase()) {
                    "https" -> true
                    "http" -> false
                    else -> return null
                }
                val host = uri.host?.removePrefix("[")?.removeSuffix("]") ?: return null
                if (host.isEmpty()) return null
                Endpoint(host, if (uri.port > 0) uri.port else if (tls) 443 else DEFAULT_PORT, tls)
            } catch (e: Exception) {
                null
            }
        }
    }
}
