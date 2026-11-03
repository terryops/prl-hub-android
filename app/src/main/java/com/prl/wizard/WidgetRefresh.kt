package com.prl.wizard

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkInfo
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The widgets' own refresh while the app is closed (BRIDGE.md "Widget snapshot"): every ~30
 * minutes on a network, plus once after each page update and when a widget is placed. Figures the
 * page published within the last two minutes are reused instead of fetched (iOS WidgetBridge); a
 * failed fetch keeps the old figures, whose age the stamp shows. Nothing runs while no widget is
 * placed.
 */
object WidgetRefresh {
    private const val TAG = "PearlWidgetRefresh"
    private const val PERIODIC = "widgets.periodic"
    private const val NOW = "widgets.now"
    private const val API = "https://pearlhub.net"
    // Public wallet gateway identifier, also in prl-hub-wallet/src/chain.ts (not a wallet secret).
    private const val NODE_KEY = "212e47a59f6f1f1e4a98b50428700972391dd55cee237080"
    private const val TIMEOUT_MS = 25_000
    private const val MAX_REPLY = 8 * 1024 * 1024
    /** A source tried this recently is not tried again by the next one-shot. */
    private const val RETRY_AFTER = 60L
    private val userAgent = "PearlHubAndroid/${BuildConfig.VERSION_NAME}"

    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun schedule(context: Context) {
        val request = PeriodicWorkRequest.Builder(Job::class.java, 30, TimeUnit.MINUTES).setConstraints(network).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun soon(context: Context) {
        val request = OneTimeWorkRequest.Builder(Job::class.java).setConstraints(network).build()
        val manager = WorkManager.getInstance(context)
        val policy = try {
            val work = manager.getWorkInfosForUniqueWork(NOW).get(5, TimeUnit.SECONDS)
            val running = work.any { it.state == WorkInfo.State.RUNNING }
            val queued = work.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }
            // A publication during a read needs one successor, while offline updates coalesce.
            if (running && !queued) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP
        } catch (e: Exception) {
            if (e is InterruptedException) Thread.currentThread().interrupt()
            Log.w(TAG, "Widget refresh queue unreadable: ${e.javaClass.simpleName}")
            ExistingWorkPolicy.APPEND_OR_REPLACE
        }
        manager.enqueueUniqueWork(NOW, policy, request)
    }

    fun cancel(context: Context) {
        val manager = WorkManager.getInstance(context)
        manager.cancelUniqueWork(PERIODIC)
        manager.cancelUniqueWork(NOW)
    }

    class Job(context: Context, params: WorkerParameters) : Worker(context, params) {
        override fun doWork(): Result {
            val context = applicationContext
            try {
                if (!Widgets.anyPlaced(context)) {
                    cancel(context)
                    return Result.success()
                }
                run(context)
                Widgets.work.submit { Widgets.redraw(context) }.get(30, TimeUnit.SECONDS)
            } catch (e: Exception) {
                Log.w(TAG, "Widget refresh failed: ${e.javaClass.simpleName}")
            }
            return Result.success()
        }
    }

    /** One refresh: the placed widgets' sources, in parallel, each merged only if still current. */
    fun run(context: Context) {
        val stored = Widgets.stored(context)
        val state = stored.state
        val now = Widgets.now()
        val needs = Widgets.needs(context, state)
        val tasks = mutableListOf<Callable<Unit>>()

        if (Widgets.Need.PRICE in needs && !WidgetRules.recent(state.price?.at ?: 0, now) && Widgets.beginRefresh(context, Widgets.Need.PRICE, "price", now, RETRY_AFTER) != null) {
            tasks += Callable { price(context) }
        }
        val app = stored.app
        if (Widgets.Need.POOLS in needs && app != null && app.monitors.isNotEmpty() && !WidgetRules.recent(state.poolsAt, now)) {
            val request = Widgets.beginRefresh(context, Widgets.Need.POOLS, WidgetData.fingerprint(app), now, RETRY_AFTER)
            if (request != null) tasks += Callable { pools(context, app, request) }
        }
        val wallet = stored.wallet
        if (Widgets.Need.WALLET in needs && wallet?.xpub != null && !WidgetRules.recent((state.wallet as? WalletState.Present)?.at ?: wallet.at, now)) {
            val request = Widgets.beginRefresh(context, Widgets.Need.WALLET, WidgetData.fingerprint(wallet), now, RETRY_AFTER)
            if (request != null) tasks += Callable { wallet(context, wallet, request) }
        }
        if (BuildConfig.DEBUG) Log.d(TAG, "refresh: needs $needs, ${tasks.size} sources due")
        if (tasks.isEmpty()) return
        val pool = Executors.newFixedThreadPool(tasks.size)
        try {
            for (future in pool.invokeAll(tasks)) {
                try {
                    future.get()
                } catch (e: Exception) {
                    Log.w(TAG, "Widget source failed: ${(e.cause ?: e).javaClass.simpleName}")
                }
            }
        } finally {
            pool.shutdownNow()
        }
    }

