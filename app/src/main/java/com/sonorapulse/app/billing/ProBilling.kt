package com.sonorapulse.app.billing

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One-time "Pro" unlock through Google Play Billing (non-consumable in-app product).
 *
 * - The entitlement is cached in SharedPreferences so Pro works offline.
 * - On every app start/resume, queryPurchasesAsync() restores (or revokes, after a refund) Pro.
 * - Purchases are acknowledged; Google refunds unacknowledged purchases after 3 days.
 * - If Play billing is unavailable or the product isn't set up in Play Console yet,
 *   the user sees a friendly message and the app keeps working on the free tier.
 */
object ProBilling {
    const val PRODUCT_ID = "pro_unlock"
    private const val TAG = "ProBilling"
    private const val PREFS = "sonorapulse_prefs"
    private const val KEY_PRO = "pro_unlocked"
    private const val NOT_AVAILABLE = "Pro isn't available yet. Please check back soon."

    private val _isPro = MutableStateFlow(false)
    val isPro: StateFlow<Boolean> = _isPro.asStateFlow()

    /** Formatted store price (e.g. "$4.99") once Play returns the product, else null. */
    private val _price = MutableStateFlow<String?>(null)
    val price: StateFlow<String?> = _price.asStateFlow()

    /** Short user-facing messages (shown as toasts). */
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var prefs: SharedPreferences? = null
    private var client: BillingClient? = null
    @Volatile private var productDetails: ProductDetails? = null
    @Volatile private var connecting = false
    @Volatile private var pendingPurchaseActivity: java.lang.ref.WeakReference<Activity>? = null
    @Volatile private var restoreRequestedByUser = false

