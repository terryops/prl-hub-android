package com.prl.wizard

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import org.json.JSONException
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * window.PearlAndroid (BRIDGE.md "Message channel"): JSON requests from https://pearlhub.net
 * frames, one JSON reply per request id. Messages arrive on the main thread; slow work runs on
 * executors and replies are posted back to the main thread.
 */
class Bridge(private val activity: MainActivity) : WebViewCompat.WebMessageListener {
    class Call(private val id: String, private val proxy: JavaScriptReplyProxy) {
        private val done = AtomicBoolean(false)

        fun ok(result: JSONObject = JSONObject()) = send(JSONObject().put("id", id).put("ok", true).put("result", result))

        fun fail(error: String) = send(JSONObject().put("id", id).put("ok", false).put("error", error))

        // Replies exist only after addWebMessageListener, which needs WEB_MESSAGE_LISTENER.
        @SuppressLint("RequiresFeature")
        private fun send(reply: JSONObject) {
            if (!done.compareAndSet(false, true)) return
            val text = reply.toString()
            main.post {
                try {
                    proxy.postMessage(text)
                } catch (e: RuntimeException) {
                    Log.w(TAG, "Reply not delivered", e)
                }
            }
        }
    }

    private class BadParams : Exception()

    private val network = Executors.newCachedThreadPool()
    private val walletWork = Executors.newSingleThreadExecutor()
    private val store = WalletSecretStore(activity.applicationContext)
    private val safeTradeKeys = SafeTradeKeyStore(activity.applicationContext)
    private var liveGeneration = 0L
    private var closed = false

    /** Activity-owned workers finish accepted requests, then release their threads. */
    fun close() {
        closed = true
        liveGeneration++
        network.shutdown()
        walletWork.shutdown()
    }

    override fun onPostMessage(
        view: WebView,
        message: WebMessageCompat,
        sourceOrigin: Uri,
        isMainFrame: Boolean,
        replyProxy: JavaScriptReplyProxy,
    ) {
        if (message.type != WebMessageCompat.TYPE_STRING) return
        val request = try {
            JSONObject(message.data ?: return)
        } catch (_: JSONException) {
            return
        }
        val id = request.opt("id") as? String ?: return
        if (id.isEmpty() || id.length > 128) return
        val call = Call(id, replyProxy)
        if (closed) return call.fail("cancelled")
        val method = request.opt("method") as? String ?: return call.fail("unsupported")
        val params = request.opt("params").let { if (it == null || it == JSONObject.NULL) JSONObject() else it }
            as? JSONObject ?: return call.fail("bad_params")
        try {
            dispatch(method, params, call)
        } catch (_: BadParams) {
            call.fail("bad_params")
        } catch (e: RuntimeException) {
            Log.w(TAG, "$method failed", e)
            call.fail("failed")
        }
    }

