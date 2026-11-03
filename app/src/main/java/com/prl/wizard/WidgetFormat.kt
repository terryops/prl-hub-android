package com.prl.wizard

import org.json.JSONArray
import org.json.JSONObject
import java.math.RoundingMode
import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor

/**
 * Widget figures, formatted like the web app (web/src/format.ts, the wallet's balanceParts and
 * dualFiat) in the app language's locale. Plain JVM code so it is unit-tested.
 */
object WidgetFormat {
    const val DASH = "—"
    private const val ATOMS = 100_000_000L
    private val UNITS = listOf("EH/s" to 1e18, "PH/s" to 1e15, "TH/s" to 1e12, "GH/s" to 1e9, "MH/s" to 1e6, "kH/s" to 1e3)

    /** The app language (BCP 47, e.g. "zh-Hans", "pt-BR"); the device locale until the page says. */
    fun locale(lang: String?): Locale =
        if (lang != null && lang in WidgetData.LANGS) Locale.forLanguageTag(lang) else Locale.getDefault()

    /** `toLocaleString(lang, { minimumFractionDigits, maximumFractionDigits })`, rounding half away from zero. */
    fun number(value: Double, locale: Locale, min: Int, max: Int = min): String =
        NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = min
            maximumFractionDigits = max
            isGroupingUsed = true
            roundingMode = RoundingMode.HALF_UP
        }.format(value)

    private fun integer(value: Long, locale: Locale): String =
        NumberFormat.getIntegerInstance(locale).apply { isGroupingUsed = true }.format(value)

    private fun decimalSeparator(locale: Locale): Char = DecimalFormatSymbols.getInstance(locale).decimalSeparator

    /** format.ts `hashrate(x, lang)`. */
    fun hashrate(x: Double?, locale: Locale): String {
        if (x == null || !x.isFinite()) return DASH
        for ((unit, n) in UNITS) if (x >= n) return number(x / n, locale, 2) + " " + unit
        return "${floor(x + 0.5).toLong()} H/s"
    }

    /** format.ts `currencyAmount`: "$1.85", "−¥3", "NT$12.50". */
    fun money(symbol: String, value: Double, decimals: Int, locale: Locale): String {
        if (!value.isFinite()) return DASH
        return (if (value < 0) "−" else "") + symbol + number(abs(value), locale, decimals)
    }

    /** The live PRL price, two decimals (the iOS widget's priceLine); "—" until known. */
    fun price(price: WidgetPrice?, locale: Locale): String =
        price?.takeIf { it.usd > 0 }?.let { money("$", it.usd, 2, locale) } ?: DASH

    /**
     * The wallet's balance split: grouped whole PRL plus the first two decimals, then the rest of
     * the eight decimals with trailing zeros trimmed. Exact on atoms, truncated (never rounded).
     */
    fun balanceParts(atoms: Long, locale: Locale): Pair<String, String> {
        val a = if (atoms < 0) -atoms else atoms
        val fraction = (a % ATOMS).toString().padStart(8, '0').trimEnd('0').padEnd(2, '0')
        val head = (if (atoms < 0) "−" else "") + integer(a / ATOMS, locale) + decimalSeparator(locale) + fraction.take(2)
        return head to fraction.drop(2)
    }

    /** format.ts `prl(value, digits, lang)`: truncated to `digits` decimals, trailing zeros trimmed. */
    fun prl(atoms: Long, digits: Int, locale: Locale): String {
        val a = if (atoms < 0) -atoms else atoms
        val fraction = (a % ATOMS).toString().padStart(8, '0').take(digits).trimEnd('0')
        return (if (atoms < 0) "−" else "") + integer(a / ATOMS, locale) +
            (if (fraction.isEmpty()) "" else decimalSeparator(locale) + fraction)
    }

    /** "≈ $33.08 · ¥224.53" (the wallet's dualFiat); null until a price is known. */
    fun fiatLine(atoms: Long, price: WidgetPrice?, fiat: WidgetFiat?, locale: Locale): String? {
        val usdPrice = price?.usd?.takeIf { it > 0 } ?: return null
        val usd = atoms.toDouble() / ATOMS * usdPrice
        if (!usd.isFinite()) return null
        var line = "≈ " + money("$", usd, 2, locale)
        if (fiat != null && fiat.rate > 0 && (usd * fiat.rate).isFinite()) line += " · " + money(fiat.symbol, usd * fiat.rate, fiat.decimals, locale)
        return line
    }

    /** Rounded to two decimals first, so a tiny decline reads "0.00%", never "−0.00%". */
    private fun rounded(value: Double, decimals: Int): Double {
        var scale = 1.0
        repeat(decimals) { scale *= 10 }
        val magnitude = floor(abs(value) * scale + 0.5) / scale
        return if (value < 0) -magnitude else magnitude
    }

    /** "+1.60%", "−0.84%", "0.00%", "—". */
    fun change(value: Double?, locale: Locale, decimals: Int = 2): String {
        if (value == null || !value.isFinite()) return DASH
        val r = rounded(value, decimals)
        return (if (r > 0) "+" else if (r < 0) "−" else "") + number(abs(r), locale, decimals) + "%"
    }

    /** 1 up, −1 down, 0 flat or unknown — decided on the rounded value like [change]. */
    fun direction(value: Double?, decimals: Int = 2): Int {
        if (value == null || !value.isFinite()) return 0
        val r = rounded(value, decimals)
        return if (r > 0) 1 else if (r < 0) -1 else 0
    }
}

