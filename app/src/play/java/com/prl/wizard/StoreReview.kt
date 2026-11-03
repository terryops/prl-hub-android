package com.prl.wizard

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.play.core.review.ReviewManagerFactory
import com.google.android.play.core.review.ReviewManager

/** Google Play's in-app review sheet (Play build only; see ReviewPrompt). */
object StoreReview {
    const val SUPPORTED = true

    /** A beat after the good news (the rigs just reported in), as on iOS. */
    private const val DELAY_MS = 1_500L

    fun launch(activity: MainActivity, manager: ReviewManager = ReviewManagerFactory.create(activity)) {
        if (!activity.canOpenStoreUi()) return
        manager.requestReviewFlow().addOnCompleteListener { request ->
            if (!request.isSuccessful) {
                Log.i("PearlReview", "review flow unavailable", request.exception)
                return@addOnCompleteListener
            }
            Handler(Looper.getMainLooper()).postDelayed({
                if (activity.canOpenStoreUi()) manager.launchReviewFlow(activity, request.result)
            }, DELAY_MS)
        }
    }
}
