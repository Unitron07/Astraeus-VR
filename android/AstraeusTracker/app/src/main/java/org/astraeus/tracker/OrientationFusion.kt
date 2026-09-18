package org.astraeus.tracker

import java.util.ArrayDeque

/** Gyro history in Android sensor coordinates, independent of render/output cadence. */
class OrientationFusion {
    private data class Entry(val t: Long, val q: Quat)
    private val history = ArrayDeque<Entry>()
    var timestamp = 0L; private set
    var omega = Vec3(); private set
    var anomalies = 0; private set
    var generation = 0; private set
    fun add(t: Long, value: Vec3): Boolean {
        if(t<=timestamp || !value.finite()) { anomalies++; return false }
        if(timestamp!=0L && t-timestamp>100_000_000L) {
            history.clear(); generation++; anomalies++
        }
        val previous = history.peekLast()
        val q = if(previous==null) Quat() else
            (previous.q*rotationVector((omega+value)*(0.5f*(t-timestamp)/1e9f))).normalized()
        timestamp=t; omega=value
        history.addLast(Entry(t,q))
        while(history.size>2048 || (history.size>2 && t-history.first.t>2_000_000_000L)) history.removeFirst()
        return true
    }
    fun at(t: Long): Quat? {
        val last=history.peekLast() ?: return null
        if(t>=last.t) return if(t-last.t<=20_000_000L)
            (last.q*rotationVector(omega*((t-last.t)/1e9f))).normalized() else null
        var previous=history.peekFirst() ?: return null
        if(t<previous.t) return null
        for(next in history) {
            if(next.t>=t) {
                if(next.t==previous.t) return next.q
                val fraction=(t-previous.t).toFloat()/(next.t-previous.t)
                return previous.q.stepTo(next.q,previous.q.angleTo(next.q)*fraction)
            }
            previous=next
        }
        return null
    }
    fun reset() { history.clear(); timestamp=0; omega=Vec3(); generation++ }
}

/** q_camera_from_sensor is obtained from paired ARCore camera and sensor poses. */
class SensorFrameTransform {
    var cameraFromSensor = Quat(); private set
    var ready = false; private set
    fun calibrate(camera: Quat, sensor: Quat) {
        if(!ready) { cameraFromSensor=(camera.inverse()*sensor).normalized(); ready=true }
    }
    fun delta(a: Quat,b: Quat) =
        (cameraFromSensor*a.inverse()*b*cameraFromSensor.inverse()).normalized()
    fun angularVelocity(v: Vec3) = cameraFromSensor.rotate(v)
}
