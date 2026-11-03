package com.prl.wizard

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.UserManager
import android.webkit.WebView
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.Lifecycle
import androidx.webkit.JavaScriptExecutionException
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewOutcomeReceiver
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Native bridge regressions; only run against a disposable installation. */
class WalletLifecycleCheck : Instrumentation() {
    private var activity: MainActivity? = null

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        if (!targetContext.getSystemService(UserManager::class.java).isUserUnlocked) {
            result.putString("stream", "wallet_checks_failed: unlock the test device first\n")
            finish(Activity.RESULT_CANCELED, result)
            return
        }
        val store = WalletSecretStore(targetContext)
        // Never replace an existing wallet secret or a browser wallet in a person's installation.
        if (store.hasSecret() || File(targetContext.applicationInfo.dataDir, "app_webview").exists()) {
            result.putString("stream", "wallet_checks_failed: use a fresh disposable installation\n")
            finish(Activity.RESULT_CANCELED, result)
            return
        }
        var passed = 0
        val failures = mutableListOf<String>()
        fun verify(name: String, block: () -> Unit) {
            try {
                block()
                passed++
            } catch (error: Throwable) {
                failures += "$name: ${error.stackTraceToString()}"
            } finally {
                store.clear()
                activity?.let { current ->
                    main { current.finish() }
                    await("activity did not finish") { current.isDestroyed }
                }
                activity = null
            }
        }
        verify("concurrent store instances preserve one secret") {
            val other = WalletSecretStore(targetContext)
            check(!store.hasSecret() && !other.hasSecret())
            val start = CountDownLatch(1)
            val failure = AtomicReference<Throwable>()
            val first = AtomicReference<String>()
            val second = AtomicReference<String>()
            val threads = listOf(thread {
                try { start.await(); first.set(store.create()) } catch (error: Throwable) { failure.set(error) }
            }, thread {
                try { start.await(); second.set(other.create()) } catch (error: Throwable) { failure.set(error) }
            })
            start.countDown()
            threads.forEach { it.join(20_000) }
            check(threads.none { it.isAlive })
            failure.get()?.let { throw it }
            check(first.get() == second.get()) { "Another store instance replaced the first secret" }
            check(store.read() == first.get() && other.read() == first.get())
        }
        verify("read-only resolution after clear cannot create a key") {
            store.create()
            val access = store.access()
            WalletSecretStore(targetContext).clear()
            check(store.resolve(create = false, access) == null)
            check(!store.hasSecret())
        }
        verify("clear invalidates pending creation but permits a fresh request") {
            val pending = store.access()
            WalletSecretStore(targetContext).clear()
            check(store.resolve(create = true, pending) == null)
            check(!store.hasSecret()) { "A stale authentication request resurrected the secret" }
            val fresh = store.resolve(create = true, store.access())
            check(fresh != null && store.read() == fresh)
        }
        verify("background authentication responds instead of silently hanging") {
            val current = launch()
            main { current.moveTaskToBack(true) }
            await("fragment state was not saved") { current.supportFragmentManager.isStateSaved }
            val done = CountDownLatch(1)
            var error: String? = null
            main { current.walletAuth.authenticate("Unlock", null) { error = it; done.countDown() } }
            check(done.await(2, TimeUnit.SECONDS)) { "Biometric request never replied after state saving" }
            check(error == "cancelled")
            check(noPendingAuth(current.walletAuth))
        }
        verify("authentication on a destroyed activity is cancelled") {
            val current = launch()
            main { current.finish() }
            await("activity did not finish") { current.isDestroyed }
            var error: String? = null
            main { current.walletAuth.authenticate("Unlock", null) { error = it } }
            check(error == "cancelled")
            check(noPendingAuth(current.walletAuth))
        }
        verify("a prompt-start exception releases the pending callback") {
            val current = launch()
            var error: String? = null
            main { current.walletAuth.authenticate("", null) { error = it } }
            check(error == "failed")
            check(noPendingAuth(current.walletAuth)) { "A failed prompt blocked future authentication" }
        }
        verify("background file.save does not open a document picker") { checkBackgroundExport(staging = false) }
        verify("staging completed after state saving does not open a document picker") { checkBackgroundExport(staging = true) }
        if (Build.VERSION.SDK_INT >= 33) {
            verify("stop and newer starts invalidate pending permission results") { checkPendingLiveStart() }
        }
        result.putString("stream", if (failures.isEmpty()) "wallet_checks_passed: $passed\n" else "wallet_checks_failed:\n${failures.joinToString("\n")}\n")
        finish(if (failures.isEmpty()) Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
    }

