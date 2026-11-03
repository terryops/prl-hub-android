package com.prl.wizard

/**
 * The website APK has no store to ask: the wallet reads pearlhub.net's latest.json itself
 * instead, so these do nothing here. See the Play build's StoreUpdate.
 */
object StoreUpdate {
    const val SUPPORTED = false

    @Suppress("UNUSED_PARAMETER")
    fun check(activity: MainActivity, done: (Int?) -> Unit) = done(null)

    @Suppress("UNUSED_PARAMETER")
    fun start(activity: MainActivity, done: (Boolean) -> Unit) = done(false)

    @Suppress("UNUSED_PARAMETER")
    fun resume(activity: MainActivity) = Unit
}
