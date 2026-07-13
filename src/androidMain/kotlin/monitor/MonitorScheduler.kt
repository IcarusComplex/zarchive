package monitor

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

private const val UNIQUE_WORK_NAME = "search_monitor"
private const val UNIQUE_CHECK_NOW_WORK_NAME = "search_monitor_check_now"

/**
 * Reactively enqueues/cancels MonitorWorker's periodic job (called from AndroidApp whenever
 * vm.monitorEnabled/vm.monitorIntervalHours change, or a query edit settles). WorkManager
 * persists periodic work across reboots on its own -- no extra boot receiver needed here.
 *
 * [restartAnchor] picks the enqueue policy:
 *  - `true` (`CANCEL_AND_REENQUEUE`) restarts the interval countdown from *now* -- matching
 *    desktop's stopMonitorLoop()/startMonitorLoop(), which always begins a fresh
 *    delay(intervalHours) after whatever just triggered the restart. Use this for an explicit
 *    user action: flipping the switch on, changing the interval, or a query edit settling.
 *  - `false` (`KEEP`) only creates the job if one isn't already scheduled, leaving an existing
 *    schedule's countdown untouched otherwise. Use this for a passive "make sure it's still
 *    scheduled" sync (app launch/resume) -- Android can recreate the Activity, and thus
 *    re-trigger this whole path, far more often than the app is genuinely (re)launched (screen
 *    rotation, memory pressure, etc). Using `CANCEL_AND_REENQUEUE` there was resetting the
 *    periodic timer's anchor on every one of those events, so the monitor could go a full day
 *    without ever completing an interval if the app was opened more often than that -- visible in
 *    the history trail as checks seconds/minutes apart instead of the configured interval.
 */
object MonitorScheduler {
    fun reschedule(context: Context, enabled: Boolean, intervalHours: Int, restartAnchor: Boolean = true) {
        val workManager = WorkManager.getInstance(context)
        if (!enabled) {
            workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
            return
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<MonitorWorker>(
            intervalHours.coerceAtLeast(1).toLong(), TimeUnit.HOURS,
        ).setConstraints(constraints).build()
        val policy = if (restartAnchor) ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE else ExistingPeriodicWorkPolicy.KEEP
        workManager.enqueueUniquePeriodicWork(UNIQUE_WORK_NAME, policy, request)
    }

    // Runs a single check immediately (subject to the same network constraint) -- mirrors the
    // immediate check desktop's startMonitorLoop() always does before its first delay(). REPLACE
    // so rapid repeat triggers (e.g. fast edits) collapse into one run instead of queuing up.
    fun checkNow(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<MonitorWorker>().setConstraints(constraints).build()
        WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_CHECK_NOW_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }
}
