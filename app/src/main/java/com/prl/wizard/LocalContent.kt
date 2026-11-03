package com.prl.wizard

import android.content.res.AssetManager
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/**
 * Answers WebView requests the shell owns: the bundled UI under `assets/www/` at
 * https://pearlhub.net, and Blockbook GETs with a CORS header. Everything else returns null and
 * goes to the network. Called on WebView background threads.
 */
class LocalContent(private val assets: AssetManager) {
    private val csp: String? by lazy {
        try {
            assets.open("www/csp.txt").bufferedReader().use { it.readText() }.trim().lineSequence().first().trim().ifEmpty { null }
        } catch (_: IOException) {
            null
        }
    }

    fun intercept(request: WebResourceRequest): WebResourceResponse? {
        val url = request.url.toString()
        return when (request.method) {
            "GET" -> WebRoutes.assetPath(url)?.let(::asset)
                ?: if (WebRoutes.isBlockbookApi(url)) Blockbook.get(url, request.requestHeaders) else null
            "OPTIONS" -> if (WebRoutes.isBlockbookApi(url)) Blockbook.preflight() else null
            else -> null
        }
    }

    private fun asset(path: String): WebResourceResponse? {
        val stream = try {
            assets.open(path, AssetManager.ACCESS_STREAMING)
        } catch (_: IOException) {
            return null
        }
        val mime = WebRoutes.mimeType(path)
        val headers = mutableMapOf("Cache-Control" to "no-cache", "X-Content-Type-Options" to "nosniff")
        if (mime == "text/html") csp?.let { headers["Content-Security-Policy"] = it }
        return WebResourceResponse(mime, if (WebRoutes.isText(mime)) "utf-8" else null, 200, "OK", headers, stream)
    }
}

/** Blockbook sends no CORS headers, so the shell makes these requests for the page. */
object Blockbook {
    private const val TAG = "PearlBlockbook"
    private const val TIMEOUT_MS = 25_000
    private const val MAX_POST_REPLY = 16 * 1024 * 1024
    private val userAgent = "PearlHubAndroid/${BuildConfig.VERSION_NAME}"

    private fun cors() = mutableMapOf(
        "Access-Control-Allow-Origin" to WebRoutes.ORIGIN,
        "Cache-Control" to "no-store",
        "Vary" to "Origin",
    )

    private fun open(url: String, method: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = false
            useCaches = false
            setRequestProperty("User-Agent", userAgent)
        }

    fun preflight(): WebResourceResponse = WebResourceResponse(
        "text/plain", "utf-8", 204, "No Content",
        cors().apply {
            put("Access-Control-Allow-Methods", "GET")
            put("Access-Control-Allow-Headers", "Accept, Content-Type")
            put("Access-Control-Max-Age", "600")
        },
        ByteArrayInputStream(ByteArray(0)),
    )

    /** Streams the upstream body (address pages reach ~12 MB) straight into the WebView. */
    fun get(url: String, requestHeaders: Map<String, String>): WebResourceResponse {
        val connection = try {
            open(url, "GET")
        } catch (e: IOException) {
            return failure(502, e)
        }
        return try {
            val accept = requestHeaders.entries.firstOrNull { it.key.equals("Accept", ignoreCase = true) }?.value
            connection.setRequestProperty("Accept", accept ?: "application/json")
            val status = connection.responseCode
            // WebResourceResponse cannot carry 3xx, and redirects are not followed.
            val code = if (status in 200..299 || status in 400..599) status else 502
            val body = (if (status >= 400) connection.errorStream else connection.inputStream)
                ?: ByteArrayInputStream(ByteArray(0))
            val (mime, charset) = contentType(connection.contentType)
            WebResourceResponse(mime, charset, code, reason(code, connection.responseMessage), cors(), Closing(body, connection))
        } catch (e: IOException) {
            connection.disconnect()
            failure(if (e is SocketTimeoutException) 504 else 502, e)
        }
    }

    class Reply(val status: Int, val body: String)

    /** Blocking; call off the main thread. */
    fun post(url: String, body: String, contentType: String): Reply {
        val connection = open(url, "POST")
        try {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", contentType)
            connection.setRequestProperty("Accept", "application/json")
            val bytes = body.toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
            val status = connection.responseCode
            val stream = (if (status >= 400) connection.errorStream else connection.inputStream) ?: return Reply(status, "")
            return Reply(status, stream.use { readCapped(it) })
        } finally {
            connection.disconnect()
        }
    }

    private fun readCapped(input: InputStream): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            if (out.size() + n > MAX_POST_REPLY) throw IOException("reply too large")
            out.write(buffer, 0, n)
        }
        return out.toString("UTF-8")
    }

    private fun contentType(header: String?): Pair<String, String?> {
        if (header.isNullOrBlank()) return "application/octet-stream" to null
        val parts = header.split(';').map { it.trim() }
        val charset = parts.drop(1).firstOrNull { it.startsWith("charset=", ignoreCase = true) }
            ?.substringAfter('=')?.trim('"', ' ')
        return parts[0].lowercase() to charset
    }

    private fun reason(code: Int, message: String?): String =
        message?.takeIf { it.isNotBlank() && it.all { c -> c.code in 0x20..0x7e } } ?: when (code) {
            200 -> "OK"
            400 -> "Bad Request"
            404 -> "Not Found"
            429 -> "Too Many Requests"
            500 -> "Internal Server Error"
            502 -> "Bad Gateway"
            503 -> "Service Unavailable"
            504 -> "Gateway Timeout"
            else -> "Status $code"
        }

    private fun failure(code: Int, e: Exception): WebResourceResponse {
        Log.w(TAG, "Blockbook request failed: ${e.javaClass.simpleName}")
        val body = """{"error":"${if (code == 504) "timeout" else "network"}"}""".toByteArray()
        return WebResourceResponse("application/json", "utf-8", code, reason(code, null), cors(), ByteArrayInputStream(body))
    }

    /** Closes the connection with the body. */
    private class Closing(input: InputStream, private val connection: HttpURLConnection) : FilterInputStream(input) {
        override fun close() {
            try {
                super.close()
            } finally {
                connection.disconnect()
            }
        }
    }
}