/**
 * Totals across the enabled monitors (the iOS WidgetMiningSummary). A partial subtotal would read
 * as lost hashrate, so totals stay unknown until every monitor has figures.
 */
data class MiningSummary(val monitorCount: Int, val knownCount: Int, val hashrate: Double?, val online: Int?, val total: Int?) {
    fun hashrateText(locale: Locale): String = WidgetFormat.hashrate(hashrate, locale)
    val workersText: String get() = if (online != null && total != null) "$online/$total" else WidgetFormat.DASH

    companion object {
        fun of(pools: List<WidgetPool>, monitors: List<WidgetMonitor>): MiningSummary {
            val targets = mutableSetOf<String>()
            val figures = pools.filter { it.monitorKey?.let(targets::add) ?: true }
            val count = maxOf(monitors.distinctBy { it.key }.size, figures.size)
            val known = figures.filter { it.hashrate != null && it.total >= it.online }
            val complete = count > 0 && known.size >= count
            return MiningSummary(
                monitorCount = count,
                knownCount = known.size.coerceAtMost(count),
                hashrate = if (complete) known.sumOf { it.hashrate ?: 0.0 } else null,
                online = if (complete) known.sumOf { it.online } else null,
                total = if (complete) known.sumOf { it.total } else null,
            )
        }
    }
}

/** Sizes, taps, freshness and redraw times: the decisions behind each widget. */
object WidgetRules {
    const val FRESH_FOR = 120L          // a part published this recently is reused, not fetched
    const val FRESH_BADGE_MINUTES = 5L  // the stamp's check badge
    const val STALE_AFTER = 15 * 60L    // quote / pools figures dim after this

    val KINDS = listOf("hub", "price", "quote", "change1h", "change24h", "quoteMining", "coverMarket", "coverMining")

    enum class HubSize { SMALL, MEDIUM, LARGE }
    enum class MarketSize { MINI, COMPACT, REGULAR, COVER }

    /** Where a tap goes (BRIDGE.md "Widget snapshot"). */
    enum class Target(val route: String) { PRICE("trade"), WALLET("wallet"), POOLS("workers"), LOCKED("pro") }

    // Launcher sizes in dp: a 2-cell side is ~110 dp at the smallest grids; Pixel's 2×2 is ~180.
    const val MEDIUM_MIN_WIDTH = 230
    const val LARGE_MIN_HEIGHT = 250
    const val COMPACT_MAX_HEIGHT = 140
    const val MARKET_MIN_WIDTH = 110
    const val COVER_MIN_WIDTH = 230
    const val COVER_MIN_HEIGHT = 250

    fun hubSize(widthDp: Int, heightDp: Int): HubSize = when {
        widthDp in 1 until MEDIUM_MIN_WIDTH -> HubSize.SMALL
        heightDp >= LARGE_MIN_HEIGHT -> HubSize.LARGE
        else -> HubSize.MEDIUM
    }

    /** One launcher row: the price, quote and quote-and-mining widgets switch to one-line rows. */
    fun compact(heightDp: Int): Boolean = heightDp in 1 until COMPACT_MAX_HEIGHT

    /** Cover hosts may report narrow/tall or wide/short slots, independently of the main display. */
    fun marketSize(widthDp: Int, heightDp: Int, cover: Boolean = false): MarketSize = when {
        widthDp in 1 until MARKET_MIN_WIDTH -> MarketSize.MINI
        cover && widthDp >= COVER_MIN_WIDTH && heightDp >= COVER_MIN_HEIGHT -> MarketSize.COVER
        compact(heightDp) -> MarketSize.COMPACT
        else -> MarketSize.REGULAR
    }

