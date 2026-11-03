package com.prl.wizard

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.UserManager
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.lifecycle.Lifecycle
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.appupdate.testing.FakeAppUpdateManager
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.review.ReviewInfo
import com.google.android.play.core.review.testing.FakeReviewManager
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Play-only callbacks under disconnect/recreation. Uses SDK fakes and never opens a purchase sheet. */
class StoreLifecycleCheck : Instrumentation() {
    private var activity: MainActivity? = null
    private lateinit var fake: FakeBillingClient

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val results = Bundle()
        if (!targetContext.getSystemService(UserManager::class.java).isUserUnlocked) {
            results.putString("stream", "store_checks_failed: unlock the test device first\n")
            finish(Activity.RESULT_CANCELED, results)
            return
        }
        val client = Billing::class.java.getDeclaredField("client").apply { isAccessible = true }
        val original = client.get(null)
        val failures = mutableListOf<String>()
        var passed = 0
        fun verify(name: String, block: () -> Unit) {
            try {
                fake = FakeBillingClient()
                main { client.set(null, fake) }
                activity = startActivitySync(Intent(Intent.ACTION_MAIN).setClassName(targetContext, MainActivity::class.java.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
                preparePage(activity!!)
                block()
                passed++
            } catch (error: Throwable) {
                failures += "$name: ${error.stackTraceToString()}"
            } finally {
                activity?.let { current ->
                    main { current.finish() }
                    await("activity did not finish") { current.isDestroyed }
                }
                activity = null
                // Reset the fixture even when exercising an implementation that leaks its old flow.
                main {
                    for (name in listOf("flow", "client")) Billing::class.java.getDeclaredField(name).apply { isAccessible = true }.set(null, null)
                }
            }
        }
        try {
            verify("disconnect releases a pending purchase") {
                val reply = purchase()
                main { fake.disconnect() }
                check(reply.done.await(2, TimeUnit.SECONDS)) { "Purchase remains pending after Billing disconnects" }
                check(reply.error.get() == "unavailable")
                check(reply.count.get() == 1)
            }
            verify("resume reconciles a purchase whose sheet callback was lost") {
                val reply = purchase()
                fake.purchases = listOf(paid())
                main { Billing.resume(activity!!) }
                check(reply.done.await(2, TimeUnit.SECONDS)) { "Owned purchase did not settle the original request" }
                check(reply.value.get()?.getString("state") == "purchased")
                main { updates().onPurchasesUpdated(FakeBillingClient.ok(), fake.purchases) }
                waitForIdleSync()
                check(reply.count.get() == 1)
            }
            verify("purchase completion after recreation reaches the new page") {
                purchase()
                activity = recreate(activity!!)
                main { updates().onPurchasesUpdated(FakeBillingClient.ok(), listOf(paid())) }
                await("Old activity swallowed the completed purchase") { js(activity!!, "window.storeEvents.some(e=>e.name==='billing')") == "true" }
            }
            verify("late product details do not launch a sheet on a destroyed activity") {
                fake.holdDetails = true
                val reply = Reply()
                main { Billing.purchase(activity!!) { value, error -> reply.finish(value, error) } }
                await("product query did not start") { fake.productListener != null }
                val listener = fake.productListener
                activity = recreate(activity!!)
                main { fake.productListener = listener; fake.replyProduct() }
                await("late product callback was ignored") { fake.launches > 0 || reply.done.count == 0L }
                check(fake.launches == 0) { "Purchase sheet launched for the destroyed activity" }
                check(reply.error.get() == "cancelled")
            }
            verify("late update response does not launch on a destroyed activity") {
                val manager = DeferredUpdateManager()
                val done = CountDownLatch(1)
                var started = true
                main { StoreUpdate.start(activity!!, manager) { started = it; done.countDown() } }
                activity = recreate(activity!!)
                manager.reply.setResult(updateInfo())
                check(done.await(2, TimeUnit.SECONDS))
                check(manager.launches == 0 && !started) { "Update UI launched for the destroyed activity" }
            }
            verify("late resume check does not restart an update on a destroyed activity") {
                val manager = DeferredUpdateManager()
                val source = FakeAppUpdateManager(targetContext).apply { setUpdateAvailable(999) }
                main {
                    source.startUpdateFlowForResult(source.appUpdateInfo.result, activity!!.storeUpdateFlow, AppUpdateOptions.defaultOptions(AppUpdateType.IMMEDIATE))
                    source.userAcceptsUpdate()
                    StoreUpdate.resume(activity!!, manager)
                }
                val progress = source.appUpdateInfo.result
                check(progress.updateAvailability() == 3)
                activity = recreate(activity!!)
                manager.reply.setResult(progress)
                waitForIdleSync()
                check(manager.launches == 0) { "Update restarted from the destroyed activity" }
            }
            verify("review delay does not open a sheet after the activity stops") {
                val manager = RecordingReviewManager()
                val delayed = CountDownLatch(1)
                main {
                    StoreReview.launch(activity!!, manager)
                    activity!!.moveTaskToBack(true)
                    Handler(Looper.getMainLooper()).postDelayed({ delayed.countDown() }, 2_000)
                }
                await("activity did not stop") { activity!!.lifecycle.currentState == Lifecycle.State.CREATED }
                check(delayed.await(3, TimeUnit.SECONDS))
                check(manager.launches == 0) { "Review sheet launched after its activity went to the background" }
            }
            verify("foreground reviews still launch after the delay") {
                val manager = RecordingReviewManager()
                main { StoreReview.launch(activity!!, manager) }
                check(manager.launched.await(3, TimeUnit.SECONDS))
                check(manager.launches == 1)
            }
            verify("background review requests do not consume the review cooldown") {
                val prefs = activity!!.getSharedPreferences("shell", android.content.Context.MODE_PRIVATE)
                val keys = listOf("review.launchCount", "review.firstLaunch", "review.lastAsked", "review.askedVersion")
                val saved = keys.associateWith { prefs.all[it] }
                try {
                    prefs.edit().putInt(keys[0], 3).putLong(keys[1], System.currentTimeMillis() / 1000 - 4 * 86_400)
                        .remove(keys[2]).remove(keys[3]).commit()
                    main { activity!!.moveTaskToBack(true) }
                    await("activity did not stop") { activity!!.lifecycle.currentState == Lifecycle.State.CREATED }
                    var asked = true
                    main { asked = ReviewPrompt.request(activity!!) }
                    check(!asked && !prefs.contains(keys[2]) && !prefs.contains(keys[3])) {
                        "A background request consumed the 120-day review cooldown"
                    }
                } finally {
                    prefs.edit().apply {
                        keys.forEach { key ->
                            when (val value = saved[key]) {
                                is Int -> putInt(key, value)
                                is Long -> putLong(key, value)
                                is String -> putString(key, value)
                                else -> remove(key)
                            }
                        }
                    }.commit()
                }
            }
        } finally {
            main { client.set(null, original) }
        }
        results.putString("stream", if (failures.isEmpty()) "store_checks_passed: $passed\n" else "store_checks_failed:\n${failures.joinToString("\n")}\n")
        finish(if (failures.isEmpty()) Activity.RESULT_OK else Activity.RESULT_CANCELED, results)
    }

    private class Reply {
        val done = CountDownLatch(1)
        val count = AtomicInteger()
        val error = AtomicReference<String?>()
        val value = AtomicReference<JSONObject?>()
        fun finish(purchase: JSONObject?, failure: String?) {
            count.incrementAndGet()
            value.set(purchase)
            error.set(failure)
            done.countDown()
        }
    }

    private inner class RecordingReviewManager : FakeReviewManager(targetContext) {
        var launches = 0
        val launched = CountDownLatch(1)
        override fun launchReviewFlow(activity: Activity, info: ReviewInfo): com.google.android.gms.tasks.Task<Void> {
            launches++
            launched.countDown()
            return super.launchReviewFlow(activity, info)
        }
    }

    private fun purchase(): Reply = Reply().also { reply ->
        main { Billing.purchase(activity!!) { value, error -> reply.finish(value, error) } }
        await("fake purchase flow did not launch") { fake.launches == 1 }
    }

    private fun paid() = Purchase("""{"productIds":["com.prl.wizard.pro"],"purchaseToken":"regression-test-only","purchaseState":0}""", "")

    private fun updates() = Billing::class.java.getDeclaredField("updates").apply { isAccessible = true }.get(null) as PurchasesUpdatedListener

    private fun main(block: () -> Unit) {
        val failure = AtomicReference<Throwable>()
        runOnMainSync { try { block() } catch (error: Throwable) { failure.set(error) } }
        failure.get()?.let { throw it }
    }

    private fun preparePage(current: MainActivity) {
        await("activity did not resume") { current.lifecycle.currentState == Lifecycle.State.RESUMED }
        main {
            view(current).loadDataWithBaseURL(WebRoutes.ORIGIN,
                "<html><script>window.storeEvents=[];window.__pearlEvent=(name,data)=>storeEvents.push({name,data});</script></html>",
                "text/html", "utf-8", null)
        }
        await("test document did not load") { js(current, "Array.isArray(window.storeEvents)") == "true" }
    }

    private fun recreate(old: MainActivity): MainActivity {
        val monitor = addMonitor(MainActivity::class.java.name, null, false)
        try {
            main { old.recreate() }
            val next = waitForMonitorWithTimeout(monitor, 5_000) as? MainActivity ?: error("Activity was not recreated")
            await("old activity was not destroyed") { old.isDestroyed }
            check(!old.isFinishing)
            activity = next
            preparePage(next)
            return next
        } finally {
            removeMonitor(monitor)
        }
    }

    private fun view(current: MainActivity) = MainActivity::class.java.getDeclaredField("webView").apply { isAccessible = true }.get(current) as WebView

    private fun js(current: MainActivity, expression: String): String? {
        val done = CountDownLatch(1)
        var result: String? = null
        main { view(current).evaluateJavascript(expression) { result = it; done.countDown() } }
        check(done.await(2, TimeUnit.SECONDS))
        return result
    }

    private fun await(message: String, condition: () -> Boolean) {
        repeat(100) {
            if (condition()) return
            Thread.sleep(30)
        }
        error(message)
    }

    private fun updateInfo(): AppUpdateInfo = FakeAppUpdateManager(targetContext).apply { setUpdateAvailable(999) }.appUpdateInfo.result

    private inner class DeferredUpdateManager : FakeAppUpdateManager(targetContext) {
        val reply = TaskCompletionSource<AppUpdateInfo>()
        var launches = 0
        override fun getAppUpdateInfo() = reply.task
        override fun startUpdateFlowForResult(info: AppUpdateInfo, launcher: ActivityResultLauncher<IntentSenderRequest>, options: AppUpdateOptions): Boolean {
            launches++
            return true
        }
    }
}
