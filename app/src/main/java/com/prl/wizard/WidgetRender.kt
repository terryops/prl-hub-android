package com.prl.wizard

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Paint
import android.graphics.Typeface
import android.icu.text.DateFormat
import android.icu.text.DisplayContext
import android.icu.text.RelativeDateTimeFormatter
import android.icu.util.ULocale
import android.os.Build
import android.util.SizeF
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import androidx.annotation.RequiresApi
import androidx.core.net.toUri
import com.prl.wizard.WidgetRules.HubSize
import com.prl.wizard.WidgetRules.MarketSize
import com.prl.wizard.WidgetRules.Target
import java.util.Date
import java.util.Locale
import kotlin.math.floor
import kotlin.math.max

/** The colours of one palette in one appearance (WidgetStyle.swift). */
data class WidgetColors(
    val primary: Int, val secondary: Int, val muted: Int, val price: Int,
    val gold: Int, val positive: Int, val negative: Int,
) {
    companion object {
        fun of(palette: String, night: Boolean): WidgetColors = when {
            // Modern Silver
            palette == "mono" && !night -> WidgetColors(0xFF21384F.toInt(), 0xFF536C87.toInt(), 0xFF6C8096.toInt(), 0xFF355F93.toInt(), 0xFFB77914.toInt(), 0xFF16805F.toInt(), 0xFFC84255.toInt())
            palette == "mono" -> WidgetColors(0xFFF3F7FC.toInt(), 0xFFBCD0E5.toInt(), 0xFF99ADC4.toInt(), 0xFFD4E5F7.toInt(), 0xFFF5CE72.toInt(), 0xFF67D6AA.toInt(), 0xFFFF8CA7.toInt())
            // Classic Blue: white text on the pearl gradient
            // (iOS only tints small marks with system green / orange there; text needs lighter shades to read on blue.)
            !night -> WidgetColors(0xFFFFFFFF.toInt(), 0xCCFFFFFF.toInt(), 0x99FFFFFF.toInt(), 0xFFFFC747.toInt(), 0xFFFFC747.toInt(), 0xFFA6F4C5.toInt(), 0xFFFFB4A8.toInt())
            else -> WidgetColors(0xFFFFFFFF.toInt(), 0xE0FFFFFF.toInt(), 0xBFFFFFFF.toInt(), 0xFFF2D186.toInt(), 0xFFF2D186.toInt(), 0xFF9DE4C8.toInt(), 0xFFF6CAA1.toInt())
        }
    }
}

/**
 * Builds the widgets' RemoteViews. Text follows the app language from the snapshot (a
 * configuration context in that locale), colours follow the app's palette and appearance.
 */
object WidgetRender {
    private enum class Role { PRIMARY, SECONDARY, MUTED, PRICE, GOLD, POSITIVE, NEGATIVE }
    private enum class Surface { BG, PANEL, PILL, DIV, RIG_OK, RIG_PARTIAL, RIG_DOWN }

    // [system classic, light classic, dark classic, system mono, light mono, dark mono]
    private val SURFACES = mapOf(
        Surface.BG to intArrayOf(R.drawable.widget_bg_classic, R.drawable.widget_bg_classic_light, R.drawable.widget_bg_classic_dark, R.drawable.widget_bg_mono, R.drawable.widget_bg_mono_light, R.drawable.widget_bg_mono_dark),
        Surface.PANEL to intArrayOf(R.drawable.widget_panel_classic, R.drawable.widget_panel_classic_light, R.drawable.widget_panel_classic_dark, R.drawable.widget_panel_mono, R.drawable.widget_panel_mono_light, R.drawable.widget_panel_mono_dark),
        Surface.PILL to intArrayOf(R.drawable.widget_pill_classic, R.drawable.widget_pill_classic_light, R.drawable.widget_pill_classic_dark, R.drawable.widget_pill_mono, R.drawable.widget_pill_mono_light, R.drawable.widget_pill_mono_dark),
        Surface.DIV to intArrayOf(R.drawable.widget_div_classic, R.drawable.widget_div_classic_light, R.drawable.widget_div_classic_dark, R.drawable.widget_div_mono, R.drawable.widget_div_mono_light, R.drawable.widget_div_mono_dark),
        Surface.RIG_OK to intArrayOf(R.drawable.widget_rigs_ok_classic, R.drawable.widget_rigs_ok_classic, R.drawable.widget_rigs_ok_classic, R.drawable.widget_rigs_ok_mono, R.drawable.widget_rigs_ok_mono_light, R.drawable.widget_rigs_ok_mono_dark),
        Surface.RIG_PARTIAL to intArrayOf(R.drawable.widget_rigs_partial_classic, R.drawable.widget_rigs_partial_classic_light, R.drawable.widget_rigs_partial_classic_dark, R.drawable.widget_rigs_partial_mono, R.drawable.widget_rigs_partial_mono_light, R.drawable.widget_rigs_partial_mono_dark),
        Surface.RIG_DOWN to intArrayOf(R.drawable.widget_rigs_down_classic, R.drawable.widget_rigs_down_classic, R.drawable.widget_rigs_down_classic, R.drawable.widget_rigs_down_mono, R.drawable.widget_rigs_down_mono_light, R.drawable.widget_rigs_down_mono_dark),
    )

    /** A widget's size in dp as the launcher reports it (portrait width, portrait height). */
    data class Size(val width: Int, val height: Int)

