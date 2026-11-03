package com.prl.wizard

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import androidx.core.net.toUri

/**
 * The iOS app's ReviewPrompt (prl-hub `Sources/App/ReviewPrompt.swift`), with Google Play's in-app
 * review in place of StoreKit's. Play, like Apple, rations the sheet and never says whether it
 * appeared, so the app only asks at a good moment and never twice for the same reason: at least
 * 3 launches, at least 3 days since the first, once per version, and 120 days apart. The moment is
 * a 我的监控 refresh that returned a miner's rigs (the page calls `review.request` then). The launch
 * counters also gate the one-time donation prompt (`info.launches`, `info.firstLaunch`).
 *
 * Only the Play build asks; the website APK has no store to rate in.
 */
object ReviewPrompt {
    private const val PREFS = "shell"
    private const val LAUNCHES = "review.launchCount"
    private const val FIRST_LAUNCH = "review.firstLaunch"      // unix seconds
    private const val LAST_ASKED = "review.lastAsked"          // unix seconds
    private const val ASKED_VERSION = "review.askedVersion"    // versionName

    private const val MIN_LAUNCHES = 3
    private const val MIN_DAYS_INSTALLED = 3
    private const val MIN_DAYS_BETWEEN_ASKS = 120
    private const val DAY = 86_400L

    private var counted = false

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun now() = System.currentTimeMillis() / 1000

    /** Counts this process's first activity start; later ones (a new activity instance) are the same launch. */
    fun registerLaunch(context: Context) {
        if (counted) return
        counted = true
        val p = prefs(context)
        p.edit {
            putInt(LAUNCHES, p.getInt(LAUNCHES, 0) + 1)
            if (p.getLong(FIRST_LAUNCH, 0) <= 0) putLong(FIRST_LAUNCH, now())
        }
    }

    fun launches(context: Context) = prefs(context).getInt(LAUNCHES, 0)
    fun firstLaunch(context: Context) = prefs(context).getLong(FIRST_LAUNCH, 0)

    fun eligible(context: Context): Boolean {
        if (!StoreReview.SUPPORTED) return false
        val p = prefs(context)
        if (p.getInt(LAUNCHES, 0) < MIN_LAUNCHES) return false
        val first = p.getLong(FIRST_LAUNCH, 0)
        val now = now()
        if (first <= 0 || now - first < MIN_DAYS_INSTALLED * DAY) return false
        if (p.getString(ASKED_VERSION, null) == BuildConfig.VERSION_NAME) return false
        val last = p.getLong(LAST_ASKED, 0)
        return last <= 0 || now - last >= MIN_DAYS_BETWEEN_ASKS * DAY
    }

    /** Asks for a rating if every gate passes; true when the request went to Play. */
    fun request(activity: MainActivity): Boolean {
        if (!activity.canOpenStoreUi() || !eligible(activity)) return false
        prefs(activity).edit {
            putLong(LAST_ASKED, now())
            putString(ASKED_VERSION, BuildConfig.VERSION_NAME)
        }
        StoreReview.launch(activity)
        return true
    }

    /** 设置 → 关于 → 为 Pearl Hub 评分: the store page, which the in-app sheet's rationing cannot swallow. */
    fun openStorePage(activity: MainActivity): Boolean {
        if (!StoreReview.SUPPORTED) return false
        val id = activity.packageName
        return try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, "market://details?id=$id".toUri()).setPackage("com.android.vending"))
            true
        } catch (_: ActivityNotFoundException) {
            activity.openExternal("https://play.google.com/store/apps/details?id=$id")
        }
    }
}
