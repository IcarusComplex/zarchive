package monitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import data.SettingsStore

/**
 * MonitorForegroundService (unlike WorkManager's periodic job) does not survive a device reboot
 * on its own -- restarts it here if the monitor was left on, so a reboot doesn't silently and
 * permanently stop background checks until the user happens to reopen the app.
 */
class MonitorBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && SettingsStore.getSettingBoolean("monitorEnabled", false)) {
            MonitorForegroundService.start(context)
        }
    }
}
