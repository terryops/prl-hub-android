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
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

class SafeTradeTest {
    private fun params(vararg pairs: Pair<String, Any?>) = JSONObject().apply { for ((k, v) in pairs) put(k, v) }
    private fun get(path: String, vararg more: Pair<String, Any?>) = SafeTrade.request(params("method" to "GET", "path" to path, *more))
    private fun post(path: String, vararg more: Pair<String, Any?>) = SafeTrade.request(params("method" to "POST", "path" to path, *more))
    private val stored = "auth" to "stored"
    private val withdrawJson = "json" to JSONObject().put("currency", "pearl").put("amount", "12.5").put("blockchain_key", "pearl-mainnet")

    @Test
    fun signatureIsHexHmacSha256OfNonceAndKeyUnderTheSecret() {
        // Vectors from Python: hmac.new(secret, nonce + key, hashlib.sha256).hexdigest(); the first is iOS's.
        assertEquals(
            "426f9f52954773d96d0d96b04386023394e032d90e254e598cd15c5081848371",
            SafeTrade.signature("1790474921000", "test-key", "test-secret"),
        )
        assertEquals(
            "6a67ab1cae7acfb321f71ebc2265a698833476cb96216a34854a962fa513cd66",
            SafeTrade.signature("1792116928123", "AbCdEfGh12345678", "Zx9!secret/Value~42"),
        )
        assertEquals(
            "a7295999a6aff23d7a0a032bfe08b44d74d2b1baa9ffa0d111d9f2ce5302d7cb",
            SafeTrade.signature("1", "ключ", "秘密"),
        )
    }

    @Test
    fun noncesStayStrictlyIncreasingWhenTheClockStandsStillOrGoesBack() {
        var now = 1_790_474_921_000L
        val nonces = SafeTradeNonce { now }
        assertEquals("1790474921000", nonces.next())
        assertEquals("1790474921001", nonces.next())
        now -= 60_000
        assertEquals("1790474921002", nonces.next())
        now += 120_000
        assertEquals("1790474981000", nonces.next())
    }

    @Test
    fun noncesStayUniqueAndIncreasingAcrossThreads() {
        val nonces = SafeTradeNonce { 1_000L }
        val all = ConcurrentLinkedQueue<Long>()
        val workers = List(8) {
            thread {
                var last = 0L
                repeat(2_000) {
                    val n = nonces.next().toLong()
                    assertTrue(n > last)
                    last = n
                    all += n
                }
            }
        }
        workers.forEach { it.join() }
        assertEquals(16_000, all.toSet().size)
        assertEquals(1_000L + 15_999, all.max())
    }

    @Test
    fun offsetOnlyCorrectsAClearlyWrongClock() {
        val local = 1_790_474_921_000L
        // iOS's cases: the header's second matches; device 10 s behind; device 5 s ahead.
        assertEquals(0L, SafeTradeNonce.offset(local, local))
        assertEquals(10_500L, SafeTradeNonce.offset(local + 10_000, local))
        assertEquals(-4_500L, SafeTradeNonce.offset(local - 5_000, local))
        // Within the ±2 s (after the half-second correction) a small skew is left alone.
        assertEquals(0L, SafeTradeNonce.offset(local - 1_500, local))
        assertEquals(0L, SafeTradeNonce.offset(local + 1_400, local))
        assertEquals(0L, SafeTradeNonce.offset(local - 2_400, local))
        assertEquals(2_000L, SafeTradeNonce.offset(local + 1_500, local))
        assertEquals(-2_000L, SafeTradeNonce.offset(local - 2_500, local))
    }