    /** Medium and large Pearl Hub widgets are Pro, as on iOS; the small one is free. */
    fun locked(size: HubSize, pro: Boolean): Boolean = size != HubSize.SMALL && !pro

    fun ageMinutes(stamp: Long, now: Long): Long = maxOf(0, (now - stamp) / 60)

    /** The badge: refreshed within the last five whole minutes. */
    fun fresh(stamp: Long, now: Long): Boolean = stamp > 0 && ageMinutes(stamp, now) <= FRESH_BADGE_MINUTES

    fun stale(stamp: Long, now: Long): Boolean = stamp <= 0 || now - stamp >= STALE_AFTER

    /** A published part that is recent enough to reuse instead of fetching (WidgetBridge.freshFor). */
    fun recent(at: Long, now: Long): Boolean = at > 0 && now - at in -60 until FRESH_FOR

    /** A wall-clock correction must not suppress requests until the old clock catches up. */
    fun retryAllowed(lastAttempt: Long, now: Long, delay: Long): Boolean =
        lastAttempt <= 0 || now - lastAttempt !in 0 until delay

    /** Retargeting must not inherit the old target's retry delay. */
    fun retryAllowed(lastAttempt: Long, lastSource: String?, source: String, now: Long, delay: Long): Boolean =
        lastSource != source || retryAllowed(lastAttempt, now, delay)

    /**
     * When a placed widget's text next changes without new data: the relative stamps tick by the
     * minute in their first hour, then by the hour, then by the day; quote figures dim at the stale
     * mark; the date flips at midnight. Null when nothing will change.
     */
    fun nextRedraw(now: Long, relative: List<Long>, staleMarks: List<Long>, midnight: Long?): Long? {
        val times = mutableListOf<Long>()
        for (s in relative) {
            if (s <= 0) continue
            val age = maxOf(0, now - s)
            val step = when {
                age < 3600 -> 60L
                age < 86_400 -> 3600L
                else -> 86_400L
            }
            times += s + (age / step + 1) * step
        }
        for (s in staleMarks) if (s > 0 && s + STALE_AFTER > now) times += s + STALE_AFTER
        if (midnight != null && midnight > now) times += midnight
        return times.minOrNull()?.coerceAtLeast(now + 1)
    }
}

/** Replies of the shell's own fetches, checked like the web and the wallet check them. */
object WidgetSources {
    class Invalid(message: String) : Exception(message)

    sealed interface PoolResult {
        data object Missing : PoolResult
        data class Live(val hashrate: Double, val online: Int, val total: Int) : PoolResult
    }

    private val ORDER = listOf("live", "m15", "m30", "hour", "h3", "h6", "day")
    private val TXID = Regex("[a-f0-9]{64}")
    private val VALUE = Regex("[0-9]{1,19}")

    /** monitors.ts `nearestWindow`. */
    fun nearestWindow(selected: String, available: List<String>): String {
        val mine = maxOf(0, ORDER.indexOf(selected))
        return available.filter { it in ORDER }.sortedWith(
            compareBy<String>({ abs(ORDER.indexOf(it) - mine) }, { ORDER.indexOf(it) }),
        ).firstOrNull() ?: "day"
    }

    /** format.ts `rate`: the workers' sum for the window, else the account figure. */
    fun rate(stats: JSONObject, window: String): Double {
        val workers = stats.optJSONArray("workers")
        var sum = 0.0
        if (workers != null) for (i in 0 until workers.length()) {
            sum += WidgetData.number(workers.optJSONObject(i)?.optJSONObject("rates")?.opt(window))?.takeIf { it > 0 } ?: 0.0
        }
        return if (sum > 0) sum else WidgetData.number(stats.optJSONObject("rates")?.opt(window))?.takeIf { it > 0 } ?: 0.0
    }

    /** `POST /v1/pool/stats` → the real-time figures the page publishes for a monitor. */
    fun poolStats(o: JSONObject): PoolResult {
        if (o.opt("ok") != true) throw Invalid("pool stats not ok")
        if (o.opt("missing") == true) return PoolResult.Missing
        val windows = o.optJSONArray("windows")?.let { a -> (0 until a.length()).map { a.opt(it) as? String ?: throw Invalid("pool window") } }
            ?.takeIf { w -> w.any { it in ORDER } } ?: throw Invalid("pool windows")
        fun checkRates(rates: JSONObject?) {
            if (rates == null) throw Invalid("pool rates")
            for (window in ORDER) if (rates.has(window) && (WidgetData.number(rates.opt(window))?.takeIf { it >= 0 } == null)) throw Invalid("pool rate")
        }
        checkRates(o.optJSONObject("rates"))
        val workers = o.optJSONArray("workers") ?: throw Invalid("pool workers")
        var online = 0
        for (i in 0 until workers.length()) {
            val worker = workers.optJSONObject(i) ?: throw Invalid("pool worker")
            if (worker.opt("online") !is Boolean) throw Invalid("pool worker online")
            if (worker.opt("online") == true) online++
            checkRates(worker.optJSONObject("rates"))
        }
        val hashrate = rate(o, nearestWindow("live", windows)).takeIf { it.isFinite() } ?: throw Invalid("pool rate overflow")
        return PoolResult.Live(hashrate, online, workers.length())
    }

