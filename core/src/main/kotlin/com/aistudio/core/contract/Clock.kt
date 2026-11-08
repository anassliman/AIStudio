package com.aistudio.core.contract

import com.aistudio.core.model.ClockSnapshot

/** Platform-neutral clock port. Android implementations belong outside core. */
fun interface Clock {
    fun snapshot(): ClockSnapshot
}