    @Test
    fun observeLearnsTheServerClockFromTheDateHeader() {
        val server = 1_790_474_921_000L // Sun, 27 Sep 2026 02:08:41 GMT
        assertEquals(server, SafeTradeNonce.parseHttpDate("Sun, 27 Sep 2026 02:08:41 GMT"))
        assertNull(SafeTradeNonce.parseHttpDate("yesterday"))
        assertNull(SafeTradeNonce.parseHttpDate("2026-09-27T02:08:41Z"))

        var now = server - 10_000
        val nonces = SafeTradeNonce { now }
        nonces.observe(null)
        nonces.observe("not a date")
        assertEquals((server - 10_000).toString(), nonces.next())
        nonces.observe("Sun, 27 Sep 2026 02:08:41 GMT")
        assertEquals((server + 500).toString(), nonces.next())
        // The device clock is corrected: a later header within 2 s drops the offset again.
        now = server + 1_000
        nonces.observe("Sun, 27 Sep 2026 02:08:41 GMT")
        assertEquals((server + 1_000).toString(), nonces.next())
        // A fast device clock moves nonces back, but never below the last one.
        now = server + 5_000
        nonces.observe("Sun, 27 Sep 2026 02:08:41 GMT")
        assertEquals((server + 1_001).toString(), nonces.next())
    }

    @Test
    fun aFastClockOrOneSetBackStartsNoncesAgainFromTheServerClock() {
        val server = 1_790_474_921_000L // Sun, 27 Sep 2026 02:08:41 GMT
        val date = "Sun, 27 Sep 2026 02:08:41 GMT"
        // A minute fast: the first nonce is a minute ahead; once a reply shows it, the next is on SafeTrade's time.
        var now = server + 60_000
        val fast = SafeTradeNonce { now }
        assertEquals((server + 60_000).toString(), fast.next())
        fast.observe(date)
        assertEquals((server + 501).toString(), fast.next())
        assertEquals((server + 502).toString(), fast.next())
        // A clock set back a minute (automatic time switched on): stuck ahead until a reply shows it.
        now = server + 60_000
        val setBack = SafeTradeNonce { now }
        assertEquals((server + 60_000).toString(), setBack.next())
        now = server
        assertEquals((server + 60_001).toString(), setBack.next())
        setBack.observe(date)
        assertEquals((server + 1).toString(), setBack.next())
    }

    @Test
    fun recognisesStaleNonceRefusals() {
        assertTrue(SafeTrade.isNonceError("""{"errors":["authz.nonce_expired"]}"""))
        assertTrue(SafeTrade.isNonceError("""{"errors":["authz.invalid_signature","AUTHZ.Invalid-Nonce"]}"""))
        assertFalse(SafeTrade.isNonceError("""{"errors":["authz.invalid_signature"]}"""))
        assertFalse(SafeTrade.isNonceError("""{"errors":["authz.nonces_expired"]}"""))
        assertFalse(SafeTrade.isNonceError("""{"errors":"authz.nonce_expired"}"""))
        assertFalse(SafeTrade.isNonceError("""{"error":["authz.nonce_expired"]}"""))
        assertFalse(SafeTrade.isNonceError("""["authz.nonce_expired"]"""))
        assertFalse(SafeTrade.isNonceError("<html><body>authz.nonce_expired</body></html>"))
        assertFalse(SafeTrade.isNonceError(""))
        assertEquals(listOf("authz", "not", "trusted", "ip"), SafeTrade.tokens("authz.not_trusted_ip"))
        assertEquals(listOf("account", "withdraw", "invalid", "otp", "code"), SafeTrade.errorKeys("""{"errors":["account.withdraw.invalid_otp_code"]}""").flatMap(SafeTrade::tokens))
    }

    @Test
    fun masksTheKeyLikeIos() {
        assertNull(SafeTrade.masked(""))
        assertEquals("••••", SafeTrade.masked("a"))
        assertEquals("••••", SafeTrade.masked("abcdef"))
        assertEquals("abcd••••fg", SafeTrade.masked("abcdefg"))
        assertEquals("Ab12••••yz", SafeTrade.masked("Ab12xxxxxxxxxxxxxxyz"))
    }

