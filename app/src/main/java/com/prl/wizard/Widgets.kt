package com.prl.wizard

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.core.content.edit
import org.json.JSONObject
import java.util.Calendar
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The home-screen widgets (BRIDGE.md `widgets.update`, `widgets.pin`, "Widget snapshot"): keeps
 * the page's latest `app` and `wallet` parts and the shell's own fetches in an app-private
 * SharedPreferences file (backups exclude all app data), redraws every placed widget, and keeps
 * the refresh worker and the redraw clock running only while a widget is on the home screen.
 */
object Widgets {
    private const val TAG = "PearlWidgets"
    private const val PREFS = "widgets"
    private const val APP = "app"
    private const val WALLET = "wallet"
    private const val LIVE_PRICE = "live.price"
    private const val LIVE_POOLS = "live.pools"
    private const val LIVE_WALLET = "live.wallet"
    private const val TRIED = "tried."
    private const val TRIED_SOURCE = "tried.source."
    private const val REQUEST = "request"
    private const val ACTION_TICK = "com.prl.wizard.WIDGET_TICK"

    enum class Kind(val key: String, val provider: Class<out AppWidgetProvider>) {
        HUB("hub", HubWidget::class.java),
        PRICE("price", PriceWidget::class.java),
        QUOTE("quote", QuoteWidget::class.java),
        CHANGE_1H("change1h", Change1hWidget::class.java),
        CHANGE_24H("change24h", Change24hWidget::class.java),
        QUOTE_MINING("quoteMining", QuoteMiningWidget::class.java),
        COVER_MARKET("coverMarket", CoverMarketWidget::class.java),
        COVER_MINING("coverMining", CoverMiningWidget::class.java);

        companion object {
            fun of(key: String?): Kind? = entries.firstOrNull { it.key == key }
        }
    }

    /** What a placed widget needs fetched while the app is closed. */
    enum class Need { PRICE, POOLS, WALLET }

    private val marketKinds = listOf(Kind.QUOTE, Kind.CHANGE_1H, Kind.CHANGE_24H, Kind.COVER_MARKET)
    private val miningKinds = listOf(Kind.QUOTE_MINING, Kind.COVER_MINING)

    /** Ordered work: page updates, fetch results and redraws never interleave. */
    val work: ExecutorService = Executors.newSingleThreadExecutor()

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun now(): Long = System.currentTimeMillis() / 1000

    // region bridge

    /** `widgets.update`: false for bad params. Stores, redraws and refreshes off the main thread. */
    fun publish(context: Context, part: String, data: Any?): Boolean {
        val app = context.applicationContext
        when (part) {
            "app" -> {
                val o = data as? JSONObject ?: return false
                if (o.toString().length > WidgetData.MAX_JSON) return false
                val parsed = WidgetData.parseApp(o)
                work.execute { guarded("app update") { saveApp(app, parsed); changed(app, refresh = true) } }
            }
            "wallet" -> {
                val o = when (data) {
                    null, JSONObject.NULL -> null
                    is JSONObject -> data
                    else -> return false
                }
                if (o != null && o.toString().length > WidgetData.MAX_JSON) return false
                val parsed = WidgetData.parseWallet(o)
                work.execute { guarded("wallet update") { saveWallet(app, parsed); changed(app, refresh = true) } }
            }
            else -> return false
        }
        return true
    }

    /** `widgets.pin`: asks the launcher to place a widget. False when it cannot. */
    fun pin(context: Context, kind: Kind): Boolean {
        val manager = AppWidgetManager.getInstance(context)
        if (!manager.isRequestPinAppWidgetSupported) return false
        return try {
            manager.requestPinAppWidget(ComponentName(context, kind.provider), null, null)
        } catch (e: IllegalStateException) {
            // The app is not in the foreground (the launcher only takes requests from a visible app).
            Log.w(TAG, "Pin refused: ${e.message}")
            false
        }
    }

    // endregion

    // region storage

    @Synchronized
    private fun saveApp(context: Context, part: AppPart) {
        prefs(context).edit(commit = true) { putString(APP, WidgetData.json(part).toString()) }
    }

    @Synchronized
    private fun saveWallet(context: Context, part: WalletPart?) {
        val p = prefs(context)
        val old = p.getString(WALLET, null)?.takeIf { it != "null" }?.let { parse { WidgetData.parseWallet(JSONObject(it)) } }
        p.edit(commit = true) {
            putString(WALLET, part?.let { WidgetData.json(it).toString() } ?: "null")
            // Another wallet (or none): its fetched balance must never show.
            if (part == null || old == null || old.xpub != part.xpub || old.network != part.network) remove(LIVE_WALLET)
        }
    }

