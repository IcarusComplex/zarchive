package monitor

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Fires once per completed MonitorWorker run -- success or failure, hits or not. Lets a
 * foregrounded AndroidApp refresh its "last checked"/status line live instead of only ever picking
 * one up at cold start or on an ON_RESUME (see syncMonitorOnForeground in AndroidApp.kt): without
 * this, a check that completed while the app sat open in the foreground the whole time (no
 * launch/resume event to trigger a re-sync) left the status line stuck on whatever it showed when
 * the app was opened, even though SettingsStore -- and the history trail -- had already moved on.
 * No replay buffer, unlike MonitorHitBus: a stale "checked" signal from before the app was even
 * open carries no useful payload, and the launch-time sync already covers that case.
 */
object MonitorCheckBus {
    private val _checked = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val checked = _checked.asSharedFlow()

    fun notifyChecked() {
        _checked.tryEmit(Unit)
    }
}