    @Test
    fun credentialsArePrintableAsciiWithoutWhitespace() {
        assertTrue(SafeTrade.isCredential("a"))
        assertTrue(SafeTrade.isCredential("Ab-12_!~/+=" + "x".repeat(245)))
        assertTrue(SafeTrade.isCredential("x".repeat(256)))
        assertFalse(SafeTrade.isCredential(""))
        assertFalse(SafeTrade.isCredential("x".repeat(257)))
        for (bad in listOf("ab cd", "ab\tcd", "abcd\n", "ключ", "ab\u0000", "ab\u007f")) assertFalse(bad, SafeTrade.isCredential(bad))
    }

    @Test
    fun allowsTheIosCallsPerMethod() {
        for (path in listOf(
            "/api/v2/peatio/public/markets/pearlusdt/tickers",
            "/api/v2/trade/public/markets/pearlusdt",
            "/api/v2/trade/public/markets/pearlusdt/depth",
            "/api/v2/trade/public/markets/pearlusdt/k-line",
            "/api/v2/trade/public/currencies/pearl",
            "/api/v2/trade/public/currencies/u",
            "/cdn-cgi/trace",
        )) {
            val r = get(path)
            assertNotNull(path, r)
            assertSame(SafeTrade.Auth.None, r!!.auth)
            assertNull(r.body)
            assertNull(r.deviceCheck)
            assertNotNull(path, get(path, stored))
        }
        for (path in listOf(
            "/api/v2/trade/account/balances/spot",
            "/api/v2/trade/market/orders",
            "/api/v2/trade/account/withdraws",
            "/api/v2/trade/account/deposit_address/pearl",
            "/api/v2/trade/account/deposits",
            "/api/v2/trade/account/beneficiaries",
        )) {
            assertSame(path, SafeTrade.Auth.Stored, get(path, stored)?.auth)
            assertNull(path, get(path))
            assertNull(path, get(path, "auth" to "none"))
        }
        val order = post("/api/v2/trade/market/orders", stored, "form" to JSONObject().put("market", "pearlusdt").put("side", "buy").put("amount", "2.5").put("type", "market"))!!
        assertEquals("application/x-www-form-urlencoded", order.contentType)
        assertEquals("amount=2.5&market=pearlusdt&side=buy&type=market", String(order.body!!))
        assertNull(order.deviceCheck)
        val cancel = post("/api/v2/trade/market/orders/12345678901234567890/cancel", stored)!!
        assertNull(cancel.body)
        assertNull(cancel.contentType)
        val code = post("/api/v2/trade/account/withdraws/generate_code", stored, withdrawJson)!!
        assertEquals("application/json", code.contentType)
        assertEquals(withdrawJson.second.toString(), String(code.body!!))
        assertNull(code.deviceCheck)
        val withdraw = post("/api/v2/trade/account/withdraws", stored, withdrawJson, "title" to "  确认提现  ", "subtitle" to "12.5 PEARL")!!
        assertEquals("确认提现", withdraw.deviceCheck!!.title)
        assertEquals("12.5 PEARL", withdraw.deviceCheck.subtitle)
        assertNull(post("/api/v2/trade/account/withdraws", stored, withdrawJson, "title" to "确认提现", "subtitle" to "")!!.deviceCheck!!.subtitle)
        assertNull(post("/api/v2/trade/account/withdraws", stored, withdrawJson, "title" to "确认提现", "subtitle" to null)!!.deviceCheck!!.subtitle)
    }

