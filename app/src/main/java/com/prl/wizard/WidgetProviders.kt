package com.prl.wizard

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle

/**
 * Home-screen and cover-screen widgets. Every one draws from the same snapshot, so any update redraws
 * them all (Widgets.redraw); a newly placed widget also asks for a refresh.
 */
abstract class WidgetProvider : AppWidgetProvider() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            // The date line, clock times and the device language (before the app publishes its own).
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_LOCALE_CHANGED ->
                Widgets.async(this, context, refresh = false)
            else -> super.onReceive(context, intent)
        }
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = Widgets.async(this, context, refresh = true)

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) =
        Widgets.async(this, context, refresh = false)

    /** The last widget of this kind went away: stop refreshing when no other kind is placed. */
    override fun onDisabled(context: Context) = Widgets.async(this, context, refresh = false)
}

/** "Pearl Hub": price and balance (small, free); pools (medium) and transfers (large) with Pro. */
class HubWidget : WidgetProvider()

/** "PRL Price": date, weekday and the live price. */
class PriceWidget : WidgetProvider()

/** "PRL Quote": the price with its 1-hour and 24-hour changes. */
class QuoteWidget : WidgetProvider()

/** The iOS standalone rolling changes; also fit a single cell. */
class Change1hWidget : WidgetProvider()
class Change24hWidget : WidgetProvider()

/** "Quote & Pool Monitor": the price, total real-time hashrate and online workers. */
class QuoteMiningWidget : WidgetProvider()

/** Standard AppWidgets: any home/cover-screen host can place these glanceable cards. */
class CoverMarketWidget : WidgetProvider()
class CoverMiningWidget : WidgetProvider()
