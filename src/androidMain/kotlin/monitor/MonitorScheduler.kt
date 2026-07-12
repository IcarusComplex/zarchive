package monitor

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

private const val UNIQUE_WORK_NAME = "search_monitor"

/**
 * Reactively enqueues/cancels MonitorWorker's periodic job (called from AndroidApp whenever
 * vm.monitorEnabled/vm.monitorIntervalHours change). WorkManager persists periodic work across
 * reboots on its own -- no extra boot receiver needed here.
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
        workManager.enqueueUniquePeriodicWork(UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}