    @Test
    fun rejectsEverythingElse() {
        for (path in listOf(
            "", "/", "/api/v2/trade/account/balances", "/api/v2/trade/account/balances/spot/", "/api/v2/trade/account/members/me",
            "/api/v2/trade/public/markets/PEARLUSDT", "/api/v2/trade/public/markets/ab", "/api/v2/trade/public/markets/pearlusdt/trades",
            "/api/v2/trade/public/markets/${"a".repeat(33)}", "/api/v2/peatio/public/markets/pearlusdt/tickers/",
            "/api/v2/trade/public/markets/../account/balances/spot", "/api/v2/trade/account/deposit_address/..",
            "/api/v2/trade/account/balances/spot?x=1", "/api/v2/trade/market/orders#x", "/api/v2/trade/market/orders%2F",
            "https://safetrade.com/api/v2/trade/market/orders", "//evil.example/api/v2/trade/market/orders",
            "/cdn-cgi/trace/", "api/v2/trade/market/orders", "/api/v2/trade/market/orders\n",
        )) {
            assertNull(path, get(path, stored))
            assertNull(path, post(path, stored))
        }
        // Wrong method for the path, or no / another method.
        assertNull(post("/api/v2/trade/account/balances/spot", stored))
        assertNull(post("/api/v2/trade/account/deposits", stored))
        assertNull(get("/api/v2/trade/market/orders/1/cancel", stored))
        assertNull(get("/api/v2/trade/account/withdraws/generate_code", stored))
        assertNull(post("/api/v2/trade/market/orders/x1/cancel", stored))
        assertNull(post("/api/v2/trade/market/orders/${"1".repeat(21)}/cancel", stored))
        for (m in listOf("get", "PUT", "DELETE", "OPTIONS", "")) assertNull(m, SafeTrade.request(params("method" to m, "path" to "/cdn-cgi/trace")))
        assertNull(SafeTrade.request(params("path" to "/cdn-cgi/trace")))
        assertNull(SafeTrade.request(params("method" to "GET", "path" to 5)))
        // Private calls need keys; unknown auth modes are refused.
        assertNull(post("/api/v2/trade/market/orders/1/cancel"))
        assertNull(post("/api/v2/trade/account/withdraws/generate_code", withdrawJson))
        for (auth in listOf("NONE", "keychain", "", 1)) assertNull(get("/cdn-cgi/trace", "auth" to auth))
    }

    @Test
    fun typedKeysOnlyCheckTheBalances() {
        val keys = arrayOf("auth" to "explicit", "key" to "Ab12cd34", "secret" to "s3cr3t/value")
        val r = get(SafeTrade.BALANCES, *keys)!!
        val auth = r.auth as SafeTrade.Auth.Explicit
        assertEquals("Ab12cd34", auth.key)
        assertEquals("s3cr3t/value", auth.secret)
        assertNull(get("/api/v2/trade/market/orders", *keys))
        assertNull(get("/api/v2/trade/account/deposits", *keys))
        assertNull(get("/cdn-cgi/trace", *keys))
        assertNull(post("/api/v2/trade/market/orders/1/cancel", *keys))
        assertNull(post(SafeTrade.WITHDRAW, *keys, withdrawJson, "title" to "确认提现"))
        assertNull(get(SafeTrade.BALANCES, "auth" to "explicit", "key" to "Ab12cd34"))
        assertNull(get(SafeTrade.BALANCES, "auth" to "explicit", "secret" to "s3cr3t"))
        assertNull(get(SafeTrade.BALANCES, "auth" to "explicit", "key" to "", "secret" to "s3cr3t"))
        assertNull(get(SafeTrade.BALANCES, "auth" to "explicit", "key" to "Ab12 cd34", "secret" to "s3cr3t"))
        assertNull(get(SafeTrade.BALANCES, "auth" to "explicit", "key" to "Ab12cd34", "secret" to "x".repeat(257)))
        assertNull(get(SafeTrade.BALANCES, "auth" to "explicit", "key" to 1234, "secret" to "s3cr3t"))
    }

    @Test
    fun aWithdrawalNeedsTheStoredKeysAndPromptText() {
        val path = SafeTrade.WITHDRAW
        assertNotNull(post(path, stored, withdrawJson, "title" to "确认提现"))
        assertNull(post(path, stored, withdrawJson))
        assertNull(post(path, stored, withdrawJson, "title" to ""))
        assertNull(post(path, stored, withdrawJson, "title" to "   "))
        assertNull(post(path, stored, withdrawJson, "title" to 1))
        assertNull(post(path, stored, withdrawJson, "title" to "x".repeat(65)))
        assertNotNull(post(path, stored, withdrawJson, "title" to "x".repeat(64)))
        assertNotNull(post(path, stored, withdrawJson, "title" to "确认", "subtitle" to "y".repeat(128)))
        assertNull(post(path, stored, withdrawJson, "title" to "确认", "subtitle" to "y".repeat(129)))
        assertNull(post(path, stored, withdrawJson, "title" to "确认", "subtitle" to false))
        assertNull(post(path, withdrawJson, "title" to "确认提现"))
        assertNull(post(path, "auth" to "none", withdrawJson, "title" to "确认提现"))
    }

