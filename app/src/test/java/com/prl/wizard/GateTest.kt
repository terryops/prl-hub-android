package com.prl.wizard

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

class GateTest {
    private fun params(vararg pairs: Pair<String, Any?>) = JSONObject().apply { for ((k, v) in pairs) put(k, v) }
    private fun get(path: String, vararg more: Pair<String, Any?>) = Gate.request(params("method" to "GET", "path" to path, *more))
    private fun post(path: String, vararg more: Pair<String, Any?>) = Gate.request(params("method" to "POST", "path" to path, *more))
    private fun delete(path: String, vararg more: Pair<String, Any?>) = Gate.request(params("method" to "DELETE", "path" to path, *more))
    private val stored = "auth" to "stored"
    private val orderJson = "json" to """{"currency_pair":"PEARL_USDT","side":"buy","type":"limit","time_in_force":"gtc","amount":"10","price":"1.52"}"""
    private val withdrawJson = "json" to """{"currency":"PEARL","chain":"PRL","amount":"25","address":"prl1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq"}"""

    @Test
    fun signatureMatchesGateV4ReferenceVectors() {
        assertEquals(
            "cf83e1357eefb8bdf1542850d66d8007d620e4050b5715dc83f4a921d36ce9ce47d0d13c5d85f2b0ff8318d2877eec2f63b931bd47417a81a538327af927da3e",
            Gate.sha512Hex(ByteArray(0)),
        )
        assertEquals(
            "2f7c41873cb3f4ef2fdef00f6a598992381fabdcceac6d45f425ec97a65052e6280d816f6e5294fcabab77d775326ca8f55a442faac71b1fc727bd5027526f5f",
            Gate.signature(
                method = "GET",
                path = "/api/v4/spot/orders",
                query = "currency_pair=PEARL_USDT&status=open",
                body = ByteArray(0),
                timestamp = "1790474921",
                apiSecret = "gate-secret-123",
            ),
        )
        assertEquals(
            "a3bca29cff0c3de8fae9294375d1a066d67330c62dfca8672b11acaa822a5c196f67b6f969cb7c62a6209affb575370fb432ce5f9c1a3315e04b0c0ae945c698",
            Gate.signature(
                method = "POST",
                path = "/api/v4/spot/orders",
                query = "",
                body = """{"currency_pair":"PEARL_USDT","side":"buy","type":"limit","time_in_force":"gtc","amount":"10","price":"1.52"}""".toByteArray(),
                timestamp = "1790474921",
                apiSecret = "gate-secret-123",
            ),
        )
    }

    @Test
    fun gateClockLearnsServerSecondsFromDateHeader() {
        val server = 1_790_474_921_000L // Sun, 27 Sep 2026 02:08:41 GMT
        var now = server - 10_000L
        val clock = GateClock { now }
        assertEquals("1790474911", clock.next())
        clock.observe("Sun, 27 Sep 2026 02:08:41 GMT")
        assertEquals("1790474921", clock.next())
        // Fast device clock is also pulled back to server seconds.
        now = server + 60_000L
        clock.observe("Sun, 27 Sep 2026 02:08:41 GMT")
        assertEquals("1790474921", clock.next())
    }

