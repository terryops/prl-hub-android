package com.prl.wizard

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Blocking worker for WidgetScheduleCheck; packaged only in the instrumentation APK. */
class WidgetScheduleGateWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        started.countDown()
        return if (release.await(30, TimeUnit.SECONDS)) Result.success() else Result.failure()
    }

    companion object {
        var started = CountDownLatch(1)
        var release = CountDownLatch(1)
    }
}
