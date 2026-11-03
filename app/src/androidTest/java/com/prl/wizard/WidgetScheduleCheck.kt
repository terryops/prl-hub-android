package com.prl.wizard

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.os.UserManager
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.io.File
import java.util.concurrent.TimeUnit

/** Queue regression: only run on a fresh disposable installation (qa/widget-refresh.mjs). */
class WidgetScheduleCheck : Instrumentation() {
    private var expectedPackage: String? = null
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); expectedPackage = arguments?.getString("package"); start() }

    override fun onStart() {
        val report = Bundle()
        val manager = WorkManager.getInstance(targetContext)
        val name = "widgets.now"
        var ownsQueue = false
        fun work() = manager.getWorkInfosForUniqueWork(name).get(10, TimeUnit.SECONDS).filter { !it.state.isFinished }
        try {
            check(targetContext.packageName == expectedPackage) { "pass the disposable target package with -e package" }
            check(targetContext.getSystemService(UserManager::class.java).isUserUnlocked &&
                !WalletSecretStore(targetContext).hasSecret() &&
                !File(targetContext.applicationInfo.dataDir, "app_webview").exists() &&
                targetContext.getSharedPreferences("widgets", 0).all.isEmpty() &&
                !Widgets.anyPlaced(targetContext) && work().isEmpty()
            ) { "use a fresh disposable installation without wallet, web view, widget data or existing widget jobs" }
            ownsQueue = true
            manager.cancelUniqueWork(name).result.get(10, TimeUnit.SECONDS)
            manager.enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, OneTimeWorkRequest.Builder(WidgetScheduleGateWorker::class.java).build()).result.get(10, TimeUnit.SECONDS)
            check(WidgetScheduleGateWorker.started.await(10, TimeUnit.SECONDS)) { "gate did not start" }
            repeat(20) { WidgetRefresh.soon(targetContext) }
            val active = work()
            check(active.count { it.state == WorkInfo.State.RUNNING } == 1) { "expected one running gate: $active" }
            check(active.count { it.state == WorkInfo.State.BLOCKED || it.state == WorkInfo.State.ENQUEUED } == 1) { "expected exactly one queued successor: $active" }
            WidgetScheduleGateWorker.release.countDown()
            manager.cancelUniqueWork(name).result.get(10, TimeUnit.SECONDS)
            manager.enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, OneTimeWorkRequest.Builder(WidgetScheduleGateWorker::class.java).setInitialDelay(1, TimeUnit.HOURS).build()).result.get(10, TimeUnit.SECONDS)
            repeat(20) { WidgetRefresh.soon(targetContext) }
            check(work().size == 1) { "pending/offline updates did not coalesce: ${work()}" }
            report.putString("stream", "widget_schedule_checks_passed: 2 (20 active updates retain one successor; 20 pending updates coalesce)\n")
            finish(Activity.RESULT_OK, report)
        } catch (e: Throwable) {
            report.putString("stream", "widget_schedule_checks_failed: ${e.stackTraceToString()}\n")
            finish(Activity.RESULT_CANCELED, report)
        } finally {
            WidgetScheduleGateWorker.release.countDown()
            if (ownsQueue) manager.cancelUniqueWork(name)
        }
    }
}