    private fun price(context: Context) {
        val reply = JSONObject(request("$API/v1/price"))
        val price = WidgetData.parsePrice(reply) ?: throw IOException("price reply")
        if (price.at <= 0) throw IOException("price reply has no time")
        val kept = Widgets.saveLivePrice(context, price)
        if (BuildConfig.DEBUG) Log.d(TAG, "price ${price.usd} at ${price.at} kept=$kept")
    }

    private fun pools(context: Context, app: AppPart, requestId: Long) {
        val monitors = app.monitors.distinctBy { it.key }
        val reads = monitors.map { m ->
            try {
                val requestedAt = Widgets.now()
                val body = JSONObject().put("pool", m.pool).put("addr", m.addr)
                if (m.acct != null) body.put("acct", m.acct)
                val reply = JSONObject(request("$API/v1/pool/stats", body.toString()))
                WidgetSources.poolStats(reply) to WidgetSources.poolTime(reply, requestedAt)
            } catch (e: Exception) {
                Log.w(TAG, "Pool stats failed: ${e.javaClass.simpleName}")
                null
            }
        }
        val results = reads.map { it?.first }
        if (results.all { it == null }) return
        val at = reads.mapNotNull { it?.second }.minOrNull() ?: return
        val kept = Widgets.saveLivePools(context, app, results, at, requestId)
        if (BuildConfig.DEBUG) Log.d(TAG, "pools ${results.count { it != null }}/${results.size} answered, kept=$kept")
    }

    private fun wallet(context: Context, wallet: WalletPart, requestId: Long) {
        val xpub = wallet.xpub ?: return
        val origins = if (wallet.network == "testnet") listOf("https://blockbook.testnet.pearlresearch.ai") else listOf("$API/node", "https://blockbook.pearlresearch.ai")
        var lastError: Exception? = null
        for (origin in origins) {
            try {
                val requestedAt = Widgets.now()
                // One refresh reads every target from the same index. On failure start over at the fallback.
                val groups = (listOf(xpub) + wallet.changeAddresses).map { target ->
                    WidgetSources.utxos(JSONArray(request("$origin/api/v2/utxo/" + URLEncoder.encode(target, "UTF-8"))))
                }
                val (balance, pending) = WidgetSources.balance(groups)
                val kept = Widgets.saveLiveWallet(context, LiveWallet(xpub, wallet.network, base = wallet.at, balance = balance, pending = pending, at = requestedAt, source = WidgetData.fingerprint(wallet), request = requestId))
                if (BuildConfig.DEBUG) Log.d(TAG, "wallet ${groups.size} UTXO sets, kept=$kept")
                return
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IOException("No wallet indexer")
    }

    /** GET, or POST a JSON body. Throws on anything but a 2xx JSON reply. */
    private fun request(url: String, json: String? = null): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = false
            useCaches = false
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Accept", "application/json")
            if (url.startsWith("$API/node/")) setRequestProperty("X-Pearl-Hub-Key", NODE_KEY)
        }
        try {
            if (json != null) {
                val bytes = json.toByteArray(Charsets.UTF_8)
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            if (status !in 200..299) throw IOException("HTTP $status")
            return connection.inputStream.use(::readCapped)
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
            if (out.size() + n > MAX_REPLY) throw IOException("reply too large")
            out.write(buffer, 0, n)
        }
        return out.toString("UTF-8")
    }
}
