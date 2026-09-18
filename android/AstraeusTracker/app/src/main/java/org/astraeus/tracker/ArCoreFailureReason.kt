package org.astraeus.tracker

import com.google.ar.core.TrackingFailureReason

/** Stable wire IDs are independent of ARCore enum ordinal/order. */
fun failureReasonCode(reason: TrackingFailureReason): Int = when (reason) {
    TrackingFailureReason.NONE -> 0
    TrackingFailureReason.BAD_STATE -> 1
    TrackingFailureReason.INSUFFICIENT_LIGHT -> 2
    TrackingFailureReason.EXCESSIVE_MOTION -> 3
    TrackingFailureReason.INSUFFICIENT_FEATURES -> 4
    TrackingFailureReason.CAMERA_UNAVAILABLE -> 5
    else -> 255
}
