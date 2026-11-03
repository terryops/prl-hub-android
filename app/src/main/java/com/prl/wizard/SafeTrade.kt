package com.prl.wizard

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs
import kotlin.math.max

/**
 * SafeTrade (safetrade.com) for the 交易 tab, `safetrade.*` in BRIDGE.md — the iOS app's
 * `Sources/Trade/SafeTradeClient.swift`, `SafeTradeRules.swift` and `SafeTradeSecrets.swift`.
 * SafeTrade's private API refuses the WebView's CORS preflight, so the shell signs and sends every
 * SafeTrade request and the page only sees the status and body. Only the calls the iOS app makes
 * are allowed. Plain JVM code (the key store aside), so it is unit-tested; never log keys,
 * signatures or bodies.
 */
object SafeTrade {
    const val ROOT = "https://safetrade.com"

    /** SafeTrade sits behind a Cloudflare WAF that blocks some clients by User-Agent / TLS fingerprint. */
    val USER_AGENT = "PearlHubAndroid/${BuildConfig.VERSION_NAME}"

    const val TIMEOUT_MS = 20_000
    const val MAX_REPLY = 8 * 1024 * 1024
    const val MAX_JSON = 16 * 1024
    const val MAX_FIELDS = 16
    const val MAX_FIELD_VALUE = 64
    const val MAX_CREDENTIAL = 256
    const val MAX_TITLE = 64
    const val MAX_SUBTITLE = 128
    const val BALANCES = "/api/v2/trade/account/balances/spot"
    const val WITHDRAW = "/api/v2/trade/account/withdraws"
    private const val FORM_TYPE = "application/x-www-form-urlencoded"
    private const val JSON_TYPE = "application/json"

    private val GET_PATHS = listOf(
        "^/api/v2/peatio/public/markets/[a-z0-9]{3,32}/tickers$",
        "^/api/v2/trade/public/markets/[a-z0-9]{3,32}(/depth|/k-line)?$",
        "^/api/v2/trade/public/currencies/[a-z0-9]{1,32}$",
        "^/api/v2/trade/account/balances/spot$",
        "^/api/v2/trade/market/orders$",
        "^/api/v2/trade/account/withdraws$",
        "^/api/v2/trade/account/deposit_address/[a-z0-9]{1,32}$",
        "^/api/v2/trade/account/deposits$",
        "^/api/v2/trade/account/beneficiaries$",
        "^/cdn-cgi/trace$",
    ).map(::Regex)

    private val POST_PATHS = listOf(
        "^/api/v2/trade/market/orders$",
        "^/api/v2/trade/market/orders/[0-9]{1,20}/cancel$",
        "^/api/v2/trade/account/withdraws/generate_code$",
        "^/api/v2/trade/account/withdraws$",
    ).map(::Regex)

    private val FIELD_NAME = Regex("^[a-z_]{1,32}$")

    /** Process-wide like iOS's: nonces keep increasing across activities and bridges. */
    val nonceGenerator = SafeTradeNonce()

    /** How a request is signed: not at all, with the stored pair, or with typed keys being checked. */
    sealed interface Auth {
        data object None : Auth
        data object Stored : Auth
        class Explicit(val key: String, val secret: String) : Auth
    }

    /** The biometric / screen-lock prompt shown before a withdrawal is sent. */
    class DeviceCheck(val title: String, val subtitle: String?)

    class Request(
        val method: String,
        val path: String,
        /** Encoded, without `?`; null when there is none. */
        val query: String?,
        val contentType: String?,
        val body: ByteArray?,
        val auth: Auth,
        val deviceCheck: DeviceCheck?,
    ) {
        val target: String get() = if (query == null) path else "$path?$query"
    }

    class Reply(val status: Int, val body: String)

    /** A bridge error code for a request that got no HTTP reply: `unreached`, `unconfirmed` or `failed`. */
    class Failure(val code: String) : Exception(code)

