package com.prl.wizard

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.core.content.edit
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.MessageDigest
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Gate (api.gateio.ws, REST API v4) for the 交易 tab, `gate.*` in BRIDGE.md — the iOS app's
 * `Sources/Trade/GateClient.swift` and `GateSecrets.swift`. Gate's private API refuses browser
 * CORS preflights, so the shell signs and sends every Gate request and the page only sees the
 * status and body. Only the calls the iOS app makes for `PEARL_USDT` spot trading and wallet
 * deposits / withdrawals are allowed. Plain JVM code (the key store aside), so it is unit-tested;
 * never log keys, signatures or bodies.
 */
object Gate {
    const val ROOT = "https://api.gateio.ws"
    val USER_AGENT = "PearlHubAndroid/${BuildConfig.VERSION_NAME}"

    const val TIMEOUT_MS = 20_000
    const val MAX_REPLY = 8 * 1024 * 1024
    const val MAX_JSON = 16 * 1024
    const val ACCOUNTS = "/api/v4/spot/accounts"
    const val WITHDRAW = "/api/v4/withdrawals"
    private const val JSON_TYPE = "application/json"

    private val PUBLIC_GET_PATHS = listOf(
        "^/api/v4/spot/(tickers|candlesticks|order_book)$",
        "^/api/v4/spot/currency_pairs/[A-Z0-9_]{3,32}$",
        "^/api/v4/wallet/currency_chains$",
    ).map(::Regex)

    private val PRIVATE_GET_PATHS = listOf(
        "^/api/v4/spot/accounts$",
        "^/api/v4/spot/orders$",
        "^/api/v4/wallet/(deposit_address|deposits|withdrawals|withdraw_status|saved_address)$",
    ).map(::Regex)

    private val GET_PATHS = PUBLIC_GET_PATHS + PRIVATE_GET_PATHS

    private val POST_PATHS = listOf(
        "^/api/v4/spot/orders$",
        "^/api/v4/withdrawals$",
    ).map(::Regex)

    private val DELETE_PATHS = listOf(
        "^/api/v4/spot/orders/[0-9]{1,24}$",
    ).map(::Regex)

    val clockGenerator = GateClock()

    sealed interface Auth {
        data object None : Auth
        data object Stored : Auth
        class Explicit(val key: String, val secret: String) : Auth
    }

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

    class Failure(val code: String) : Exception(code)

    /**
     * Parses `gate.request` params, returning null when anything falls outside the allowlist or
     * parameter rules: unknown method/path, `form` body, `json` on `GET`/`DELETE`, missing `json`
     * or `query` on `POST`, `explicit` auth outside `GET /api/v4/spot/accounts`, unsigned private
     * calls, or a withdrawal without stored auth and prompt text.
     */
    fun request(p: JSONObject): Request? {
        val method = p.opt("method") as? String ?: return null
        val path = p.opt("path") as? String ?: return null
        if (".." in path || '?' in path || '#' in path || '%' in path) return null
        val patterns = when (method) {
            "GET" -> GET_PATHS
            "POST" -> POST_PATHS
            "DELETE" -> DELETE_PATHS
            else -> return null
        }
        if (patterns.none { it.matches(path) }) return null

        if (p.present("form") != null) return null
        val rawQuery = p.present("query")
        val rawJson = p.present("json")
        if ((method == "GET" || method == "DELETE") && rawJson != null) return null
        if (method == "POST" && (rawQuery != null || rawJson == null)) return null

        val query = if (rawQuery == null) null else SafeTrade.fields(rawQuery)?.let(SafeTrade::encode) ?: return null
        var contentType: String? = null
        var body: ByteArray? = null
        if (rawJson != null) {
            val text = when (rawJson) {
                is String -> rawJson.takeIf(SafeTrade::isJsonObject) ?: return null
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
                if (!SafeTrade.isCredential(key) || !SafeTrade.isCredential(secret)) return null
                Auth.Explicit(key, secret)
            }
            else -> return null
        }
        if (auth is Auth.Explicit && (method != "GET" || path != ACCOUNTS)) return null
        if (auth == Auth.None && isPrivate(path)) return null

        val check = if (method == "POST" && path == WITHDRAW) {
            if (auth != Auth.Stored) return null
            val title = (p.opt("title") as? String)?.trim()?.takeIf { it.isNotEmpty() && it.length <= SafeTrade.MAX_TITLE } ?: return null
            val rawSubtitle = p.present("subtitle")
            val subtitle = if (rawSubtitle == null) null else (rawSubtitle as? String)?.trim()?.takeIf { it.length <= SafeTrade.MAX_SUBTITLE } ?: return null
            DeviceCheck(title, subtitle?.ifEmpty { null })
        } else {
            null
        }
        return Request(method, path, query?.ifEmpty { null }, contentType, body, auth, check)
    }