    @Test
    fun bodiesFollowTheMethod() {
        val form = "form" to JSONObject().put("market", "pearlusdt")
        val query = "query" to JSONObject().put("market", "pearlusdt")
        // GET has no body; POST has no query; never both bodies.
        assertNull(get("/api/v2/trade/market/orders", stored, form))
        assertNull(get("/api/v2/trade/market/orders", stored, withdrawJson))
        assertNull(post("/api/v2/trade/market/orders", stored, query, form))
        assertNull(post("/api/v2/trade/market/orders", stored, form, withdrawJson))
        assertNotNull(post("/api/v2/trade/market/orders", stored, form, "json" to null))
        // JSON must be an object of at most 16 KiB once serialized. Text is sent exactly as written.
        val exact = """{"currency":"pearl","amount":0.0005,"blockchain_key":"pearl-mainnet"}"""
        assertArrayEquals(exact.toByteArray(), post("/api/v2/trade/account/withdraws/generate_code", stored, "json" to exact)!!.body)
        assertNull(post("/api/v2/trade/account/withdraws/generate_code", stored, "json" to "[1]"))
        assertNull(post("/api/v2/trade/account/withdraws/generate_code", stored, "json" to """{"a":1} {"b":2}"""))
        assertNull(post("/api/v2/trade/account/withdraws/generate_code", stored, "json" to "{"))
        assertNull(post("/api/v2/trade/account/withdraws/generate_code", stored, "json" to org.json.JSONArray()))
        val big = JSONObject().put("note", "x".repeat(SafeTrade.MAX_JSON))
        assertNull(post("/api/v2/trade/account/withdraws/generate_code", stored, "json" to big))
        val fits = JSONObject().put("n", "x".repeat(SafeTrade.MAX_JSON - """{"n":""}""".length))
        assertEquals(SafeTrade.MAX_JSON, post("/api/v2/trade/account/withdraws/generate_code", stored, "json" to fits)!!.body!!.size)
        val nested = JSONObject().put("currency", "pearl").put("beneficiary_id", 42).put("amount", 2)
        assertArrayEquals(nested.toString().toByteArray(), post(SafeTrade.WITHDRAW, stored, "json" to nested, "title" to "确认")!!.body)
        // A form needs string values within the field rules.
        assertNull(post("/api/v2/trade/market/orders", stored, "form" to JSONObject().put("amount", 2.5)))
        assertNull(post("/api/v2/trade/market/orders", stored, "form" to "amount=2.5"))
        assertEquals("", String(post("/api/v2/trade/market/orders", stored, "form" to JSONObject())!!.body!!))
    }

