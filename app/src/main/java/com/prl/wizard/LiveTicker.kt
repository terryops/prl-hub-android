package com.prl.wizard

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import org.json.JSONObject
import java.util.Locale
import java.util.UUID
import kotlin.math.abs

/**
 * 锁屏盯盘 (Pro), the Android counterpart of the iOS Live Activity (prl-hub
 * `Sources/App/PriceLiveActivity.swift`, `Widgets/PRLPriceLiveActivity.swift`): an ongoing
 * notification with the PRL price and its 1-hour and 24-hour change, on the lock screen and, on
 * Android 16+, as a Live Update chip in the status bar (the Dynamic Island's place).
 *
 * While the app runs the page feeds it the app-wide price (`live.update`); otherwise the alert
 * Worker pushes each new once-a-minute price as an FCM data message (`kind` = `live`, BRIDGE.md
 * "Push payload"). Like iOS it ends after 8 hours, and the person can stop it from the app or by
 * dismissing it. The copy (title, change labels) comes from the page in the app's language.
 *
 * Styles mirror iOS: `full` (card plus the price in the chip), `lock` (card; the chip shows only
 * the pearl) and `island` (price in the chip; the card is one line).
 */
object LiveTicker {
    const val CHANNEL = "live"
    private const val PREFS = "live"
    private const val NOTIFICATION_ID = 7
    private const val TAG = "live"
    const val MAX_SECONDS = 8 * 3600L
    private const val ACTION_DISMISSED = "com.prl.wizard.LIVE_DISMISSED"
    private const val SESSION = "session"
    internal const val EXTRA_SESSION = "com.prl.wizard.LIVE_SESSION"

    data class Quote(val usd: Double, val change1h: Double?, val change24h: Double?, val at: Long) {
        fun json(): JSONObject = JSONObject().put("usd", usd).put("change1h", change1h ?: JSONObject.NULL)
            .put("change24h", change24h ?: JSONObject.NULL).put("at", at)

        companion object {
            /** From the page or a push: `usd` > 0 is required, a missing or non-finite change is unknown. */
            fun from(o: JSONObject?): Quote? {
                if (o == null) return null
                val usd = o.optDouble("usd", Double.NaN)
                if (!usd.isFinite() || usd <= 0) return null
                fun change(key: String) = o.optDouble(key, Double.NaN).takeIf { it.isFinite() && abs(it) < 1e6 }
                val at = o.optLong("at", 0).takeIf { it > 0 } ?: now()
                return Quote(usd, change("change1h"), change("change24h"), at)
            }

            fun fromPush(data: Map<String, String>): Quote? =
                from(JSONObject().apply { data.forEach { (k, v) -> v.toDoubleOrNull()?.takeIf { it.isFinite() }?.let { put(k, it) } } })
        }
    }

    data class Copy(val title: String, val label1h: String, val label24h: String)

