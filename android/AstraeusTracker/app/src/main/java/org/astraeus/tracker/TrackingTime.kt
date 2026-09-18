package org.astraeus.tracker

/** Frame.timestamp is NOT assumed to be Android boot time. */
class CameraTimeMapper(private val realtimeCamera: Boolean) {
    var offsetNs = 0L; private set
    var ageNs = 0L; private set
    var valid = false; private set
    var anomalies = 0; private set
    private var previousFrame = 0L
    private var previousCamera = 0L
    fun map(frame: Long, camera: Long, now: Long): Long? {
        ageNs = now-camera
        valid = realtimeCamera && frame>0 && camera>0 && ageNs in 0..500_000_000L &&
            (previousCamera==0L || (camera>previousCamera && frame>previousFrame &&
                kotlin.math.abs((camera-previousCamera)-(frame-previousFrame))<5_000_000L))
        if(frame==previousFrame && camera==previousCamera) return null
        previousFrame=frame; previousCamera=camera
        if(!valid) { anomalies++; return null }
        offsetNs=camera-frame
        return camera
    }
}

class MeasuredRate {
    private var start = 0L
    private var last = 0L
    private var count = 0
    var hz = 0f; private set
    fun add(t: Long) {
        if(t<=last) return
        if(last==0L || t-last>1_000_000_000L) { start=t; count=0; hz=0f }
        last=t; count++
        if(t-start>=1_000_000_000L) { hz=(count-1)*1e9f/(t-start); start=t; count=1 }
    }
    fun at(t: Long) = if(t-last>1_000_000_000L) 0f else hz
}