    fun isPrivate(path: String): Boolean = PUBLIC_GET_PATHS.none { it.matches(path) }

    /** Lowercase hex SHA-512 of [bytes]. */
    fun sha512Hex(bytes: ByteArray): String = hexLower(MessageDigest.getInstance("SHA-512").digest(bytes))

    /**
     * Gate API v4 signature:
     * `hex(HMAC-SHA512(secret, "${method}\n${path}\n${query}\n${sha512Hex(body)}\n${timestamp}"))`.
     */
    fun signature(
        method: String,
        path: String,
        query: String,
        body: ByteArray,
        timestamp: String,
        apiSecret: String,
    ): String {
        val payload = "${method.uppercase()}\n$path\n$query\n${sha512Hex(body)}\n$timestamp"
        val mac = Mac.getInstance("HmacSHA512")
        mac.init(SecretKeySpec(apiSecret.toByteArray(Charsets.UTF_8), "HmacSHA512"))
        return hexLower(mac.doFinal(payload.toByteArray(Charsets.UTF_8)))
    }

    /** Gate API v4 `{"label":"REQUEST_EXPIRED"}` or `{"label":"INVALID_TIMESTAMP"}`. */
    fun errorLabel(body: String): String? = try {
        (JSONObject(body).opt("label") as? String)?.trim()?.takeIf { it.isNotEmpty() }
    } catch (_: RuntimeException) {
        null
    }

    /** Refused at signature check for an out-of-window timestamp; safe to re-sign once. */
    fun isTimestampError(body: String): Boolean {
        val label = errorLabel(body)?.uppercase() ?: return false
        return label == "REQUEST_EXPIRED" || label == "INVALID_TIMESTAMP"
    }

    fun send(
        request: Request,
        credentials: Pair<String, String>?,
        root: String = ROOT,
        clock: GateClock = clockGenerator,
    ): Reply {
        var resigned = false
        var redialed = false
        val emptyBody = ByteArray(0)
        while (true) {
            val headers = credentials?.let { (key, secret) ->
                val ts = clock.next()
                val sign = signature(
                    method = request.method,
                    path = request.path,
                    query = request.query.orEmpty(),
                    body = request.body ?: emptyBody,
                    timestamp = ts,
                    apiSecret = secret,
                )
                listOf("KEY" to key, "Timestamp" to ts, "SIGN" to sign)
            }
            val reply = try {
                exchange(root, request, headers.orEmpty(), clock)
            } catch (e: AfterConnect) {
                if (request.method != "GET") throw Failure("unconfirmed")
                if (e.dropped && !redialed) {
                    redialed = true
                    continue
                }
                throw Failure(if (e.tooLarge) "failed" else "unreached")
            }
            if (reply.status !in 200..299 && credentials != null && !resigned && isTimestampError(reply.body)) {
                resigned = true
                continue
            }
            return reply
        }
    }

    private class AfterConnect(val dropped: Boolean, val tooLarge: Boolean) : Exception()
    private class TooLarge : IOException("reply too large")

