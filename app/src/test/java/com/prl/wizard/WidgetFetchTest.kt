package com.prl.wizard

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetFetchTest {
    private val monitors = listOf(
        WidgetMonitor("AlphaPool", "prl1first", null, "First"),
        WidgetMonitor("HeroMiners", "prl1second", null, "Second"),
    )
    private val app = AppPart("en", "system", "classic", true, null, null,
        listOf(WidgetPool("First", "AlphaPool", 1.0, 1, 1), WidgetPool("Second", "HeroMiners", 2.0, 1, 1)), monitors, 1000)
    private fun results(first: Double?, second: Double?) = listOf(first, second).map { h -> h?.let { WidgetSources.PoolResult.Live(it, 1, 1) } }

    @Test
    fun anOlderPoolRequestCannotOverwriteANewerNativeResultEvenWhenItFinishesLater() {
        val newer = WidgetData.fetchedPools(app, null, app, results(30.0, 40.0), at = 1300, request = 2)!!
        assertNull(WidgetData.fetchedPools(app, newer, app, results(10.0, 20.0), at = 1400, request = 1))
        assertNull(WidgetData.fetchedPools(app, newer, app, results(10.0, 20.0), at = 1400, request = 2))
        assertEquals(newer, WidgetData.parseLivePools(WidgetData.json(newer)))
    }

    @Test
    fun aPartialFetchUsesTheLatestSuccessfulFiguresAvailableWhenItSaves() {
        val completedWhileWaiting = WidgetData.fetchedPools(app, null, app, results(30.0, 40.0), at = 1300, request = 1)!!
        val partial = WidgetData.fetchedPools(app, completedWhileWaiting, app, results(null, 50.0), at = 1400, request = 2)!!
        assertEquals(listOf(30.0, 50.0), partial.pools.map { it.hashrate })
        assertEquals(1000L, WidgetData.merge(app, null, false, null, partial, null).poolsAt)
    }

    @Test
    fun anOlderWalletRequestCannotOverwriteANewerNativeResultEvenWhenItFinishesLater() {
        val wallet = WalletPart("Wallet", "mainnet", "z".repeat(60), emptyList(), 100, 0, emptyList(), 1000)
        val newer = LiveWallet(wallet.xpub!!, "mainnet", 1000, 300, 10, 1300, WidgetData.fingerprint(wallet), request = 2)
        val older = newer.copy(balance = 200, pending = 20, at = 1400, request = 1)
        assertNull(WidgetData.fetchedWallet(wallet, newer, older))
        assertEquals(newer, WidgetData.parseLiveWallet(WidgetData.json(newer)))
        assertEquals(older, WidgetData.fetchedWallet(wallet, null, older))
    }

    @Test
    fun malformedPoolRepliesDoNotReplaceKnownFiguresWithFreshZeroes() {
        for (raw in listOf(
            "{}", """{"ok": false, "missing": true}""", """{"ok": true}""",
            """{"ok": true, "windows": ["live"], "rates": {"live": 42}, "workers": [null]}""",
            """{"ok": true, "windows": ["live"], "rates": {"live": 42}, "workers": [{"online": "true", "rates": {"live": 1}}]}""",
            """{"ok": true, "windows": ["live"], "rates": {"live": 42}, "workers": [{"online": true, "rates": {"live": -1}}]}""",
            """{"ok": true, "windows": ["live"], "rates": {"live": 42}, "workers": [{"online": true, "rates": {"live": 1e308}}, {"online": true, "rates": {"live": 1e308}}]}""",
        )) assertThrows(raw, WidgetSources.Invalid::class.java) { WidgetSources.poolStats(JSONObject(raw)) }
    }

    @Test
    fun malformedConfirmationsDoNotSilentlyBecomeUnconfirmedFunds() {
        val txid = "a".repeat(64)
        for (bad in listOf("\"3\"", "true", "{}", "9007199254740992")) {
            val raw = """[{"txid":"$txid","vout":0,"value":"100","confirmations":$bad}]"""
            assertThrows(raw, WidgetSources.Invalid::class.java) { WidgetSources.utxos(JSONArray(raw)) }
        }
    }

    @Test
    fun aChangedPublicationRejectsInflightFiguresWhileMetadataChangesKeepThem() {
        val metadata = app.copy(at = 1400, theme = "dark", lang = "ja")
        assertEquals(listOf(30.0, 40.0), WidgetData.fetchedPools(metadata, null, app, results(30.0, 40.0), 1300, 1)!!.pools.map { it.hashrate })
        val changed = app.copy(monitors = listOf(monitors[0].copy(addr = "prl1newaddress"), monitors[1]))
        assertNull(WidgetData.fetchedPools(changed, null, app, results(30.0, 40.0), 1300, 1))
    }

    @Test
    fun aNewTargetDoesNotInheritTheOldTargetsRetryDelay() {
        assertFalse(WidgetRules.retryAllowed(1000, "old", "old", 1001, 60))
        assertTrue(WidgetRules.retryAllowed(1000, "old", "new", 1001, 60))
        assertTrue(WidgetRules.retryAllowed(1000, "old", "old", 1060, 60))
    }

    @Test
    fun cachedPoolResponsesKeepTheirServerSnapshotTime() {
        assertEquals(900L, WidgetSources.poolTime(JSONObject("""{"ts":900}"""), 1300))
        assertEquals(1300L, WidgetSources.poolTime(JSONObject(), 1300))
        for (bad in listOf("\"900\"", "true", "null", "0", "1400", "900.5")) {
            assertThrows(WidgetSources.Invalid::class.java) { WidgetSources.poolTime(JSONObject("""{"ts":$bad}"""), 1300) }
        }
    }

    @Test
    fun anOlderServerSnapshotCannotReplaceNewerPublishedOrNativeFigures() {
        assertNull(WidgetData.fetchedPools(app, null, app, results(10.0, 20.0), at = 900, request = 1))
        val newer = WidgetData.fetchedPools(app, null, app, results(30.0, 40.0), at = 1300, request = 1)!!
        assertNull(WidgetData.fetchedPools(app, newer, app, results(10.0, 20.0), at = 1200, request = 2))
    }
}
