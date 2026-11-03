package com.prl.wizard

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.os.UserManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger

/** Renderer termination and executor teardown; only run against a disposable installation. */
class ShellLifecycleCheck : Instrumentation() {
    private var activity: MainActivity? = null
    private var caseFilter: String? = null

    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); caseFilter = arguments?.getString("case"); start() }

    override fun onStart() {
        val result = Bundle()
        if (Build.VERSION.SDK_INT < 29 || !targetContext.getSystemService(UserManager::class.java).isUserUnlocked ||
            WalletSecretStore(targetContext).hasSecret() ||
            File(targetContext.applicationInfo.dataDir, "app_webview").exists()
        ) {
            result.putString("stream", "shell_checks_failed: use a fresh disposable installation on an unlocked API 29+ device\n")
            finish(Activity.RESULT_CANCELED, result)
            return
        }
        var passed = 0
        val failures = mutableListOf<String>()
        fun verify(name: String, test: () -> Unit) {
            if (caseFilter?.let { it !in name } == true) return
            sendStatus(0, Bundle().apply { putString("stream", "shell_check_start: $name\n") })
            try { test(); passed++ } catch (error: Throwable) {
                failures += "$name: ${error.stackTraceToString()}"
                sendStatus(0, Bundle().apply { putString("stream", "shell_check_failure: $name: $error\n") })
            }
            finally {
                activity?.let { current ->
                    main { current.finish() }
                    await("activity did not finish") { current.isDestroyed }
                }
                activity = null
            }
        }
        verify("main renderer terminates and recovers") { checkRenderer(popup = false) }
        verify("popup renderer terminates and shared main renderer recovers") { checkRenderer(popup = true) }
        verify("destroyed activities drain and shut down bridge executors") { checkExecutors() }
        verify("a renderer replacement dismisses its old JS confirm") { checkStaleDialog("confirm") }
        verify("a renderer replacement dismisses its old JS alert") { checkStaleDialog("alert") }
        verify("a renderer replacement dismisses its old JS prompt") { checkStaleDialog("prompt") }
        verify("popup iframe navigation does not open another app") { checkPopupSubframe() }
        result.putString("stream", if (failures.isEmpty()) "shell_checks_passed: $passed\n" else
            "shell_checks_failed:\n${failures.joinToString("\n")}\n")
        finish(if (failures.isEmpty()) Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
    }

    private fun launch(): MainActivity {
        val current = startActivitySync(Intent(Intent.ACTION_MAIN).setClassName(targetContext, MainActivity::class.java.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        activity = current
        await("activity did not resume") { current.lifecycle.currentState == Lifecycle.State.RESUMED }
        main { view(current)!!.loadDataWithBaseURL(WebRoutes.ORIGIN, "<html>Shell checks</html>", "text/html", "utf-8", null) }
        checkScript(view(current)!!)
        return current
    }

    private fun checkRenderer(popup: Boolean) {
        val current = launch()
        val original = view(current)!!
        var target = original
        val popupGone = CountDownLatch(1)
        if (popup) {
            val opened = CountDownLatch(1)
            val child = AtomicReference<WebView>()
            main {
                val chrome = original.webChromeClient!!
                original.webChromeClient = object : WebChromeClient() {
                    override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                        val handled = chrome.onCreateWindow(view, isDialog, isUserGesture, resultMsg)
                        val created = (resultMsg.obj as WebView.WebViewTransport).webView!!
                        child.set(created)
                        val client = created.webViewClient
                        created.webViewClient = object : WebViewClient() {
                            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                                val handledGone = client.onRenderProcessGone(view, detail)
                                if (handledGone) popupGone.countDown()
                                return handledGone
                            }
                        }
                        opened.countDown()
                        return handled
                    }
                }
                original.evaluateJavascript("window.open('about:blank')", null)
            }
            check(opened.await(3, TimeUnit.SECONDS)) { "The shell did not create its popup" }
            target = child.get()
        }
        val renderer = AtomicReference<android.webkit.WebViewRenderProcess>()
        main {
            renderer.set(target.webViewRenderProcess ?: error("No renderer process"))
            check(renderer.get().terminate()) { "Could not terminate the live renderer" }
        }
        if (popup) check(popupGone.await(5, TimeUnit.SECONDS)) { "Popup did not handle renderer termination" }
        await("main WebView was not replaced") { view(current)?.let { it !== original } == true }
        checkScript(view(current)!!)
        main {
            val replacement = view(current)!!
            check(replacement.webViewRenderProcess !== renderer.get())
            check(original.parent == null) { "Dead WebView was retained in the view hierarchy" }
            check((replacement.parent as android.view.ViewGroup).childCount == 1)
        }
    }

    private fun checkExecutors() {
        val current = launch()
        val bridge = MainActivity::class.java.getDeclaredField("bridge").apply { isAccessible = true }.get(current) as Bridge
        val pools = listOf("walletWork", "network").map { name ->
            Bridge::class.java.getDeclaredField(name).apply { isAccessible = true }.get(bridge) as ExecutorService
        }
        val entered = CountDownLatch(pools.size)
        val release = CountDownLatch(1)
        val drained = CountDownLatch(pools.size)
        val queued = CountDownLatch(pools.size)
        try {
            pools.forEach { pool -> pool.execute { entered.countDown(); release.await(); drained.countDown() } }
            check(entered.await(3, TimeUnit.SECONDS))
            pools.forEach { pool -> pool.execute { queued.countDown() } }
            main { current.finish() }
            await("activity did not finish") { current.isDestroyed }
            check(pools.all { it.isShutdown }) { "Bridge executor remained open after activity destruction" }
            release.countDown()
            check(drained.await(3, TimeUnit.SECONDS)) { "Closing the bridge interrupted an existing request" }
            check(queued.await(3, TimeUnit.SECONDS)) { "Closing the bridge dropped queued work" }
            check(pools.all { it.awaitTermination(3, TimeUnit.SECONDS) }) { "A bridge thread survived activity destruction" }
        } finally {
            release.countDown()
            pools.forEach { it.shutdownNow() }
        }
    }

    private fun checkStaleDialog(kind: String) {
        val current = launch()
        val original = view(current)!!
        val message = "audit-dialog-$kind"
        try {
            main { original.evaluateJavascript("$kind('$message')", null) }
            await("JavaScript dialog did not appear") { findDialog(message) != null }
            main { check(original.webViewRenderProcess!!.terminate()) }
            await("main WebView was not replaced") { view(current)?.let { it !== original } == true }
            checkScript(view(current)!!)
            check(findDialog(message) == null) { "A dialog from the dead document still blocks the replacement page" }
        } finally {
            main { findDialog(message)?.let { root ->
                root.findViewById<View>(android.R.id.button2)?.takeIf { it.isShown }?.performClick()
                    ?: root.findViewById<View>(android.R.id.button1)?.performClick()
            } }
        }
    }

    private fun findDialog(message: String): View? = WindowInspector.getGlobalWindowViews().firstOrNull { root ->
        fun contains(view: View): Boolean = (view is TextView && view.text.toString() == message) ||
            (view is ViewGroup && (0 until view.childCount).any { contains(view.getChildAt(it)) })
        root.isShown && contains(root)
    }

    private fun checkPopupSubframe() {
        val current = launch()
        val original = view(current)!!
        val requests = CountDownLatch(1)
        val external = AtomicInteger()
        val child = AtomicReference<WebView>()
        val monitor = object : ActivityMonitor() {
            override fun onStartActivity(intent: Intent): ActivityResult? {
                if (intent.action != Intent.ACTION_VIEW) return null
                external.incrementAndGet()
                sendStatus(0, Bundle().apply { putString("stream", "popup_external_open: ${intent.data}\n") })
                return ActivityResult(Activity.RESULT_CANCELED, null)
            }
        }
        addMonitor(monitor)
        try {
            main {
                val chrome = original.webChromeClient!!
                original.webChromeClient = object : WebChromeClient() {
                    override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                        val handled = chrome.onCreateWindow(view, isDialog, isUserGesture, resultMsg)
                        val created = (resultMsg.obj as WebView.WebViewTransport).webView!!
                        child.set(created)
                        val client = created.webViewClient
                        created.webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                if (!request.isForMainFrame && request.url.scheme == "mailto") {
                                    sendStatus(0, Bundle().apply { putString("stream", "popup_subframe: ${request.url}\n") })
                                    requests.countDown()
                                }
                                return client.shouldOverrideUrlLoading(view, request)
                            }
                            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) =
                                client.onPageStarted(view, url, favicon)
                            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail) = client.onRenderProcessGone(view, detail)
                        }
                        return handled
                    }
                }
                original.evaluateJavascript("window.auditPopup=window.open('about:blank')", null)
            }
            await("The shell did not create its popup") { child.get() != null }
            waitForIdleSync()
            main { child.get().loadDataWithBaseURL("about:blank", "<html><iframe src=\"mailto:popup-qa@example.invalid\"></iframe></html>", "text/html", "utf-8", null) }
            check(requests.await(3, TimeUnit.SECONDS)) { "The popup did not navigate its iframe" }
            waitForIdleSync()
            check(external.get() == 0) { "An iframe URL was launched externally instead of the popup's main URL" }
            main { original.evaluateJavascript("auditPopup.location='https://pearlhub.net/privacy.html'", null) }
            await("Popup's main-frame navigation no longer opens externally") { external.get() == 1 }
        } finally {
            removeMonitor(monitor)
            main { child.get()?.destroy() }
        }
    }

    private fun view(current: MainActivity): WebView? =
        MainActivity::class.java.getDeclaredField("webView").apply { isAccessible = true }.get(current) as WebView?

    private fun checkScript(webView: WebView) {
        val done = CountDownLatch(1)
        val answer = AtomicReference<String>()
        main { webView.evaluateJavascript("1+1") { answer.set(it); done.countDown() } }
        check(done.await(5, TimeUnit.SECONDS) && answer.get() == "2") { "WebView did not run JavaScript" }
    }

    private fun main(block: () -> Unit) {
        val failure = AtomicReference<Throwable>()
        runOnMainSync { try { block() } catch (error: Throwable) { failure.set(error) } }
        failure.get()?.let { throw it }
    }

    private fun await(message: String, condition: () -> Boolean) {
        repeat(200) { if (condition()) return; Thread.sleep(30) }
        error(message)
    }
}