    private fun dispatch(method: String, p: JSONObject, call: Call) {
        when (method) {
            "info" -> call.ok(
                JSONObject()
                    .put("flavor", BuildConfig.STORE)
                    .put("versionName", BuildConfig.VERSION_NAME)
                    .put("versionCode", BuildConfig.VERSION_CODE)
                    .put("sdkInt", Build.VERSION.SDK_INT)
                    .put("fcm", Push.available(activity))
                    .put("deviceSecure", activity.walletAuth.deviceSecure())
                    .put("icon", AppIcon.current(activity).id)
                    .put("phone", activity.isPhone())
                    .put("lockPortrait", activity.lockPortrait())
                    .put("launches", ReviewPrompt.launches(activity))
                    .put("firstLaunch", ReviewPrompt.firstLaunch(activity))
                    .put("storeReview", StoreReview.SUPPORTED)
                    .put("billing", Billing.SUPPORTED)
                    .put("widgetPlaced", Widgets.anyPlaced(activity)),
            )
            "openExternal" -> {
                val url = p.str("url")
                if (!WebRoutes.isExternalAllowed(url)) throw BadParams()
                if (activity.openExternal(url)) call.ok() else call.fail("unavailable")
            }
            "share" -> {
                activity.share(p.str("text"))
                call.ok()
            }
            "clipboard.write" -> {
                activity.copy(p.str("text"), p.flag("sensitive"))
                call.ok()
            }
            "clipboard.read" -> call.ok(JSONObject().put("text", activity.paste()))
            "clipboard.clear" -> call.ok(JSONObject().put("cleared", activity.clearClipboard(p.str("text"))))
            "ui.theme" -> {
                val color = WebRoutes.parseColor(p.str("background")) ?: throw BadParams()
                activity.applyTheme(color, p.flag("dark"))
                call.ok()
            }
            "ui.secure" -> {
                activity.setSecure(p.opt("on") as? Boolean ?: throw BadParams())
                call.ok()
            }
            "ui.portrait" -> {
                activity.setLockPortrait(p.opt("lock") as? Boolean ?: throw BadParams())
                call.ok(JSONObject().put("lockPortrait", activity.lockPortrait()))
            }
            "icon.set" -> {
                val choice = AppIcon.Choice.of(p.str("icon")) ?: throw BadParams()
                AppIcon.apply(activity, choice)
                call.ok(JSONObject().put("icon", AppIcon.current(activity).id))
            }
            "review.request" -> call.ok(JSONObject().put("asked", ReviewPrompt.request(activity)))
            "review.open" -> if (ReviewPrompt.openStorePage(activity)) call.ok() else call.fail("unsupported")
            "update.check" -> StoreUpdate.check(activity) { code ->
                call.ok(JSONObject().put("available", code != null).put("versionCode", code ?: 0))
            }
            "update.start" -> StoreUpdate.start(activity) { started -> if (started) call.ok() else call.fail("unavailable") }
            "billing.product" -> Billing.product(activity) { product, error -> if (product != null) call.ok(product) else call.fail(error ?: "failed") }
            "billing.purchase" -> Billing.purchase(activity) { purchase, error -> if (purchase != null) call.ok(purchase) else call.fail(error ?: "failed") }
            "billing.owned" -> Billing.owned(activity) { list, error ->
                if (list != null) call.ok(JSONObject().put("purchases", list)) else call.fail(error ?: "failed")
            }
            "file.save" -> {
                val name = p.str("name").takeIf { it.isNotBlank() && it.length <= 128 && '/' !in it } ?: throw BadParams()
                val mime = p.str("mime").takeIf { it.matches(Regex("^[a-z]+/[a-z0-9.+-]+$")) } ?: throw BadParams()
                val text = p.str("text").takeIf { it.length <= MAX_SAVE_TEXT } ?: throw BadParams()
                activity.saveFile(name, mime, text) { error -> if (error == null) call.ok() else call.fail(error) }
            }
            "live.status" -> call.ok(LiveTicker.status(activity))
            "live.start" -> liveStart(p, call)
            "live.update" -> {
                val quote = LiveTicker.Quote.from(p.opt("state") as? JSONObject) ?: throw BadParams()
                call.ok(JSONObject().put("running", LiveTicker.update(activity, quote)))
            }
            "live.stop" -> {
                liveGeneration++
                LiveTicker.stop(activity)
                call.ok()
            }
            "haptic" -> if (activity.haptic(p.str("kind"))) call.ok() else throw BadParams()
            "notifications.status" -> call.ok(JSONObject().put("permission", Notifications.permission(activity)))
            "notifications.request" -> activity.requestNotifications { call.ok(JSONObject().put("permission", it)) }
            "notifications.settings" -> {
                activity.openNotificationSettings()
                call.ok()
            }
            "push.token" -> Push.token(activity, call)
            "push.delete" -> Push.delete(activity, call)
            "wallet.status" -> call.ok(
                JSONObject().put("hasSecret", store.hasSecret()).put("deviceSecure", activity.walletAuth.deviceSecure()),
            )
            "wallet.secret" -> walletSecret(p, call)
            "wallet.clear" -> walletWork.execute {
                try {
                    store.clear()
                    call.ok()
                } catch (e: Exception) {
                    Log.w(TAG, "wallet.clear failed", e)
                    call.fail("failed")
                }
            }
            "settings.security" -> {
                activity.openSecuritySettings()
                call.ok()
            }
            "net.post" -> netPost(p, call)
            "nock.rpc" -> nockRpc(p, call)
            "safetrade.keys" -> {
                val masked = safeTradeKeys.masked()
                call.ok(JSONObject().put("set", masked != null).put("masked", masked ?: JSONObject.NULL))
            }
            "safetrade.save" -> {
                val key = p.str("key").trim()
                val secret = p.str("secret").trim()
                if (!SafeTrade.isCredential(key) || !SafeTrade.isCredential(secret)) throw BadParams()
                walletWork.execute {
                    if (safeTradeKeys.save(key, secret)) call.ok(JSONObject().put("masked", SafeTrade.masked(key))) else call.fail("failed")
                }
            }
            "safetrade.clear" -> walletWork.execute {
                try {
                    safeTradeKeys.clear()
                    call.ok()
                } catch (e: Exception) {
                    Log.w(TAG, "safetrade.clear failed: ${e.javaClass.simpleName}")
                    call.fail("failed")
                }
            }
            "safetrade.request" -> safeTradeRequest(p, call)
            "widgets.update" -> {
                if (!p.has("data") || !Widgets.publish(activity, p.str("part"), p.opt("data"))) throw BadParams()
                call.ok()
            }
            "widgets.pin" -> {
                val kind = Widgets.Kind.of(p.str("kind")) ?: throw BadParams()
                if (Widgets.pin(activity, kind)) call.ok() else call.fail("unsupported")
            }
            else -> call.fail("unsupported")
        }
    }

