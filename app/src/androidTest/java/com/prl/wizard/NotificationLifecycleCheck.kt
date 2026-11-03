package com.prl.wizard

import android.app.Activity
import android.app.Instrumentation
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.UserManager
import org.json.JSONObject
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Native notification regression checks. Run qa/notifications.mjs against a disposable install. */
class NotificationLifecycleCheck : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val results = Bundle()
        val failures = mutableListOf<String>()
        var passed = 0
        fun verify(name: String, block: () -> Unit) {
            try {
                block()
                passed++
            } catch (error: Throwable) {
                failures += "$name: ${error.stackTraceToString()}"
            } finally {
                LiveTicker.stop(targetContext)
            }
        }
        val manager = targetContext.getSystemService(NotificationManager::class.java)
        if (!targetContext.getSystemService(UserManager::class.java).isUserUnlocked) {
            results.putString("stream", "notification_checks_failed: unlock the test device first\n")
            finish(Activity.RESULT_CANCELED, results)
            return
        }
        // Refuse to replace a person's ticker, offline or payment notification history.
        if (LiveTicker.running(targetContext) || manager.activeNotifications.any { it.notification.group in setOf(PushPayload.GROUP_OFFLINE, PushPayload.GROUP_INCOME) }) {
            results.putString("stream", "notification_checks_failed: use a disposable install\n")
            finish(Activity.RESULT_CANCELED, results)
            return
        }
        verify("stop wins over an in-flight price update", ::stopDuringUpdate)
        verify("dismissal cancels a push posted before the delete callback") {
            val now = System.currentTimeMillis() / 1000
            start(now)
            val dismissed = waitForNotification { it.tag == "live" }.notification.deleteIntent
            manager.cancel("live", 7) // Swipe, before Android delivers the delete intent.
            LiveTicker.update(targetContext, quote(now + 60, 2.0))
            sendDismissal(dismissed)
            check(prefs().all.isEmpty())
            checkTickerGone()
        }
        verify("old dismissal cannot stop a newly started ticker") {
            val now = System.currentTimeMillis() / 1000
            start(now)
            val oldDismissal = waitForNotification { it.tag == "live" }.notification.deleteIntent
            LiveTicker.stop(targetContext)
            start(now) // Same second: the session identity must not be only startedAt.
            sendDismissal(oldDismissal)
            check(LiveTicker.running(targetContext)) { "An older session's delete intent stopped the new ticker" }
            waitForNotification { it.tag == "live" }
        }
        verify("hash-colliding alert tags have independent tap intents") {
            val firstTag = "notification-regression-Aa"
            val secondTag = "notification-regression-BB"
            check(firstTag.hashCode() == secondTag.hashCode())
            try {
                Notifications.show(targetContext, PushPayload("First", "Tap opens alerts", PushPayload.CHANNEL_PRICE, firstTag, "alerts", PushPayload.GROUP_PRICE))
                val first = waitForNotification { it.tag == firstTag }.notification.contentIntent
                Notifications.show(targetContext, PushPayload("Second", "Tap opens market", PushPayload.CHANNEL_PRICE, secondTag, "market", PushPayload.GROUP_PRICE))
                val second = waitForNotification { it.tag == secondTag }.notification.contentIntent
                check(first != second) { "The second alert overwrote the first alert's tap route" }
            } finally {
                manager.cancel(firstTag, 1)
                manager.cancel(secondTag, 1)
            }
        }
        verify("style change keeps the newer price and original start", ::redrawKeepsPrice)
        verify("expired ticker removes its notification", ::expiryCancelsNotification)
        verify("offline summary does not alert a second time") {
            try {
                Notifications.show(targetContext, PushPayload("Regression check", "Offline", PushPayload.CHANNEL_OFFLINE, "check", "workers", PushPayload.GROUP_OFFLINE))
                val summary = waitForNotification { it.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0 }
                check(summary.notification.groupAlertBehavior == Notification.GROUP_ALERT_CHILDREN)
            } finally {
                manager.activeNotifications.filter { it.notification.group == PushPayload.GROUP_OFFLINE }
                    .forEach { manager.cancel(it.tag, it.id) }
            }
        }
        verify("payments stack on their own channel and group") {
            try {
                // As the Worker's fcm.js dataFor sends a 到账提醒: kind income, the txid's tail as tag, route wallet.
                val first = PushPayload.from(mapOf("title" to "Main", "body" to "+1 PRL", "kind" to "income", "tag" to "income:check-1", "route" to "wallet"))!!
                Notifications.show(targetContext, first)
                Notifications.show(targetContext, first.copy(body = "+2 PRL", tag = "income:check-2"))
                val deadline = System.currentTimeMillis() + 5000
                var posted = emptyList<android.service.notification.StatusBarNotification>()
                while (System.currentTimeMillis() < deadline) {
                    posted = manager.activeNotifications.filter { it.notification.group == PushPayload.GROUP_INCOME && it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }
                    if (posted.size >= 2) break
                    Thread.sleep(100)
                }
                check(posted.size == 2) { "Two payments should stack, found ${posted.size}" }
                check(posted.all { it.notification.channelId == PushPayload.CHANNEL_INCOME }) { "Payments belong on the income channel" }
                val channel = manager.getNotificationChannel(PushPayload.CHANNEL_INCOME)
                check(channel?.name == targetContext.getString(R.string.channel_income)) { "Income channel missing or unnamed: ${channel?.name}" }
            } finally {
                manager.activeNotifications.filter { it.notification.group == PushPayload.GROUP_INCOME }
                    .forEach { manager.cancel(it.tag, it.id) }
            }
        }
        results.putString("stream", if (failures.isEmpty()) "notification_checks_passed: $passed\n" else "notification_checks_failed:\n${failures.joinToString("\n")}\n")
        finish(if (failures.isEmpty()) Activity.RESULT_OK else Activity.RESULT_CANCELED, results)
    }

    private fun prefs() = targetContext.getSharedPreferences("live", Context.MODE_PRIVATE)
    private fun quote(at: Long, usd: Double = 1.0) = LiveTicker.Quote(usd, null, null, at)
    private fun start(at: Long) = LiveTicker.start(targetContext, LiveTicker.Copy("PRL", "1h", "24h"), "full", quote(at))

    private fun stopDuringUpdate() {
        val now = System.currentTimeMillis() / 1000
        start(now)
        val reading = CountDownLatch(1)
        val release = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val paused = AtomicBoolean(false)
        val failure = AtomicReference<Throwable>()
        val backing = prefs()
        val context = object : ContextWrapper(targetContext) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                if (name != "live") return super.getSharedPreferences(name, mode)
                return Proxy.newProxyInstance(SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
                    if (method.name == "getString" && args?.get(0) == "quote" && paused.compareAndSet(false, true)) {
                        reading.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                    }
                    method.invoke(backing, *(args ?: emptyArray()))
                } as SharedPreferences
            }
        }
        val updater = thread {
            try { LiveTicker.update(context, quote(now + 60, 2.0)) } catch (error: Throwable) { failure.set(error) }
        }
        var stopper: Thread? = null
        try {
            check(reading.await(5, TimeUnit.SECONDS))
            stopper = thread {
                try { LiveTicker.stop(targetContext) } catch (error: Throwable) { failure.set(error) }
                finally { stopped.countDown() }
            }
            // Without serialization stop completes here, then the paused update resurrects the ticker.
            stopped.await(200, TimeUnit.MILLISECONDS)
        } finally {
            release.countDown()
            updater.join(5_000)
            stopper?.join(5_000)
        }
        check(!updater.isAlive && stopper?.isAlive == false)
        failure.get()?.let { throw it }
        check(prefs().all.isEmpty()) { "Price update rewrote state after stop" }
        checkTickerGone()
    }

    private fun redrawKeepsPrice() {
        val now = System.currentTimeMillis() / 1000
        val started = start(now)
        LiveTicker.update(targetContext, quote(now + 60, 2.0))
        val redrawn = LiveTicker.start(targetContext, LiveTicker.Copy("价格", "1 小时", "24 小时"), "lock", quote(now))
        check(redrawn == started)
        val shown = JSONObject(prefs().getString("quote", null)!!)
        check(shown.getDouble("usd") == 2.0 && shown.getLong("at") == now + 60) { "Style change restored an older quote" }
        check(!LiveTicker.update(targetContext, quote(now + 30, 3.0)))
        check(prefs().getString("style", null) == "lock")
    }

    private fun expiryCancelsNotification() {
        val now = System.currentTimeMillis() / 1000
        start(now)
        waitForNotification { it.tag == "live" }
        prefs().edit().putLong("startedAt", now - LiveTicker.MAX_SECONDS + 5).commit()
        LiveTicker.update(targetContext, quote(now + 60, 2.0))
        val lastUpdate = waitForNotification { it.tag == "live" && it.notification.extras.getCharSequence(Notification.EXTRA_TITLE) == "$2.00" }
        check(lastUpdate.notification.timeoutAfter in 1..5_000) { "Last update extended the session past eight hours" }
        prefs().edit().putLong("startedAt", now - LiveTicker.MAX_SECONDS - 1).commit()
        check(!LiveTicker.running(targetContext))
        checkTickerGone()
    }

    private fun checkTickerGone() {
        val manager = targetContext.getSystemService(NotificationManager::class.java)
        repeat(50) {
            if (manager.activeNotifications.none { it.tag == "live" }) return
            Thread.sleep(20)
        }
        error("Stopped or expired notification is still posted")
    }

    private fun sendDismissal(intent: PendingIntent) {
        val done = CountDownLatch(1)
        intent.send(targetContext, 0, null, { _, _, _, _, _ -> done.countDown() }, Handler(Looper.getMainLooper()))
        check(done.await(2, TimeUnit.SECONDS)) { "Delete broadcast was not delivered" }
    }

    private fun waitForNotification(predicate: (android.service.notification.StatusBarNotification) -> Boolean): android.service.notification.StatusBarNotification {
        val manager = targetContext.getSystemService(NotificationManager::class.java)
        repeat(50) {
            manager.activeNotifications.firstOrNull(predicate)?.let { return it }
            Thread.sleep(20)
        }
        error("Notification was not posted; grant notification permission to the disposable install")
    }
}
