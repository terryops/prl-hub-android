package com.prl.wizard

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetDataTest {
    private val xpub = "zpub6rFR7y4Q2AijBEqTUquhVz398htDFrtymD9xYYfG1m4wAcvPhXNfE3EfH1r1ADqtfSdVCToUG868RvUUkgDKf31mGDtKsAYz2oz2AGutZYs"

    private val appJson = """
        { "lang": "zh-Hans", "theme": "system", "palette": "classic", "pro": true,
          "price": { "usd": 1.85, "change1h": -1.07, "change24h": 4.52, "at": 1792116928 },
          "fiat": { "code": "CNY", "symbol": "¥", "decimals": 2, "rate": 7.1 },
          "pools": [ { "label": "Rig A", "pool": "AlphaPool", "hashrate": 1230000000, "online": 3, "total": 4 } ],
          "monitors": [ { "pool": "AlphaPool", "addr": "prl1qexample", "acct": null, "label": "Rig A" } ],
          "at": 1792116928 }
    """

    private fun walletJson(extra: String = "") = """
        { "name": "My wallet", "network": "mainnet", "xpub": "$xpub", "changeAddresses": ["PRL1QCHANGE0ADDRESS", "bad address!", "prl1qchange0address"],
          "balance": "6361479714", "pending": "0",
          "recent": [ { "received": true, "amount": "100000000", "time": 1792116000 } ],
          "at": 1792116928 $extra }
    """

    @Test
    fun appPartFromTheBridgeExample() {
        val app = WidgetData.parseApp(JSONObject(appJson))
        assertEquals("zh-Hans", app.lang)
        assertEquals("system", app.theme)
        assertEquals("classic", app.palette)
        assertTrue(app.pro)
        assertEquals(WidgetPrice(1.85, -1.07, 4.52, 1792116928), app.price)
        assertEquals(WidgetFiat("CNY", "¥", 2, 7.1), app.fiat)
        assertEquals(listOf(WidgetPool("Rig A", "AlphaPool", 1.23e9, 3, 4)), app.pools)
        assertEquals(listOf(WidgetMonitor("AlphaPool", "prl1qexample", null, "Rig A")), app.monitors)
        assertEquals(1792116928L, app.at)
    }

    @Test
    fun appPartWithMissingAndNullFields() {
        val app = WidgetData.parseApp(JSONObject("""{ "lang": "xx", "theme": "sepia", "palette": 3, "pro": "yes", "price": null, "fiat": null }"""))
        assertNull(app.lang)
        assertEquals("system", app.theme)
        assertEquals("classic", app.palette)
        assertFalse(app.pro)
        assertNull(app.price)
        assertNull(app.fiat)
        assertTrue(app.pools.isEmpty())
        assertTrue(app.monitors.isEmpty())
        assertEquals(0L, app.at)

        val empty = WidgetData.parseApp(JSONObject("{}"))
        assertEquals(WidgetData.parseApp(JSONObject("""{ "theme": null, "palette": null, "pools": null, "monitors": null, "at": null }""")), empty)
        assertEquals("mono", WidgetData.parseApp(JSONObject("""{ "palette": "mono", "theme": "dark" }""")).palette)
        assertEquals("dark", WidgetData.parseApp(JSONObject("""{ "theme": "dark" }""")).theme)
    }

    @Test
    fun badFiguresFallBackSafely() {
        val app = WidgetData.parseApp(
            JSONObject(
                """{ "price": { "usd": 0, "at": 5 }, "fiat": { "code": "CNY", "symbol": "¥", "rate": -1 },
                     "pools": [ { "label": "", "pool": "" }, { "label": "B", "pool": "Kryptex", "hashrate": null, "online": 5, "total": 2 }, 7 ],
                     "monitors": [ { "pool": "AlphaPool" }, { "pool": "F2Pool", "addr": "a b" }, { "pool": "F2Pool", "addr": "abc", "acct": " me " } ] }""",
            ),
        )
        assertNull(app.price)
        assertNull(app.fiat)
        // An unknown hashrate stays unknown; total never reads below online.
        assertEquals(listOf(WidgetPool("B", "Kryptex", null, 5, 5)), app.pools)
        assertEquals(listOf(WidgetMonitor("F2Pool", "abc", "me", "F2Pool")), app.monitors)
        assertEquals("F2Pool|abc|me", app.monitors.single().key)
        // Changes may be missing or null; the fiat decimals default to two.
        assertEquals(WidgetPrice(2.0, null, null, 9), WidgetData.parsePrice(JSONObject("""{ "usd": 2, "change1h": null, "at": 9 }""")))
        assertEquals(2, WidgetData.parseApp(JSONObject("""{ "fiat": { "code": "EUR", "symbol": "€", "rate": 0.9 } }""")).fiat!!.decimals)
    }

    @Test
    fun walletPart() {
        val wallet = WidgetData.parseWallet(JSONObject(walletJson()))!!
        assertEquals("My wallet", wallet.name)
        assertEquals("mainnet", wallet.network)
        assertEquals(xpub, wallet.xpub)
        assertEquals(listOf("prl1qchange0address"), wallet.changeAddresses)
        assertEquals(6361479714L, wallet.balance)
        assertEquals(0L, wallet.pending)
        assertEquals(listOf(WidgetTx(true, 100000000, 1792116000)), wallet.recent)
    }

    @Test
    fun walletNullMeansNoWallet() {
        assertNull(WidgetData.parseWallet(null))
        val state = WidgetData.merge(null, null, walletKnown = true, null, null, null)
        assertEquals(WalletState.None, state.wallet)
        assertEquals(WalletState.Unknown, WidgetData.merge(null, null, walletKnown = false, null, null, null).wallet)
        assertEquals(0L, state.stamp)
    }

    /** A read-only wallet publishes the address it watches where the xpub goes (iOS WidgetFetch, c14f2e6). */
    @Test
    fun readOnlyWalletPublishesItsAddress() {
        val addr = "prl1pjwc8ys8m7zsg8yuu3xejlw3tfg5fx9vq4uk4thfqu3k8vqh0s5dqmv2sqk"
        val wallet = WidgetData.parseWallet(JSONObject("""{ "name": "Watched", "network": "mainnet", "xpub": "$addr", "changeAddresses": [], "balance": "5", "pending": "0", "at": 1792116928 }"""))!!
        assertEquals(addr, wallet.xpub)
        assertEquals(wallet, WidgetData.parseWallet(WidgetData.json(wallet)))
        val testnet = "tprl1pjwc8ys8m7zsg8yuu3xejlw3tfg5fx9vq4uk4thfqu3k8vqh0s5dqqqqqqq"
        assertEquals(testnet, WidgetData.parseWallet(JSONObject("""{ "network": "testnet", "xpub": "$testnet" }"""))!!.xpub)
        // Upper case, other prefixes and Base58-only letters are neither kind.
        for (bad in listOf(addr.uppercase(), "bc1qexampleexampleexampleexample", "prl1bad!address")) {
            assertNull(WidgetData.parseWallet(JSONObject("""{ "xpub": "$bad" }"""))!!.xpub)
        }
    }

    @Test
    fun walletWithBadFields() {
        val wallet = WidgetData.parseWallet(
            JSONObject(
                """{ "network": "testnet", "xpub": "zpub/../x", "balance": "-5", "pending": 12,
                     "recent": [ { "received": "yes", "amount": "1", "time": 3 }, { "amount": "x" }, {"amount": "2"}, {"amount": "3"}, {"amount": "4"}, {"amount": "5"}, {"amount": "6"} ] }""",
            ),
        )!!
        assertEquals("testnet", wallet.network)
        assertNull(wallet.xpub)
        assertEquals(0L, wallet.balance)
        assertEquals(0L, wallet.pending)
        assertEquals(5, wallet.recent.size)
        assertFalse(wallet.recent.first().received)
        assertNull(WidgetData.atoms("99999999999999999999"))
        assertEquals(42L, WidgetData.atoms("42"))
    }

    @Test
    fun storedCopiesRoundTrip() {
        val app = WidgetData.parseApp(JSONObject(appJson))
        assertEquals(app, WidgetData.parseApp(WidgetData.json(app)))
        val wallet = WidgetData.parseWallet(JSONObject(walletJson()))!!
        assertEquals(wallet, WidgetData.parseWallet(WidgetData.json(wallet)))
        val pools = LivePools(1, 2, true, app.pools, WidgetData.fingerprint(app))
        assertEquals(pools, WidgetData.parseLivePools(WidgetData.json(pools)))
        val live = LiveWallet(xpub, "mainnet", 1, 2, 3, 4, WidgetData.fingerprint(wallet))
        assertEquals(live, WidgetData.parseLiveWallet(WidgetData.json(live)))
    }

    @Test
    fun fetchedFiguresOnlyReplaceOlderOnes() {
        val app = WidgetData.parseApp(JSONObject(appJson))
        val wallet = WidgetData.parseWallet(JSONObject(walletJson()))!!
        // A newer fetched price wins; an older one does not.
        val newer = WidgetPrice(1.9, 1.0, 2.0, app.at + 600)
        assertEquals(newer, WidgetData.merge(app, wallet, true, newer, null, null).price)
        assertEquals(app.price, WidgetData.merge(app, wallet, true, newer.copy(at = app.at - 1), null, null).price)

        // Pools fetched from the current monitors: complete → the fetch time is the pools' time.
        val fetched = listOf(WidgetPool("Rig A", "AlphaPool", 2e9, 4, 4))
        val complete = WidgetData.merge(app, wallet, true, null, LivePools(app.at, app.at + 900, true, fetched, WidgetData.fingerprint(app)), null)
        assertEquals(fetched, complete.pools)
        assertEquals(app.at + 900, complete.poolsAt)
        assertEquals(app.at + 900, complete.stamp)
        // Partial: the figures show, but the pools keep the page's time.
        assertEquals(app.at, WidgetData.merge(app, wallet, true, null, LivePools(app.at, app.at + 900, false, fetched, WidgetData.fingerprint(app)), null).poolsAt)
        // The published pool source has changed since the fetch began: its pools stand.
        assertEquals(app.pools, WidgetData.merge(app, wallet, true, null, LivePools(app.at - 5, app.at + 900, true, fetched, WidgetData.fingerprint(app)), null).pools)

        // A fetched balance applies to the same wallet only.
        val live = LiveWallet(xpub, "mainnet", wallet.at, 7, 1, wallet.at + 300, WidgetData.fingerprint(wallet))
        val present = WidgetData.merge(app, wallet, true, null, null, live).wallet as WalletState.Present
        assertEquals(7L, present.balance)
        assertEquals(1L, present.pending)
        assertEquals(wallet.at + 300, present.at)
        val other = WidgetData.merge(app, wallet, true, null, null, live.copy(network = "testnet")).wallet as WalletState.Present
        assertEquals(wallet.balance, other.balance)
        val republished = WidgetData.merge(app, wallet, true, null, null, live.copy(base = wallet.at - 1)).wallet as WalletState.Present
        assertEquals(wallet.balance, republished.balance)
    }

    @Test
    fun changingMonitorsWithinOneSecondInvalidatesThePreviousFetch() {
        val app = WidgetData.parseApp(JSONObject(appJson))
        val fetched = LivePools(app.at, app.at + 300, true, listOf(WidgetPool("Old target", "AlphaPool", 8e9, 8, 8)), WidgetData.fingerprint(app))
        val changed = app.copy(
            monitors = listOf(WidgetMonitor("HeroMiners", "prl1newtarget", null, "New target")),
        )
        assertEquals(changed.pools, WidgetData.merge(changed, null, false, null, fetched, null).pools)
    }

    @Test
    fun metadataPublicationsPreserveTheLatestNativePoolCache() {
        val app = WidgetData.parseApp(JSONObject(appJson))
        val previous = listOf(app.pools[0].copy(hashrate = 8e9))
        val cache = LivePools(app.poolsAt, app.at + 300, true, previous, WidgetData.fingerprint(app))
        val publications = listOf(
            app.copy(at = app.at + 600),
            app.copy(price = app.price!!.copy(usd = 9.0, at = app.at + 600)),
            app.copy(theme = "dark", lang = "en", palette = "mono", pro = !app.pro,
                fiat = WidgetFiat("CNY", "¥", 2, 7.0)),
        )
        for (republished in publications) {
            assertEquals(WidgetData.fingerprint(app), WidgetData.fingerprint(republished))
            val shown = WidgetData.merge(republished, null, false, null, cache, null)
            assertEquals(previous, shown.pools)
            assertEquals(cache.at, shown.poolsAt)
            assertEquals(republished.theme, shown.theme)
            assertEquals(republished.lang, shown.lang)
            assertEquals(republished.price, shown.price)
        }
    }

    @Test
    fun changingThePoolSnapshotWithinOneSecondInvalidatesThePreviousFetch() {
        val app = WidgetData.parseApp(JSONObject(appJson))
        val cache = LivePools(app.poolsAt, app.at + 300, true,
            listOf(app.pools[0].copy(hashrate = 8e9)), WidgetData.fingerprint(app))
        val changedFigures = app.copy(pools = listOf(app.pools[0].copy(hashrate = 9e9)))
        assertEquals(changedFigures.pools, WidgetData.merge(changedFigures, null, false, null, cache, null).pools)
        val changedTime = app.copy(poolsAt = app.poolsAt + 1)
        assertEquals(changedTime.pools, WidgetData.merge(changedTime, null, false, null, cache, null).pools)
    }

    @Test
    fun publishingANewBalanceWithinOneSecondInvalidatesThePreviousFetch() {
        val wallet = WidgetData.parseWallet(JSONObject(walletJson()))!!
        val fetched = LiveWallet(xpub, "mainnet", wallet.at, 7, 1, wallet.at + 300, WidgetData.fingerprint(wallet))
        val changed = wallet.copy(balance = 9, pending = 2)
        val merged = WidgetData.merge(null, changed, true, null, null, fetched).wallet as WalletState.Present
        assertEquals(9L, merged.balance)
        assertEquals(2L, merged.pending)
    }

    @Test
    fun preFingerprintCachesAreDiscardedRatherThanGuessedFromOneSecondTimestamps() {
        val app = WidgetData.parseApp(JSONObject(appJson))
        val wallet = WidgetData.parseWallet(JSONObject(walletJson()))!!
        val oldPools = WidgetData.json(LivePools(app.at, app.at + 300, true, app.pools, WidgetData.fingerprint(app))).apply { remove("source") }
        val oldWallet = WidgetData.json(LiveWallet(xpub, "mainnet", wallet.at, 7, 1, wallet.at + 300, WidgetData.fingerprint(wallet))).apply { remove("source") }
        assertNull(WidgetData.parseLivePools(oldPools))
        assertNull(WidgetData.parseLiveWallet(oldWallet))
        assertEquals(WidgetData.fingerprint(app), WidgetData.fingerprint(WidgetData.parseApp(WidgetData.json(app))))
        assertEquals(WidgetData.fingerprint(wallet), WidgetData.fingerprint(WidgetData.parseWallet(WidgetData.json(wallet))!!))
    }

    @Test
    fun failedPoolRefreshRetainsTheLatestSuccessfulCache() {
        val app = WidgetData.parseApp(JSONObject(appJson)).let { original ->
            original.copy(
                pools = original.pools + WidgetPool("Rig B", "HeroMiners", 3e9, 3, 3),
                monitors = original.monitors + WidgetMonitor("HeroMiners", "prl1second", null, "Rig B"),
            )
        }
        val previous = listOf(app.pools[0].copy(hashrate = 8e9), app.pools[1].copy(hashrate = 9e9))
        val cache = LivePools(app.at, app.at + 300, true, previous, WidgetData.fingerprint(app))
        // A price/theme publication between background reads must not discard their latest data.
        val republished = app.copy(at = app.at + 400, theme = "dark", price = app.price!!.copy(usd = 9.0))
        val shown = WidgetData.merge(republished, null, false, null, cache, null)
        val refreshed = WidgetSources.refreshedPools(republished.monitors, listOf(null, WidgetSources.PoolResult.Live(10e9, 3, 3)), shown.pools)
        assertEquals(8e9, refreshed[0].hashrate!!, 0.0)
        assertEquals(10e9, refreshed[1].hashrate!!, 0.0)
        val partial = cache.copy(at = app.at + 600, complete = false, pools = refreshed)
        val merged = WidgetData.merge(republished, null, false, null, partial, null)
        assertEquals(refreshed, merged.pools)
        assertEquals(app.at, merged.poolsAt)
    }

    @Test
    fun poolFreshnessUsesTheStatsTimeRatherThanPublicationTime() {
        val original = WidgetData.parseApp(JSONObject(appJson))
        val app = original.copy(at = original.at + 3600, poolsAt = original.at)
        val shown = WidgetData.merge(app, null, false, null, null, null)
        assertEquals(original.at, shown.poolsAt)
        assertEquals(original.at, shown.stamp)
        assertTrue(WidgetRules.stale(shown.poolsAt, app.at))
        assertFalse(WidgetRules.recent(app.poolsAt, app.at))

        // A background read can complete within the same second as the metadata publication.
        val cache = LivePools(app.poolsAt, app.at, true, app.pools, WidgetData.fingerprint(app))
        assertEquals(app.at, WidgetData.merge(app, null, false, null, cache, null).poolsAt)
        assertEquals(original.at, WidgetData.merge(app, null, false, null, cache.copy(complete = false), null).poolsAt)
        assertEquals(app, WidgetData.parseApp(WidgetData.json(app)))
    }

    @Test
    fun poolSourceTimestampIsOptionalForOlderPublishersButExplicitUnknownStaysUnknown() {
        val legacy = WidgetData.parseApp(JSONObject(appJson))
        assertEquals(legacy.at, legacy.poolsAt)
        val missing = WidgetData.parseApp(JSONObject(appJson).put("poolsAt", 0))
        assertEquals(0L, missing.poolsAt)
        assertEquals(0L, WidgetData.merge(missing, null, false, null, null, null).poolsAt)
    }

    @Test
    fun mergedDefaults() {
        val state = WidgetData.merge(null, null, false, null, null, null)
        assertNull(state.lang)
        assertEquals("system", state.theme)
        assertEquals("classic", state.palette)
        assertFalse(state.pro)
        assertNull(state.price)
    }
}
