package com.prl.wizard

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * The home-screen widget snapshot (BRIDGE.md "Widget snapshot"): the two parts the page
 * publishes, what the shell fetched itself, and the merged state the widgets draw. Plain JVM
 * code (org.json only) so it is unit-tested. Times are Unix seconds; PRL amounts are atoms.
 */
data class WidgetPrice(val usd: Double, val change1h: Double?, val change24h: Double?, val at: Long)

data class WidgetFiat(val code: String, val symbol: String, val decimals: Int, val rate: Double)

/** One unpaused monitor's real-time figures; `hashrate` is H/s, null when unknown. */
data class WidgetPool(val label: String, val pool: String, val hashrate: Double?, val online: Int, val total: Int, val monitorKey: String? = null)

data class WidgetMonitor(val pool: String, val addr: String, val acct: String?, val label: String) {
    /** The web's targetKey: one pool + address + account. */
    val key: String get() = "$pool|$addr|${acct.orEmpty()}"
}

data class WidgetTx(val received: Boolean, val amount: Long, val time: Long)

data class AppPart(
    val lang: String?,
    val theme: String,
    val palette: String,
    val pro: Boolean,
    val price: WidgetPrice?,
    val fiat: WidgetFiat?,
    val pools: List<WidgetPool>,
    val monitors: List<WidgetMonitor>,
    val at: Long,
    val poolsAt: Long = at,
)

data class WalletPart(
    val name: String,
    val network: String,
    val xpub: String?,
    val changeAddresses: List<String>,
    val balance: Long,
    val pending: Long,
    val recent: List<WidgetTx>,
    val at: Long,
)

/**
 * Pools the shell fetched while closed. `base` is the published pools' source time; `source`
 * identifies their exact figures and monitor configuration, independently of app metadata.
 * `request` orders native attempts even when the clock changes or an older reply arrives last.
 */
data class LivePools(val base: Long, val at: Long, val complete: Boolean, val pools: List<WidgetPool>, val source: String, val request: Long = 0)

/** A fetched balance: `base`/`source` identify the wallet; `request` orders native attempts. */
data class LiveWallet(val xpub: String, val network: String, val base: Long, val balance: Long, val pending: Long, val at: Long, val source: String, val request: Long = 0)

/** The wallet as the widgets know it: never published, explicitly none, or a wallet. */
sealed interface WalletState {
    data object Unknown : WalletState
    data object None : WalletState
    data class Present(val part: WalletPart, val balance: Long, val pending: Long, val at: Long) : WalletState
}

/** Everything a widget draws, after merging the published parts with the shell's own fetches. */
data class WidgetState(
    val lang: String?,
    val theme: String,
    val palette: String,
    val pro: Boolean,
    val price: WidgetPrice?,
    val fiat: WidgetFiat?,
    val pools: List<WidgetPool>,
    val poolsAt: Long,
    val monitors: List<WidgetMonitor>,
    val wallet: WalletState,
    /** Last successful update of any figure (0 = nothing ever loaded): the freshness stamp. */
    val stamp: Long,
) {
    val hasApp: Boolean get() = poolsAt > 0 || price != null || monitors.isNotEmpty()
}

object WidgetData {
    val LANGS = setOf("en", "ru", "zh-Hans", "zh-Hant", "vi", "id", "es", "pt-BR", "fr", "de", "it", "tr", "ja", "ko", "hi", "th")
    const val MAX_JSON = 256 * 1024
    private const val MAX_POOLS = 64
    private const val MAX_RECENT = 5
    private const val MAX_CHANGE = 512
    private val BASE58 = Regex("[1-9A-HJ-NP-Za-km-z]{50,200}")
    /** A read-only wallet publishes the address it watches in the xpub's place (iOS WidgetFetch, prl-hub
     *  c14f2e6); Blockbook's /utxo/ answers an address as it does an xpub. Bech32 has "0" and "l", which
     *  Base58 lacks, so the two can't be confused. */
    private val WATCHED = Regex("t?prl1[02-9ac-hj-np-z]{20,100}")
    private fun walletKey(v: Any?) = (v as? String)?.takeIf { BASE58.matches(it) || WATCHED.matches(it) }
    private val ADDRESS = Regex("[a-z0-9]{10,120}")
    private val DIGITS = Regex("[0-9]{1,19}")
    private val FINGERPRINT = Regex("[0-9a-f]{64}")

