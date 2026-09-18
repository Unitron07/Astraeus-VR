package org.astraeus.tracker

import kotlin.math.*

data class Vec3(val x: Float = 0f, val y: Float = 0f, val z: Float = 0f) {
    operator fun plus(b: Vec3) = Vec3(x+b.x, y+b.y, z+b.z)
    operator fun minus(b: Vec3) = Vec3(x-b.x, y-b.y, z-b.z)
    operator fun times(s: Float) = Vec3(x*s, y*s, z*s)
    fun length() = sqrt(x*x+y*y+z*z)
    fun finite() = x.isFinite() && y.isFinite() && z.isFinite()
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

fun Quat.normalized(): Quat {
    val n = sqrt(x*x+y*y+z*z+w*w)
    require(n.isFinite() && n > 1e-8f)
    return Quat(x/n,y/n,z/n,w/n)
}
fun Quat.angleTo(b: Quat): Float {
    val d = (inverse()*b).normalized()
    return 2f*atan2(sqrt(d.x*d.x+d.y*d.y+d.z*d.z),abs(d.w))
}
fun rotationVector(v: Vec3): Quat {
    val angle = v.length()
    val scale = if (angle < 1e-6f) 0.5f else sin(angle/2f)/angle
    return Quat(v.x*scale,v.y*scale,v.z*scale,cos(angle/2f)).normalized()
}
fun Quat.stepTo(target: Quat, maxRadians: Float): Quat {
    var d = (inverse()*target).normalized()
    if(d.w < 0) d = Quat(-d.x,-d.y,-d.z,-d.w)
    val axis = Vec3(d.x,d.y,d.z)
    val angle = 2f*atan2(axis.length(),d.w)
    return if(angle < 1e-6f) this else (this*rotationVector(axis*(min(angle,maxRadians)/axis.length()))).normalized()
}
fun RigidPose.compose(b: RigidPose) = RigidPose(p+q.rotate(b.p),(q*b.q).normalized())
fun RigidPose.inverse() = RigidPose(q.inverse().rotate(p*-1f),q.inverse())

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
