package com.prl.wizard

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

/**
 * `nock.rpc` (BRIDGE.md): one gRPC-web call to a Nockchain node the wallet page cannot fetch itself.
 * The CLI's default node is plain HTTP on a bare IP with no CORS headers, so the shell posts for the
 * page — the counterpart of the iOS app's ATS exception for that one address
 * (`res/xml/network_security_config.xml` allows cleartext to it and nothing else). The page frames
 * and parses the gRPC-web messages; the shell only moves bytes. Validation is plain JVM code so it
 * is unit-tested.
 */
object NockRpc {
    /** Nodes the shell will post to. rpc.nockbox.org sends CORS headers, so the page reaches it directly. */
    val NODES = setOf("http://216.158.95.10:5556")

    /** The calls the wallet makes (iOS NockClient). */
    val PATHS = setOf(
        "nockchain.public.v2.NockchainService/WalletGetBalance",
        "nockchain.public.v2.NockchainService/WalletSendTransaction",
        "nockchain.public.v2.NockchainService/TransactionAccepted",
        "nockchain.public.v2.NockchainBlockService/GetTransactionDetails",
        "nockchain.public.v2.NockchainBlockService/GetBlocks",
        "nockchain.public.v2.NockchainBlockService/GetBlockDetails",
    )

    const val MAX_BODY = 1024 * 1024
    const val MAX_REPLY = 16 * 1024 * 1024
    const val MIN_TIMEOUT_MS = 1_000
    const val MAX_TIMEOUT_MS = 60_000
    const val DEFAULT_TIMEOUT_MS = 30_000
    private const val CONTENT_TYPE = "application/grpc-web+proto"

    class Request(val url: String, val body: ByteArray, val timeoutMs: Int)

    class Reply(val status: Int, val body: ByteArray, val grpcStatus: String?, val grpcMessage: String?)

    /**
     * The request for these bridge params, or null when any is not acceptable: an unknown node or
     * path, a body that is not standard base64 or decodes to more than [MAX_BODY] bytes, or a
     * timeout that is not a number. `timeout` (ms) is optional and clamped to 1–60 s.
     */
    fun request(node: Any?, path: Any?, body: Any?, timeout: Any?): Request? {
        if (node !is String || node !in NODES) return null
        if (path !is String || path !in PATHS) return null
        if (body !is String || body.length > (MAX_BODY + 2) / 3 * 4) return null
        val bytes = try {
            Base64.getDecoder().decode(body)
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (bytes.size > MAX_BODY) return null
        val ms = when (timeout) {
            null -> DEFAULT_TIMEOUT_MS.toDouble()
            is Number -> timeout.toDouble()
            else -> return null
        }
        if (ms.isNaN()) return null
        return Request("$node/$path", bytes, ms.coerceIn(MIN_TIMEOUT_MS.toDouble(), MAX_TIMEOUT_MS.toDouble()).toInt())
    }

    /**
     * Blocking; call off the main thread. Any HTTP status comes back with its body (gRPC errors
     * travel as 200 + a trailer frame, or in the `grpc-status` / `grpc-message` headers of a
     * trailers-only reply). Throws [IOException] on a transport failure or a reply over [MAX_REPLY].
     */
    fun call(request: Request, userAgent: String): Reply {
        val connection = (URL(request.url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = request.timeoutMs
            readTimeout = request.timeoutMs
            instanceFollowRedirects = false
            useCaches = false
            doOutput = true
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Content-Type", CONTENT_TYPE)
            setRequestProperty("Accept", CONTENT_TYPE)
            setRequestProperty("X-Grpc-Web", "1")
            setFixedLengthStreamingMode(request.body.size)
        }
        try {
            connection.outputStream.use { it.write(request.body) }
            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            val body = stream?.use { readCapped(it) } ?: ByteArray(0)
            return Reply(status, body, connection.getHeaderField("grpc-status"), connection.getHeaderField("grpc-message"))
        } finally {
            connection.disconnect()
        }
    }

    fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun readCapped(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            if (out.size() + n > MAX_REPLY) throw IOException("reply too large")
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }
}