    // region parsing (published parts and the stored copies, which use the same shape)

    fun parseApp(o: JSONObject): AppPart = AppPart(
        lang = (o.opt("lang") as? String)?.takeIf { it in LANGS },
        theme = (o.opt("theme") as? String)?.takeIf { it == "light" || it == "dark" } ?: "system",
        palette = if (o.opt("palette") == "mono") "mono" else "classic",
        pro = o.opt("pro") == true,
        price = (o.opt("price") as? JSONObject)?.let(::parsePrice),
        fiat = (o.opt("fiat") as? JSONObject)?.let(::parseFiat),
        pools = (o.opt("pools") as? JSONArray).objects().take(MAX_POOLS).mapNotNull(::parsePool),
        monitors = (o.opt("monitors") as? JSONArray).objects().take(MAX_POOLS).mapNotNull(::parseMonitor),
        at = time(o.opt("at")),
        poolsAt = time(if (o.has("poolsAt")) o.opt("poolsAt") else o.opt("at")),
    )

    /** Null for `data: null` (no wallet); throws nothing, bad fields fall back to safe values. */
    fun parseWallet(o: JSONObject?): WalletPart? {
        if (o == null) return null
        return WalletPart(
            name = text(o.opt("name"), 64),
            network = if (o.opt("network") == "testnet") "testnet" else "mainnet",
            xpub = walletKey(o.opt("xpub")),
            changeAddresses = (o.opt("changeAddresses") as? JSONArray).strings()
                .map { it.lowercase() }.filter { ADDRESS.matches(it) }.distinct().take(MAX_CHANGE),
            balance = atoms(o.opt("balance")) ?: 0,
            pending = atoms(o.opt("pending")) ?: 0,
            recent = (o.opt("recent") as? JSONArray).objects().mapNotNull(::parseTx).take(MAX_RECENT),
            at = time(o.opt("at")),
        )
    }

    fun parsePrice(o: JSONObject): WidgetPrice? {
        val usd = number(o.opt("usd"))?.takeIf { it > 0 } ?: return null
        return WidgetPrice(usd, number(o.opt("change1h")), number(o.opt("change24h")), time(o.opt("at") ?: o.opt("ts")))
    }

    private fun parseFiat(o: JSONObject): WidgetFiat? {
        val code = (o.opt("code") as? String)?.takeIf { it.length in 1..8 } ?: return null
        val symbol = (o.opt("symbol") as? String)?.takeIf { it.length in 1..8 } ?: return null
        val rate = number(o.opt("rate"))?.takeIf { it > 0 } ?: return null
        val decimals = number(o.opt("decimals"))?.toInt()?.coerceIn(0, 4) ?: 2
        return WidgetFiat(code, symbol, decimals, rate)
    }

    fun parsePool(o: JSONObject): WidgetPool? {
        val pool = text(o.opt("pool"), 40)
        val label = text(o.opt("label"), 64).ifEmpty { pool }
        if (label.isEmpty()) return null
        val online = count(o.opt("online"))
        val total = maxOf(count(o.opt("total")), online)
        val monitorKey = (o.opt("monitorKey") as? String)?.takeIf { it.isNotBlank() && it.length <= 306 }
        return WidgetPool(label, pool, number(o.opt("hashrate"))?.takeIf { it >= 0 }, online, total, monitorKey)
    }

    private fun parseMonitor(o: JSONObject): WidgetMonitor? {
        val pool = text(o.opt("pool"), 40).ifEmpty { return null }
        val addr = text(o.opt("addr"), 200).ifEmpty { return null }
        if (addr.any { it.isWhitespace() }) return null
        val acct = (o.opt("acct") as? String)?.trim()?.take(64)?.ifEmpty { null }
        return WidgetMonitor(pool, addr, acct, text(o.opt("label"), 64).ifEmpty { pool })
    }

    private fun parseTx(o: JSONObject): WidgetTx? {
        val amount = atoms(o.opt("amount")) ?: return null
        return WidgetTx(o.opt("received") == true, amount, time(o.opt("time")))
    }

    fun parseLivePools(o: JSONObject): LivePools? {
        val source = (o.opt("source") as? String)?.takeIf(FINGERPRINT::matches) ?: return null
        return LivePools(
            base = time(o.opt("base")),
            at = time(o.opt("at")),
            complete = o.opt("complete") == true,
            pools = (o.opt("pools") as? JSONArray).objects().take(MAX_POOLS).mapNotNull(::parsePool),
            source = source,
            request = requestNumber(o.opt("request")),
        )
    }