    /** Cached API replies keep their upstream snapshot time; older replies may omit it. */
    fun poolTime(o: JSONObject, requestedAt: Long): Long {
        if (!o.has("ts")) return requestedAt
        return WidgetData.number(o.opt("ts"))
            ?.takeIf { it > 0 && it == floor(it) && it <= requestedAt + 60 }
            ?.toLong() ?: throw Invalid("pool snapshot time")
    }

    /** Keep the latest successful figures for failed targets, in monitor order. */
    fun refreshedPools(monitors: List<WidgetMonitor>, results: List<PoolResult?>, previous: List<WidgetPool>): List<WidgetPool> {
        require(monitors.size == results.size)
        val labels = monitors.groupingBy { it.label to it.pool }.eachCount()
        // The page's original snapshot has no target keys, but when every target is present its
        // monitor order is enough. A partial snapshot with duplicate labels cannot be identified.
        val ordered = previous.size == monitors.size && previous.zip(monitors).all { (pool, monitor) ->
            pool.monitorKey == null && pool.label == monitor.label && pool.pool == monitor.pool
        }
        return monitors.zip(results).mapIndexedNotNull { index, (monitor, result) ->
            val prior = previous.firstOrNull { it.monitorKey == monitor.key }
                ?: if (ordered) previous[index] else if (labels[monitor.label to monitor.pool] == 1) {
                    previous.singleOrNull { it.monitorKey == null && it.label == monitor.label && it.pool == monitor.pool }
                } else null
            when (result) {
                is PoolResult.Live -> WidgetPool(monitor.label, monitor.pool, result.hashrate, result.online, result.total, monitor.key)
                PoolResult.Missing -> null
                null -> prior?.copy(monitorKey = monitor.key)
            }
        }
    }

    data class Utxo(val txid: String, val vout: Int, val value: Long, val confirmations: Int)

    /** One `/api/v2/utxo/…` reply, validated like the wallet's mergeUTXOs. */
    fun utxos(a: JSONArray): List<Utxo> = (0 until a.length()).map { i ->
        val o = a.optJSONObject(i) ?: throw Invalid("utxo not an object")
        val txid = o.opt("txid") as? String
        val vout = WidgetData.number(o.opt("vout"))
        val value = o.opt("value") as? String
        val rawConfirmations = o.opt("confirmations")
        val confirmations = if (rawConfirmations == null || rawConfirmations == JSONObject.NULL) 0.0
            else WidgetData.number(rawConfirmations) ?: throw Invalid("utxo confirmations")
        if (txid == null || !TXID.matches(txid) || vout == null || vout < 0 || vout != floor(vout) || vout > Int.MAX_VALUE ||
            value == null || !VALUE.matches(value) || confirmations < 0 || confirmations > 9_007_199_254_740_991.0 || confirmations != floor(confirmations)
        ) throw Invalid("utxo fields")
        Utxo(txid, vout.toInt(), value.toLongOrNull() ?: throw Invalid("utxo value"), confirmations.toInt())
    }

    /**
     * The xpub's and the change addresses' outputs, de-duplicated by txid:vout: confirmed outputs
     * are the balance, unconfirmed ones pending (chain.ts loadAccount). Returns balance to pending.
     */
    fun balance(groups: List<List<Utxo>>): Pair<Long, Long> {
        val seen = LinkedHashMap<String, Utxo>()
        for (group in groups) for (u in group) {
            val key = "${u.txid}:${u.vout}"
            val old = seen[key]
            if (old != null && old.value != u.value) throw Invalid("utxo mismatch")
            seen[key] = if (old != null && old.confirmations > u.confirmations) old else u
        }
        var balance = 0L
        var pending = 0L
        for (u in seen.values) {
            if (u.confirmations > 0) balance = Math.addExact(balance, u.value) else pending = Math.addExact(pending, u.value)
        }
        return balance to pending
    }
}
