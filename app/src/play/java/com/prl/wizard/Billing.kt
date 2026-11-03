package com.prl.wizard

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pro through Google Play Billing (Play build only). This is the iOS ProStore's StoreKit side:
 * one one-time product, com.prl.wizard.pro. The page uses three bridge methods:
 *   - `billing.product`: the price.
 *   - `billing.purchase`: Play's purchase sheet.
 *   - `billing.owned`: what this Google account owns, for restore and the launch-time check
 *     (iOS `Transaction.currentEntitlements`).
 *
 * Nothing is acknowledged here. The page hands each token to the Worker (POST
 * /v1/purchase/google), which checks it with Google and acknowledges it, so Pro and Google's
 * three-day acknowledgement deadline both depend on the server having seen the purchase. A
 * purchase that completes while no sheet is open (a pending payment approved later) reaches the
 * page as the `billing` event.
 */
object Billing {
    const val SUPPORTED = true
    const val PRODUCT = "com.prl.wizard.pro"
    private const val TAG = "PearlBilling"
    private val main = Handler(Looper.getMainLooper())

    private var client: BillingClient? = null
    private var connecting = false
    private val waiting = mutableListOf<(BillingClient?) -> Unit>()
    private data class PurchaseFlow(val activity: MainActivity, val done: (JSONObject?, String?) -> Unit)
    private var flow: PurchaseFlow? = null

    private val updates = PurchasesUpdatedListener { result, purchases ->
        main.post {
            val pending = flow
            flow = null
            when {
                pending != null -> finish(pending.activity, result, purchases, pending.done)
                result.responseCode == BillingResponseCode.OK && !purchases.isNullOrEmpty() ->
                    MainActivity.dispatchEvent("billing", JSONObject().put("purchases", encode(purchases)))
            }
        }
    }

    private fun client(activity: MainActivity): BillingClient = client ?: BillingClient.newBuilder(activity.applicationContext)
        .setListener(updates)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()
        .also { client = it }

    /** The connected client, or null when Play Billing can't be reached (no Play Store, signed out of Play…). */
    private fun connected(activity: MainActivity, done: (BillingClient?) -> Unit) {
        val c = client(activity)
        if (c.isReady) return done(c)
        waiting += done
        if (connecting) return
        connecting = true
        c.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                main.post {
                    if (result.responseCode != BillingResponseCode.OK) Log.i(TAG, "setup ${result.responseCode} ${result.debugMessage}")
                    flush(c.takeIf { result.responseCode == BillingResponseCode.OK })
                }
            }

