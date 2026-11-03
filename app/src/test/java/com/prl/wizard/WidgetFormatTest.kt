package com.prl.wizard

import com.prl.wizard.WidgetRules.HubSize
import com.prl.wizard.WidgetRules.Target
import com.prl.wizard.WidgetSources.PoolResult
import com.prl.wizard.WidgetSources.Utxo
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class WidgetFormatTest {
    private val en = WidgetFormat.locale("en")
    private val de = WidgetFormat.locale("de")
    private val zh = WidgetFormat.locale("zh-Hans")

    @Test
    fun appLanguageLocale() {
        assertEquals("pt-BR", WidgetFormat.locale("pt-BR").toLanguageTag())
        assertEquals("zh-Hans", zh.toLanguageTag())
        assertEquals(Locale.getDefault(), WidgetFormat.locale(null))
        assertEquals(Locale.getDefault(), WidgetFormat.locale("xx"))
    }

    @Test
    fun proGating() {
        assertFalse(WidgetRules.locked(HubSize.SMALL, pro = false))
        assertTrue(WidgetRules.locked(HubSize.MEDIUM, pro = false))
        assertTrue(WidgetRules.locked(HubSize.LARGE, pro = false))
        assertFalse(WidgetRules.locked(HubSize.MEDIUM, pro = true))
        assertFalse(WidgetRules.locked(HubSize.LARGE, pro = true))
    }

    @Test
    fun hubSizeFromTheLauncher() {
        assertEquals(HubSize.SMALL, WidgetRules.hubSize(180, 190))
        assertEquals(HubSize.SMALL, WidgetRules.hubSize(110, 400))
        assertEquals(HubSize.MEDIUM, WidgetRules.hubSize(350, 190))
        assertEquals(HubSize.MEDIUM, WidgetRules.hubSize(260, 110))
        assertEquals(HubSize.LARGE, WidgetRules.hubSize(350, 390))
        // Unknown size: the provider's default (medium).
        assertEquals(HubSize.MEDIUM, WidgetRules.hubSize(0, 0))
        assertTrue(WidgetRules.compact(80))
        assertFalse(WidgetRules.compact(190))
        assertFalse(WidgetRules.compact(0))
    }

    @Test
    fun balanceHeadAndTail() {
        assertEquals("63.61" to "479714", WidgetFormat.balanceParts(6361479714, en))
        assertEquals("63,61" to "479714", WidgetFormat.balanceParts(6361479714, de))
        assertEquals("1.00" to "", WidgetFormat.balanceParts(100000000, en))
        assertEquals("0.00" to "", WidgetFormat.balanceParts(0, en))
        assertEquals("12,345.67" to "89", WidgetFormat.balanceParts(1234567890000, en))
        assertEquals("12.345,67" to "89", WidgetFormat.balanceParts(1234567890000, de))
        // Truncated, never rounded: 0.00999999 keeps its digits.
        assertEquals("0.00" to "999999", WidgetFormat.balanceParts(999999, en))
        assertEquals("0.00" to "000005", WidgetFormat.balanceParts(5, en))
        assertEquals("0.50" to "", WidgetFormat.balanceParts(50000000, en))
    }

    @Test
    fun fiatLine() {
        val price = WidgetPrice(1.85, null, null, 1)
        val cny = WidgetFiat("CNY", "¥", 2, 7.1)
        assertEquals("≈ $117.69 · ¥835.58", WidgetFormat.fiatLine(6361479714, price, cny, en))
        assertEquals("≈ $117.69", WidgetFormat.fiatLine(6361479714, price, null, en))
        assertEquals("≈ $117,69 · ¥835,58", WidgetFormat.fiatLine(6361479714, price, cny, de))
        assertEquals("≈ $1,850.00 · ¥277,500", WidgetFormat.fiatLine(100000000000, price, WidgetFiat("JPY", "¥", 0, 150.0), en))
        assertEquals("≈ $0.00", WidgetFormat.fiatLine(0, price, null, en))
        assertNull(WidgetFormat.fiatLine(6361479714, null, cny, en))
    }

    @Test
    fun hashrateLikeTheWeb() {
        assertEquals("1.23 GH/s", WidgetFormat.hashrate(1.23e9, en))
        assertEquals("642.00 MH/s", WidgetFormat.hashrate(6.42e8, en))
        assertEquals("1.00 EH/s", WidgetFormat.hashrate(1e18, en))
        assertEquals("58.31 EH/s", WidgetFormat.hashrate(5.8306e19, en))
        assertEquals("1,23 GH/s", WidgetFormat.hashrate(1.23e9, de))
        assertEquals("1.50 kH/s", WidgetFormat.hashrate(1500.0, en))
        assertEquals("999 H/s", WidgetFormat.hashrate(999.0, en))
        assertEquals("1000 H/s", WidgetFormat.hashrate(999.5, en))
        assertEquals("0 H/s", WidgetFormat.hashrate(0.0, en))
        assertEquals("—", WidgetFormat.hashrate(null, en))
        assertEquals("—", WidgetFormat.hashrate(Double.NaN, en))
    }

    @Test
    fun priceAndChanges() {
        assertEquals("$1.85", WidgetFormat.price(WidgetPrice(1.85, null, null, 1), en))
        assertEquals("$1,85", WidgetFormat.price(WidgetPrice(1.85, null, null, 1), de))
        assertEquals("$1,234.50", WidgetFormat.price(WidgetPrice(1234.5, null, null, 1), en))
        assertEquals("—", WidgetFormat.price(null, en))
        assertEquals("+1.60%", WidgetFormat.change(1.5957446808510654, en))
        assertEquals("+9,14%", WidgetFormat.change(9.142857142857139, de))
        assertEquals("−0.84%", WidgetFormat.change(-0.84, en))
        assertEquals("−1.07%", WidgetFormat.change(-1.07, en))
        // Rounded before the sign: a tiny fall is flat.
        assertEquals("0.00%", WidgetFormat.change(-0.004, en))
        assertEquals("−0.01%", WidgetFormat.change(-0.005, en))
        assertEquals("—", WidgetFormat.change(null, en))
        assertEquals("—", WidgetFormat.change(Double.POSITIVE_INFINITY, en))
        assertEquals(1, WidgetFormat.direction(0.01))
        assertEquals(-1, WidgetFormat.direction(-2.0))
        assertEquals(0, WidgetFormat.direction(-0.004))
        assertEquals(0, WidgetFormat.direction(null))
    }

    @Test
    fun transferAmountsLikeTheWebPrl() {
        assertEquals("12.5", WidgetFormat.prl(1250000000, 4, en))
        assertEquals("3.218", WidgetFormat.prl(321800000, 4, en))
        assertEquals("1.2345", WidgetFormat.prl(123456789, 4, en))
        assertEquals("1", WidgetFormat.prl(100000000, 4, en))
        assertEquals("1.234,5", WidgetFormat.prl(123450000000, 4, de))
        assertEquals("0", WidgetFormat.prl(1, 4, en))
    }

    @Test
    fun miningTotals() {
        val a = WidgetPool("Rig A", "AlphaPool", 1.23e9, 3, 4)
        val b = WidgetPool("Rig B", "HeroMiners", 6.42e8, 2, 2)
        val monitors = listOf(WidgetMonitor("AlphaPool", "prl1a", null, "Rig A"), WidgetMonitor("HeroMiners", "prl1b", null, "Rig B"))
        val all = MiningSummary.of(listOf(a, b), monitors)
        assertEquals(2, all.monitorCount)
        assertEquals(2, all.knownCount)
        assertEquals(1.872e9, all.hashrate!!, 1.0)
        assertEquals("5/6", all.workersText)
        assertEquals("1.87 GH/s", all.hashrateText(Locale.US))
        // One monitor without figures: no partial total.
        val partial = MiningSummary.of(listOf(a), monitors)
        assertEquals(1, partial.knownCount)
        assertNull(partial.hashrate)
        assertEquals("—", partial.workersText)
        // A target watched twice counts once.
        assertEquals(2, MiningSummary.of(listOf(a, b), monitors + monitors.first()).monitorCount)
        assertEquals(0, MiningSummary.of(emptyList(), emptyList()).monitorCount)
    }

    @Test
    fun repeatedPoolTargetsDoNotDoubleCountFiguresOrHideAnUnknownTarget() {
        val a = WidgetMonitor("AlphaPool", "prl1a", null, "Rig A")
        val b = WidgetMonitor("HeroMiners", "prl1b", null, "Rig B")
        val first = WidgetPool(a.label, a.pool, 100.0, 1, 2, a.key)
        val second = WidgetPool(b.label, b.pool, 200.0, 2, 3, b.key)
        val complete = MiningSummary.of(listOf(first, first, second), listOf(a, a, b))
        assertEquals(2, complete.monitorCount)
        assertEquals(300.0, complete.hashrate!!, 0.0)
        assertEquals("3/5", complete.workersText)
        val partial = MiningSummary.of(listOf(first, first), listOf(a, b))
        assertEquals(1, partial.knownCount)
        assertNull(partial.hashrate)
        assertEquals("—", partial.workersText)
    }

    @Test
    fun routes() {
        assertEquals("trade", Target.PRICE.route)
        assertEquals("wallet", Target.WALLET.route)
        assertEquals("workers", Target.POOLS.route)
        assertEquals("pro", Target.LOCKED.route)
        for (target in Target.entries) {
            assertTrue(WebRoutes.isRoute(target.route))
            assertTrue(WebRoutes.startUrl("play", target.route).endsWith("#" + target.route))
        }
        assertEquals(listOf("hub", "price", "quote", "change1h", "change24h", "quoteMining", "coverMarket", "coverMining"), WidgetRules.KINDS)
    }

    @Test
    fun marketSlotsOnHomeAndCoverScreens() {
        val mini = WidgetRules.MarketSize.MINI
        val compact = WidgetRules.MarketSize.COMPACT
        val regular = WidgetRules.MarketSize.REGULAR
        val cover = WidgetRules.MarketSize.COVER
        assertEquals(mini, WidgetRules.marketSize(56, 56))
        assertEquals(mini, WidgetRules.marketSize(109, 350, cover = true))
        assertEquals(compact, WidgetRules.marketSize(110, 56))
        assertEquals(compact, WidgetRules.marketSize(352, 100, cover = true))
        assertEquals(regular, WidgetRules.marketSize(110, 140))
        assertEquals(regular, WidgetRules.marketSize(229, 339, cover = true))
        assertEquals(regular, WidgetRules.marketSize(352, 249, cover = true))
        assertEquals(cover, WidgetRules.marketSize(230, 250, cover = true))
        assertEquals(cover, WidgetRules.marketSize(352, 339, cover = true))
        assertEquals(regular, WidgetRules.marketSize(352, 339))
        assertEquals(regular, WidgetRules.marketSize(0, 0))
    }

    @Test
    fun freshnessAndStaleness() {
        val now = 1_800_000_000L
        assertTrue(WidgetRules.fresh(now, now))
        assertTrue(WidgetRules.fresh(now - 5 * 60 - 59, now))
        assertFalse(WidgetRules.fresh(now - 6 * 60, now))
        assertFalse(WidgetRules.fresh(0, now))
        assertFalse(WidgetRules.stale(now - 14 * 60, now))
        assertTrue(WidgetRules.stale(now - 15 * 60, now))
        assertTrue(WidgetRules.stale(0, now))
        // The page's figures are reused for two minutes.
        assertTrue(WidgetRules.recent(now - 119, now))
        assertFalse(WidgetRules.recent(now - 120, now))
        assertFalse(WidgetRules.recent(0, now))
    }

    @Test
    fun failedTargetsWithTheSamePoolAndLabelKeepTheirOwnFigures() {
        val a = WidgetMonitor("AlphaPool", "prl1addressa", null, "AlphaPool")
        val b = WidgetMonitor("AlphaPool", "prl1addressb", null, "AlphaPool")
        val previous = listOf(
            WidgetPool(a.label, a.pool, 8e9, 8, 8),
            WidgetPool(b.label, b.pool, 9e9, 9, 9),
        )
        val refreshed = WidgetSources.refreshedPools(listOf(a, b), listOf(PoolResult.Live(10e9, 10, 10), null), previous)
        assertEquals(10e9, refreshed[0].hashrate!!, 0.0)
        assertEquals(9e9, refreshed[1].hashrate!!, 0.0)
        assertEquals(listOf(a.key, b.key), refreshed.map { it.monitorKey })

        // After target A is explicitly missing, the remaining B cache is still identified correctly.
        val onlyB = WidgetSources.refreshedPools(listOf(a, b), listOf(PoolResult.Missing, null), refreshed)
        assertEquals(listOf(refreshed[1]), onlyB)
        // An old partial page snapshot cannot say which address the same label belongs to.
        assertTrue(WidgetSources.refreshedPools(listOf(a, b), listOf(PoolResult.Missing, null), previous.take(1)).isEmpty())
    }

    @Test
    fun sixHourWindowDoesNotOutrankFresherPoolWindows() {
        assertEquals("hour", WidgetSources.nearestWindow("live", listOf("h6", "hour", "day")))
        assertEquals("h3", WidgetSources.nearestWindow("live", listOf("h6", "h3")))
        assertEquals("h6", WidgetSources.nearestWindow("live", listOf("h6", "day")))
        assertEquals("hour", WidgetSources.nearestWindow("h3", listOf("h6", "hour")))
        assertEquals("hour", WidgetSources.nearestWindow("live", listOf("unknown", "hour")))
    }

    @Test
    fun retryThrottleAllowsAClockRollback() {
        val now = 1_800_000_000L
        assertFalse(WidgetRules.retryAllowed(now, now, 60))
        assertFalse(WidgetRules.retryAllowed(now - 59, now, 60))
        assertTrue(WidgetRules.retryAllowed(now - 60, now, 60))
        assertTrue(WidgetRules.retryAllowed(0, now, 60))
        assertTrue(WidgetRules.retryAllowed(now + 3600, now, 60))
    }

    @Test
    fun redrawClock() {
        val now = 1_800_000_000L
        // Minute ticks in the first hour, hourly after.
        assertEquals(now - 30 + 60, WidgetRules.nextRedraw(now, listOf(now - 30), emptyList(), null))
        assertEquals(now - 7200 + 3 * 3600, WidgetRules.nextRedraw(now, listOf(now - 7200), emptyList(), null))
        // The stale mark and midnight come first when sooner.
        assertEquals(now - 600 + 900, WidgetRules.nextRedraw(now, listOf(now - 7200), listOf(now - 600), null))
        assertEquals(now + 10, WidgetRules.nextRedraw(now, emptyList(), listOf(now - 3600), now + 10))
        assertNull(WidgetRules.nextRedraw(now, listOf(0), listOf(now - 3600), null))
    }

    @Test
    fun liveRateWindow() {
        assertEquals("live", WidgetSources.nearestWindow("live", listOf("day", "h3", "live")))
        assertEquals("m15", WidgetSources.nearestWindow("live", listOf("day", "hour", "m15")))
        assertEquals("hour", WidgetSources.nearestWindow("live", listOf("day", "hour")))
        assertEquals("day", WidgetSources.nearestWindow("live", emptyList()))

        val stats = JSONObject(
            """{ "ok": true, "windows": ["live", "day"], "rates": { "live": 5, "day": 9 },
                 "workers": [ { "name": "a", "online": true, "rates": { "live": 1000, "day": 3 } },
                              { "name": "b", "online": false, "rates": { "day": 4 } },
                              { "name": "c", "online": true, "rates": { "live": 500 } } ] }""",
        )
        assertEquals(1500.0, WidgetSources.rate(stats, "live"), 0.0)
        assertEquals(PoolResult.Live(1500.0, 2, 3), WidgetSources.poolStats(stats))
        // No worker figures: the account's own rate.
        val account = JSONObject("""{ "ok": true, "windows": ["hour"], "rates": { "hour": 77 }, "workers": [] }""")
        assertEquals(PoolResult.Live(77.0, 0, 0), WidgetSources.poolStats(account))
        assertEquals(PoolResult.Missing, WidgetSources.poolStats(JSONObject("""{ "ok": true, "pool": "AlphaPool", "missing": true }""")))
        assertThrows(WidgetSources.Invalid::class.java) { WidgetSources.poolStats(JSONObject("""{ "ok": false }""")) }
    }

    @Test
    fun utxosMergeAndDedupe() {
        val tx1 = "a".repeat(64)
        val tx2 = "b".repeat(64)
        val xpub = WidgetSources.utxos(
            JSONArray("""[ { "txid": "$tx1", "vout": 0, "value": "100", "confirmations": 3 }, { "txid": "$tx2", "vout": 1, "value": "50", "confirmations": 0 } ]"""),
        )
        val change = WidgetSources.utxos(
            JSONArray("""[ { "txid": "$tx1", "vout": 0, "value": "100", "confirmations": 4 }, { "txid": "$tx1", "vout": 2, "value": "7" } ]"""),
        )
        assertEquals(Utxo(tx1, 2, 7, 0), change[1])
        // tx1:0 counted once; unconfirmed outputs are pending.
        assertEquals(100L to 57L, WidgetSources.balance(listOf(xpub, change)))
        assertEquals(0L to 0L, WidgetSources.balance(listOf(emptyList())))
        assertThrows(WidgetSources.Invalid::class.java) {
            WidgetSources.balance(listOf(xpub, listOf(Utxo(tx1, 0, 101, 3))))
        }
        for (bad in listOf(
            """[ { "txid": "xyz", "vout": 0, "value": "1" } ]""",
            """[ { "txid": "$tx1", "vout": -1, "value": "1" } ]""",
            """[ { "txid": "$tx1", "vout": 0, "value": 1 } ]""",
            """[ { "txid": "$tx1", "vout": 0, "value": "1", "confirmations": -2 } ]""",
            """[ 5 ]""",
        )) {
            assertThrows(WidgetSources.Invalid::class.java) { WidgetSources.utxos(JSONArray(bad)) }
        }
    }
}