    @Test
    fun allowsOnlyWhitelistedGatePathsAndMethods() {
        for (path in listOf(
            "/api/v4/spot/tickers",
            "/api/v4/spot/candlesticks",
            "/api/v4/spot/order_book",
            "/api/v4/spot/currency_pairs/PEARL_USDT",
            "/api/v4/wallet/currency_chains",
        )) {
            val r = get(path, "query" to JSONObject().put("currency_pair", "PEARL_USDT"))
            assertNotNull(path, r)
            assertSame(Gate.Auth.None, r!!.auth)
        }
        for (path in listOf(
            "/api/v4/spot/accounts",
            "/api/v4/spot/orders",
            "/api/v4/wallet/deposit_address",
            "/api/v4/wallet/deposits",
            "/api/v4/wallet/withdrawals",
            "/api/v4/wallet/withdraw_status",
            "/api/v4/wallet/saved_address",
        )) {
            assertSame(path, Gate.Auth.Stored, get(path, stored)?.auth)
            assertNull(path, get(path))
            assertNull(path, get(path, "auth" to "none"))
        }

        val order = post("/api/v4/spot/orders", stored, orderJson)!!
        assertEquals("application/json", order.contentType)
        assertArrayEquals(orderJson.second.toByteArray(), order.body)
        assertNull(order.deviceCheck)

        // DELETE order requires stored auth, accepts query, forbids body.
        val cancel = delete(
            "/api/v4/spot/orders/9876543210",
            stored,
            "query" to JSONObject().put("currency_pair", "PEARL_USDT"),
        )!!
        assertEquals("/api/v4/spot/orders/9876543210?currency_pair=PEARL_USDT", cancel.target)
        assertNull(cancel.body)
        assertNull(delete("/api/v4/spot/orders/9876543210", "query" to JSONObject().put("currency_pair", "PEARL_USDT")))
        assertNull(delete("/api/v4/spot/orders/9876543210", stored, orderJson))
        assertNull(delete("/api/v4/spot/orders/abc", stored))

        // Withdrawals require stored auth + DeviceCheck prompt.
        val withdraw = post(Gate.WITHDRAW, stored, withdrawJson, "title" to "  确认提现  ", "subtitle" to "25 PEARL")!!
        assertEquals("确认提现", withdraw.deviceCheck!!.title)
        assertEquals("25 PEARL", withdraw.deviceCheck.subtitle)
        assertNull(post(Gate.WITHDRAW, stored, withdrawJson))

        // Explicit keys only check GET /api/v4/spot/accounts.
        val explicit = arrayOf("auth" to "explicit", "key" to "gate-key", "secret" to "gate-secret")
        assertNotNull(get(Gate.ACCOUNTS, *explicit))
        assertNull(get("/api/v4/spot/orders", *explicit))
        assertNull(post("/api/v4/spot/orders", *explicit, orderJson))
        assertNull(post(Gate.WITHDRAW, *explicit, withdrawJson, "title" to "确认提现"))

        // Rejects form bodies, POST queries, non-whitelisted paths.
        assertNull(post("/api/v4/spot/orders", stored, "form" to JSONObject().put("side", "buy")))
        assertNull(post("/api/v4/spot/orders", stored, orderJson, "query" to JSONObject().put("currency_pair", "PEARL_USDT")))
        assertNull(post("/api/v4/spot/orders", stored))
        assertNull(get("/api/v4/futures/usdt/positions", stored))
        assertNull(get("/api/v4/spot/currency_pairs/pearl_usdt"))
    }

    @Test
    fun sendsGateSignedHeadersAndResignsExpiredTimestampOnce() {
        val serverMs = 1_790_474_921_000L
        val expired = """{"label":"REQUEST_EXPIRED","message":"Timestamp too first or expired"}"""
        Raw { i, _ ->
            if (i == 0) reply(401, expired, "Date: Sun, 27 Sep 2026 02:08:41 GMT") else reply(200, "[]")
        }.use { raw ->
            val clock = GateClock { serverMs - 15_000L }
            val req = get("/api/v4/spot/orders", stored, "query" to JSONObject().put("currency_pair", "PEARL_USDT").put("status", "open"))!!
            val reply = Gate.send(req, "gate-key-1" to "gate-secret-123", raw.root, clock)
            assertEquals(200, reply.status)
            assertEquals("[]", reply.body)
            assertEquals(2, raw.requests.size)

            val first = raw.requests[0]
            assertTrue(first.startsWith("GET /api/v4/spot/orders?currency_pair=PEARL_USDT&status=open HTTP/1.1\r\n"))
            assertEquals("gate-key-1", header(first, "KEY"))
            assertEquals("1790474906", header(first, "Timestamp"))

            val second = raw.requests[1]
            assertEquals("1790474921", header(second, "Timestamp"))
            assertEquals(
                "2f7c41873cb3f4ef2fdef00f6a598992381fabdcceac6d45f425ec97a65052e6280d816f6e5294fcabab77d775326ca8f55a442faac71b1fc727bd5027526f5f",
                header(second, "SIGN"),
            )
        }
    }