    /** Nominal sizes for the size-mapped layouts until the launcher reports the real one. */
    private val NOMINAL = mapOf(HubSize.SMALL to Size(160, 160), HubSize.MEDIUM to Size(320, 160), HubSize.LARGE to Size(320, 330))

    /** Everything a render needs: the localized context, locale, colours and the clock. */
    private class Scope(base: Context, val state: WidgetState, val now: Long) {
        val locale: Locale = WidgetFormat.locale(state.lang)
        val text: Context = localized(base, locale)
        val app: Context = base
        val mode = state.theme
        val systemNight = (base.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val night = if (mode == "system") systemNight else mode == "dark"
        /** Android 12+ switches day/night colours in the launcher itself for the "system" appearance. */
        val live = Build.VERSION.SDK_INT >= 31 && mode == "system"
        val day = WidgetColors.of(state.palette, false)
        val dark = WidgetColors.of(state.palette, true)
        val fontScale = base.resources.configuration.fontScale.coerceIn(0.85f, 1.3f)

        fun string(id: Int, vararg args: Any): String = text.getString(id, *args)

        fun surface(s: Surface): Int {
            val set = SURFACES.getValue(s)
            val offset = if (state.palette == "mono") 3 else 0
            return set[offset + if (live) 0 else if (night) 2 else 1]
        }

        fun color(role: Role, colors: WidgetColors): Int = when (role) {
            Role.PRIMARY -> colors.primary
            Role.SECONDARY -> colors.secondary
            Role.MUTED -> colors.muted
            Role.PRICE -> colors.price
            Role.GOLD -> colors.gold
            Role.POSITIVE -> colors.positive
            Role.NEGATIVE -> colors.negative
        }
    }

    private fun localized(context: Context, locale: Locale): Context {
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return context.createConfigurationContext(config)
    }

    // region painting

    private fun RemoteViews.text(s: Scope, id: Int, value: CharSequence, role: Role) {
        setTextViewText(id, value)
        paint(s, id, role)
    }

    private fun RemoteViews.paint(s: Scope, id: Int, role: Role) {
        if (s.live) liveColor(this, id, "setTextColor", s.color(role, s.day), s.color(role, s.dark))
        else setTextColor(id, s.color(role, if (s.night) s.dark else s.day))
    }

    private fun RemoteViews.tint(s: Scope, id: Int, role: Role) {
        if (s.live) liveColor(this, id, "setColorFilter", s.color(role, s.day), s.color(role, s.dark))
        else setInt(id, "setColorFilter", s.color(role, if (s.night) s.dark else s.day))
    }

    @RequiresApi(31)
    private fun liveColor(views: RemoteViews, id: Int, method: String, day: Int, night: Int) = views.setColorInt(id, method, day, night)

    private fun RemoteViews.surface(s: Scope, id: Int, surface: Surface) = setInt(id, "setBackgroundResource", s.surface(surface))

    private fun RemoteViews.show(id: Int, visible: Boolean) = setViewVisibility(id, if (visible) View.VISIBLE else View.GONE)

    private fun RemoteViews.size(id: Int, dp: Float) = setTextViewTextSize(id, TypedValue.COMPLEX_UNIT_DIP, dp)

    private fun RemoteViews.tap(context: Context, id: Int, target: Target) = setOnClickPendingIntent(id, open(context, target))