    private val purchasesListener = PurchasesUpdatedListener { result, purchases ->
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> purchases?.forEach { handlePurchase(it, fromPurchaseFlow = true) }
            BillingClient.BillingResponseCode.USER_CANCELED -> Unit
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                emit("You already own Pro. Restoring it now.")
                refreshPurchases()
            }
            else -> {
                Log.w(TAG, "Purchase failed: ${result.responseCode} ${result.debugMessage}")
                emit(friendlyError(result))
            }
        }
    }

    /** Call once from Activity.onCreate. Safe to call more than once. */
    fun init(context: Context) {
        val app = context.applicationContext
        if (prefs == null) {
            prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            _isPro.value = prefs?.getBoolean(KEY_PRO, false) ?: false
        }
        if (client == null) {
            client = try {
                BillingClient.newBuilder(app)
                    .setListener(purchasesListener)
                    .enablePendingPurchases(
                        PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()
                    )
                    .enableAutoServiceReconnection()
                    .build()
            } catch (t: Throwable) {
                Log.e(TAG, "Could not create BillingClient", t)
                null
            }
        }
        connect()
    }

    private fun connect(onReady: (() -> Unit)? = null) {
        val c = client ?: return
        if (c.isReady) {
            onReady?.invoke(); return
        }
        if (connecting) {
            if (onReady != null) emit("Connecting to Google Play. Try again in a moment.")
            return
        }
        connecting = true
        try {
            c.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    connecting = false
                    if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                        queryProduct()
                        refreshPurchases()
                        onReady?.invoke()
                    } else {
                        Log.w(TAG, "Billing setup failed: ${result.responseCode} ${result.debugMessage}")
                        if (onReady != null || restoreRequestedByUser) {
                            restoreRequestedByUser = false
                            emit(friendlyError(result))
                        }
                    }
                }

                override fun onBillingServiceDisconnected() {
                    connecting = false
                }
            })
        } catch (t: Throwable) {
            connecting = false
            Log.e(TAG, "startConnection crashed", t)
            if (onReady != null || restoreRequestedByUser) {
                restoreRequestedByUser = false
                emit(NOT_AVAILABLE)
            }
        }
    }

    private fun queryProduct(onDone: (() -> Unit)? = null) {
        val c = client ?: run { onDone?.invoke(); return }
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(PRODUCT_ID)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build()
                )
            )
            .build()
        try {
            c.queryProductDetailsAsync(params) { result, detailsResult ->
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    val details = detailsResult.productDetailsList.firstOrNull { it.productId == PRODUCT_ID }
                    productDetails = details
                    _price.value = details?.oneTimePurchaseOfferDetails?.formattedPrice
                } else {
                    Log.w(TAG, "Product query failed: ${result.responseCode} ${result.debugMessage}")
                }
                onDone?.invoke()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "queryProductDetailsAsync crashed", t)
            onDone?.invoke()
        }
    }

    /** Restores Pro from Play (call on start/resume, and from "Restore purchase"). */
    fun refreshPurchases(userInitiated: Boolean = false) {
        val c = client
        if (c == null) {
            if (userInitiated) emit(NOT_AVAILABLE)
            return
        }
        if (!c.isReady) {
            // A successful connection runs refreshPurchases() itself; the flag makes it report back.
            if (userInitiated) restoreRequestedByUser = true
            connect()
            return
        }
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        try {
            c.queryPurchasesAsync(params) { result, purchases ->
                val wasUser = userInitiated || restoreRequestedByUser
                restoreRequestedByUser = false
                if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                    // Keep the cached entitlement; never take Pro away on a network error.
                    Log.w(TAG, "Purchase query failed: ${result.responseCode} ${result.debugMessage}")
                    if (wasUser) emit(friendlyError(result))
                    return@queryPurchasesAsync
                }
                val pro = purchases.filter { PRODUCT_ID in it.products }
                val owned = pro.firstOrNull { it.purchaseState == Purchase.PurchaseState.PURCHASED }
                if (owned != null) {
                    handlePurchase(owned, fromPurchaseFlow = false)
                    if (wasUser) emit("Pro restored. Thanks for your support!")
                } else {
                    // Play says it isn't owned (never bought, or refunded): lock Pro.
                    setPro(false)
                    if (pro.any { it.purchaseState == Purchase.PurchaseState.PENDING }) {
                        if (wasUser) emit("Your Pro purchase is still pending. It unlocks once payment completes.")
                    } else if (wasUser) {
                        emit("No Pro purchase found for this Google account.")
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "queryPurchasesAsync crashed", t)
            if (userInitiated) emit(NOT_AVAILABLE)
        }
    }

    /** Starts the Google Play purchase sheet for Pro. */
    fun buyPro(activity: Activity) {
        val c = client
        if (c == null) {
            emit(NOT_AVAILABLE); return
        }
        if (!c.isReady) {
            pendingPurchaseActivity = java.lang.ref.WeakReference(activity)
            connect {
                val a = pendingPurchaseActivity?.get()
                pendingPurchaseActivity = null
                if (a != null) a.runOnUiThread { buyPro(a) }
            }
            return
        }
        val details = productDetails
        if (details == null) {
            // Product may not exist in Play Console yet; try once more, then explain.
            queryProduct {
                activity.runOnUiThread {
                    if (productDetails != null) launch(activity, c, productDetails!!)
                    else emit(NOT_AVAILABLE)
                }
            }
            return
        }
        launch(activity, c, details)
    }

    private fun launch(activity: Activity, c: BillingClient, details: ProductDetails) {
        try {
            val params = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(
                    listOf(
                        BillingFlowParams.ProductDetailsParams.newBuilder()
                            .setProductDetails(details)
                            .build()
                    )
                )
                .build()
            val result = c.launchBillingFlow(activity, params)
            when (result.responseCode) {
                BillingClient.BillingResponseCode.OK -> Unit
                BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                    emit("You already own Pro. Restoring it now.")
                    refreshPurchases()
                }
                else -> emit(friendlyError(result))
            }
        } catch (t: Throwable) {
            Log.e(TAG, "launchBillingFlow crashed", t)
            emit(NOT_AVAILABLE)
        }
    }

    private fun handlePurchase(purchase: Purchase, fromPurchaseFlow: Boolean) {
        if (PRODUCT_ID !in purchase.products) return
        when (purchase.purchaseState) {
            Purchase.PurchaseState.PURCHASED -> {
                val wasPro = _isPro.value
                setPro(true)
                if (fromPurchaseFlow && !wasPro) emit("Pro unlocked. Thanks for your support!")
                if (!purchase.isAcknowledged) acknowledge(purchase)
            }
            Purchase.PurchaseState.PENDING ->
                emit("Purchase pending. Pro unlocks as soon as payment completes.")
            else -> Unit
        }
    }

    private fun acknowledge(purchase: Purchase, attempt: Int = 1) {
        val c = client ?: return
        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()
        try {
            c.acknowledgePurchase(params) { result ->
                if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                    Log.w(TAG, "Acknowledge failed (attempt $attempt): ${result.responseCode} ${result.debugMessage}")
                    // Retried automatically on next start via refreshPurchases(); one quick retry now.
                    if (attempt < 2) acknowledge(purchase, attempt + 1)
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "acknowledgePurchase crashed", t)
        }
    }

    private fun setPro(value: Boolean) {
        _isPro.value = value
        prefs?.edit()?.putBoolean(KEY_PRO, value)?.apply()
    }

    private fun emit(message: String) {
        _messages.tryEmit(message)
    }

    private fun friendlyError(result: BillingResult): String = when (result.responseCode) {
        BillingClient.BillingResponseCode.BILLING_UNAVAILABLE,
        BillingClient.BillingResponseCode.ITEM_UNAVAILABLE,
        BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED,
        BillingClient.BillingResponseCode.DEVELOPER_ERROR -> NOT_AVAILABLE
        BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE,
        BillingClient.BillingResponseCode.SERVICE_DISCONNECTED,
        BillingClient.BillingResponseCode.NETWORK_ERROR ->
            "Couldn't reach Google Play. Check your connection and try again."
        else -> "Something went wrong with Google Play. Please try again later."
    }
}
