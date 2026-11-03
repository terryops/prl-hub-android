package com.prl.wizard

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

class NockRpcTest {
    private val node = "http://216.158.95.10:5556"
    private val path = "nockchain.public.v2.NockchainBlockService/GetBlocks"
    private val empty = "AAAAAAA=" // an empty gRPC-web message: flag 0, length 0

    @Test
    fun acceptsTheCliNodeAndEveryWalletCall() {
        for (p in NockRpc.PATHS) {
            val r = NockRpc.request(node, p, empty, null)
            assertNotNull(p, r)
            assertEquals("$node/$p", r!!.url)
            assertArrayEquals(ByteArray(5), r.body)
            assertEquals(30_000, r.timeoutMs)
        }
        assertEquals(6, NockRpc.PATHS.size)
    }

    @Test
    fun rejectsOtherNodes() {
        for (n in listOf(
            "https://216.158.95.10:5556", "http://216.158.95.10", "http://216.158.95.10:5557",
            "http://216.158.95.10:5556/", "https://rpc.nockbox.org", "http://example.com:5556",
            "HTTP://216.158.95.10:5556", "",
        )) assertNull(n, NockRpc.request(n, path, empty, null))
        assertNull(NockRpc.request(null, path, empty, null))
        assertNull(NockRpc.request(5556, path, empty, null))
    }

    @Test
    fun rejectsOtherPaths() {
        for (p in listOf(
            "", "/nockchain.public.v2.NockchainBlockService/GetBlocks", "nockchain.public.v2.NockchainBlockService/GetBlocks/",
            "nockchain.public.v2.NockchainBlockService/getblocks", "nockchain.public.v2.NockchainService/WalletGetBalance?x=1",
            "nockchain.public.v1.NockchainService/WalletGetBalance", "../admin", "grpc.health.v1.Health/Check",
        )) assertNull(p, NockRpc.request(node, p, empty, null))
        assertNull(NockRpc.request(node, null, empty, null))
    }

    @Test
    fun bodyMustBeStandardBase64UpTo1MiB() {
        assertNull(NockRpc.request(node, path, "not base64!", null))
        assertNull(NockRpc.request(node, path, "AAAA AAA=", null))
        assertNull(NockRpc.request(node, path, "_-__", null)) // URL-safe alphabet
        assertNull(NockRpc.request(node, path, null, null))
        assertNull(NockRpc.request(node, path, byteArrayOf(0), null))
        assertNotNull(NockRpc.request(node, path, "AAAAAAA", null)) // padding is optional
        assertArrayEquals(ByteArray(0), NockRpc.request(node, path, "", null)!!.body)

        val max = Base64.getEncoder().encodeToString(ByteArray(NockRpc.MAX_BODY) { 7 })
        assertEquals(NockRpc.MAX_BODY, NockRpc.request(node, path, max, null)!!.body.size)
        val over = Base64.getEncoder().encodeToString(ByteArray(NockRpc.MAX_BODY + 1))
        assertNull(NockRpc.request(node, path, over, null))
        assertNull(NockRpc.request(node, path, "A".repeat(8 * 1024 * 1024), null))
    }

    @Test
    fun timeoutIsClampedAndMustBeANumber() {
        fun ms(t: Any?) = NockRpc.request(node, path, empty, t)?.timeoutMs
        assertEquals(30_000, ms(null))
        assertEquals(12_000, ms(12_000))
        assertEquals(12_000, ms(12_000L))
        assertEquals(12_500, ms(12_500.7))
        assertEquals(1_000, ms(0))
        assertEquals(1_000, ms(-5))
        assertEquals(1_000, ms(999))
        assertEquals(60_000, ms(60_001))
        assertEquals(60_000, ms(Long.MAX_VALUE))
        assertEquals(60_000, ms(Double.POSITIVE_INFINITY))
        assertNull(ms(Double.NaN))
        assertNull(ms("20000"))
        assertNull(ms(true))
    }

    @Test
    fun encodesRepliesAsStandardBase64() {
        assertEquals("", NockRpc.encode(ByteArray(0)))
        assertEquals("gAAAAA9ncnBjLXN0YXR1czowDQo=", NockRpc.encode("\u0080\u0000\u0000\u0000\u000fgrpc-status:0\r\n".toByteArray(Charsets.ISO_8859_1)))
    }
}