    @Test
    fun queryFieldsAreLimitedAndEncoded() {
        val kline = get(
            "/api/v2/trade/public/markets/pearlusdt/k-line",
            "query" to JSONObject().put("period", "60").put("time_from", "1790467721").put("time_to", "1790474921").put("limit", "120"),
        )!!
        assertEquals("limit=120&period=60&time_from=1790467721&time_to=1790474921", kline.query)
        assertEquals("/api/v2/trade/public/markets/pearlusdt/k-line?limit=120&period=60&time_from=1790467721&time_to=1790474921", kline.target)
        val odd = get("/api/v2/trade/market/orders", stored, "query" to JSONObject().put("market", "a+b/c=d%e?f~*.-_:\"!"))!!
        assertEquals("market=a%2Bb%2Fc%3Dd%25e%3Ff%7E*.-_%3A%22%21", odd.query)
        assertEquals("a=1&b=x%2Fy", SafeTrade.encode(mapOf("a" to "1", "b" to "x/y")))
        assertNull(get("/cdn-cgi/trace", "query" to JSONObject())!!.query)
        assertNull(get("/cdn-cgi/trace")!!.query)
        assertEquals("/cdn-cgi/trace", get("/cdn-cgi/trace")!!.target)

        fun q(name: String, value: Any) = get("/api/v2/trade/market/orders", stored, "query" to JSONObject().put(name, value))
        assertNotNull(q("state", ""))
        assertNotNull(q("time_from", "x".repeat(64)))
        assertNotNull(q("a".repeat(32), "1"))
        for (name in listOf("", "Market", "state[]", "time-from", "a1", "a".repeat(33), "a b")) assertNull(name, q(name, "1"))
        for (value in listOf("a b", "a&b=c", "a#b", "x".repeat(65), "ключ", "a\nb", "a\u007f")) assertNull(value, q("state", value))
        assertNull(q("limit", 120))
        assertNull(q("limit", JSONObject.NULL))
        assertNull(get("/api/v2/trade/market/orders", stored, "query" to "limit=1"))
        val many = JSONObject().apply { for (i in 0 until 16) put("k" + "a".repeat(i), "1") }
        assertNotNull(get("/api/v2/trade/market/orders", stored, "query" to many))
        assertNull(get("/api/v2/trade/market/orders", stored, "query" to many.put("zz", "1")))
    }

    // The client against a local plain-HTTP server (the root is a parameter for this).

    @Test
    fun everyStatusComesBackWithItsBodyAndSignedHeaders() {
        val refusal = """{"errors":["authz.invalid_signature"]}"""
        Raw { _, _ -> reply(401, refusal) }.use { server ->
            val nonces = SafeTradeNonce { 1_790_474_921_000L }
            val reply = SafeTrade.send(get(SafeTrade.BALANCES, stored)!!, "Ab12cd34" to "s3cr3t", server.root, nonces)
            assertEquals(401, reply.status)
            assertEquals(refusal, reply.body)
            assertEquals(1, server.requests.size)
            val sent = server.requests[0]
            assertTrue(sent.startsWith("GET /api/v2/trade/account/balances/spot HTTP/1.1\r\n"))
            assertEquals("Ab12cd34", header(sent, "X-Auth-Apikey"))
            assertEquals("1790474921000", header(sent, "X-Auth-Nonce"))
            assertEquals(SafeTrade.signature("1790474921000", "Ab12cd34", "s3cr3t"), header(sent, "X-Auth-Signature"))
            assertEquals(SafeTrade.USER_AGENT, header(sent, "User-Agent"))
            assertEquals("application/json", header(sent, "Accept"))
            assertNull(header(sent, "Cookie"))
        }
        Raw { _, _ -> reply(403, "<html>blocked</html>", "Content-Type: text/html") }.use { server ->
            val reply = SafeTrade.send(get("/cdn-cgi/trace")!!, null, server.root, SafeTradeNonce())
            assertEquals(403, reply.status)
            assertEquals("<html>blocked</html>", reply.body)
            assertNull(header(server.requests[0], "X-Auth-Apikey"))
        }
        Raw { _, _ -> reply(302, "", "Location: https://evil.example/") }.use { server ->
            assertEquals(302, SafeTrade.send(get("/cdn-cgi/trace")!!, null, server.root, SafeTradeNonce()).status)
            assertEquals(1, server.requests.size)
        }
    }