    /**
     * The request for `safetrade.request` params, or null when anything is not acceptable: a path
     * outside the whitelist for its method, a query / form field or JSON body over the limits, a
     * body on a GET or a query on a POST, both `form` and `json`, an auth mode the path does not
     * allow, or a withdrawal without prompt text.
     */
    fun request(p: JSONObject): Request? {
        val method = p.opt("method") as? String ?: return null
        val path = p.opt("path") as? String ?: return null
        // Also outside every pattern; spelled out because nothing may reach another URL.
        if (".." in path || '?' in path || '#' in path || '%' in path) return null
        val patterns = when (method) {
            "GET" -> GET_PATHS
            "POST" -> POST_PATHS
            else -> return null
        }
        if (patterns.none { it.matches(path) }) return null

        val rawQuery = p.present("query")
        val rawForm = p.present("form")
        val rawJson = p.present("json")
        if (method == "GET" && (rawForm != null || rawJson != null)) return null
        if (method == "POST" && rawQuery != null) return null
        if (rawForm != null && rawJson != null) return null
        val query = if (rawQuery == null) null else fields(rawQuery)?.let(::encode) ?: return null
        var contentType: String? = null
        var body: ByteArray? = null
        if (rawForm != null) {
            body = encode(fields(rawForm) ?: return null).toByteArray(Charsets.UTF_8)
            contentType = FORM_TYPE
        }
        if (rawJson != null) {
            // The page's own JSON text goes out as written: org.json would rewrite 0.0005 as 5.0E-4.
            val text = when (rawJson) {
                is String -> rawJson.takeIf(::isJsonObject) ?: return null
                is JSONObject -> rawJson.toString()
                else -> return null
            }
            body = text.toByteArray(Charsets.UTF_8)
            if (body.size > MAX_JSON) return null
            contentType = JSON_TYPE
        }

        val auth = when (p.present("auth") ?: "none") {
            "none" -> Auth.None
            "stored" -> Auth.Stored
            "explicit" -> {
                val key = p.opt("key") as? String ?: return null
                val secret = p.opt("secret") as? String ?: return null
                if (!isCredential(key) || !isCredential(secret)) return null
                Auth.Explicit(key, secret)
            }
            else -> return null
        }
        // Typed keys are only ever checked against the balances before they are saved.
        if (auth is Auth.Explicit && (method != "GET" || path != BALANCES)) return null
        if (auth == Auth.None && isPrivate(path)) return null

        val check = if (method == "POST" && path == WITHDRAW) {
            if (auth != Auth.Stored) return null
            val title = (p.opt("title") as? String)?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_TITLE } ?: return null
            val rawSubtitle = p.present("subtitle")
            val subtitle = if (rawSubtitle == null) null else (rawSubtitle as? String)?.trim()?.takeIf { it.length <= MAX_SUBTITLE } ?: return null
            DeviceCheck(title, subtitle?.ifEmpty { null })
        } else {
            null
        }
        return Request(method, path, query?.ifEmpty { null }, contentType, body, auth, check)
    }

    /** Exactly one JSON object, nothing after it. */
    fun isJsonObject(text: String): Boolean = try {
        val tokener = JSONTokener(text)
        tokener.nextValue() is JSONObject && tokener.nextClean() == '\u0000'
    } catch (_: JSONException) {
        false
    }

    /** Account and order calls are signed; public market data and Cloudflare's trace need not be. */
    fun isPrivate(path: String): Boolean =
        path.startsWith("/api/v2/trade/account/") || path.startsWith("/api/v2/trade/market/")

    /** An API key or secret: printable ASCII without whitespace, at most [MAX_CREDENTIAL] characters. */
    fun isCredential(text: String): Boolean = text.length in 1..MAX_CREDENTIAL && text.all { it in '!'..'~' }

    /** Query / form fields: at most [MAX_FIELDS], names `[a-z_]{1,32}`, values printable ASCII without space, `&`, `#`. */
    fun fields(value: Any): Map<String, String>? {
        val obj = value as? JSONObject ?: return null
        if (obj.length() > MAX_FIELDS) return null
        val out = sortedMapOf<String, String>()
        for (name in obj.keys()) {
            val text = obj.opt(name) as? String ?: return null
            if (!FIELD_NAME.matches(name) || text.length > MAX_FIELD_VALUE) return null
            if (!text.all { it in '!'..'~' && it != '&' && it != '#' }) return null
            out[name] = text
        }
        return out
    }

    /** `a=1&b=x%2Fy`, percent-encoded as UTF-8 (`%20`, never `+`). */
    fun encode(fields: Map<String, String>): String =
        fields.entries.joinToString("&") { (name, value) -> "${percent(name)}=${percent(value)}" }

    private fun percent(text: String) = URLEncoder.encode(text, "UTF-8").replace("+", "%20")

    /** hex(HMAC-SHA256(secret, nonce + key)), lowercase. */
    fun signature(nonce: String, apiKey: String, apiSecret: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(apiSecret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val digest = mac.doFinal((nonce + apiKey).toByteArray(Charsets.UTF_8))
        val hex = "0123456789abcdef"
        return buildString(digest.size * 2) {
            for (b in digest) append(hex[(b.toInt() shr 4) and 15]).append(hex[b.toInt() and 15])
        }
    }

    /** `{"errors":["account.withdraw.invalid_otp_code"]}` → the error keys; none for anything else. */
    fun errorKeys(body: String): List<String> {
        val errors = try {
            JSONObject(body).opt("errors") as? JSONArray
        } catch (_: RuntimeException) {
            null
        } ?: return emptyList()
        return List(errors.length()) { errors.opt(it) as? String ?: return emptyList() }
    }

    /** `authz.not_trusted_ip` → `authz`, `not`, `trusted`, `ip`. */
    fun tokens(key: String): List<String> = key.lowercase().split('.', '_', '-').filter { it.isNotEmpty() }

    /** Refused for a stale nonce: the gateway refused it before acting, so one re-sign is safe even for a POST. */
    fun isNonceError(body: String): Boolean = errorKeys(body).any { "nonce" in tokens(it) }

    /** iOS `maskedKey`: `abcd••••yz`; `••••` for 6 characters or fewer; null when none is set. */
    fun masked(key: String): String? = when {
        key.isEmpty() -> null
        key.length <= 6 -> "••••"
        else -> key.take(4) + "••••" + key.takeLast(2)
    }

    /**
     * Blocking; call off the main thread. Every HTTP status comes back with its body. A signed
     * request refused for a stale nonce is signed again and sent once more. Throws [Failure]:
     * `unreached` when the connection could not be made (nothing was sent) or a GET failed after
     * one redial, `unconfirmed` when a POST failed after connecting (the exchange may have acted
     * on it), `failed` when a GET's reply exceeded [MAX_REPLY].
     */
    fun send(
        request: Request,
        credentials: Pair<String, String>?,
        root: String = ROOT,
        nonces: SafeTradeNonce = nonceGenerator,
    ): Reply {
        var resigned = false
        var redialed = false
        while (true) {
            val headers = credentials?.let { (key, secret) ->
                val nonce = nonces.next()
                listOf("X-Auth-Apikey" to key, "X-Auth-Nonce" to nonce, "X-Auth-Signature" to signature(nonce, key, secret))
            }
            val reply = try {
                exchange(root, request, headers.orEmpty(), nonces)
            } catch (e: AfterConnect) {
                if (request.method == "POST") throw Failure("unconfirmed")
                // The pooled connection died while the app slept; a GET is safe to send once more.
                if (e.dropped && !redialed) {
                    redialed = true
                    continue
                }
                throw Failure(if (e.tooLarge) "failed" else "unreached")
            }
            if (reply.status !in 200..299 && credentials != null && !resigned && isNonceError(reply.body)) {
                resigned = true
                continue
            }
            return reply
        }
    }

    /** Failed once the connection was made: the request may have reached the exchange. */
    private class AfterConnect(val dropped: Boolean, val tooLarge: Boolean) : Exception()

    private class TooLarge : IOException("reply too large")

    private fun exchange(root: String, request: Request, auth: List<Pair<String, String>>, nonces: SafeTradeNonce): Reply {
        val post = request.method == "POST"
        val connection = try {
            URL(root + request.target).openConnection() as HttpURLConnection
        } catch (_: IOException) {
            throw Failure("unreached")
        }
        try {
            connection.requestMethod = request.method
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", USER_AGENT)
            for ((name, value) in auth) connection.setRequestProperty(name, value)
            val body = request.body
            if (post) {
                connection.doOutput = true
                request.contentType?.let { connection.setRequestProperty("Content-Type", it) }
                connection.setFixedLengthStreamingMode(body?.size ?: 0)
            }
            // DNS, TCP and TLS: a failure here means nothing reached the exchange.
            try {
                connection.connect()
            } catch (_: IOException) {
                throw Failure("unreached")
            }
            try {
                if (post) connection.outputStream.use { out -> if (body != null) out.write(body) }
                val status = connection.responseCode
                if (status < 100) throw IOException("not an HTTP reply")
                nonces.observe(connection.getHeaderField("Date"))
                val stream = if (status >= 400) connection.errorStream else connection.inputStream
                return Reply(status, stream?.use(::readCapped) ?: "")
            } catch (_: TooLarge) {
                throw AfterConnect(dropped = false, tooLarge = true)
            } catch (_: SocketTimeoutException) {
                throw AfterConnect(dropped = false, tooLarge = false)
            } catch (e: Exception) {
                throw AfterConnect(dropped = e is IOException, tooLarge = false)
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun readCapped(input: InputStream): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            if (out.size() + n > MAX_REPLY) throw TooLarge()
            out.write(buffer, 0, n)
        }
        return out.toString("UTF-8")
    }

    private fun JSONObject.present(key: String): Any? = opt(key)?.takeIf { it != JSONObject.NULL }
}

/**
 * Millisecond nonces on SafeTrade's clock (iOS `SafeTradeNonceGenerator`): SafeTrade's gateway
 * (Barong) refuses a nonce more than its lifetime away from its own time (`authz.nonce_expired`) and
 * keeps no record of earlier ones, so a phone whose clock is off would fail every signed call. The
 * offset is learned from reply `Date` headers. Nonces increase between replies; once a reply shows
 * the last one ran ahead of SafeTrade's time (a fast clock, or one set back), they start again from
 * SafeTrade's time, so the one re-sign after a refusal can pass.
 */
class SafeTradeNonce(private val clock: () -> Long = System::currentTimeMillis) {
    private var last = 0L
    private var offsetMs = 0L

    @Synchronized
    fun next(): String {
        val nonce = max(clock() + offsetMs, last + 1)
        last = nonce
        return nonce.toString()
    }

    @Synchronized
    fun observe(dateHeader: String?) {
        val server = parseHttpDate(dateHeader ?: return) ?: return
        val local = clock()
        offsetMs = offset(server, local)
        val now = local + offsetMs
        if (last > now + MAX_AHEAD_MS) last = now
    }

    companion object {
        /** Further ahead of SafeTrade's time than a burst of nonces gets: they came from a wrong clock. */
        const val MAX_AHEAD_MS = 1_000L

        /**
         * The header has whole-second resolution (the true time is on average half a second
         * later), so only a clearly wrong device clock is corrected.
         */
        fun offset(serverMs: Long, localMs: Long): Long {
            val skew = serverMs - localMs + 500
            return if (abs(skew) >= 2000) skew else 0
        }

        /** RFC 1123 `Sun, 27 Sep 2026 02:08:41 GMT` → Unix milliseconds. */
        fun parseHttpDate(header: String): Long? = try {
            ZonedDateTime.parse(header.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
        } catch (_: DateTimeParseException) {
            null
        }
    }
}

/**
 * The SafeTrade API key pair, device-only: one AES-256-GCM ciphertext of `{key, secret}` under its
 * own Android Keystore key (StrongBox when available, no user-authentication binding — the iOS
 * Keychain item has no access control either), in a SharedPreferences file that backups exclude
 * (`res/xml/backup_rules.xml`, `data_extraction_rules.xml`). The masked key is kept beside it so
 * `safetrade.keys` needs no Keystore call. Blocking Keystore calls: use off the main thread.
 */
class SafeTradeKeyStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val keyStore: KeyStore by lazy { KeyStore.getInstance(KEYSTORE).apply { load(null) } }

    // Written in one commit, so all three are present or none.
    fun hasKeys(): Boolean = prefs.contains(CIPHERTEXT) && prefs.contains(IV) && prefs.contains(MASKED)

    fun masked(): String? = if (hasKeys()) prefs.getString(MASKED, null) else null

    fun save(key: String, secret: String): Boolean = synchronized(lock) {
        try {
            val plain = JSONObject().put("key", key).put("secret", secret).toString().toByteArray(Charsets.UTF_8)
            val cipher = try {
                encrypt(existingKey() ?: generateKey(), plain)
            } catch (e: GeneralSecurityException) {
                // A key the Keystore can no longer use: start over with a new one.
                Log.i(TAG, "Replacing the SafeTrade key (${e.javaClass.simpleName})")
                keyStore.deleteEntry(ALIAS)
                encrypt(generateKey(), plain)
            } finally {
                plain.fill(0)
            }
            val saved = prefs.edit()
                .putString(IV, encode(cipher.first))
                .putString(CIPHERTEXT, encode(cipher.second))
                .putString(MASKED, SafeTrade.masked(key))
                .commit()
            saved
        } catch (e: Exception) {
            Log.w(TAG, "SafeTrade keys not saved: ${e.javaClass.simpleName}")
            false
        }
    }

    /**
     * The saved (key, secret), or null when none is saved or it cannot be decrypted. A pair the
     * Keystore can never decrypt again (its key is gone or invalidated, or the ciphertext fails its
     * tag) is deleted, so `safetrade.keys` stops reporting a key that every signed call would refuse.
     */
    fun read(): Pair<String, String>? = synchronized(lock) {
        if (!hasKeys()) return@synchronized null
        try {
            val key = existingKey() ?: run {
                Log.w(TAG, "SafeTrade Keystore key missing; deleting the saved pair")
                prefs.edit(commit = true) { clear() }
                return@synchronized null
            }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, decode(prefs.getString(IV, null)!!)))
            cipher.updateAAD(ALIAS.toByteArray())
            val plain = cipher.doFinal(decode(prefs.getString(CIPHERTEXT, null)!!))
            val json = JSONObject(String(plain, Charsets.UTF_8)).also { plain.fill(0) }
            val apiKey = json.optString("key")
            val apiSecret = json.optString("secret")
            if (apiKey.isEmpty() || apiSecret.isEmpty()) null else apiKey to apiSecret
        } catch (e: Exception) {
            Log.w(TAG, "SafeTrade keys unreadable: ${e.javaClass.simpleName}")
            if (isPermanent(e)) {
                prefs.edit(commit = true) { clear() }
                try {
                    keyStore.deleteEntry(ALIAS)
                } catch (_: Exception) {
                    // A new key replaces it on the next save.
                }
            }
            null
        }
    }

    /** Failures that a retry can't fix (as opposed to a busy Keystore). */
    private fun isPermanent(e: Exception) = e is AEADBadTagException || e is KeyPermanentlyInvalidatedException ||
        e is UnrecoverableKeyException || e is JSONException || e is IllegalArgumentException

    fun clear() = synchronized(lock) {
        prefs.edit(commit = true) { clear() }
        keyStore.deleteEntry(ALIAS)
    }

    private fun existingKey(): SecretKey? = keyStore.getKey(ALIAS, null) as? SecretKey

    private fun encrypt(key: SecretKey, plain: ByteArray): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(ALIAS.toByteArray())
        val ciphertext = cipher.doFinal(plain)
        return cipher.iv to ciphertext
    }

    /** StrongBox when the device has it, else the TEE. */
    private fun generateKey(): SecretKey {
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                return generateKey(strongBox = true)
            } catch (e: Exception) {
                Log.i(TAG, "StrongBox unavailable (${e.javaClass.simpleName}); using the TEE")
            }
        }
        return generateKey(strongBox = false)
    }

    private fun generateKey(strongBox: Boolean): SecretKey {
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
        if (strongBox && Build.VERSION.SDK_INT >= 28) spec.setIsStrongBoxBacked(true)
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(spec.build())
            generateKey()
        }
    }

    private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    private fun decode(text: String) = Base64.decode(text, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    companion object {
        private const val TAG = "PearlSafeTrade"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "safetrade.keys"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val PREFS = "safetrade"
        private const val IV = "iv"
        private const val CIPHERTEXT = "ciphertext"
        private const val MASKED = "masked"
        private val lock = Any()
    }
}
