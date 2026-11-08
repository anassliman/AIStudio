package com.aistudio.core.model

import java.time.Instant

/** Identifies the device boot session used to validate monotonic approval deadlines. */
sealed interface BootCount {
    data class Known(val value: Long) : BootCount {
        init {
            require(value >= 0) { "Boot count must not be negative" }
        }
    }

    data object Unknown : BootCount
}

/** A coherent time sample; wall time is for timestamps, monotonic time is for expiry. */
data class ClockSnapshot(
    val wallTime: Instant,
    val elapsedRealtimeMillis: Long,
    val bootCount: BootCount,
) {
    init {
        require(elapsedRealtimeMillis >= 0) { "Elapsed realtime must not be negative" }
    }
}
