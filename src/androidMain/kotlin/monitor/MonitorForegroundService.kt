package monitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidapp.MainActivity
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import co.za.zarchive.R
import data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val SERVICE_CHANNEL_ID = "search_monitor_service"
private const val SERVICE_NOTIFICATION_ID = 1002

/**
 * Real-device testing (v1.1.1) showed WorkManager's periodic job alone doesn't survive the app
 * being fully closed for hours, even with battery usage set to "Unrestricted" -- this device/OS
 * version exposes no per-app "allow background activity" toggle or "lock in recents" option to
 * fall back to either. A foreground service with a persistent (silent, minimum-priority)
 * notification is the standard, OEM-agnostic way to keep a process alive for background polling
 * -- the system treats it the same as any actively-running, user-visible component, which is a
 * fundamentally different (and much stronger) guarantee than a scheduled job.
 *
 * Deliberately thin: this service's only job is to stay alive and fire
 * MonitorScheduler.checkNow() on schedule. The actual check logic (dedup lock, history,
 * notifications) stays entirely in MonitorWorker, unchanged -- both this service's periodic
 * trigger and WorkManager's own periodic job (kept as a redundant backup) fire the same
 * checkNow() path, and MonitorWorker's MIN_CHECK_GAP_MS dedup already absorbs near-simultaneous
 * triggers from either source.
 */
class MonitorForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loopJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(SERVICE_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(SERVICE_NOTIFICATION_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Guards against onStartCommand firing again (e.g. a redundant start() call, or
        // START_STICKY recreating the service) while a loop is already running -- restarting it
        // would reset the interval countdown from now, same haywire class of bug as the
        // Compose-effect races this whole feature already worked around once.
        if (loopJob?.isActive != true) {
            loopJob = scope.launch { runLoop() }
        }
        return START_STICKY
    }

    private suspend fun runLoop() {
        while (true) {
            val intervalHours = SettingsStore.getSetting("monitorIntervalHours", "1").toIntOrNull()?.coerceAtLeast(1) ?: 1
            delay(intervalHours * 3_600_000L)
            MonitorScheduler.checkNow(applicationContext)
        }
    }

    override fun onDestroy() {
        loopJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(SERVICE_CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                // IMPORTANCE_LOW, not MIN -- a foreground service notification is required to be
                // visible in the status bar precisely so the user always knows something is
                // running in the background. MIN hides the icon entirely, which reads as the
                // service trying to stay invisible -- exactly the pattern Play Protect's on-device
                // scanner flags foreground-service abuse on (worse combined with
                // FOREGROUND_SERVICE_DATA_SYNC + a boot receiver, since an unsigned sideloaded APK
                // has no publisher reputation to offset it).
                NotificationChannel(SERVICE_CHANNEL_ID, "Monitor active", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shows while the background search monitor is running"
                    setShowBadge(false)
                },
            )
        }
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // No .setPriority()/.setSilent() -- minSdk 26 means the channel's importance above is
        // always authoritative; setting a redundant/conflicting priority here was leftover
        // pre-channel-era API that only muddies intent.
        return NotificationCompat.Builder(this, SERVICE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("ZArchive monitor active")
            .setContentText("Watching for new listings in the background")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, MonitorForegroundService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MonitorForegroundService::class.java))
        }
    }
}