            override fun onBillingServiceDisconnected() {
                main.post {
                    val pending = flow
                    flow = null
                    flush(null)
                    // The sheet callback can be lost with the connection. Let the page retry;
                    // purchases discovered later still reach it through resume/the billing event.
                    pending?.done?.invoke(null, "unavailable")
                }
            }
        })
    }

    private fun flush(c: BillingClient?) {
        connecting = false
        val list = waiting.toList()
        waiting.clear()
        list.forEach { it(c) }
    }

    private fun details(activity: MainActivity, done: (ProductDetails?, String?) -> Unit) = connected(activity) { c ->
        if (c == null) return@connected done(null, "unavailable")
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(PRODUCT)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build(),
                ),
            )
            .build()
        c.queryProductDetailsAsync(params) { result, found ->
            main.post {
                val d = found.productDetailsList.firstOrNull { it.productId == PRODUCT }
                if (result.responseCode != BillingResponseCode.OK || d == null) {
                    Log.i(TAG, "product ${result.responseCode} ${result.debugMessage}")
                    done(null, "unavailable")
                } else {
                    done(d, null)
                }
            }
        }
    }

    /** `{ productId, price, currency, micros }`: the price as Play formats it for this account's country. */
    fun product(activity: MainActivity, done: (JSONObject?, String?) -> Unit) = details(activity) { d, error ->
        val offer = d?.oneTimePurchaseOfferDetailsList?.firstOrNull()
        if (offer == null) return@details done(null, error ?: "unavailable")
        done(
            JSONObject()
                .put("productId", PRODUCT)
                .put("price", offer.formattedPrice)
                .put("currency", offer.priceCurrencyCode)
                .put("micros", offer.priceAmountMicros),
            null,
        )
    }

    /** Play's purchase sheet → the purchase (`state` "purchased" or "pending"), or an error code. */
    fun purchase(activity: MainActivity, done: (JSONObject?, String?) -> Unit) {
        if (!activity.canOpenStoreUi()) return done(null, "cancelled")
        details(activity) { d, error ->
            if (!activity.canOpenStoreUi()) return@details done(null, "cancelled")
            val c = client
            if (d == null || c == null) return@details done(null, error ?: "unavailable")
            val stale = flow
            flow = null
            stale?.done?.invoke(null, "cancelled")
            val params = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(listOf(BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(d).build()))
                .build()
            flow = PurchaseFlow(activity, done)
            val started = c.launchBillingFlow(activity, params)
            if (started.responseCode != BillingResponseCode.OK) {
                flow = null
                finish(activity, started, null, done)
            }
        }
    }

    private fun finish(activity: MainActivity, result: BillingResult, purchases: List<Purchase>?, done: (JSONObject?, String?) -> Unit) {
        when (result.responseCode) {
            BillingResponseCode.OK -> {
                val p = purchases?.firstOrNull { PRODUCT in it.products }
                if (p != null) done(encode(p), null) else done(null, "failed")
            }
            // Bought before (another phone, a reinstall): hand the page that purchase to restore.
            BillingResponseCode.ITEM_ALREADY_OWNED -> owned(activity) { list, error ->
                val p = list?.optJSONObject(0)
                if (p != null) done(p, null) else done(null, error ?: "failed")
            }
            else -> {
                Log.i(TAG, "purchase ${result.responseCode} ${result.debugMessage}")
                done(null, errorFor(result.responseCode))
            }
        }
    }

    /** `[{ productId, purchaseToken, orderId, state, acknowledged }]` for Pro, as Play knows this Google account. */
    fun owned(activity: MainActivity, done: (JSONArray?, String?) -> Unit) = connected(activity) { c ->
        if (c == null) return@connected done(null, "unavailable")
        c.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()) { result, purchases ->
            main.post {
                if (result.responseCode != BillingResponseCode.OK) {
                    Log.i(TAG, "owned ${result.responseCode} ${result.debugMessage}")
                    done(null, errorFor(result.responseCode))
                } else {
                    done(encode(purchases), null)
                }
            }
        }
    }

    /** Recover payments completed while Play was disconnected, including pending payments. */
    fun resume(activity: MainActivity) {
        owned(activity) { purchases, _ ->
            if (purchases != null && purchases.length() > 0) {
                val pending = flow
                if (pending?.activity === activity) {
                    // Finding the payment must also release a page still awaiting its sheet result.
                    val owned = (0 until purchases.length()).mapNotNull(purchases::optJSONObject)
                    val purchase = owned.firstOrNull { it.optString("state") == "purchased" } ?: owned.first()
                    flow = null
                    pending.done(purchase, null)
                } else {
                    MainActivity.dispatchEvent("billing", JSONObject().put("purchases", purchases))
                }
            }
        }
    }

    /** A late Play result belongs to the current page, not a reply proxy owned by a dead activity. */
    fun detach(activity: MainActivity) {
        val pending = flow ?: return
        if (pending.activity !== activity) return
        flow = null
        pending.done(null, "cancelled")
    }

    private fun errorFor(code: Int) = when (code) {
        BillingResponseCode.USER_CANCELED -> "cancelled"
        BillingResponseCode.BILLING_UNAVAILABLE, BillingResponseCode.SERVICE_UNAVAILABLE,
        BillingResponseCode.SERVICE_DISCONNECTED, BillingResponseCode.NETWORK_ERROR,
        BillingResponseCode.ITEM_UNAVAILABLE, BillingResponseCode.FEATURE_NOT_SUPPORTED,
        -> "unavailable"
        else -> "failed"
    }

    private fun encode(p: Purchase) = JSONObject()
        .put("productId", PRODUCT)
        .put("purchaseToken", p.purchaseToken)
        .put("orderId", p.orderId ?: JSONObject.NULL)
        .put("state", if (p.purchaseState == Purchase.PurchaseState.PURCHASED) "purchased" else "pending")
        .put("acknowledged", p.isAcknowledged)

    private fun encode(list: List<Purchase>) = JSONArray(list.filter { PRODUCT in it.products }.map(::encode))
}