    private fun walletSecret(p: JSONObject, call: Call) {
        val title = p.strOrNull("title")?.trim()?.ifEmpty { null } ?: activity.getString(R.string.unlock_wallet)
        val subtitle = p.strOrNull("subtitle")
        val create = p.flag("create")
        val access = store.access()
        if (!activity.walletAuth.deviceSecure()) return call.fail("no_lock_screen")
        if (!access.hasSecret && !create) return call.fail("no_secret")
        activity.walletAuth.authenticate(title, subtitle) { error ->
            if (error != null) return@authenticate call.fail(error)
            if (closed) return@authenticate call.fail("cancelled")
            walletWork.execute {
                try {
                    // Created only after the person confirmed, so a cancelled prompt leaves nothing behind.
                    val secret = store.resolve(create, access) ?: return@execute call.fail("no_secret")
                    call.ok(JSONObject().put("secret", secret))
                } catch (e: Exception) {
                    Log.w(TAG, "wallet.secret failed", e)
                    call.fail("failed")
                }
            }
        }
    }

    private fun liveStart(p: JSONObject, call: Call) {
        val quote = LiveTicker.Quote.from(p.opt("state") as? JSONObject) ?: throw BadParams()
        fun text(key: String) = p.str(key).trim().take(64).ifEmpty { throw BadParams() }
        val copy = LiveTicker.Copy(text("title"), text("label1h"), text("label24h"))
        val style = p.strOrNull("style") ?: "full"
        if (style !in setOf("full", "lock", "island")) throw BadParams()
        val generation = ++liveGeneration
        activity.requestNotifications { permission ->
            // Permission may arrive after a newer style request or an explicit stop.
            if (generation != liveGeneration) return@requestNotifications call.fail("cancelled")
            LiveTicker.ensureChannel(activity)
            if (permission != "granted" || !LiveTicker.canShow(activity)) return@requestNotifications call.fail("denied")
            val started = LiveTicker.start(activity, copy, style, quote)
            call.ok(JSONObject().put("running", true).put("startedAt", started))
        }
    }

