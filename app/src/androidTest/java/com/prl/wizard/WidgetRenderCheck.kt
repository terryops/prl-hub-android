package com.prl.wizard

import android.app.Activity
import android.app.Instrumentation
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Bundle
import android.util.SizeF
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.io.File
import kotlin.math.roundToInt

/**
 * Device-side RemoteViews check, without a test framework or a production QA activity.
 * Run with qa/widgets.mjs. It does not publish snapshots, bind widgets or touch wallet storage.
 */
class WidgetRenderCheck : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val results = Bundle()
        try {
            var checks = 0
            var failure: Throwable? = null
            runOnMainSync {
                try { checks = checkRenders() } catch (error: Throwable) { failure = error }
            }
            failure?.let { throw it }
            results.putString("stream", "\nwidget_checks_passed: $checks\n")
            finish(Activity.RESULT_OK, results)
        } catch (error: Throwable) {
            results.putString("stream", "\nwidget_checks_failed: ${error.stackTraceToString()}\n")
            finish(Activity.RESULT_CANCELED, results)
        }
    }

    private fun checkRenders(): Int {
        val context = targetContext
        val manager = AppWidgetManager.getInstance(context)
        val now = Widgets.now()
        val state = WidgetState(
            lang = "en", theme = "light", palette = "classic", pro = false,
            price = WidgetPrice(1.85, -0.84, 5.26, now), fiat = null,
            pools = listOf(WidgetPool("Rig A", "AlphaPool", 1.872e9, 5, 6)), poolsAt = now,
            monitors = listOf(WidgetMonitor("AlphaPool", "prl1demo", null, "Rig A")),
            wallet = WalletState.None, stamp = now,
        )
        val out = File(context.filesDir, "widget-qa").apply { mkdirs() }
        check(Widgets.Kind.entries.map { it.key } == WidgetRules.KINDS) { "Bridge kind list and installed widgets differ" }
        val sizes = listOf(56 to 56, 96 to 160, 110 to 56, 180 to 80, 180 to 180, 230 to 250, 352 to 339, 200 to 350, 350 to 120)
        var count = 0
        fun render(kind: Widgets.Kind, snapshot: WidgetState, width: Int, height: Int, name: String? = null) {
            val info = manager.installedProviders.first { it.provider.className == kind.provider.name }
            val host = AppWidgetHostView(context)
            host.setAppWidget(-1, info)
            host.setPadding(0, 0, 0, 0)
            host.updateAppWidgetSize(Bundle(), listOf(SizeF(width.toFloat(), height.toFloat())))
            val density = context.resources.displayMetrics.density
            val w = (width * density).roundToInt()
            val h = (height * density).roundToInt()
            fun layout() {
                host.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
                host.layout(0, 0, w, h)
            }
            layout()
            host.updateAppWidget(Widgets.render(context, kind, snapshot, now, WidgetRender.Size(width, height)))
            layout()
            val texts = mutableListOf<TextView>()
            fun collect(view: View) {
                if (view.visibility != View.VISIBLE) return
                if (view is TextView) texts += view
                if (view is ViewGroup) for (i in 0 until view.childCount) collect(view.getChildAt(i))
            }
            collect(host)
            check(texts.isNotEmpty() && texts.none { it.text.toString().contains("Problem loading") }) { "$kind ${width}x$height failed to inflate" }
            val bucket = WidgetRules.marketSize(width, height, kind == Widgets.Kind.COVER_MARKET || kind == Widgets.Kind.COVER_MINING)
            if (kind != Widgets.Kind.HUB) {
                check((host.findViewById<View>(R.id.w_mini_value) != null) == (bucket == WidgetRules.MarketSize.MINI)) { "$kind ${width}x$height selected wrong layout ($bucket)" }
            }
            for (text in texts.filter { it.text.isNotEmpty() }) {
                check(text.width > 0 && text.height > 0) { "$kind ${width}x$height has no room for '${text.text}'" }
                val bounds = Rect()
                text.getDrawingRect(bounds)
                host.offsetDescendantRectToMyCoords(text, bounds)
                check(bounds.left >= 0 && bounds.top >= 0 && bounds.bottom <= h + 1 && bounds.right <= w + 1) { "$kind ${width}x$height clips '${text.text}' ($bounds)" }
                if (text.id in setOf(R.id.w_price, R.id.w_change, R.id.w_change1h, R.id.w_change24h, R.id.w_hashrate, R.id.w_workers, R.id.w_mini_value)) {
                    val lines = text.layout
                    check(lines != null && (0 until lines.lineCount).all { lines.getEllipsisCount(it) == 0 } && lines.height <= text.height + 1) { "$kind ${width}x$height truncates figure '${text.text}'" }
                }
            }
            if (kind == Widgets.Kind.CHANGE_1H || kind == Widgets.Kind.CHANGE_24H) {
                val percent = WidgetFormat.change(if (kind == Widgets.Kind.CHANGE_1H) snapshot.price?.change1h else snapshot.price?.change24h, WidgetFormat.locale(snapshot.lang))
                check(texts.any { it.text.toString() == percent }) { "$kind does not display its period's change" }
            }
            if (kind == Widgets.Kind.QUOTE_MINING || kind == Widgets.Kind.COVER_MINING) {
                val summary = MiningSummary.of(snapshot.pools, snapshot.monitors)
                if (summary.monitorCount > 0) {
                    check(texts.any { it.text.toString() == summary.hashrateText(WidgetFormat.locale(snapshot.lang)) }) { "$kind displays an incomplete hashrate" }
                    check(texts.any { it.text.toString().endsWith(summary.workersText) }) { "$kind displays an incomplete worker total" }
                }
                val colors = WidgetColors.of(snapshot.palette, snapshot.theme == "dark")
                val priceView = host.findViewById<TextView>(if (bucket == WidgetRules.MarketSize.MINI) R.id.w_mini_title else R.id.w_price)
                check(priceView.currentTextColor == if (WidgetRules.stale(snapshot.price?.at ?: 0, now)) colors.muted else colors.price) { "$kind price freshness follows the wrong source" }
            }
            if (name != null) {
                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                host.draw(Canvas(bitmap))
                File(out, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            count++
        }
        for (kind in Widgets.Kind.entries.filter { it != Widgets.Kind.HUB }) {
            for ((w, h) in sizes) render(kind, state, w, h, "${kind.key}-${w}x$h")
            val empty = state.copy(price = null, pools = emptyList(), monitors = emptyList(), poolsAt = 0)
            render(kind, empty, 180, 180, "${kind.key}-empty")
            render(kind, empty, 56, 56)
            val stale = state.copy(price = state.price?.copy(at = now - 1800), poolsAt = now - 1800)
            render(kind, stale, 180, 180, "${kind.key}-stale")
            render(kind, state.copy(palette = "mono", theme = "dark", lang = "zh-Hans"), 352, 339, "${kind.key}-dark-zh")
        }
        for (lang in WidgetData.LANGS) {
            render(Widgets.Kind.CHANGE_1H, state.copy(lang = lang), 110, 56)
            render(Widgets.Kind.COVER_MINING, state.copy(lang = lang), 230, 250)
        }
        // The worker count and hashrate must stay unknown for partial monitor data.
        render(Widgets.Kind.COVER_MINING, state.copy(monitors = state.monitors + WidgetMonitor("HeroMiners", "prl1other", null, "Rig B")), 352, 339, "coverMining-partial")
        render(Widgets.Kind.COVER_MINING, state.copy(price = state.price?.copy(at = now - 1800)), 352, 339, "coverMining-price-stale")
        render(Widgets.Kind.COVER_MINING, state.copy(poolsAt = now - 1800), 352, 339, "coverMining-pools-stale")
        return count
    }
}
