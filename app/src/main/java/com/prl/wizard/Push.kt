package com.prl.wizard

import android.content.Context
import android.util.Log
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailabilityLight
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import org.json.JSONObject

/** Firebase is initialized by hand from BuildConfig; builds without its config have no push. */
object Push {
    private val configured = listOf(
        BuildConfig.FIREBASE_PROJECT_ID, BuildConfig.FIREBASE_APP_ID,
        BuildConfig.FIREBASE_API_KEY, BuildConfig.FIREBASE_SENDER_ID,
    ).all { it.isNotEmpty() }

    fun init(context: Context) {
        if (!configured || FirebaseApp.getApps(context).isNotEmpty()) return
        try {
            FirebaseApp.initializeApp(
                context,
                FirebaseOptions.Builder()
                    .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
                    .setApplicationId(BuildConfig.FIREBASE_APP_ID)
                    .setApiKey(BuildConfig.FIREBASE_API_KEY)
                    .setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID)
                    .build(),
            )
        } catch (e: RuntimeException) {
            Log.w("PearlPush", "Firebase init failed", e)
        }
    }

    /** Firebase is configured in this build and Google Play services can deliver. */
    fun available(context: Context): Boolean =
        configured && FirebaseApp.getApps(context).isNotEmpty() &&
            GoogleApiAvailabilityLight.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS

    // getToken/deleteToken are deprecated in firebase-messaging 26 in favour of register(), whose
    // token only arrives later in PushService.onRegistered; the bridge needs a direct answer.
    @Suppress("DEPRECATION")
    fun token(context: Context, call: Bridge.Call) {
        if (!available(context)) return call.fail("unavailable")
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            val token = if (task.isSuccessful) task.result else null
            if (token.isNullOrEmpty()) call.fail("failed") else call.ok(JSONObject().put("token", token))
        }
    }

    @Suppress("DEPRECATION")
    fun delete(context: Context, call: Bridge.Call) {
        if (!available(context)) return call.fail("unavailable")
        FirebaseMessaging.getInstance().deleteToken().addOnCompleteListener { task ->
            if (task.isSuccessful) call.ok() else call.fail("failed")
        }
    }
}

class PushService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        if (message.data["kind"] == PushPayload.KIND_LIVE) {
            // 锁屏盯盘: the Worker's once-a-minute price (BRIDGE.md "Push payload"); ignored when the ticker is off.
            LiveTicker.Quote.fromPush(message.data)?.let { LiveTicker.update(this, it) }
            return
        }
        PushPayload.from(message.data)?.let { Notifications.show(this, it) }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onNewToken(token: String) = tokenChanged(token)

    override fun onRegistered(token: String) = tokenChanged(token)

    private fun tokenChanged(token: String) {
        MainActivity.dispatchEvent("pushToken", JSONObject().put("token", token))
    }
}