    fun parseLiveWallet(o: JSONObject): LiveWallet? {
        val xpub = walletKey(o.opt("xpub")) ?: return null
        val source = (o.opt("source") as? String)?.takeIf(FINGERPRINT::matches) ?: return null
        return LiveWallet(
            xpub = xpub,
            network = if (o.opt("network") == "testnet") "testnet" else "mainnet",
            base = time(o.opt("base")),
            balance = atoms(o.opt("balance")) ?: return null,
            pending = atoms(o.opt("pending")) ?: 0,
            at = time(o.opt("at")),
            source = source,
            request = requestNumber(o.opt("request")),
        )
    }

    // endregion

    // region storage form (the same keys the page publishes)

    fun json(p: AppPart): JSONObject = JSONObject()
        .put("lang", p.lang ?: JSONObject.NULL)
        .put("theme", p.theme)
        .put("palette", p.palette)
        .put("pro", p.pro)
        .put("price", p.price?.let(::json) ?: JSONObject.NULL)
        .put("fiat", p.fiat?.let { JSONObject().put("code", it.code).put("symbol", it.symbol).put("decimals", it.decimals).put("rate", it.rate) } ?: JSONObject.NULL)
        .put("pools", JSONArray(p.pools.map(::json)))
        .put("monitors", JSONArray(p.monitors.map { JSONObject().put("pool", it.pool).put("addr", it.addr).put("acct", it.acct ?: JSONObject.NULL).put("label", it.label) }))
        .put("at", p.at)
        .put("poolsAt", p.poolsAt)

    fun json(p: WalletPart): JSONObject = JSONObject()
        .put("name", p.name)
        .put("network", p.network)
        .put("xpub", p.xpub ?: JSONObject.NULL)
        .put("changeAddresses", JSONArray(p.changeAddresses))
        .put("balance", p.balance.toString())
        .put("pending", p.pending.toString())
        .put("recent", JSONArray(p.recent.map { JSONObject().put("received", it.received).put("amount", it.amount.toString()).put("time", it.time) }))
        .put("at", p.at)

    fun json(p: WidgetPrice): JSONObject = JSONObject()
        .put("usd", p.usd).put("change1h", p.change1h ?: JSONObject.NULL).put("change24h", p.change24h ?: JSONObject.NULL).put("at", p.at)

    fun json(p: WidgetPool): JSONObject = JSONObject()
        .put("label", p.label).put("pool", p.pool).put("hashrate", p.hashrate ?: JSONObject.NULL).put("online", p.online).put("total", p.total)
        .put("monitorKey", p.monitorKey ?: JSONObject.NULL)

    fun json(p: LivePools): JSONObject = JSONObject()
        .put("base", p.base).put("at", p.at).put("complete", p.complete).put("pools", JSONArray(p.pools.map(::json))).put("source", p.source).put("request", p.request)

    fun json(p: LiveWallet): JSONObject = JSONObject()
        .put("xpub", p.xpub).put("network", p.network).put("base", p.base)
        .put("balance", p.balance.toString()).put("pending", p.pending.toString()).put("at", p.at).put("source", p.source).put("request", p.request)

    /** Pool identity survives metadata publications, but detects even same-second pool edits. */
    fun fingerprint(p: AppPart): String {
        val normalized = json(p)
        return fingerprint(JSONObject()
            .put("pools", normalized.getJSONArray("pools"))
            .put("monitors", normalized.getJSONArray("monitors"))
            .put("poolsAt", p.poolsAt))
    }

    /** The complete wallet snapshot detects same-second balance and configuration changes. */
    fun fingerprint(p: WalletPart): String = fingerprint(json(p))

    private fun fingerprint(snapshot: JSONObject): String = MessageDigest.getInstance("SHA-256")
        .digest(snapshot.toString().toByteArray(Charsets.UTF_8))
        .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    /** Apply one native pool fetch against the publication and native cache current at save time. */
    fun fetchedPools(current: AppPart, previous: LivePools?, source: AppPart, results: List<WidgetSources.PoolResult?>, at: Long, request: Long): LivePools? {
        val identity = fingerprint(source)
        if (fingerprint(current) != identity || results.all { it == null } || at <= current.poolsAt) return null
        val cached = previous?.takeIf { it.base == current.poolsAt && it.source == identity && it.at > current.poolsAt }
        if (cached != null && (request <= cached.request || at < cached.at)) return null
        val pools = WidgetSources.refreshedPools(source.monitors.distinctBy { it.key }, results, cached?.pools ?: current.pools)
        return LivePools(current.poolsAt, at, results.none { it == null }, pools, identity, request)
    }