    private fun exchange(root: String, request: Request, auth: List<Pair<String, String>>, clock: GateClock): Reply {
        val hasBody = request.method == "POST"
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
            if (hasBody) {
                connection.doOutput = true
                request.contentType?.let { connection.setRequestProperty("Content-Type", it) }
                connection.setFixedLengthStreamingMode(body?.size ?: 0)
            }
            try {
                connection.connect()
            } catch (_: IOException) {
                throw Failure("unreached")
            }
            try {
                if (hasBody) connection.outputStream.use { out -> if (body != null) out.write(body) }
                val status = connection.responseCode
                if (status < 100) throw IOException("not an HTTP reply")
                clock.observe(connection.getHeaderField("Date"))
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

    private fun hexLower(digest: ByteArray): String {
        val hex = "0123456789abcdef"
        return buildString(digest.size * 2) {
            for (b in digest) append(hex[(b.toInt() shr 4) and 15]).append(hex[b.toInt() and 15])
        }
    }

    private fun JSONObject.present(key: String): Any? = opt(key)?.takeIf { it != JSONObject.NULL }
}

/**
 * Unix-second timestamps on Gate's clock (iOS `GateTimestampGenerator`). Unlike SafeTrade's
 * monotonic millisecond nonces, Gate API v4 checks only that `Timestamp` (in seconds) falls within
 * its server time window and permits concurrent requests with the same second. Offsets ≥ 2 s are
 * learned from HTTP `Date` headers.
 */
class GateClock(private val clock: () -> Long = System::currentTimeMillis) {
    private var offsetMs = 0L

    @Synchronized
    fun next(): String = Math.floorDiv(clock() + offsetMs, 1_000L).toString()

    @Synchronized
    fun observe(dateHeader: String?) {
        val server = SafeTradeNonce.parseHttpDate(dateHeader ?: return) ?: return
        offsetMs = SafeTradeNonce.offset(server, clock())
    }
}

/**
 * The Gate API key pair, device-only: AES-256-GCM ciphertext of `{key, secret}` under its own
 * Android Keystore key (`gate.keys`, StrongBox when available, no user-authentication binding) in
 * no-backup SharedPreferences (`gate`). Mirrors [SafeTradeKeyStore].
 */
class GateKeyStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val keyStore: KeyStore by lazy { KeyStore.getInstance(KEYSTORE).apply { load(null) } }

    fun hasKeys(): Boolean = prefs.contains(CIPHERTEXT) && prefs.contains(IV) && prefs.contains(MASKED)

    fun masked(): String? = if (hasKeys()) prefs.getString(MASKED, null) else null

    fun save(key: String, secret: String): Boolean = synchronized(lock) {
        try {
            val plain = JSONObject().put("key", key).put("secret", secret).toString().toByteArray(Charsets.UTF_8)
            val cipher = try {
                encrypt(existingKey() ?: generateKey(), plain)
            } catch (e: GeneralSecurityException) {
                Log.i(TAG, "Replacing the Gate key (${e.javaClass.simpleName})")
                keyStore.deleteEntry(ALIAS)
                encrypt(generateKey(), plain)
            } finally {
                plain.fill(0)
            }
            prefs.edit()
                .putString(IV, encode(cipher.first))
                .putString(CIPHERTEXT, encode(cipher.second))
                .putString(MASKED, SafeTrade.masked(key))
                .commit()
        } catch (e: Exception) {
            Log.w(TAG, "Gate keys not saved: ${e.javaClass.simpleName}")
            false
        }
    }

    fun read(): Pair<String, String>? = synchronized(lock) {
        if (!hasKeys()) return@synchronized null
        try {
            val key = existingKey() ?: run {
                Log.w(TAG, "Gate Keystore key missing; deleting the saved pair")
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
            Log.w(TAG, "Gate keys unreadable: ${e.javaClass.simpleName}")
            if (isPermanent(e)) {
                prefs.edit(commit = true) { clear() }
                try {
                    keyStore.deleteEntry(ALIAS)
                } catch (_: Exception) {
                }
            }
            null
        }
    }

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
        private const val TAG = "PearlGate"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "gate.keys"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val PREFS = "gate"
        private const val IV = "iv"
        private const val CIPHERTEXT = "ciphertext"
        private const val MASKED = "masked"
        private val lock = Any()
    }
}
