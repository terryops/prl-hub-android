package com.prl.wizard

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException

/**
 * URL policy of the shell (BRIDGE.md "Origin and loading"). Plain JVM code so it is unit-tested;
 * URLs are split by hand because java.net.URI rejects characters Chromium leaves in queries.
 */
object WebRoutes {
    const val HOST = "pearlhub.net"
    const val ORIGIN = "https://$HOST"
    private val BLOCKBOOK_HOSTS = setOf("blockbook.pearlresearch.ai", "blockbook.testnet.pearlresearch.ai")
    private val EXTERNAL_SCHEMES = setOf("http", "https", "mailto", "tg")
    private val ROUTE = Regex("[a-z][a-z0-9-]{0,31}")
    private val SCHEME = Regex("[a-zA-Z][a-zA-Z0-9+.-]*")

    private class Parts(val scheme: String, val authority: String, val rawPath: String)

    fun startUrl(store: String, route: String? = null): String =
        "$ORIGIN/?client=android&store=$store" + (if (isRoute(route)) "#$route" else "")

    fun isRoute(route: String?): Boolean = route != null && ROUTE.matches(route)

    fun isAppUrl(url: String): Boolean = split(url)?.let { isHttpsOn(it, setOf(HOST)) } == true

    /** The asset under `www/` that answers a GET of [url], or null when it must go to the network. */
    fun assetPath(url: String): String? {
        val parts = split(url) ?: return null
        if (!isHttpsOn(parts, setOf(HOST))) return null
        val path = safePath(parts.rawPath) ?: return null
        if (path.startsWith("/v1/")) return null
        return "www" + if (path.endsWith("/")) path + "index.html" else path
    }

    /** GETs and posts the shell performs for the page: Blockbook's `/api/` on mainnet and testnet. */
    fun isBlockbookApi(url: String): Boolean {
        val parts = split(url) ?: return false
        if (!isHttpsOn(parts, BLOCKBOOK_HOSTS)) return false
        return safePath(parts.rawPath)?.startsWith("/api/") == true
    }

    /** Links the shell hands to other apps: web, mail and Telegram only. */
    fun isExternalAllowed(url: String): Boolean {
        val colon = url.indexOf(':')
        if (colon <= 0 || !SCHEME.matches(url.substring(0, colon))) return false
        val scheme = url.substring(0, colon).lowercase()
        if (scheme !in EXTERNAL_SCHEMES) return false
        if (scheme == "http" || scheme == "https") {
            val parts = split(url) ?: return false
            return parts.authority.isNotEmpty() && '@' !in parts.authority
        }
        return url.length > colon + 1
    }

    fun mimeType(path: String): String = when (path.substringAfterLast('/').substringAfterLast('.', "").lowercase()) {
        "html", "htm" -> "text/html"
        "js", "mjs" -> "text/javascript"
        "css" -> "text/css"
        "json", "map" -> "application/json"
        "webmanifest" -> "application/manifest+json"
        "txt" -> "text/plain"
        "xml" -> "application/xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "svg" -> "image/svg+xml"
        "webp" -> "image/webp"
        "ico" -> "image/x-icon"
        "woff2" -> "font/woff2"
        "woff" -> "font/woff"
        "ttf" -> "font/ttf"
        "wasm" -> "application/wasm"
        else -> "application/octet-stream"
    }

    fun isText(mime: String): Boolean =
        mime.startsWith("text/") || mime in setOf("application/json", "application/manifest+json", "application/xml", "image/svg+xml")

    /** `#rrggbb` → opaque ARGB, or null. */
    fun parseColor(value: String?): Int? {
        if (value == null || value.length != 7 || value[0] != '#') return null
        if (!value.substring(1).all { Character.digit(it, 16) >= 0 }) return null
        return (0xFF000000L or value.substring(1).toLong(16)).toInt()
    }

    private fun isHttpsOn(parts: Parts, hosts: Set<String>): Boolean {
        if (parts.scheme != "https" || '@' in parts.authority) return false
        val host = parts.authority.substringBefore(':').lowercase()
        val port = if (':' in parts.authority) parts.authority.substringAfter(':') else ""
        return host in hosts && (port.isEmpty() || port == "443")
    }

    private fun split(url: String): Parts? {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd <= 0 || !SCHEME.matches(url.substring(0, schemeEnd))) return null
        val rest = url.substring(schemeEnd + 3)
        val authorityEnd = rest.indexOfAny(charArrayOf('/', '?', '#')).let { if (it < 0) rest.length else it }
        val pathEnd = rest.indexOfAny(charArrayOf('?', '#'), authorityEnd).let { if (it < 0) rest.length else it }
        return Parts(url.substring(0, schemeEnd).lowercase(), rest.substring(0, authorityEnd), rest.substring(authorityEnd, pathEnd).ifEmpty { "/" })
    }

    /**
     * The decoded path when it is safe to map onto files: no `.`/`..` or empty segments, no encoded
     * `/` or `\`, no control characters, valid UTF-8. Null otherwise.
     */
    private fun safePath(raw: String): String? {
        if (!raw.startsWith("/")) return null
        val path = decode(raw) ?: return null
        val segments = path.split('/').drop(1)
        segments.forEachIndexed { index, segment ->
            if (segment == "." || segment == "..") return null
            if (segment.isEmpty() && index != segments.lastIndex) return null
        }
        return path
    }

    private fun decode(raw: String): String? {
        val bytes = ByteArrayOutputStream()
        var i = 0
        while (i < raw.length) {
            if (raw[i] == '%') {
                if (i + 2 >= raw.length) return null
                val high = Character.digit(raw[i + 1], 16)
                val low = Character.digit(raw[i + 2], 16)
                if (high < 0 || low < 0) return null
                val b = high * 16 + low
                if (b == '/'.code || b == '\\'.code || b < 0x20 || b == 0x7f) return null
                bytes.write(b)
                i += 3
            } else {
                val end = raw.indexOf('%', i).let { if (it < 0) raw.length else it }
                val run = raw.substring(i, end)
                if (run.any { it == '\\' || it < ' ' || it.code == 0x7f }) return null
                bytes.write(run.toByteArray(Charsets.UTF_8))
                i = end
            }
        }
        return try {
            Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
        } catch (_: CharacterCodingException) {
            null
        }
    }
}
