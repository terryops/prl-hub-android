package com.prl.wizard

import org.json.JSONArray
import org.json.JSONObject

/**
 * The website APK sells Pro for PRL on the page (orders), as the website does, so it has no Play
 * Billing. See the Play build's Billing.
 */
object Billing {
    const val SUPPORTED = false

    @Suppress("UNUSED_PARAMETER")
    fun product(activity: MainActivity, done: (JSONObject?, String?) -> Unit) = done(null, "unsupported")

    @Suppress("UNUSED_PARAMETER")
    fun purchase(activity: MainActivity, done: (JSONObject?, String?) -> Unit) = done(null, "unsupported")

    @Suppress("UNUSED_PARAMETER")
    fun owned(activity: MainActivity, done: (JSONArray?, String?) -> Unit) = done(null, "unsupported")

    @Suppress("UNUSED_PARAMETER")
    fun resume(activity: MainActivity) = Unit

    @Suppress("UNUSED_PARAMETER")
    fun detach(activity: MainActivity) = Unit
}
