package com.prl.wizard

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.PersistableBundle
import android.provider.Settings
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.HapticFeedbackConstants
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.WindowManager
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.graphics.drawable.toDrawable
import androidx.core.net.toUri
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.webkit.ServiceWorkerClientCompat
import androidx.webkit.ServiceWorkerControllerCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONObject
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.roundToInt

/** The single activity: a WebView showing the bundled web UI, plus the native pieces it calls. */
class MainActivity : AppCompatActivity() {
    private lateinit var root: FrameLayout
    private lateinit var content: LocalContent
    private lateinit var bridge: Bridge
    lateinit var walletAuth: WalletAuth
        private set
    private var webView: WebView? = null
    /** The density the WebView was made for (see onConfigurationChanged). */
    private var webDensity = 0
    private var pageLoaded = false
    private var keyboardShown = false
    private var keepSplash = true
    private var themed = false
    private var background = Color.TRANSPARENT
    private var night = false
    private val notificationWaiters = mutableListOf<(String) -> Unit>()
    private val webDialogs = mutableMapOf<AlertDialog, () -> Unit>()
    private val endSplash = Runnable { keepSplash = false }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        Notifications.recordPromptResult(this)
        val state = Notifications.permission(this)
        notificationWaiters.toList().also { notificationWaiters.clear() }.forEach { it(state) }
    }

    private val confirmCredential = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        walletAuth.onConfirmCredentialResult(it.resultCode)
    }

    /** `file.save`: the system's "save as" screen for one text file (the account's settings export). */
    private var pendingSave: ((String?) -> Unit)? = null
    private var pendingExport: String? = null
    private val exports by lazy { PendingExportStore(File(noBackupFilesDir, "pending-exports")) }
    private val createDocument = registerForActivityResult(object : ActivityResultContract<Pair<String, String>, Uri?>() {
        override fun createIntent(context: Context, input: Pair<String, String>) = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE).setType(input.second).putExtra(Intent.EXTRA_TITLE, input.first)

        override fun parseResult(resultCode: Int, intent: Intent?): Uri? = if (resultCode == RESULT_OK) intent?.data else null
    }) { uri ->
        val export = pendingExport ?: return@registerForActivityResult
        val done = pendingSave
        pendingExport = null
        pendingSave = null
        io.execute {
            val error = try {
                if (uri == null) {
                    "cancelled"
                } else {
                    exports.open(export).use { input ->
                        contentResolver.openOutputStream(uri, "wt")?.use { input.copyTo(it) }
                            ?: throw IllegalStateException("no stream")
                    }
                    null
                }
            } catch (e: Exception) {
                Log.w(TAG, "file.save failed", e)
                "failed"
            } finally {
                exports.discard(export)
            }
            main.post { done?.invoke(error) }
        }
    }

    /** `update.start`: Google Play's full-screen update (Play build; see StoreUpdate). */
    val storeUpdateFlow = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        if (it.resultCode != RESULT_OK) Log.i(TAG, "store update not taken (${it.resultCode})")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        pendingExport = savedInstanceState?.getString(PENDING_EXPORT)
        val restoredExport = pendingExport
        io.execute { exports.discardExcept(restoredExport) }
        splash.setKeepOnScreenCondition { keepSplash }
        main.postDelayed(endSplash, SPLASH_CAP_MS)
        current = WeakReference(this)
        ReviewPrompt.registerLaunch(this)
        applyOrientation()

        walletAuth = WalletAuth(this, confirmCredential)
        content = LocalContent(assets)
        bridge = Bridge(this)
        background = ContextCompat.getColor(this, R.color.page_background)
        night = isNight()
        applyBars(night)

        root = FrameLayout(this)
        root.setBackgroundColor(background)
        setContentView(root)
        // The page never sits under the status bar, navigation bar, a cutout or the keyboard.
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, max(bars.bottom, ime.bottom))
            // The page hides its bottom tab bar while the keyboard is up, as iOS does.
            val keyboard = insets.isVisible(WindowInsetsCompat.Type.ime())
            if (keyboard != keyboardShown) {
                keyboardShown = keyboard
                if (pageLoaded) sendEvent("keyboard", JSONObject().put("visible", keyboard))
            }
            WindowInsetsCompat.CONSUMED
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = back()
        })

        // Recreated for another display's density: back to the page that was open.
        val page = savedInstanceState?.getString(PAGE_URL)?.takeIf { WebRoutes.isAppUrl(it) }
        if (createWebView()) {
            if (page != null) webView?.loadUrl(page) else load(routeFrom(intent))
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val route = routeFrom(intent) ?: return
        if (pageLoaded) sendEvent("notification", JSONObject().put("route", route)) else load(route)
    }

    override fun onResume() {
        super.onResume()
        webView?.onResume()
        sendEvent("resume")
        Billing.resume(this)
        StoreUpdate.resume(this)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        // The text can be 2 MiB; only its private filename belongs in the saved-state Bundle.
        pendingExport?.let { outState.putString(PENDING_EXPORT, it) }
        webView?.url?.takeIf { WebRoutes.isAppUrl(it) }?.let { outState.putString(PAGE_URL, it) }
        super.onSaveInstanceState(outState)
    }

    override fun onPause() {
        sendEvent("pause")
        webView?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        Billing.detach(this)
        dismissWebDialogs()
        bridge.close()
        main.removeCallbacks(endSplash)
        if (current?.get() === this) current = null
        webView?.destroy()
        webView = null
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // A WebView keeps drawing at the scale of the display it was made on. Moved to a display of
        // another density (razr: the 420 dpi main screen and the 360 dpi cover screen), it lays the
        // page out for the new window but draws it at the old scale, so the page runs off the right
        // and bottom edges. A new activity gets a WebView for the display it is on; the page reopens
        // where it was (onSaveInstanceState).
        if (webView != null && newConfig.densityDpi != webDensity) {
            recreate()
            return
        }
        webView?.settings?.textZoom = textZoom(newConfig)
        Notifications.ensureChannels(this)
        LiveTicker.refresh(this)
        applyOrientation()
        if (isNight() != night) {
            night = isNight()
            // Widgets on the "system" appearance below Android 12 pick their colours when drawn.
            Widgets.redrawLater(this)
        }
        if (!themed) {
            background = ContextCompat.getColor(this, R.color.page_background)
            paintBackground()
            applyBars(isNight())
        }
    }

    // region WebView

    private fun createWebView(): Boolean {
        val view = try {
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                null
            } else {
                WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
                WebView(this)
            }
        } catch (e: RuntimeException) {
            // No WebView provider installed or enabled.
            Log.w(TAG, "WebView unavailable", e)
            null
        }
        if (view == null) {
            showWebViewUpdate()
            return false
        }
        configure(view)
        root.addView(view, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        webView = view
        webDensity = resources.configuration.densityDpi
        return true
    }

    // WEB_MESSAGE_LISTENER was checked in createWebView before the WebView was made.
    @SuppressLint("SetJavaScriptEnabled", "RequiresFeature")
    private fun configure(view: WebView) {
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            safeBrowsingEnabled = true
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            setGeolocationEnabled(false)
            userAgentString = "$userAgentString PearlHubAndroid/${BuildConfig.VERSION_NAME}"
            textZoom = textZoom(resources.configuration)
        }
        // prefers-color-scheme follows the DayNight theme; the page draws its own dark mode.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(view.settings, false)
        }
        view.setBackgroundColor(background)
        view.webViewClient = ShellClient()
        view.webChromeClient = ShellChromeClient()
        view.setDownloadListener { url, _, _, _, _ -> openExternal(url) }
        WebViewCompat.addWebMessageListener(view, "PearlAndroid", setOf(WebRoutes.ORIGIN), bridge)
        interceptServiceWorkers()
    }

    /** A service worker's fetches skip the WebViewClient; route them through the same rules. */
    private fun interceptServiceWorkers() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_BASIC_USAGE) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_SHOULD_INTERCEPT_REQUEST)
        ) return
        val controller = ServiceWorkerControllerCompat.getInstance()
        controller.setServiceWorkerClient(object : ServiceWorkerClientCompat() {
            override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? = content.intercept(request)
        })
        val settings = controller.serviceWorkerWebSettings
        if (WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_FILE_ACCESS)) settings.allowFileAccess = false
        if (WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_CONTENT_ACCESS)) settings.allowContentAccess = false
    }

    private fun load(route: String?) {
        webView?.loadUrl(WebRoutes.startUrl(BuildConfig.STORE, route))
    }

    private fun replaceWebView() {
        dismissWebDialogs()
        webView?.let {
            root.removeView(it)
            it.destroy()
        }
        webView = null
        pageLoaded = false
        if (createWebView()) load(null)
    }

    private fun textZoom(config: Configuration) = (config.fontScale * 100).roundToInt().coerceAtMost(130)

    private fun back() {
        val view = webView
        if (view == null || !pageLoaded) {
            moveTaskToBack(true)
            return
        }
        view.evaluateJavascript("window.__pearlBack ? window.__pearlBack() : false") { result ->
            if (result != "true") moveTaskToBack(true)
        }
    }

    fun sendEvent(name: String, data: JSONObject = JSONObject()) {
        webView?.evaluateJavascript("window.__pearlEvent && window.__pearlEvent(${JSONObject.quote(name)}, $data)", null)
    }

    private inner class ShellClient : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
            content.intercept(request)

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toString()
            if (!request.isForMainFrame) {
                val scheme = request.url.scheme?.lowercase()
                return scheme !in setOf("https", "http", "about", "blob", "data")
            }
            if (WebRoutes.isAppUrl(url)) return false
            openExternal(url)
            return true
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            pageLoaded = false
        }

        override fun onPageFinished(view: WebView, url: String?) {
            pageLoaded = true
            keepSplash = false
            if (keyboardShown) sendEvent("keyboard", JSONObject().put("visible", true))
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            // A network page of the app origin failed (offline): return to the bundled UI.
            val url = request.url.toString()
            if (request.isForMainFrame && WebRoutes.isAppUrl(url) && WebRoutes.assetPath(url) == null) load(null)
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            Log.w(TAG, "WebView renderer gone (crash=${detail.didCrash()})")
            if (view === webView) replaceWebView()
            return true
        }
    }

    private inner class ShellChromeClient : WebChromeClient() {
        /** target=_blank and window.open: the URL arrives as the popup's first navigation. */
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            val popup = WebView(this@MainActivity)
            var done = false
            fun finish(url: String?) {
                if (done) return
                done = true
                if (url != null) openExternal(url)
                main.post { popup.destroy() }
            }
            popup.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                    if (!request.isForMainFrame) return true
                    finish(request.url.toString())
                    return true
                }

                override fun onPageStarted(v: WebView, url: String?, favicon: Bitmap?) {
                    if (url.isNullOrEmpty() || url == "about:blank") return
                    v.stopLoading()
                    finish(url)
                }

                override fun onRenderProcessGone(v: WebView, detail: RenderProcessGoneDetail): Boolean {
                    finish(null)
                    return true
                }
            }
            main.postDelayed({ finish(null) }, POPUP_TIMEOUT_MS)
            (resultMsg.obj as WebView.WebViewTransport).webView = popup
            resultMsg.sendToTarget()
            return true
        }

        override fun onJsAlert(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
            var answered = false
            showWebDialog(dialog(message).setPositiveButton(android.R.string.ok) { _, _ -> answered = true; result.confirm() }) {
                if (!answered) result.confirm()
            }
            return true
        }

        override fun onJsConfirm(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
            var answered = false
            showWebDialog(dialog(message)
                .setPositiveButton(android.R.string.ok) { _, _ -> answered = true; result.confirm() }
                .setNegativeButton(android.R.string.cancel, null)) { if (!answered) result.cancel() }
            return true
        }

        override fun onJsPrompt(view: WebView, url: String?, message: String?, defaultValue: String?, result: JsPromptResult): Boolean {
            var answered = false
            val input = EditText(themedForDialogs()).apply {
                setText(defaultValue.orEmpty())
                setSingleLine()
                selectAll()
            }
            val padding = (20 * resources.displayMetrics.density).roundToInt()
            val frame = FrameLayout(this@MainActivity).apply {
                setPadding(padding, padding / 2, padding, 0)
                addView(input)
            }
            showWebDialog(dialog(message)
                .setView(frame)
                .setPositiveButton(android.R.string.ok) { _, _ -> answered = true; result.confirm(input.text.toString()) }
                .setNegativeButton(android.R.string.cancel, null)) { if (!answered) result.cancel() }
            input.requestFocus()
            return true
        }

        private fun dialog(message: String?) = MaterialAlertDialogBuilder(themedForDialogs()).setMessage(message.orEmpty())
    }

    // endregion

    private fun showWebDialog(builder: MaterialAlertDialogBuilder, dismissed: () -> Unit) {
        val dialog = builder.create()
        var finished = false
        val finish: () -> Unit = {
            if (!finished) {
                finished = true
                webDialogs -= dialog
                dismissed()
            }
        }
        webDialogs[dialog] = finish
        dialog.setOnDismissListener { finish() }
        dialog.show()
    }

    private fun dismissWebDialogs() {
        // A custom JS dialog belongs to its WebView document, which cannot survive replacement.
        webDialogs.toList().forEach { (dialog, finish) -> finish(); dialog.dismiss() }
    }

    // region Native features called from the bridge

    internal fun canOpenStoreUi(): Boolean =
        !isFinishing && !isDestroyed && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)

    fun applyTheme(color: Int, dark: Boolean) {
        themed = true
        keepSplash = false
        background = color
        paintBackground()
        applyBars(dark)
    }

    private fun paintBackground() {
        window.setBackgroundDrawable(background.toDrawable())
        root.setBackgroundColor(background)
        webView?.setBackgroundColor(background)
    }

    /** Transparent bars over the page colour; `dark` picks light icons. */
    private fun applyBars(dark: Boolean) {
        val style = if (dark) {
            SystemBarStyle.dark(Color.TRANSPARENT)
        } else {
            SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        }
        enableEdgeToEdge(style, style)
    }

    /**
     * The activity handles uiMode changes itself (the page must not reload), so its theme keeps
     * the colours it started with. Dialogs get a theme resolved for the current day/night mode.
     */
    /** The page's alert / confirm / prompt dialogs: the app theme, and OK / Cancel in the app's
     *  language (set in 设置, published with the widget snapshot), which can differ from the phone's. */
    private fun themedForDialogs() = ContextThemeWrapper(this, R.style.Theme_PearlHub).apply {
        val lang = runCatching { Widgets.state(this@MainActivity).lang }.getOrNull() ?: return@apply
        applyOverrideConfiguration(Configuration().apply { setLocale(WidgetFormat.locale(lang)) })
    }

    private fun isNight() =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    fun setSecure(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    fun openExternal(url: String): Boolean {
        if (!WebRoutes.isExternalAllowed(url)) return false
        return try {
            startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE))
            true
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_app_for_link, Toast.LENGTH_SHORT).show()
            false
        }
    }

    fun share(text: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        startActivity(Intent.createChooser(send, null))
    }

    fun copy(text: String, sensitive: Boolean) {
        val clip = ClipData.newPlainText(getString(R.string.app_name), text)
        if (sensitive) {
            // ClipDescription.EXTRA_IS_SENSITIVE (API 33); older systems ignore it.
            clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        }
        getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
    }

    /** After a pasted recovery phrase was used: clears the clipboard if it still holds `text` (iOS does the same). */
    fun clearClipboard(text: String): Boolean {
        val clipboard = getSystemService(ClipboardManager::class.java)
        if (text.isEmpty() || paste().trim() != text.trim()) return false
        if (Build.VERSION.SDK_INT >= 28) clipboard.clearPrimaryClip() else clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
        return true
    }

    fun saveFile(name: String, mime: String, text: String, done: (String?) -> Unit) {
        if (isDestroyed || isFinishing || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return done("cancelled")
        if (pendingSave != null || pendingExport != null) return done("failed")
        pendingSave = done
        io.execute {
            val export = try {
                exports.stage(text)
            } catch (e: Exception) {
                Log.w(TAG, "file.save staging failed", e)
                null
            }
            main.post {
                // Staging can outlive onSaveInstanceState/onStop without the activity being destroyed.
                // A picker launched then would not have its export filename in the saved state.
                if (export == null || isDestroyed || isFinishing || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    if (export != null) io.execute { exports.discard(export) }
                    pendingSave = null
                    done(if (export == null) "failed" else "cancelled")
                    return@post
                }
                pendingExport = export
                try {
                    createDocument.launch(name to mime)
                } catch (e: RuntimeException) {
                    pendingExport = null
                    pendingSave = null
                    io.execute { exports.discard(export) }
                    Log.w(TAG, "file.save picker failed", e)
                    done(if (e is ActivityNotFoundException) "unavailable" else "failed")
                }
            }
        }
    }

    // 设置 → 外观 → 锁定竖屏, on by default as on iPhone. Phones only: Android 16 ignores orientation
    // requests on large screens anyway, and iOS offers the switch on iPhone only.
    fun isPhone() = resources.configuration.smallestScreenWidthDp < 600

    fun lockPortrait() = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(LOCK_PORTRAIT, true)

    fun setLockPortrait(lock: Boolean) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit { putBoolean(LOCK_PORTRAIT, lock) }
        applyOrientation()
    }

    @SuppressLint("SourceLockedOrientationActivity")
    private fun applyOrientation() {
        val wanted = if (isPhone() && lockPortrait()) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        if (requestedOrientation != wanted) requestedOrientation = wanted
    }

    fun paste(): String {
        val clip = getSystemService(ClipboardManager::class.java).primaryClip ?: return ""
        if (clip.itemCount == 0) return ""
        return clip.getItemAt(0).text?.toString().orEmpty()
    }

    fun haptic(kind: String): Boolean {
        val feedback = when (kind) {
            "light" -> HapticFeedbackConstants.CLOCK_TICK
            "success" -> if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
            "warning" -> if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
            else -> return false
        }
        (webView ?: root).performHapticFeedback(feedback)
        return true
    }

    fun requestNotifications(done: (String) -> Unit) {
        val state = Notifications.permission(this)
        if (Build.VERSION.SDK_INT < 33 || state == "granted") return done(state)
        notificationWaiters += done
        if (notificationWaiters.size == 1) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    fun openNotificationSettings() {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri()))
        }
    }

    fun openSecuritySettings() {
        try {
            startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
        } catch (_: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    // endregion

    private fun showWebViewUpdate() {
        keepSplash = false
        val provider = try {
            WebViewCompat.getCurrentWebViewPackage(this)?.packageName
        } catch (_: RuntimeException) {
            null
        } ?: "com.google.android.webview"
        MaterialAlertDialogBuilder(themedForDialogs())
            .setTitle(R.string.webview_update_title)
            .setMessage(R.string.webview_update_message)
            .setCancelable(false)
            .setPositiveButton(R.string.webview_update_action) { _, _ ->
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, "market://details?id=$provider".toUri()))
                } catch (_: ActivityNotFoundException) {
                    openExternal("https://play.google.com/store/apps/details?id=$provider")
                }
                finish()
            }
            .setNegativeButton(R.string.close) { _, _ -> finish() }
            .show()
    }

    private fun routeFrom(intent: Intent?): String? {
        if (intent == null || intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return null
        return intent.getStringExtra(EXTRA_ROUTE)?.takeIf(WebRoutes::isRoute)
    }

    companion object {
        const val ACTION_OPEN_ROUTE = "com.prl.wizard.OPEN_ROUTE"
        const val EXTRA_ROUTE = "route"
        private const val TAG = "PearlHub"
        private const val SPLASH_CAP_MS = 3_000L
        private const val POPUP_TIMEOUT_MS = 10_000L
        private const val PREFS = "shell"
        private const val LOCK_PORTRAIT = "ui.lockPortrait"
        private const val PENDING_EXPORT = "file.pendingExport"
        private const val PAGE_URL = "web.pageUrl"
        private val main = Handler(Looper.getMainLooper())
        private val io = Executors.newSingleThreadExecutor()
        private var current: WeakReference<MainActivity>? = null

        /** Delivers a page event from outside the activity (FCM token rotation). */
        fun dispatchEvent(name: String, data: JSONObject) {
            main.post { current?.get()?.takeIf { it.pageLoaded }?.sendEvent(name, data) }
        }
    }
}
