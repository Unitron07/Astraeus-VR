package org.astraeus.tracker

import com.google.ar.core.TrackingFailureReason

/** Stable wire IDs are independent of ARCore enum ordinal/order. */
@Suppress("REDUNDANT_ELSE_IN_WHEN") // Retain a safe wire fallback when SDK enum values expand.
fun failureReasonCode(reason: TrackingFailureReason): Int = when (reason) {
    TrackingFailureReason.NONE -> 0
    TrackingFailureReason.BAD_STATE -> 1
    TrackingFailureReason.INSUFFICIENT_LIGHT -> 2
    TrackingFailureReason.EXCESSIVE_MOTION -> 3
    TrackingFailureReason.INSUFFICIENT_FEATURES -> 4
    TrackingFailureReason.CAMERA_UNAVAILABLE -> 5
    else -> 255
}

fun failureReasonName(code: Int) = when(code) {
    0 -> "NONE"
    1 -> "BAD_STATE"
    2 -> "INSUFFICIENT_LIGHT"
    3 -> "EXCESSIVE_MOTION"
    4 -> "INSUFFICIENT_FEATURES"
    5 -> "CAMERA_UNAVAILABLE"
    else -> "UNKNOWN"
}
fun arCoreStateName(code: Int) = when(code) { 2 -> "TRACKING"; 1 -> "PAUSED"; else -> "STOPPED" }
