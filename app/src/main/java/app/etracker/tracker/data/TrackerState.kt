package app.etracker.tracker.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Live status shared between the tracking service and the UI. */
data class TrackerUi(
    val running: Boolean = false,
    val status: String = "Stopped",
    val lastFixMs: Long? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    val accuracy: Float? = null,
    val lastSentMs: Long? = null,
    val queued: Int = 0,
    val revoked: Boolean = false,
)

object TrackerState {
    private val _flow = MutableStateFlow(TrackerUi())
    val flow: StateFlow<TrackerUi> = _flow.asStateFlow()

    fun update(block: TrackerUi.() -> TrackerUi) = _flow.update(block)
}