    @Test
    fun mutatingPostOrDeleteLostAfterConnectReportsUnconfirmedWhileGetRedials() {
        val cancel = delete("/api/v4/spot/orders/123", stored, "query" to JSONObject().put("currency_pair", "PEARL_USDT"))!!
        Raw { _, _ -> null }.use { raw ->
            assertEquals("unconfirmed", assertThrows(Gate.Failure::class.java) { Gate.send(cancel, "k" to "s", raw.root, GateClock()) }.code)
            assertTrue(raw.requests.size in 1..2)
        }
        val order = post("/api/v4/spot/orders", stored, orderJson)!!
        Raw { _, _ -> null }.use { raw ->
            assertEquals("unconfirmed", assertThrows(Gate.Failure::class.java) { Gate.send(order, "k" to "s", raw.root, GateClock()) }.code)
            assertEquals(1, raw.requests.size)
        }
        val ticker = get("/api/v4/spot/tickers", "query" to JSONObject().put("currency_pair", "PEARL_USDT"))!!
        Raw { _, _ -> null }.use { raw ->
            assertEquals("unreached", assertThrows(Gate.Failure::class.java) { Gate.send(ticker, null, raw.root, GateClock()) }.code)
            assertTrue(raw.requests.size >= 2)
        }
        Raw { i, _ -> if (i == 0) null else reply(200, "[]") }.use { raw ->
            assertEquals("[]", Gate.send(ticker, null, raw.root, GateClock()).body)
        }
    }

    private class Raw(private val respond: (Int, String) -> String?) : AutoCloseable {
        private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val requests = CopyOnWriteArrayList<String>()
        val root = "http://127.0.0.1:${socket.localPort}"

        init {
            thread(isDaemon = true) {
                while (true) {
                    val client = try {
                        socket.accept()
                    } catch (_: IOException) {
                        return@thread
                    }
                    client.use {
                        val request = read(it.getInputStream())
                        val index = requests.size
                        requests += request
                        respond(index, request)?.let { text -> it.getOutputStream().write(text.toByteArray()) }
                    }
                }
            }
        }

        override fun close() = socket.close()

        private fun read(input: InputStream): String {
            val head = ByteArrayOutputStream()
            var tail = 0
            while (tail != 0x0d0a0d0a) {
                val b = input.read()
                if (b < 0) break
                head.write(b)
                tail = (tail shl 8) or b
            }
            val text = head.toString("ISO-8859-1")
            val length = Regex("(?im)^content-length:\\s*(\\d+)").find(text)?.groupValues?.get(1)?.toInt() ?: 0
            val body = ByteArray(length)
            var n = 0
            while (n < length) {
                val r = input.read(body, n, length - n)
                if (r < 0) break
                n += r
            }
            return text + String(body, 0, n, Charsets.UTF_8)
        }
    }

    private fun reply(status: Int, body: String, vararg headers: String): String {
        val bytes = body.toByteArray()
        return buildString {
            append("HTTP/1.1 $status Status\r\n")
            for (h in headers) append(h).append("\r\n")
            if (headers.none { it.startsWith("Content-Type:") }) append("Content-Type: application/json\r\n")
            append("Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n")
            append(body)
        }
    }

    private fun header(request: String, name: String): String? = request.substringBefore("\r\n\r\n").lineSequence()
        .firstOrNull { it.startsWith("$name:", ignoreCase = true) }?.substringAfter(':')?.trim()
}
