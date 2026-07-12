package monitor

import data.SearchResult
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Bridges MonitorWorker (may run with no Activity/Compose alive) back to a live AndroidApp UI:
 * if the app happens to be foregrounded when a scheduled check finds hits, this lets the existing
 * monitor-alerts modal pop up immediately, in addition to the system notification the Worker
 * always posts. A replay buffer of 1 covers the narrow window between a hit being emitted and
 * AndroidApp's collector starting (e.g. app launched right as a check completes).
 */
object MonitorHitBus {
    private val _hits = MutableSharedFlow<List<SearchResult>>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val hits = _hits.asSharedFlow()

    suspend fun emit(newHits: List<SearchResult>) {
        if (newHits.isNotEmpty()) _hits.emit(newHits)
    }
}
