package com.prl.wizard

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit

object Notifications {
    private const val NOTIFICATION_ID = 1
    private const val SUMMARY_ID = 2
    private const val PREFS = "shell"
    private const val DENIED_ONCE = "notificationsDeniedOnce"
    private const val OFFLINE_HISTORY_SIZE = 20
    private const val NEXT_OFFLINE_SLOT = "notifications.nextOfflineSlot"
    private val offlineTags = (0 until OFFLINE_HISTORY_SIZE).map { "${PushPayload.GROUP_OFFLINE}@history:$it" }
    // 到账提醒: the latest payments. Fewer slots than the offline group: a payout address can be paid
    // every block, and Android drops an app's posts past 50.
    private const val INCOME_HISTORY_SIZE = 10
    private const val NEXT_INCOME_SLOT = "notifications.nextIncomeSlot"
    private val incomeTags = (0 until INCOME_HISTORY_SIZE).map { "${PushPayload.GROUP_INCOME}@history:$it" }

    /** Creates the channels, or renames them after a language change. */
    fun ensureChannels(context: Context) {
        NotificationManagerCompat.from(context).createNotificationChannelsCompat(
            listOf(
                NotificationChannelCompat.Builder(PushPayload.CHANNEL_PRICE, NotificationManagerCompat.IMPORTANCE_HIGH)
                    .setName(context.getString(R.string.channel_price)).build(),
                NotificationChannelCompat.Builder(PushPayload.CHANNEL_OFFLINE, NotificationManagerCompat.IMPORTANCE_HIGH)
                    .setName(context.getString(R.string.channel_offline)).build(),
                NotificationChannelCompat.Builder(PushPayload.CHANNEL_INCOME, NotificationManagerCompat.IMPORTANCE_HIGH)
                    .setName(context.getString(R.string.channel_income)).build(),
            ),
        )
        LiveTicker.ensureChannel(context)
    }

    private fun granted(context: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /**
     * "granted", "denied" or "default". "default" means asking still shows the system prompt:
     * never asked, or denied once (the prompt comes back once more on Android 13+).
     */
    fun permission(activity: Activity): String {
        if (granted(activity)) {
            return if (NotificationManagerCompat.from(activity).areNotificationsEnabled()) "granted" else "denied"
        }
        if (ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.POST_NOTIFICATIONS)) return "default"
        return if (activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(DENIED_ONCE, false)) "denied" else "default"
    }

    /** After the system prompt: remembers a first explicit denial so the next one reads as final. */
    fun recordPromptResult(activity: Activity) {
        if (ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.POST_NOTIFICATIONS)) {
            activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(DENIED_ONCE, true) }
        }
    }

    @Synchronized
    fun show(context: Context, payload: PushPayload) {
        if (!granted(context)) return
        val manager = NotificationManagerCompat.from(context)
        // Bounded, persistent slots also cap a burst before activeNotifications reflects each post.
        // Leave room under Android's 50-notification cap for price alerts and the live ticker.
        val tag = when (payload.group) {
            PushPayload.GROUP_OFFLINE -> nextSlot(context, NEXT_OFFLINE_SLOT, offlineTags)
            PushPayload.GROUP_INCOME -> nextSlot(context, NEXT_INCOME_SLOT, incomeTags)
            else -> payload.tag
        }
        val open = Intent(context, MainActivity::class.java)
            .setAction(MainActivity.ACTION_OPEN_ROUTE)
            // Extras are not part of PendingIntent identity, and different tags can hash equally.
            .setData(Uri.Builder().scheme("pearlhub-notification").authority("alert").appendPath(tag).build())
            .putExtra(MainActivity.EXTRA_ROUTE, payload.route)
        val tap = PendingIntent.getActivity(
            context, tag.hashCode(), open,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, payload.channel)
            .setSmallIcon(R.drawable.ic_stat_pearl)
            .setColor(ContextCompat.getColor(context, R.color.pearl_blue))
            .setContentTitle(payload.title.ifEmpty { context.getString(R.string.app_name) })
            .setContentText(payload.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(payload.body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(tap)
            .setGroup(payload.group)
            .build()
        try {
            // Clear the old unbounded tags as well when updating an already installed build.
            manager.activeNotifications.filter {
                it.id == NOTIFICATION_ID && it.notification.group == PushPayload.GROUP_OFFLINE && it.tag !in offlineTags
            }.forEach { manager.cancel(it.tag, it.id) }
            manager.notify(tag, NOTIFICATION_ID, notification)
            // The group's summary, which Android needs to bundle the alerts under one header.
            manager.notify(payload.group, SUMMARY_ID, NotificationCompat.Builder(context, payload.channel)
                .setSmallIcon(R.drawable.ic_stat_pearl)
                .setColor(ContextCompat.getColor(context, R.color.pearl_blue))
                .setContentTitle(payload.title.ifEmpty { context.getString(R.string.app_name) })
                .setContentText(payload.body)
                .setGroup(payload.group)
                .setGroupSummary(true)
                // The child already alerts on its channel; the summary only groups it.
                .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
                .setAutoCancel(true)
                .setContentIntent(tap)
                .build())
        } catch (_: SecurityException) {
            // Permission revoked between the check and the post.
        }
    }

    /** The next of a stacking group's history slots, round and round. */
    private fun nextSlot(context: Context, key: String, tags: List<String>): String {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val slot = p.getInt(key, 0).coerceIn(0, tags.size - 1)
        p.edit { putInt(key, (slot + 1) % tags.size) }
        return tags[slot]
    }
}
