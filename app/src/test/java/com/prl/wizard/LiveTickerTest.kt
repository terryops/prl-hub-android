package com.prl.wizard

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveTickerTest {
    @Test
    fun quoteFromPageAndPush() {
        val page = LiveTicker.Quote.from(JSONObject("""{"usd":1.85,"change1h":-1.07,"change24h":null,"at":1792116928}"""))!!
        assertEquals(1.85, page.usd, 0.0)
        assertEquals(-1.07, page.change1h!!, 0.0)
        assertNull(page.change24h)
        assertEquals(1792116928L, page.at)

        // FCM data values are strings; a missing change is unknown.
        val push = LiveTicker.Quote.fromPush(mapOf("kind" to "live", "usd" to "0.0123", "change24h" to "4.5", "at" to "1792116990"))!!
        assertEquals(0.0123, push.usd, 0.0)
        assertNull(push.change1h)
        assertEquals(4.5, push.change24h!!, 0.0)
        assertEquals(1792116990L, push.at)
    }

    @Test
    fun quoteRejectsBadPrices() {
        assertNull(LiveTicker.Quote.from(null))
        assertNull(LiveTicker.Quote.from(JSONObject("""{"usd":0}""")))
        assertNull(LiveTicker.Quote.from(JSONObject("""{"usd":-2}""")))
        assertNull(LiveTicker.Quote.from(JSONObject("""{"usd":"abc"}""")))
        assertNull(LiveTicker.Quote.fromPush(mapOf("usd" to "NaN")))
        // A non-finite change is dropped, the price kept.
        val q = LiveTicker.Quote.fromPush(mapOf("usd" to "2", "change1h" to "Infinity"))!!
        assertNull(q.change1h)
    }

    @Test
    fun formattingMatchesTheIosLiveActivity() {
        assertEquals("$1.85", LiveTicker.usd(1.85123))
        assertEquals("$0.01", LiveTicker.usd(0.012345))
        assertEquals("$1234.50", LiveTicker.usd(1234.5))
        assertEquals("+4.52%", LiveTicker.percent(4.523))
        assertEquals("-1.07%", LiveTicker.percent(-1.07))
        assertEquals("+0.00%", LiveTicker.percent(0.0))
        assertEquals("—", LiveTicker.percent(null))
    }
}