    class Stored(
        val app: AppPart?,
        val wallet: WalletPart?,
        val walletKnown: Boolean,
        val livePrice: WidgetPrice?,
        val livePools: LivePools?,
        val liveWallet: LiveWallet?,
    ) {
        val state: WidgetState get() = WidgetData.merge(app, wallet, walletKnown, livePrice, livePools, liveWallet)
    }

    @Synchronized
    fun stored(context: Context): Stored {
        val p = prefs(context)
        val walletRaw = p.getString(WALLET, null)
        return Stored(
            app = p.getString(APP, null)?.let { parse { WidgetData.parseApp(JSONObject(it)) } },
            wallet = walletRaw?.takeIf { it != "null" }?.let { parse { WidgetData.parseWallet(JSONObject(it)) } },
            walletKnown = walletRaw != null,
            livePrice = p.getString(LIVE_PRICE, null)?.let { parse { WidgetData.parsePrice(JSONObject(it)) } },
            livePools = p.getString(LIVE_POOLS, null)?.let { parse { WidgetData.parseLivePools(JSONObject(it)) } },
            liveWallet = p.getString(LIVE_WALLET, null)?.let { parse { WidgetData.parseLiveWallet(JSONObject(it)) } },
        )
    }

    fun state(context: Context): WidgetState = stored(context).state

    /** A fetched price, kept when it is newer than what the widgets show. */
    @Synchronized
    fun saveLivePrice(context: Context, price: WidgetPrice): Boolean {
        val current = stored(context).state.price
        if (current != null && price.at <= current.at) return false
        prefs(context).edit(commit = true) { putString(LIVE_PRICE, WidgetData.json(price).toString()) }
        return true
    }

    /** Fetched pools, kept while their published figures and monitor configuration are current. */
    @Synchronized
    fun saveLivePools(context: Context, source: AppPart, results: List<WidgetSources.PoolResult?>, at: Long, request: Long): Boolean {
        val stored = stored(context)
        val current = stored.app ?: return false
        val pools = WidgetData.fetchedPools(current, stored.livePools, source, results, at, request) ?: return false
        prefs(context).edit(commit = true) { putString(LIVE_POOLS, WidgetData.json(pools).toString()) }
        return true
    }

    /** A fetched balance, kept only for the wallet it was fetched for, unchanged since. */
    @Synchronized
    fun saveLiveWallet(context: Context, wallet: LiveWallet): Boolean {
        val stored = stored(context)
        val current = stored.wallet ?: return false
        if (WidgetData.fetchedWallet(current, stored.liveWallet, wallet) == null) return false
        prefs(context).edit(commit = true) { putString(LIVE_WALLET, WidgetData.json(wallet).toString()) }
        return true
    }

    /** Claim the retry slot and an ordered request number atomically across WorkManager jobs. */
    @Synchronized
    fun beginRefresh(context: Context, need: Need, source: String, now: Long, delay: Long): Long? {
        val p = prefs(context)
        if (!WidgetRules.retryAllowed(p.getLong(TRIED + need.name, 0), p.getString(TRIED_SOURCE + need.name, null), source, now, delay)) return null
        val request = Math.addExact(p.getLong(REQUEST, 0), 1L)
        p.edit(commit = true) {
            putLong(TRIED + need.name, now)
            putString(TRIED_SOURCE + need.name, source)
            putLong(REQUEST, request)
        }
        return request
    }

    private inline fun <T> parse(block: () -> T): T? = try {
        block()
    } catch (e: RuntimeException) {
        Log.w(TAG, "Stored widget data unreadable: ${e.javaClass.simpleName}")
        null
    }

    // endregion

    // region placed widgets

