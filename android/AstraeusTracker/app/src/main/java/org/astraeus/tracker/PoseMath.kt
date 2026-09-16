package org.astraeus.tracker

import kotlin.math.*

data class Vec3(val x: Float = 0f, val y: Float = 0f, val z: Float = 0f) {
    operator fun plus(b: Vec3) = Vec3(x+b.x, y+b.y, z+b.z)
    operator fun minus(b: Vec3) = Vec3(x-b.x, y-b.y, z-b.z)
    operator fun times(s: Float) = Vec3(x*s, y*s, z*s)
}
data class Quat(val x: Float = 0f, val y: Float = 0f, val z: Float = 0f, val w: Float = 1f) {
    fun inverse() = Quat(-x,-y,-z,w)
    operator fun times(b: Quat) = Quat(
        w*b.x+x*b.w+y*b.z-z*b.y, w*b.y-x*b.z+y*b.w+z*b.x,
        w*b.z+x*b.y-y*b.x+z*b.w, w*b.w-x*b.x-y*b.y-z*b.z)
    fun rotate(v: Vec3): Vec3 {
        val r = this * Quat(v.x,v.y,v.z,0f) * inverse()
        return Vec3(r.x,r.y,r.z)
    }
}
data class RigidPose(val p: Vec3 = Vec3(), val q: Quat = Quat())

/** The sole ARCore-world -> Astraeus-origin transformation boundary. */
class OriginTransform {
    private var origin = RigidPose()
    var revision = 0; private set
    fun recenter(pose: RigidPose) { origin = pose; revision++ }
    fun apply(pose: RigidPose) = RigidPose(
        origin.q.inverse().rotate(pose.p-origin.p), origin.q.inverse()*pose.q)
}

data class Velocity(val linear: Vec3 = Vec3(), val angular: Vec3 = Vec3(), val flags: Int = 0)
class VelocityEstimator {
    private var previous: RigidPose? = null
    private var time = 0L
    fun reset() { previous = null }
    fun sample(pose: RigidPose, timestamp: Long): Velocity {
        val old = previous
        val dt = (timestamp-time)/1e9f
        previous = pose; time = timestamp
        if (old == null || dt !in 0.001f..0.2f) return Velocity()
        var delta = pose.q * old.q.inverse()
        if (delta.w < 0f) delta = Quat(-delta.x,-delta.y,-delta.z,-delta.w)
        val length = sqrt(delta.x*delta.x+delta.y*delta.y+delta.z*delta.z)
        val scale = if (length < 1e-6f) 2f/dt else 2f*atan2(length,delta.w)/(length*dt)
        return Velocity((pose.p-old.p)*(1f/dt), Vec3(delta.x,delta.y,delta.z)*scale, 3)
    }
}