    private fun now() = System.currentTimeMillis() / 1000
    private fun prefs(context: Context): SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun ensureChannel(context: Context) {
        // Default importance without sound: low-importance notifications are hidden from the lock
        // screen on many phones, and the lock screen is the point.
        NotificationManagerCompat.from(context).createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName(context.getString(R.string.channel_live))
                .setSound(null, null)
                .setVibrationEnabled(false)
                .setLightsEnabled(false)
                .setShowBadge(false)
                .build(),
        )
    }

    /** The channel is on and notifications are allowed. */
    fun canShow(context: Context): Boolean {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return false
        return manager.getNotificationChannelCompat(CHANNEL)?.importance != NotificationManagerCompat.IMPORTANCE_NONE
    }

    /** Started and not past its 8 hours. A ticker that ran out is cleared here. */
    @Synchronized
    fun running(context: Context): Boolean {
        val p = prefs(context)
        val started = p.getLong("startedAt", 0)
        if (started <= 0) return false
        if (now() - started >= MAX_SECONDS) {
            stop(context)
            return false
        }
        return true
    }

    @Synchronized
    fun status(context: Context): JSONObject {
        val on = running(context)
        val p = prefs(context)
        return JSONObject().put("running", on)
            .put("style", p.getString("style", "full"))
            .put("startedAt", if (on) p.getLong("startedAt", 0) else JSONObject.NULL)
            .put("canShow", canShow(context))
    }

    /** Starts the ticker, or redraws it in another style keeping its start (iOS restarts it). */
    @Synchronized
    fun start(context: Context, copy: Copy, style: String, quote: Quote): Long {
        ensureChannel(context)
        val p = prefs(context)
        val on = running(context)
        val started = if (on) p.getLong("startedAt", now()) else now()
        val session = if (on) p.getString(SESSION, null) else UUID.randomUUID().toString()
        // A style/language change may carry the page's cached price, older than the latest push.
        val shown = if (on) storedQuote(p) else null
        val current = shown?.takeIf { it.at > quote.at } ?: quote
        p.edit {
            putLong("startedAt", started)
            putString(SESSION, session)
            putString("style", style.takeIf { it in setOf("full", "lock", "island") } ?: "full")
            putString("title", copy.title)
            putString("label1h", copy.label1h)
            putString("label24h", copy.label24h)
            putString("quote", current.json().toString())
        }
        post(context)
        return started
    }

    /** A newer price; ignored when the ticker is off or the price is older than what is shown. */
    @Synchronized
    fun update(context: Context, quote: Quote): Boolean {
        if (!running(context)) return false
        val p = prefs(context)
        val shown = storedQuote(p)
        if (shown != null && quote.at < shown.at) return false
        if (shown != null && shown.copy(at = quote.at) == quote && quote.at - shown.at < 60) return true
        p.edit { putString("quote", quote.json().toString()) }
        post(context)
        return true
    }

    @Synchronized
    fun stop(context: Context) {
        NotificationManagerCompat.from(context).cancel(TAG, NOTIFICATION_ID)
        clear(context)
    }

    private fun clear(context: Context) {
        prefs(context).edit { clear() }
    }

    /** Redraws after a language change (the channel name) without touching the price. */
    @Synchronized
    fun refresh(context: Context) {
        ensureChannel(context)
        if (running(context)) post(context)
    }

    private fun storedQuote(p: SharedPreferences): Quote? =
        Quote.from(p.getString("quote", null)?.let { runCatching { JSONObject(it) }.getOrNull() })

    // Every state transition and post shares the same lock: FCM runs off the UI thread, and an
    // update must finish before stop/dismiss clears the session and cancels its notification.
    private fun post(context: Context) {
        val p = prefs(context)
        val quote = storedQuote(p) ?: return
        val started = p.getLong("startedAt", now())
        val remaining = (started + MAX_SECONDS) * 1000 - System.currentTimeMillis()
        if (remaining <= 0) {
            stop(context)
            return
        }
        val style = p.getString("style", "full")
        val title = p.getString("title", null).orEmpty().ifEmpty { "PRL" }
        val price = usd(quote.usd)
        val label1h = p.getString("label1h", null) ?: "1h"
        val label24h = p.getString("label24h", null) ?: "24h"
        val changes = "$label1h ${percent(quote.change1h)}  ·  $label24h ${percent(quote.change24h)}"

        val open = Intent(context, MainActivity::class.java)
            .setAction(MainActivity.ACTION_OPEN_ROUTE)
            .setData(Uri.parse("pearlhub-notification://live"))
            .putExtra(MainActivity.EXTRA_ROUTE, "market")
        val tap = PendingIntent.getActivity(context, NOTIFICATION_ID, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val session = p.getString(SESSION, null)
        val dismissed = PendingIntent.getBroadcast(
            context, NOTIFICATION_ID, Intent(context, LiveTickerDismissed::class.java)
                .setAction(ACTION_DISMISSED)
                .setData(Uri.Builder().scheme("pearlhub-live").authority("dismiss").appendPath(session ?: "legacy-$started").build())
                .putExtra(EXTRA_SESSION, session),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_pearl)
            .setColor(ContextCompat.getColor(context, R.color.pearl_blue))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setWhen(quote.at * 1000)
            .setShowWhen(true)
            .setTimeoutAfter(remaining)
            .setContentIntent(tap)
            .setDeleteIntent(dismissed)
            .setRequestPromotedOngoing(true)
        if (style == "island") {
            // One line on the lock screen; the price lives in the status-bar chip.
            builder.setContentTitle("PRL $price  ·  $label24h ${percent(quote.change24h)}")
        } else {
            builder.setSubText(title)
                .setContentTitle(price)
                .setContentText(changes)
                .setStyle(NotificationCompat.BigTextStyle().setBigContentTitle(price).bigText(changes))
        }
        if (style != "lock") builder.setShortCriticalText(price)
        try {
            NotificationManagerCompat.from(context).notify(TAG, NOTIFICATION_ID, builder.build())
        } catch (_: SecurityException) {
            // Notification permission withdrawn while running.
        }
    }

    // The iOS Live Activity's formats (PRLPriceLiveActivity.swift): "$%.2f" and "%+.2f%%", "—" when unknown.
    internal fun usd(value: Double): String = String.format(Locale.US, "$%.2f", value)
    internal fun percent(value: Double?): String = value?.let { String.format(Locale.US, "%+.2f%%", it) } ?: "—"

    @Synchronized
    internal fun dismissed(context: Context, session: String?) {
        val p = prefs(context)
        // Keep a queued delete callback from an older session from stopping a newer start.
        // Null still identifies a running notification created before session IDs were introduced.
        if (p.getLong("startedAt", 0) > 0 && p.getString(SESSION, null) == session) {
            // A push may have reposted it between the swipe and delivery of the delete intent.
            stop(context)
            MainActivity.dispatchEvent("live", JSONObject().put("running", false))
        }
    }
}

/** The person swiped the ticker away (Android 14+ lets ongoing notifications go): it stops, as on iOS. */
class LiveTickerDismissed : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        LiveTicker.dismissed(context, intent.getStringExtra(LiveTicker.EXTRA_SESSION))
    }
}
