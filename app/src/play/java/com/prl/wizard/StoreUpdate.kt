package com.prl.wizard

import android.util.Log
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability

/**
 * The iOS UpdateChecker's App Store lookup, asked of Google Play instead (Play build only). Play knows
 * whether a newer version of this install is out: the wallet's banner asks (`update.check`), and its
 * 更新 runs Play's full-screen update (`update.start`), or opens the store page when Play won't.
 */
object StoreUpdate {
    const val SUPPORTED = true
    private const val TAG = "PearlUpdate"
    private val IMMEDIATE = AppUpdateOptions.defaultOptions(AppUpdateType.IMMEDIATE)

    private fun pending(info: AppUpdateInfo) = info.updateAvailability().let {
        it == UpdateAvailability.UPDATE_AVAILABLE || it == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS
    }

    /** The newer version's code, or null: none, or Play can't tell (an install that didn't come from Play). */
    fun check(activity: MainActivity, done: (Int?) -> Unit) {
        AppUpdateManagerFactory.create(activity).appUpdateInfo
            .addOnSuccessListener { info -> done(if (pending(info)) info.availableVersionCode() else null) }
            .addOnFailureListener { e ->
                Log.i(TAG, "update info unavailable", e)
                done(null)
            }
    }

    /** True when Play's update screen or the store page opened. */
    fun start(activity: MainActivity, manager: AppUpdateManager = AppUpdateManagerFactory.create(activity), done: (Boolean) -> Unit) {
        if (!activity.canOpenStoreUi()) return done(false)
        manager.appUpdateInfo
            .addOnSuccessListener { info ->
                if (!activity.canOpenStoreUi()) return@addOnSuccessListener done(false)
                val started = pending(info) && info.isUpdateTypeAllowed(IMMEDIATE) &&
                    manager.startUpdateFlowForResult(info, activity.storeUpdateFlow, IMMEDIATE)
                done(started || ReviewPrompt.openStorePage(activity))
            }
            .addOnFailureListener { done(activity.canOpenStoreUi() && ReviewPrompt.openStorePage(activity)) }
    }

    /** An update the person started and left (the app went to the background) carries on, as Play asks. */
    fun resume(activity: MainActivity, manager: AppUpdateManager = AppUpdateManagerFactory.create(activity)) {
        manager.appUpdateInfo.addOnSuccessListener { info ->
            if (activity.canOpenStoreUi() && info.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS) {
                manager.startUpdateFlowForResult(info, activity.storeUpdateFlow, IMMEDIATE)
            }
        }
    }
}