    fun ids(context: Context, kind: Kind): IntArray = try {
        AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, kind.provider))
    } catch (e: RuntimeException) {
        Log.w(TAG, "No widget ids: ${e.javaClass.simpleName}")
        IntArray(0)
    }

    fun anyPlaced(context: Context): Boolean = Kind.entries.any { ids(context, it).isNotEmpty() }

    /** The size the launcher reports for a widget, in portrait (dp). */
    fun size(options: Bundle?): WidgetRender.Size? {
        if (options == null) return null
        val width = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
        val height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
        return if (width > 0 && height > 0) WidgetRender.Size(width, height) else null
    }

    private fun hubSize(manager: AppWidgetManager, id: Int): WidgetRules.HubSize {
        val size = size(manager.getAppWidgetOptions(id)) ?: return WidgetRules.HubSize.MEDIUM
        return WidgetRules.hubSize(size.width, size.height)
    }

    /** What the placed widgets show: a locked Pearl Hub widget fetches nothing (iOS). */
    fun needs(context: Context, state: WidgetState): Set<Need> {
        val manager = AppWidgetManager.getInstance(context)
        val needs = mutableSetOf<Need>()
        for (id in ids(context, Kind.HUB)) {
            if (!WidgetRules.locked(hubSize(manager, id), state.pro)) needs += Need.entries
        }
        if ((marketKinds + Kind.PRICE).any { ids(context, it).isNotEmpty() }) needs += Need.PRICE
        if (miningKinds.any { ids(context, it).isNotEmpty() }) needs += listOf(Need.PRICE, Need.POOLS)
        return needs
    }

    // endregion

    // region drawing

    /** Something changed: redraw now, and keep (or stop) the refresh schedule. */
    fun changed(context: Context, refresh: Boolean) {
        val placed = redraw(context)
        if (!placed) {
            WidgetRefresh.cancel(context)
            return
        }
        WidgetRefresh.schedule(context)
        if (refresh) WidgetRefresh.soon(context)
    }

    /** Redraws every placed widget and sets the next clock tick. True when any widget is placed. */
    fun redraw(context: Context): Boolean {
        val manager = AppWidgetManager.getInstance(context)
        val state = state(context)
        val now = now()
        var placed = false
        for (kind in Kind.entries) for (id in ids(context, kind)) {
            placed = true
            try {
                val size = size(manager.getAppWidgetOptions(id))
                if (BuildConfig.DEBUG) Log.d(TAG, "draw ${kind.key} #$id at $size")
                manager.updateAppWidget(id, render(context, kind, state, now, size))
            } catch (e: RuntimeException) {
                Log.w(TAG, "Widget $id (${kind.key}) not drawn", e)
            }
        }
        scheduleTick(context, state, now)
        return placed
    }

    fun render(context: Context, kind: Kind, state: WidgetState, now: Long, size: WidgetRender.Size?) = when (kind) {
        Kind.HUB -> WidgetRender.hub(context, state, now, size)
        Kind.PRICE -> WidgetRender.price(context, state, now, size)
        Kind.QUOTE -> WidgetRender.quote(context, state, now, size)
        Kind.CHANGE_1H -> WidgetRender.change(context, state, now, size, hour = true)
        Kind.CHANGE_24H -> WidgetRender.change(context, state, now, size, hour = false)
        Kind.QUOTE_MINING -> WidgetRender.quoteMining(context, state, now, size)
        Kind.COVER_MARKET -> WidgetRender.quote(context, state, now, size, cover = true)
        Kind.COVER_MINING -> WidgetRender.quoteMining(context, state, now, size, cover = true)
    }

    /** Redraws on the widget thread (the system appearance changed while the app runs). */
    fun redrawLater(context: Context) {
        val app = context.applicationContext
        work.execute { guarded("redraw") { redraw(app) } }
    }

    /** Runs a redraw (and optionally a refresh) on the widget thread for a receiver. */
    fun async(receiver: BroadcastReceiver, context: Context, refresh: Boolean) {
        val app = context.applicationContext
        val pending = receiver.goAsync()
        work.execute {
            try {
                changed(app, refresh)
            } catch (e: RuntimeException) {
                Log.w(TAG, "Widget update failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    private inline fun guarded(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: RuntimeException) {
            Log.w(TAG, "$what failed", e)
        }
    }

    /**
     * The next time a placed widget's text changes without new data (relative stamps, the stale
     * mark, the date), as an inexact alarm that does not wake the device: it fires when the screen
     * is next on, which is when a home-screen widget can be seen.
     */
    private fun scheduleTick(context: Context, state: WidgetState, now: Long) {
        val relative = mutableListOf<Long>()
        val stale = mutableListOf<Long>()
        var midnight: Long? = null
        if (ids(context, Kind.HUB).isNotEmpty()) {
            relative += state.stamp
            (state.wallet as? WalletState.Present)?.part?.recent?.forEach { relative += it.time }
        }
        if ((marketKinds + miningKinds).any { ids(context, it).isNotEmpty() }) stale += state.price?.at ?: 0
        if (miningKinds.any { ids(context, it).isNotEmpty() }) stale += state.poolsAt
        if (ids(context, Kind.PRICE).isNotEmpty()) {
            midnight = Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, 1)
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 5); set(Calendar.MILLISECOND, 0)
            }.timeInMillis / 1000
        }
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = PendingIntent.getBroadcast(
            context, 0, Intent(context, WidgetTickReceiver::class.java).setAction(ACTION_TICK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val next = WidgetRules.nextRedraw(now, relative, stale, midnight)
        if (next == null) alarms.cancel(intent) else alarms.set(AlarmManager.RTC, next * 1000, intent)
    }

    // endregion
}

/** The redraw clock (Widgets.scheduleTick). Not exported: only this app's alarm reaches it. */
class WidgetTickReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val pending = goAsync()
        Widgets.work.execute {
            try {
                Widgets.redraw(app)
            } catch (e: RuntimeException) {
                Log.w("PearlWidgets", "Tick redraw failed", e)
            } finally {
                pending.finish()
            }
        }
    }
}