    private fun netPost(p: JSONObject, call: Call) {
        val url = p.str("url")
        val body = p.str("body")
        val contentType = p.strOrNull("contentType") ?: "text/plain"
        if (!WebRoutes.isBlockbookApi(url) || contentType.any { it < ' ' } || body.length > MAX_POST_BODY) throw BadParams()
        network.execute {
            try {
                val reply = Blockbook.post(url, body, contentType)
                call.ok(JSONObject().put("status", reply.status).put("body", reply.body))
            } catch (e: Exception) {
                Log.w(TAG, "net.post failed: ${e.javaClass.simpleName}")
                call.fail("failed")
            }
        }
    }

    private fun nockRpc(p: JSONObject, call: Call) {
        val timeout = if (p.isNull("timeout")) null else p.opt("timeout")
        val request = NockRpc.request(p.opt("node"), p.opt("path"), p.opt("body"), timeout) ?: throw BadParams()
        network.execute {
            try {
                val reply = NockRpc.call(request, "PearlHubAndroid/${BuildConfig.VERSION_NAME}")
                call.ok(
                    JSONObject()
                        .put("status", reply.status)
                        .put("body", NockRpc.encode(reply.body))
                        .put("grpcStatus", reply.grpcStatus ?: JSONObject.NULL)
                        .put("grpcMessage", reply.grpcMessage ?: JSONObject.NULL),
                )
            } catch (e: Exception) {
                Log.w(TAG, "nock.rpc failed: ${e.javaClass.simpleName}")
                call.fail("failed")
            }
        }
    }

    private fun safeTradeRequest(p: JSONObject, call: Call) {
        val request = SafeTrade.request(p) ?: throw BadParams()
        if (request.auth == SafeTrade.Auth.Stored && !safeTradeKeys.hasKeys()) return call.fail("no_credentials")
        val check = request.deviceCheck ?: return safeTradeSend(request, call)
        // As on iOS, every withdrawal waits for the person's biometrics / screen lock: the page alone cannot withdraw.
        activity.walletAuth.authenticate(check.title, check.subtitle) { error ->
            if (error != null) return@authenticate call.fail(error)
            if (closed) return@authenticate call.fail("cancelled")
            safeTradeSend(request, call)
        }
    }

    private fun safeTradeSend(request: SafeTrade.Request, call: Call) = network.execute {
        try {
            val credentials = when (val auth = request.auth) {
                SafeTrade.Auth.None -> null
                SafeTrade.Auth.Stored -> safeTradeKeys.read() ?: return@execute call.fail("no_credentials")
                is SafeTrade.Auth.Explicit -> auth.key to auth.secret
            }
            val reply = SafeTrade.send(request, credentials)
            call.ok(JSONObject().put("status", reply.status).put("body", reply.body))
        } catch (e: SafeTrade.Failure) {
            call.fail(e.code)
        } catch (e: Exception) {
            // Raised before the connection was made (SafeTrade.send classifies everything after it): nothing
            // reached SafeTrade. Not `failed`, which after a withdrawal's prompt would read as the check failing.
            Log.w(TAG, "safetrade.request failed: ${e.javaClass.simpleName}")
            call.fail("unreached")
        }
    }

    private fun JSONObject.str(key: String): String = opt(key) as? String ?: throw BadParams()

    private fun JSONObject.strOrNull(key: String): String? =
        if (isNull(key)) null else opt(key) as? String ?: throw BadParams()

    private fun JSONObject.flag(key: String): Boolean =
        if (isNull(key)) false else opt(key) as? Boolean ?: throw BadParams()

    companion object {
        private const val TAG = "PearlBridge"
        private const val MAX_POST_BODY = 4 * 1024 * 1024
        private const val MAX_SAVE_TEXT = 2 * 1024 * 1024
        private val main = Handler(Looper.getMainLooper())
    }
}
