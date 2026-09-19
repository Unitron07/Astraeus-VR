package org.astraeus.tracker

data class AnchorSample(val id: Int=0, val state: Int=0, val pose: RigidPose=RigidPose())

/** Backend poses/states must be sampled after the same Session.update as the camera. */
interface AnchorHandle {
    fun sample(id: Int): AnchorSample
    fun detach()
}

/** One local reference. PAUSED is retained; STOPPED is explicitly retired. */
class AnchorReference(private val create: (RigidPose)->AnchorHandle) : AutoCloseable {
    private var handle: AnchorHandle?=null
    private var healthyFrames=0
    private var id=0
    fun update(healthy: Boolean, camera: RigidPose): AnchorSample {
        handle?.let {
            val sample=it.sample(id)
            if(sample.state==0) { it.detach(); handle=null; healthyFrames=0 }
            return sample // Expose STOPPED before considering replacement.
        }
        healthyFrames=if(healthy) healthyFrames+1 else 0
        if(healthyFrames>=3) {
            handle=create(camera); id++; healthyFrames=0
            return handle!!.sample(id)
        }
        return AnchorSample(id)
    }
    override fun close() { handle?.detach(); handle=null; healthyFrames=0 }
}

enum class TrackingEvent {
    NONE, RAW_WORLD_UPDATE_COMPENSATED, ANCHOR_RELATIVE_DISCONTINUITY,
    TRACKING_REACQUISITION, ANCHOR_LOST, CLOCK_ANOMALY, SENSOR_ANOMALY,
    ANCHOR_CREATED, ANCHOR_REPLACED
}