    @Test
    fun aStaleNonceIsSignedAgainOnceOnTheServerClock() {
        val server = 1_790_474_921_000L
        val stale = """{"errors":["authz.nonce_expired"]}"""
        Raw { i, _ -> if (i == 0) reply(401, stale, "Date: Sun, 27 Sep 2026 02:08:41 GMT") else reply(200, "[]") }.use { raw ->
            val nonces = SafeTradeNonce { server - 10_000 }
            val reply = SafeTrade.send(get(SafeTrade.BALANCES, stored)!!, "Ab12cd34" to "s3cr3t", raw.root, nonces)
            assertEquals(200, reply.status)
            assertEquals("[]", reply.body)
            assertEquals(2, raw.requests.size)
            assertEquals((server - 10_000).toString(), header(raw.requests[0], "X-Auth-Nonce"))
            val second = header(raw.requests[1], "X-Auth-Nonce")
            assertEquals((server + 500).toString(), second)
            assertEquals(SafeTrade.signature(second!!, "Ab12cd34", "s3cr3t"), header(raw.requests[1], "X-Auth-Signature"))
        }
        // A phone a minute fast: the re-sign lands within SafeTrade's window instead of a minute ahead again.
        Raw { i, _ -> if (i == 0) reply(401, stale, "Date: Sun, 27 Sep 2026 02:08:41 GMT") else reply(200, "[]") }.use { raw ->
            val nonces = SafeTradeNonce { server + 60_000 }
            assertEquals(200, SafeTrade.send(get(SafeTrade.BALANCES, stored)!!, "Ab12cd34" to "s3cr3t", raw.root, nonces).status)
            assertEquals((server + 60_000).toString(), header(raw.requests[0], "X-Auth-Nonce"))
            assertEquals((server + 501).toString(), header(raw.requests[1], "X-Auth-Nonce"))
        }
        // Only once, and for a POST too (the gateway refused it before acting).
        val order = post("/api/v2/trade/market/orders", stored, "form" to JSONObject().put("market", "pearlusdt"))!!
        Raw { _, _ -> reply(403, stale) }.use { raw ->
            val reply = SafeTrade.send(order, "Ab12cd34" to "s3cr3t", raw.root, SafeTradeNonce())
            assertEquals(403, reply.status)
            assertEquals(stale, reply.body)
            assertEquals(2, raw.requests.size)
            assertTrue(raw.requests.all { it.endsWith("\r\n\r\nmarket=pearlusdt") })
            assertEquals("application/x-www-form-urlencoded", header(raw.requests[1], "Content-Type"))
        }
        // Unsigned requests are never re-sent.
        Raw { _, _ -> reply(401, stale) }.use { raw ->
            assertEquals(401, SafeTrade.send(get("/cdn-cgi/trace")!!, null, raw.root, SafeTradeNonce()).status)
            assertEquals(1, raw.requests.size)
        }
    }

    @Test
    fun noConnectionMeansUnreached() {
        val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val root = "http://127.0.0.1:$port"
        val order = post("/api/v2/trade/market/orders", stored, "form" to JSONObject().put("market", "pearlusdt"))!!
        assertEquals("unreached", assertThrows(SafeTrade.Failure::class.java) { SafeTrade.send(order, "k" to "s", root, SafeTradeNonce()) }.code)
        assertEquals("unreached", assertThrows(SafeTrade.Failure::class.java) { SafeTrade.send(get("/cdn-cgi/trace")!!, null, root, SafeTradeNonce()) }.code)
    }

    @Test
    fun aPostLostAfterConnectingIsUnconfirmedAndAGetIsRedialed() {
        val order = post("/api/v2/trade/market/orders", stored, "form" to JSONObject().put("market", "pearlusdt"))!!
        Raw { _, _ -> null }.use { raw ->
            assertEquals("unconfirmed", assertThrows(SafeTrade.Failure::class.java) { SafeTrade.send(order, "k" to "s", raw.root, SafeTradeNonce()) }.code)
            assertEquals(1, raw.requests.size)
        }
        Raw { _, _ -> null }.use { raw ->
            assertEquals("unreached", assertThrows(SafeTrade.Failure::class.java) { SafeTrade.send(get("/cdn-cgi/trace")!!, null, raw.root, SafeTradeNonce()) }.code)
            assertTrue(raw.requests.size >= 2)
        }
        // A connection that dies once (the pooled one after sleep) costs a GET nothing.
        Raw { i, _ -> if (i == 0) null else reply(200, "ok") }.use { raw ->
            assertEquals("ok", SafeTrade.send(get("/cdn-cgi/trace")!!, null, raw.root, SafeTradeNonce()).body)
        }
    }

    /** One connection per request: reads it, answers with [respond] (or closes without a reply on null). */
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
