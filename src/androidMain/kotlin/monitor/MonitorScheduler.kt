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
 * `CANCEL_AND_REENQUEUE` (not `UPDATE`) so every reschedule() call restarts the interval countdown
 * from *now* -- matching desktop's stopMonitorLoop()/startMonitorLoop(), which always begins a
 * fresh delay(intervalHours) after whatever just triggered the restart.
 */
object MonitorScheduler {
    fun reschedule(context: Context, enabled: Boolean, intervalHours: Int) {
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
        workManager.enqueueUniquePeriodicWork(UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE, request)
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