    private fun launch(): MainActivity {
        val current = startActivitySync(Intent(Intent.ACTION_MAIN).setClassName(targetContext, MainActivity::class.java.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        activity = current
        await("activity did not resume") { current.lifecycle.currentState == Lifecycle.State.RESUMED }
        check(current.walletAuth.deviceSecure()) { "The disposable test device needs its existing screen lock" }
        // A local empty document keeps browser-wallet creation and server requests out of these checks.
        main {
            val view = MainActivity::class.java.getDeclaredField("webView").apply { isAccessible = true }.get(current) as WebView
            view.loadDataWithBaseURL(WebRoutes.ORIGIN, "<html><body>Wallet lifecycle checks</body></html>", "text/html", "utf-8", null)
        }
        return current
    }

    private fun noPendingAuth(auth: WalletAuth): Boolean =
        WalletAuth::class.java.getDeclaredField("pending").apply { isAccessible = true }.get(auth) == null

    private class Reply : JavaScriptReplyProxy() {
        private val done = CountDownLatch(1)
        private val value = AtomicReference<JSONObject>()
        override fun postMessage(message: String) { value.set(JSONObject(message)); done.countDown() }
        override fun postMessage(message: ByteArray) = error("Unexpected binary bridge reply")
        override fun executeJavaScript(script: String, receiver: WebViewOutcomeReceiver<String, JavaScriptExecutionException>?) =
            error("Unexpected bridge script")
        fun await(): JSONObject {
            check(done.await(3, TimeUnit.SECONDS)) { "Bridge request did not reply" }
            return value.get()
        }
    }

    private fun checkPendingLiveStart() {
        val current = launch()
        val bridge = Bridge(current)
        val view = MainActivity::class.java.getDeclaredField("webView").apply { isAccessible = true }.get(current) as WebView
        val first = Reply()
        val second = Reply()
        val stopped = Reply()
        fun request(method: String, reply: Reply, style: String = "full") {
            val params = if (method == "live.start") JSONObject().put("title", style).put("label1h", "1h")
                .put("label24h", "24h").put("style", style).put("state", JSONObject().put("usd", 1)) else JSONObject()
            bridge.onPostMessage(view, WebMessageCompat(JSONObject().put("id", method + style).put("method", method)
                .put("params", params).toString()), Uri.parse(WebRoutes.ORIGIN), true, reply)
        }
        try {
            main {
                check(Notifications.permission(current) != "granted") { "Use a fresh installation without notification permission" }
                // Both starts share the real system permission prompt; stop wins while it is
                // still open. The older start is also superseded by the second style request.
                request("live.start", first, "full")
                request("live.start", second, "island")
                request("live.stop", stopped)
            }
            check(stopped.await().getBoolean("ok"))
            await("notification permission prompt did not appear") {
                val root = uiAutomation.rootInActiveWindow ?: return@await false
                findPermissionAllow(root)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
            }
            val replies = listOf(first.await(), second.await())
            check(replies.all { !it.getBoolean("ok") && it.optString("error") == "cancelled" }) {
                "Stale permission callbacks restarted or overwrote the ticker: $replies"
            }
            check(!LiveTicker.running(current)) { "A permission callback re-enabled a stopped ticker" }
            val fresh = Reply()
            main { request("live.start", fresh, "island") }
            check(fresh.await().getBoolean("ok"))
            check(LiveTicker.status(current).getString("style") == "island")
        } finally {
            main { LiveTicker.stop(current) }
        }
    }

    private fun findPermissionAllow(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.viewIdResourceName?.endsWith(":id/permission_allow_button") == true) return node
        repeat(node.childCount) { index -> node.getChild(index)?.let { findPermissionAllow(it)?.let { found -> return found } } }
        return null
    }

    private fun checkBackgroundExport(staging: Boolean) {
        val current = launch()
        val attempts = AtomicInteger()
        val monitor = object : ActivityMonitor() {
            override fun onStartActivity(intent: Intent): ActivityResult? {
                if (intent.action != Intent.ACTION_CREATE_DOCUMENT) return null
                attempts.incrementAndGet()
                return ActivityResult(Activity.RESULT_CANCELED, null)
            }
        }
        addMonitor(monitor)
        val io = MainActivity::class.java.getDeclaredField("io").apply { isAccessible = true }.get(null) as ExecutorService
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val done = CountDownLatch(1)
        var error: String? = null
        fun request() = current.saveFile("check.json", "application/json", "{}") { error = it; done.countDown() }
        try {
            if (staging) {
                io.execute { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
                check(entered.await(2, TimeUnit.SECONDS))
                main { request() }
            }
            main { current.moveTaskToBack(true) }
            await("fragment state was not saved") { current.supportFragmentManager.isStateSaved }
            if (!staging) main { request() }
            release.countDown()
            await("export did not finish staging") { done.count == 0L || attempts.get() > 0 }
            targetContext.startActivity(Intent(Intent.ACTION_MAIN).setClassName(targetContext, MainActivity::class.java.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            await("activity did not resume") { current.lifecycle.currentState == Lifecycle.State.RESUMED }
            check(done.await(2, TimeUnit.SECONDS))
            check(error == "cancelled")
            check(attempts.get() == 0) { "Document picker launched after the activity saved state" }
            val drained = CountDownLatch(1)
            io.execute { drained.countDown() }
            check(drained.await(2, TimeUnit.SECONDS))
            check(File(current.noBackupFilesDir, "pending-exports").listFiles().orEmpty().isEmpty())
        } finally {
            release.countDown()
            removeMonitor(monitor)
        }
    }

    private fun main(block: () -> Unit) {
        val failure = AtomicReference<Throwable>()
        runOnMainSync { try { block() } catch (error: Throwable) { failure.set(error) } }
        failure.get()?.let { throw it }
    }

    private fun await(message: String, condition: () -> Boolean) {
        repeat(100) {
            if (condition()) return
            Thread.sleep(30)
        }
        error(message)
    }
}