    /** Opens the app on a route, like a notification tap (MainActivity.routeFrom). */
    fun open(context: Context, target: Target): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(MainActivity.ACTION_OPEN_ROUTE)
            // Keeps one PendingIntent per route; extras alone do not tell them apart.
            .setData("pearlhub-widget:${target.route}".toUri())
            .putExtra(MainActivity.EXTRA_ROUTE, target.route)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(
            context, REQUEST_BASE + target.ordinal, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private const val REQUEST_BASE = 0x5770

    // endregion

    // region text

    private val boldPaint = Paint().apply { typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true; textSize = 100f }

    /** The largest head size (dp) at which "head tail PRL" fits `width` dp, between `min` and `max`. */
    private fun fitBalance(head: String, tail: String, unit: String, tailRatio: Float, unitRatio: Float, width: Float, max: Float, min: Float): Float {
        val per100 = synchronized(boldPaint) {
            boldPaint.measureText(head) + boldPaint.measureText(tail) * tailRatio + boldPaint.measureText(unit) * unitRatio
        }
        if (per100 <= 0f) return max
        val gap = 4f
        return ((width - gap) * 100f / per100).coerceIn(min, max)
    }

    private fun relative(s: Scope, at: Long): String {
        val f = RelativeDateTimeFormatter.getInstance(
            ULocale.forLocale(s.locale), null, RelativeDateTimeFormatter.Style.SHORT, DisplayContext.CAPITALIZATION_FOR_STANDALONE,
        )
        val age = max(0L, s.now - at)
        return when {
            age < 60 -> f.format(RelativeDateTimeFormatter.Direction.PLAIN, RelativeDateTimeFormatter.AbsoluteUnit.NOW)
            age < 3600 -> f.format((age / 60).toDouble(), RelativeDateTimeFormatter.Direction.LAST, RelativeDateTimeFormatter.RelativeUnit.MINUTES)
            age < 86_400 -> f.format((age / 3600).toDouble(), RelativeDateTimeFormatter.Direction.LAST, RelativeDateTimeFormatter.RelativeUnit.HOURS)
            else -> f.format((age / 86_400).toDouble(), RelativeDateTimeFormatter.Direction.LAST, RelativeDateTimeFormatter.RelativeUnit.DAYS)
        }
    }

    private fun clock(s: Scope, at: Long): String {
        val skeleton = if (android.text.format.DateFormat.is24HourFormat(s.app)) "Hm" else "hm"
        return DateFormat.getInstanceForSkeleton(skeleton, s.locale).format(Date(at * 1000))
    }

    private fun dateTime(s: Scope, at: Long): String =
        DateFormat.getInstanceForSkeleton("yMMMdjm", s.locale).format(Date(at * 1000))

    /** "Fri, Oct 16"; Chinese drops the space before the weekday, like the system lock screen. */
    private fun dateLine(s: Scope): String {
        val date = Date(s.now * 1000)
        if (s.locale.language == "zh") {
            return DateFormat.getInstanceForSkeleton("MMMd", s.locale).format(date) + DateFormat.getInstanceForSkeleton("EEE", s.locale).format(date)
        }
        return DateFormat.getInstanceForSkeleton("EEEMMMd", s.locale).format(date)
    }

    // endregion

    // region Pearl Hub

    fun hub(context: Context, state: WidgetState, now: Long, size: Size?): RemoteViews {
        val s = Scope(context, state, now)
        fun build(bucket: HubSize, real: Size?): RemoteViews {
            val dims = real?.takeIf { WidgetRules.hubSize(it.width, it.height) == bucket } ?: NOMINAL.getValue(bucket)
            return when {
                WidgetRules.locked(bucket, state.pro) -> hubLocked(s, bucket)
                bucket == HubSize.SMALL -> hubSmall(s, dims)
                else -> hubWide(s, bucket, dims)
            }
        }
        if (Build.VERSION.SDK_INT >= 31) {
            return RemoteViews(
                mapOf(
                    SizeF(100f, 80f) to build(HubSize.SMALL, size),
                    SizeF(WidgetRules.MEDIUM_MIN_WIDTH.toFloat(), 80f) to build(HubSize.MEDIUM, size),
                    SizeF(WidgetRules.MEDIUM_MIN_WIDTH.toFloat(), WidgetRules.LARGE_MIN_HEIGHT.toFloat()) to build(HubSize.LARGE, size),
                ),
            )
        }
        val real = size ?: NOMINAL.getValue(HubSize.MEDIUM)
        return build(WidgetRules.hubSize(real.width, real.height), real)
    }

    private fun background(views: RemoteViews, s: Scope) = views.surface(s, android.R.id.background, Surface.BG)

    private fun hubSmall(s: Scope, size: Size): RemoteViews {
        val v = RemoteViews(s.app.packageName, R.layout.widget_hub_small)
        background(v, s)
        v.text(s, R.id.w_price_label, "PRL/USD", Role.SECONDARY)
        priceText(v, s, R.id.w_price)
        v.surface(s, R.id.w_divider, Surface.DIV)
        v.surface(s, R.id.w_divider_pools, Surface.DIV)
        balance(v, s, width = size.width - 28f, max = 18f, tailRatio = 0.6f, unitRatio = 0.5f)
        // iOS: the small widget sums every pool, without the completeness rule of the totals.
        val hasMonitors = s.state.monitors.isNotEmpty() || s.state.pools.isNotEmpty()
        v.show(R.id.w_pools_area, hasMonitors)
        if (hasMonitors) {
            val pools = s.state.pools
            val raw = pools.sumOf { it.hashrate ?: 0.0 }
            val online = pools.sumOf { it.online }
            v.text(s, R.id.w_hashrate, if (raw > 0) WidgetFormat.hashrate(raw, s.locale) else WidgetFormat.DASH, if (online == 0) Role.MUTED else Role.PRIMARY)
            rigsPill(v, s, R.id.w_workers, online, pools.sumOf { it.total })
            v.tint(s, R.id.w_bolt, Role.GOLD)
            v.show(R.id.w_desktop, false)
            v.tap(s.app, R.id.w_pools_area, Target.POOLS)
        }
        v.tap(s.app, android.R.id.background, Target.WALLET)
        v.tap(s.app, R.id.w_price_area, Target.PRICE)
        v.tap(s.app, R.id.w_wallet_area, Target.WALLET)
        return v
    }

    private fun hubWide(s: Scope, bucket: HubSize, size: Size): RemoteViews {
        val large = bucket == HubSize.LARGE
        val three = !large && s.state.pools.size >= 3
        val layout = if (large) R.layout.widget_hub_large else if (three) R.layout.widget_hub_medium_three else R.layout.widget_hub_medium
        val v = RemoteViews(s.app.packageName, layout)
        background(v, s)
        stamp(v, s)
        v.text(s, R.id.w_price_label, "PRL/USD", Role.SECONDARY)
        priceText(v, s, R.id.w_price)
        v.text(s, R.id.w_price_unit, "USD", Role.SECONDARY)
        v.surface(s, R.id.w_rule, Surface.DIV)
        v.surface(s, R.id.w_divider, Surface.DIV)
        v.text(s, R.id.w_balance_label, s.string(R.string.widget_balance), Role.SECONDARY)
        val column = (size.width - 32f) / 2f - 14f
        balance(v, s, width = column, max = if (large) 22f else 20f, tailRatio = 0.55f, unitRatio = 0.55f)
        v.tap(s.app, android.R.id.background, Target.WALLET)
        v.tap(s.app, R.id.w_price_area, Target.PRICE)
        v.tap(s.app, R.id.w_wallet_area, Target.WALLET)

        val hasMonitors = s.state.monitors.isNotEmpty() || s.state.pools.isNotEmpty()
        val hasWallet = s.state.wallet is WalletState.Present
        if (!large) {
            // Medium: the pools strip; without monitors, the recent transfers (iOS TxStrip).
            v.show(R.id.w_pools_area, hasMonitors)
            v.show(R.id.w_tx_area, !hasMonitors)
            if (hasMonitors) poolStrip(v, s, three) else transfers(v, s, rows = 2, layoutRows = 2)
            return v
        }
        // Large: one labelled row per pool (iOS PoolRow), then recent transfers.
        val rows = s.state.pools.isNotEmpty()
        v.show(R.id.w_pools_area, hasMonitors && !rows)
        v.show(R.id.w_prows, hasMonitors && rows)
        if (hasMonitors) if (rows) poolRows(v, s) else poolStrip(v, s)
        v.show(R.id.w_divider_tx, hasMonitors && hasWallet)
        v.surface(s, R.id.w_divider_tx, Surface.DIV)
        v.show(R.id.w_tx_area, hasWallet || !hasMonitors)
        // Rows that fit under the header and the pools at this height.
        val scale = s.fontScale
        var used = 24f + 18f + 92f * scale + 22f + 14f * scale
        if (hasMonitors) used += if (rows) (minOf(s.state.pools.size, 4) * 26f + (if (s.state.pools.size > 4) 18f else 0f)) * scale + 22f else 42f * scale + 22f
        val fit = floor((size.height - used) / (19f * scale)).toInt().coerceIn(1, 5)
        transfers(v, s, rows = if (s.state.pools.size >= 4) 1 else minOf(fit, 2), layoutRows = TX_ROWS.size)
        return v
    }

    private fun stamp(v: RemoteViews, s: Scope) {
        val at = s.state.stamp
        v.show(R.id.w_stamp_row, at > 0)
        if (at <= 0) return
        val fresh = WidgetRules.fresh(at, s.now)
        v.show(R.id.w_stamp_check, fresh)
        if (fresh) v.surface(s, R.id.w_stamp, Surface.PILL) else v.setInt(R.id.w_stamp, "setBackgroundResource", 0)
        v.text(s, R.id.w_stamp_text, relative(s, at), if (fresh) Role.PRIMARY else Role.MUTED)
    }

    private fun priceText(v: RemoteViews, s: Scope, id: Int) {
        val price = s.state.price
        v.text(s, id, WidgetFormat.price(price, s.locale), if (price == null) Role.MUTED else Role.PRICE)
    }

    /** Head (two decimals), tail (the remaining decimals) and "PRL", or the no-wallet state. */
    private fun balance(v: RemoteViews, s: Scope, width: Float, max: Float, tailRatio: Float, unitRatio: Float) {
        v.paint(s, R.id.w_balance_head, Role.PRIMARY)
        v.paint(s, R.id.w_balance_tail, Role.SECONDARY)
        v.paint(s, R.id.w_balance_unit, Role.SECONDARY)
        val maxSize = max * s.fontScale
        when (val wallet = s.state.wallet) {
            is WalletState.Present -> {
                val (head, tail) = WidgetFormat.balanceParts(wallet.balance, s.locale)
                val size = fitBalance(head, tail, " PRL", tailRatio, unitRatio, width, maxSize, maxSize * 0.55f)
                v.setTextViewText(R.id.w_balance_head, head)
                v.setTextViewText(R.id.w_balance_tail, tail)
                v.show(R.id.w_balance_unit, true)
                v.size(R.id.w_balance_head, size)
                v.size(R.id.w_balance_tail, size * tailRatio)
                v.size(R.id.w_balance_unit, size * unitRatio)
                // Testnet coins have no price.
                val fiat = if (wallet.part.network == "mainnet") WidgetFormat.fiatLine(wallet.balance, s.state.price, s.state.fiat, s.locale) else null
                v.text(s, R.id.w_fiat, fiat ?: " ", Role.SECONDARY)
            }
            else -> {
                v.setTextViewText(R.id.w_balance_head, "——")
                v.setTextViewText(R.id.w_balance_tail, "")
                v.size(R.id.w_balance_head, maxSize)
                v.show(R.id.w_balance_unit, false)
                val hint = if (wallet == WalletState.None) s.string(R.string.widget_create_wallet) else " "
                v.text(s, R.id.w_fiat, hint, Role.SECONDARY)
            }
        }
    }

    private val POOL_COLUMNS = arrayOf(
        intArrayOf(R.id.w_pool1, R.id.w_pool1_dot, R.id.w_pool1_bolt, R.id.w_pool1_rate, R.id.w_pool1_workers),
        intArrayOf(R.id.w_pool2, R.id.w_pool2_dot, R.id.w_pool2_bolt, R.id.w_pool2_rate, R.id.w_pool2_workers),
    )

    /** Two columns normally; from three published pools, hashrate + rigs only in three columns. */
    private fun poolStrip(v: RemoteViews, s: Scope, three: Boolean = false) {
        v.surface(s, R.id.w_pools_area, Surface.PANEL)
        v.surface(s, R.id.w_pool_rule, Surface.DIV)
        val columns = if (three) POOL_COLUMNS + arrayOf(intArrayOf(R.id.w_pool3, R.id.w_pool3_dot, R.id.w_pool3_bolt, R.id.w_pool3_rate, R.id.w_pool3_workers)) else POOL_COLUMNS
        val pools = s.state.pools.take(columns.size)
        v.show(R.id.w_pool_rule, pools.size > 1)
        if (three) v.surface(s, R.id.w_pool_rule2, Surface.DIV)
        columns.forEachIndexed { i, ids ->
            val pool = pools.getOrNull(i)
            v.show(ids[0], pool != null || i == 0)
            v.show(ids[1], false)
            v.show(ids[2], !three)
            v.tint(s, ids[2], Role.GOLD)
            v.text(s, ids[3], pool?.let { WidgetFormat.hashrate(it.hashrate, s.locale) } ?: WidgetFormat.DASH, if ((pool?.online ?: 0) == 0) Role.MUTED else Role.PRIMARY)
            if (three) v.size(ids[3], (if (pools.any { WidgetFormat.hashrate(it.hashrate, s.locale).length > 9 }) 11f else 12f) * s.fontScale)
            rigsPill(v, s, ids[4], pool?.online ?: 0, pool?.total ?: 0)
        }
        v.tap(s.app, R.id.w_pools_area, Target.POOLS)
    }

    private val POOL_ROWS = arrayOf(
        intArrayOf(R.id.w_prow1, R.id.w_prow1_dot, R.id.w_prow1_label, R.id.w_prow1_bolt, R.id.w_prow1_rate, R.id.w_prow1_desktop, R.id.w_prow1_workers),
        intArrayOf(R.id.w_prow2, R.id.w_prow2_dot, R.id.w_prow2_label, R.id.w_prow2_bolt, R.id.w_prow2_rate, R.id.w_prow2_desktop, R.id.w_prow2_workers),
        intArrayOf(R.id.w_prow3, R.id.w_prow3_dot, R.id.w_prow3_label, R.id.w_prow3_bolt, R.id.w_prow3_rate, R.id.w_prow3_desktop, R.id.w_prow3_workers),
        intArrayOf(R.id.w_prow4, R.id.w_prow4_dot, R.id.w_prow4_label, R.id.w_prow4_bolt, R.id.w_prow4_rate, R.id.w_prow4_desktop, R.id.w_prow4_workers),
    )

    /** One row per pool with its label (iOS PoolRow), up to four, then "+N". */
    private fun poolRows(v: RemoteViews, s: Scope) {
        val pools = s.state.pools
        POOL_ROWS.forEachIndexed { i, ids ->
            val pool = pools.getOrNull(i)
            v.show(ids[0], pool != null)
            if (pool == null) return@forEachIndexed
            v.show(ids[1], false)
            v.text(s, ids[2], pool.label, Role.PRIMARY)
            v.tint(s, ids[3], Role.GOLD)
            v.text(s, ids[4], WidgetFormat.hashrate(pool.hashrate, s.locale), if (pool.online == 0) Role.MUTED else Role.PRIMARY)
            v.show(ids[5], false)
            rigsPill(v, s, ids[6], pool.online, pool.total)
        }
        v.show(R.id.w_prows_more, pools.size > 4)
        if (pools.size > 4) v.text(s, R.id.w_prows_more, "+${pools.size - 4}", Role.SECONDARY)
        v.tap(s.app, R.id.w_prows, Target.POOLS)
    }

    /** iOS RigsPill: quiet / gold / rose, with solid trouble fills on Classic Blue. */
    private fun rigsPill(v: RemoteViews, s: Scope, id: Int, online: Int, total: Int) {
        val down = online == 0 || total == 0
        val partial = !down && online < total
        v.surface(s, id, if (down) Surface.RIG_DOWN else if (partial) Surface.RIG_PARTIAL else Surface.RIG_OK)
        v.setTextViewText(id, "$online/$total")
        val role = if (down) Role.NEGATIVE else if (partial) Role.GOLD else Role.SECONDARY
        if (s.state.palette == "mono" || !down && !partial) v.paint(s, id, role)
        else v.setTextColor(id, if (down) 0xFFFFFFFF.toInt() else 0xFF4A3000.toInt())
    }

    private val TX_ROWS = arrayOf(
        intArrayOf(R.id.w_tx1, R.id.w_tx1_icon, R.id.w_tx1_amount, R.id.w_tx1_time),
        intArrayOf(R.id.w_tx2, R.id.w_tx2_icon, R.id.w_tx2_amount, R.id.w_tx2_time),
        intArrayOf(R.id.w_tx3, R.id.w_tx3_icon, R.id.w_tx3_amount, R.id.w_tx3_time),
        intArrayOf(R.id.w_tx4, R.id.w_tx4_icon, R.id.w_tx4_amount, R.id.w_tx4_time),
        intArrayOf(R.id.w_tx5, R.id.w_tx5_icon, R.id.w_tx5_amount, R.id.w_tx5_time),
    )

    /** "Recent Transactions": received / sent, amount, relative time (iOS TxRow). */
    private fun transfers(v: RemoteViews, s: Scope, rows: Int, layoutRows: Int) {
        v.text(s, R.id.w_tx_title, s.string(R.string.widget_recent), Role.SECONDARY)
        val recent = (s.state.wallet as? WalletState.Present)?.part?.recent.orEmpty().take(rows)
        v.show(R.id.w_tx_empty, recent.isEmpty())
        v.text(s, R.id.w_tx_empty, s.string(R.string.widget_no_transactions), Role.SECONDARY)
        for (i in 0 until layoutRows) {
            val ids = TX_ROWS[i]
            val tx = recent.getOrNull(i)
            v.show(ids[0], tx != null)
            if (tx == null) continue
            v.setImageViewResource(ids[1], if (tx.received) R.drawable.ic_widget_arrow_down else R.drawable.ic_widget_arrow_up)
            v.tint(s, ids[1], if (tx.received) Role.POSITIVE else Role.NEGATIVE)
            v.text(s, ids[2], (if (tx.received) "+" else "−") + WidgetFormat.prl(tx.amount, 4, s.locale) + " PRL", Role.PRIMARY)
            v.text(s, ids[3], if (tx.time > 0) relative(s, tx.time) else "", Role.MUTED)
        }
        v.tap(s.app, R.id.w_tx_area, Target.WALLET)
    }

    private fun hubLocked(s: Scope, bucket: HubSize): RemoteViews {
        val v = RemoteViews(s.app.packageName, R.layout.widget_hub_locked)
        background(v, s)
        for (id in intArrayOf(R.id.w_bar1, R.id.w_bar2, R.id.w_bar3, R.id.w_bar4, R.id.w_bar5, R.id.w_bar6, R.id.w_bar7)) v.surface(s, id, Surface.PILL)
        val large = bucket == HubSize.LARGE
        v.show(R.id.w_bar6, large)
        v.show(R.id.w_bar7, large)
        v.tint(s, R.id.w_lock, Role.PRIMARY)
        v.text(s, R.id.w_lock_title, s.string(R.string.widget_pro_feature), Role.PRIMARY)
        v.text(s, R.id.w_lock_pitch, s.string(R.string.widget_pro_pitch), Role.SECONDARY)
        v.text(s, R.id.w_lock_action, s.string(R.string.widget_tap_to_unlock), Role.PRIMARY)
        v.surface(s, R.id.w_lock_action, Surface.PILL)
        v.text(s, R.id.w_lock_free, s.string(R.string.widget_small_free), Role.SECONDARY)
        v.tap(s.app, android.R.id.background, Target.LOCKED)
        return v
    }

    // endregion

    // region PRL Price, PRL Quote, Quote & Pool Monitor

    private fun marketMapped(size: Size?, cover: Boolean = false, build: (MarketSize) -> RemoteViews): RemoteViews {
        if (Build.VERSION.SDK_INT >= 31) {
            val layouts = linkedMapOf(
                SizeF(56f, 48f) to build(MarketSize.MINI),
                SizeF(WidgetRules.MARKET_MIN_WIDTH.toFloat(), 48f) to build(MarketSize.COMPACT),
                SizeF(WidgetRules.MARKET_MIN_WIDTH.toFloat(), WidgetRules.COMPACT_MAX_HEIGHT.toFloat()) to build(MarketSize.REGULAR),
            )
            if (cover) layouts[SizeF(WidgetRules.COVER_MIN_WIDTH.toFloat(), WidgetRules.COVER_MIN_HEIGHT.toFloat())] = build(MarketSize.COVER)
            return RemoteViews(layouts)
        }
        val real = size ?: if (cover) Size(352, 339) else Size(160, 160)
        return build(WidgetRules.marketSize(real.width, real.height, cover))
    }

    /** A single-cell card with autosized figures; no dependence on a vendor's cover-screen SDK. */
    private fun mini(s: Scope, title: String, value: String, detail: String, role: Role, target: Target, description: String): RemoteViews =
        RemoteViews(s.app.packageName, R.layout.widget_market_mini).also { v ->
            background(v, s)
            v.text(s, R.id.w_mini_title, title, Role.SECONDARY)
            v.text(s, R.id.w_mini_value, value, role)
            v.text(s, R.id.w_mini_detail, detail, Role.SECONDARY)
            v.show(R.id.w_mini_detail, detail.isNotEmpty())
            v.setContentDescription(android.R.id.background, description)
            v.tap(s.app, android.R.id.background, target)
        }

    private fun changeRole(value: Double?, stale: Boolean): Role = when {
        stale -> Role.MUTED
        WidgetFormat.direction(value) > 0 -> Role.POSITIVE
        WidgetFormat.direction(value) < 0 -> Role.NEGATIVE
        else -> Role.SECONDARY
    }

    private fun arrow(value: Double?): Int = when (WidgetFormat.direction(value)) {
        1 -> R.drawable.ic_widget_trend_up
        -1 -> R.drawable.ic_widget_trend_down
        else -> R.drawable.ic_widget_trend_flat
    }

    fun change(context: Context, state: WidgetState, now: Long, size: Size?, hour: Boolean): RemoteViews {
        val s = Scope(context, state, now)
        val price = state.price
        val value = if (hour) price?.change1h else price?.change24h
        val title = "PRL · " + if (hour) "1h" else "24h"
        val stale = WidgetRules.stale(price?.at ?: 0, now)
        val role = changeRole(value, stale)
        val percent = WidgetFormat.change(value, s.locale)
        val description = buildString {
            append(s.string(if (hour) R.string.widget_change_1h_name else R.string.widget_change_24h_name)).append(' ').append(percent)
            append(", ").append(WidgetFormat.price(price, s.locale))
            if (price != null && price.at > 0) append(", ").append(s.string(R.string.widget_quote_updated, dateTime(s, price.at)))
        }
        return marketMapped(size) { bucket ->
            if (bucket == MarketSize.MINI) {
                mini(s, title, percent, if (stale) "◷" else WidgetFormat.price(price, s.locale), role, Target.PRICE, description)
            } else RemoteViews(context.packageName, if (bucket == MarketSize.COMPACT) R.layout.widget_change_compact else R.layout.widget_change).also { v ->
                background(v, s)
                v.text(s, R.id.w_pair, title, Role.SECONDARY)
                v.text(s, R.id.w_change, percent, role)
                v.setImageViewResource(R.id.w_trend, arrow(value))
                v.tint(s, R.id.w_trend, role)
                v.text(s, R.id.w_price, WidgetFormat.price(price, s.locale), if (stale) Role.MUTED else Role.SECONDARY)
                v.text(s, R.id.w_time, if (price != null && price.at > 0) clock(s, price.at) else "", Role.SECONDARY)
                v.show(R.id.w_time, bucket != MarketSize.COMPACT)
                v.show(R.id.w_stale_icon, price != null && stale)
                v.tint(s, R.id.w_stale_icon, Role.SECONDARY)
                v.setContentDescription(android.R.id.background, description)
                v.tap(context, android.R.id.background, Target.PRICE)
            }
        }
    }

    fun price(context: Context, state: WidgetState, now: Long, size: Size?): RemoteViews {
        val s = Scope(context, state, now)
        fun build(layout: Int) = RemoteViews(context.packageName, layout).also { v ->
            background(v, s)
            v.text(s, R.id.w_date, dateLine(s), if (layout == R.layout.widget_price) Role.PRIMARY else Role.SECONDARY)
            if (layout == R.layout.widget_price) v.text(s, R.id.w_price_label, "PRL/USD", Role.SECONDARY)
            priceText(v, s, R.id.w_price)
            v.tap(context, android.R.id.background, Target.PRICE)
            v.tap(context, R.id.w_price_area, Target.PRICE)
        }
        return marketMapped(size) { bucket ->
            if (bucket == MarketSize.MINI) mini(s, "PRL · USD", WidgetFormat.price(state.price, s.locale), "", Role.PRICE, Target.PRICE,
                dateLine(s) + ", " + s.string(R.string.widget_price_name) + " " + WidgetFormat.price(state.price, s.locale))
            else build(if (bucket == MarketSize.COMPACT) R.layout.widget_price_compact else R.layout.widget_price)
        }
    }

    fun quote(context: Context, state: WidgetState, now: Long, size: Size?, cover: Boolean = false): RemoteViews {
        val s = Scope(context, state, now)
        val price = state.price
        val stale = WidgetRules.stale(price?.at ?: 0, now)
        val text = WidgetFormat.price(price, s.locale)
        val description = buildString {
            append(s.string(R.string.widget_quote_name)).append(' ').append(text)
            append(", ").append(s.string(R.string.widget_one_hour)).append(' ').append(WidgetFormat.change(price?.change1h, s.locale))
            append(", ").append(s.string(R.string.widget_one_day)).append(' ').append(WidgetFormat.change(price?.change24h, s.locale))
            if (price != null && price.at > 0) append(", ").append(s.string(R.string.widget_quote_updated, dateTime(s, price.at)))
        }
        fun changeRole(value: Double?) = changeRole(value, stale)
        fun build(bucket: MarketSize): RemoteViews {
            if (bucket == MarketSize.MINI) return mini(s, "PRL · USD", text,
                if (stale) "◷" else "24h " + WidgetFormat.change(price?.change24h, s.locale),
                if (stale) Role.MUTED else Role.PRICE, Target.PRICE, description)
            val compact = bucket == MarketSize.COMPACT
            val layout = when (bucket) {
                MarketSize.COMPACT -> R.layout.widget_quote_compact
                MarketSize.COVER -> R.layout.widget_cover_market
                else -> R.layout.widget_quote
            }
            return RemoteViews(context.packageName, layout).also { v ->
                background(v, s)
                v.tint(s, R.id.w_mark, Role.PRIMARY)
                v.text(s, R.id.w_pair, if (compact) "PRL" else "PRL · USD", Role.SECONDARY)
                v.show(R.id.w_stale_icon, price != null && stale)
                v.tint(s, R.id.w_stale_icon, Role.SECONDARY)
                v.text(s, R.id.w_time, if (price != null && price.at > 0) clock(s, price.at) else "", Role.SECONDARY)
                v.text(s, R.id.w_price, text, if (price == null || stale) Role.MUTED else Role.PRICE)
                if (compact) {
                    v.text(s, R.id.w_change1h, "1h " + WidgetFormat.change(price?.change1h, s.locale), changeRole(price?.change1h))
                    v.text(s, R.id.w_change24h, "24h " + WidgetFormat.change(price?.change24h, s.locale), changeRole(price?.change24h))
                } else {
                    v.text(s, R.id.w_label1h, "1h", Role.SECONDARY)
                    v.text(s, R.id.w_label24h, "24h", Role.SECONDARY)
                    v.text(s, R.id.w_change1h, WidgetFormat.change(price?.change1h, s.locale), changeRole(price?.change1h))
                    v.text(s, R.id.w_change24h, WidgetFormat.change(price?.change24h, s.locale), changeRole(price?.change24h))
                    v.setImageViewResource(R.id.w_arrow1h, arrow(price?.change1h))
                    v.setImageViewResource(R.id.w_arrow24h, arrow(price?.change24h))
                    v.tint(s, R.id.w_arrow1h, changeRole(price?.change1h))
                    v.tint(s, R.id.w_arrow24h, changeRole(price?.change24h))
                }
                v.setContentDescription(android.R.id.background, description)
                v.tap(context, android.R.id.background, Target.PRICE)
                v.tap(context, R.id.w_price_area, Target.PRICE)
            }
        }
        return marketMapped(size, cover, ::build)
    }

    fun quoteMining(context: Context, state: WidgetState, now: Long, size: Size?, cover: Boolean = false): RemoteViews {
        val s = Scope(context, state, now)
        val price = state.price
        val priceStale = WidgetRules.stale(price?.at ?: 0, now)
        val poolsStale = WidgetRules.stale(state.poolsAt, now)
        val mining = MiningSummary.of(state.pools, state.monitors)
        val none = mining.monitorCount == 0
        val monitorLabel = when {
            mining.knownCount < mining.monitorCount -> s.string(R.string.widget_monitors_with_data, mining.knownCount, mining.monitorCount)
            mining.monitorCount == 1 -> state.pools.firstOrNull()?.label ?: state.monitors.firstOrNull()?.label ?: s.string(R.string.widget_monitor)
            else -> s.string(R.string.widget_monitors_combined, mining.monitorCount)
        }
        val description = buildString {
            append(s.string(R.string.widget_quote_mining_name)).append(", ")
            append(s.string(R.string.widget_quote_name)).append(' ').append(WidgetFormat.price(price, s.locale))
            if (none) {
                append(", ").append(s.string(R.string.widget_add_monitor))
            } else {
                append(", ").append(s.string(R.string.widget_hashrate)).append(' ').append(mining.hashrateText(s.locale))
                append(", ").append(s.string(R.string.widget_online)).append(' ').append(mining.workersText)
                append(", ").append(monitorLabel)
                if (state.poolsAt > 0) append(", ").append(s.string(R.string.widget_pools_updated, dateTime(s, state.poolsAt)))
            }
        }
        val figure = if (poolsStale) Role.MUTED else Role.PRIMARY
        fun build(bucket: MarketSize): RemoteViews {
            if (bucket == MarketSize.MINI) return mini(s, WidgetFormat.price(price, s.locale), mining.hashrateText(s.locale),
                if (none) "+" else (if (poolsStale) "◷ " else "") + mining.workersText,
                figure, Target.POOLS, description).also { v ->
                    v.paint(s, R.id.w_mini_title, if (priceStale) Role.MUTED else Role.PRICE)
                }
            val compact = bucket == MarketSize.COMPACT
            val layout = when (bucket) {
                MarketSize.COMPACT -> R.layout.widget_quote_mining_compact
                MarketSize.COVER -> R.layout.widget_cover_mining
                else -> R.layout.widget_quote_mining
            }
            return RemoteViews(context.packageName, layout).also { v ->
                background(v, s)
                v.tint(s, R.id.w_mark, Role.PRIMARY)
                v.text(s, R.id.w_pair, "PRL", Role.SECONDARY)
                v.text(s, R.id.w_price, WidgetFormat.price(price, s.locale), if (price == null || priceStale) Role.MUTED else Role.PRICE)
                v.setImageViewResource(R.id.w_bolt, if (none) R.drawable.ic_widget_add else R.drawable.ic_widget_bolt)
                v.tint(s, R.id.w_bolt, if (none) Role.PRIMARY else Role.GOLD)
                v.text(s, R.id.w_hashrate, if (none) s.string(R.string.widget_add_monitor) else mining.hashrateText(s.locale), if (none) Role.PRIMARY else figure)
                v.tint(s, R.id.w_desktop, Role.SECONDARY)
                if (compact) {
                    if (none) v.setTextViewTextSize(R.id.w_hashrate, TypedValue.COMPLEX_UNIT_SP, 11f)
                    v.show(R.id.w_desktop, !none)
                    v.show(R.id.w_workers, !none)
                    v.text(s, R.id.w_workers, mining.workersText, figure)
                } else {
                    v.show(R.id.w_empty_row, none)
                    v.show(R.id.w_hashrate_row, !none)
                    v.text(s, R.id.w_empty, s.string(R.string.widget_add_monitor), Role.PRIMARY)
                    v.tint(s, R.id.w_empty_icon, Role.PRIMARY)
                    v.show(R.id.w_desktop, !none)
                    v.text(s, R.id.w_workers, if (none) s.string(R.string.widget_monitor) else mining.workersText, if (none) Role.SECONDARY else figure)
                    v.show(R.id.w_footer, !none)
                    v.text(s, R.id.w_monitor_label, monitorLabel, Role.SECONDARY)
                    v.show(R.id.w_stale_icon, !none && state.poolsAt > 0 && poolsStale)
                    v.tint(s, R.id.w_stale_icon, Role.SECONDARY)
                    v.text(s, R.id.w_pools_time, if (state.poolsAt > 0) clock(s, state.poolsAt) else "", Role.SECONDARY)
                }
                v.setContentDescription(android.R.id.background, description)
                v.tap(context, android.R.id.background, Target.POOLS)
                v.tap(context, R.id.w_price_area, Target.PRICE)
                v.tap(context, R.id.w_mining_area, Target.POOLS)
            }
        }
        return marketMapped(size, cover, ::build)
    }

    // endregion
}