    /** Apply one native balance fetch only to the exact wallet publication it read. */
    fun fetchedWallet(current: WalletPart, previous: LiveWallet?, incoming: LiveWallet): LiveWallet? {
        if (current.xpub != incoming.xpub || current.network != incoming.network || current.at != incoming.base || incoming.source != fingerprint(current)) return null
        val cached = previous?.takeIf { it.base == incoming.base && it.source == incoming.source && it.xpub == incoming.xpub && it.network == incoming.network }
        if (cached != null && incoming.request <= cached.request) return null
        return incoming
    }

    // endregion

    /**
     * The state the widgets draw. A fetched figure replaces a published one only when it is newer
     * and its published source has not changed since the fetch started (WidgetBridge.swift:
     * a slow refresh must not overwrite newer app figures or resurrect a removed wallet).
     */
    fun merge(
        app: AppPart?,
        wallet: WalletPart?,
        walletKnown: Boolean,
        livePrice: WidgetPrice?,
        livePools: LivePools?,
        liveWallet: LiveWallet?,
    ): WidgetState {
        val price = listOfNotNull(app?.price, livePrice).maxByOrNull { it.at }
        val publishedPoolsAt = app?.poolsAt ?: 0
        val pools: List<WidgetPool>
        val poolsAt: Long
        if (livePools != null && app != null && livePools.base == publishedPoolsAt && livePools.source == fingerprint(app) && livePools.at > publishedPoolsAt) {
            pools = livePools.pools
            poolsAt = if (livePools.complete) livePools.at else publishedPoolsAt
        } else {
            pools = app?.pools.orEmpty()
            poolsAt = publishedPoolsAt
        }
        val walletState = when {
            wallet != null -> {
                val live = liveWallet?.takeIf {
                    it.xpub == wallet.xpub && it.network == wallet.network && it.base == wallet.at &&
                        it.source == fingerprint(wallet) && it.at > wallet.at
                }
                if (live != null) WalletState.Present(wallet, live.balance, live.pending, live.at)
                else WalletState.Present(wallet, wallet.balance, wallet.pending, wallet.at)
            }
            walletKnown -> WalletState.None
            else -> WalletState.Unknown
        }
        val walletAt = (walletState as? WalletState.Present)?.at ?: 0
        val stamp = maxOf(poolsAt, walletAt, price?.at ?: 0)
        return WidgetState(
            lang = app?.lang,
            theme = app?.theme ?: "system",
            palette = app?.palette ?: "classic",
            pro = app?.pro == true,
            price = price,
            fiat = app?.fiat,
            pools = pools,
            poolsAt = poolsAt,
            monitors = app?.monitors.orEmpty(),
            wallet = walletState,
            stamp = stamp,
        )
    }

    // region field helpers

    /** A finite JSON number (not a boolean, not a string). */
    fun number(v: Any?): Double? = (v as? Number)?.toDouble()?.takeIf { it.isFinite() }

    private fun time(v: Any?): Long = number(v)?.takeIf { it > 0 && it < 1e11 }?.toLong() ?: 0

    private fun requestNumber(v: Any?): Long = number(v)?.takeIf { it >= 0 && it <= 9_007_199_254_740_991.0 && it % 1.0 == 0.0 }?.toLong() ?: 0

    private fun count(v: Any?): Int = number(v)?.takeIf { it >= 0 }?.toInt()?.coerceAtMost(1_000_000) ?: 0

    private fun text(v: Any?, max: Int): String = ((v as? String) ?: "").trim().take(max)

    /** An atom string ("6361479714"); null when it is not a non-negative integer that fits a Long. */
    fun atoms(v: Any?): Long? {
        val s = v as? String ?: return null
        if (!DIGITS.matches(s)) return null
        return s.toLongOrNull()
    }

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { opt(it) as? JSONObject }

    private fun JSONArray?.strings(): List<String> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { opt(it) as? String }

    // endregion
}
