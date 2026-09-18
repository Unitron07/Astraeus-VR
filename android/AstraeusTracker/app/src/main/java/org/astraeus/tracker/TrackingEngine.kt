package org.astraeus.tracker

import kotlin.math.*

enum class TrackingQuality { UNAVAILABLE, FULL_6DOF, INERTIAL_ONLY, RECOVERING, DEGRADED }
data class TrackingConfig(
    val outputHz: Int = 120, val jumpMeters: Float = 0.15f, val maxSpeed: Float = 8f,
    val jumpRadians: Float = Math.toRadians(35.0).toFloat(),
    val anchorRadiansPerSecond: Float = Math.toRadians(0.5).toFloat(),
    val gradualCorrection: Boolean = false, val translationRate: Float = 0.01f,
    val rotationRate: Float = Math.toRadians(0.25).toFloat()) {
    init {
        require(outputHz in 30..240)
        require(listOf(jumpMeters,maxSpeed,jumpRadians,anchorRadiansPerSecond,translationRate,rotationRate)
            .all { it.isFinite() && it>=0 })
        require(maxSpeed>0 && jumpMeters>0 && jumpRadians>0)
    }
}
data class RawArCorePose(val frameTimestamp: Long, val timestamp: Long, val arrival: Long,
    val state: Int, val reason: Int, val camera: RigidPose, val sensorOrientation: Quat,
    val clockValid: Boolean)
data class AstraeusPose(val timestamp: Long, val pose: RigidPose, val velocity: Velocity,
    val quality: TrackingQuality)

/** One synchronized, platform-independent owner of world/fusion state. */
class TrackingEngine(val config: TrackingConfig) {
    val gyro = OrientationFusion()
    val frameTransform = SensorFrameTransform()
    var world = RigidPose(); private set
    var user = RigidPose(); private set
    private var targetWorld = RigidPose()
    var revision = 0; private set
    var raw: RawArCorePose? = null; private set
    var stable = RigidPose(); private set
    var innovationPosition = 0f; private set
    var innovationAngle = 0f; private set
    var impliedSpeed = 0f; private set
    var discontinuities = 0; private set
    var discontinuity = false; private set
    var lastJump = 0f; private set
    var lastJumpAngle = 0f; private set
    var residualPosition = 0f; private set
    var residualAngle = 0f; private set
    var anomalies = 0; private set
    private var anchored = false
    private var anchorGyro: Quat? = null
    private var anchorGeneration = -1
    private var anchorOrientation = Quat()
    private var lastValidTime = 0L
    private var recoveringUntil = 0L
    private var lastOutput = RigidPose()
    private var linear = Vec3()
    private var linearValid = false
    private var reseed = false
    private var forcedLost = false
    private var gyroTrusted = true

    @Synchronized fun onGyro(t: Long, value: Vec3, trusted: Boolean=true): Boolean {
        if(t<=gyro.timestamp) return gyro.add(t,value)
        if(trusted!=gyroTrusted) gyro.reset()
        gyroTrusted=trusted
        return gyro.add(t,value)
    }
    private fun orientation(t: Long): Quat? {
        if(!gyroTrusted) return null
        val a=anchorGyro ?: return null
        if(anchorGeneration!=gyro.generation) return null
        val b=gyro.at(t) ?: return null
        return (anchorOrientation*frameTransform.delta(a,b)).normalized()
    }
    @Synchronized fun onVisual(next: RawArCorePose) {
        val previous=raw
        if(previous!=null && next.frameTimestamp<=previous.frameTimestamp) {
            anomalies++
            if(next.frameTimestamp==previous.frameTimestamp || next.timestamp<=previous.timestamp) return
            forcedLost=true; linearValid=false
        }
        raw=next; discontinuity=false
        if(!next.clockValid || next.state!=2) { linearValid=false; return }
        if(next.timestamp<=lastValidTime) { anomalies++; forcedLost=true; linearValid=false; return }
        frameTransform.calibrate(next.camera.q,next.sensorOrientation)
        val dt=if(lastValidTime==0L) 0f else (next.timestamp-lastValidTime)/1e9f
        val mapped=world.compose(next.camera)
        val predictedQ=orientation(next.timestamp)
        val expectedQ=predictedQ ?: if(anchored) lastOutput.q else stable.q
        val expected=RigidPose(stable.p,expectedQ)
        innovationPosition=if(anchored) (mapped.p-stable.p).length() else 0f
        innovationAngle=if(anchored) expectedQ.angleTo(mapped.q) else 0f
        impliedSpeed=if(dt>0) innovationPosition/dt else 0f
        val recovery=anchored && (previous?.state!=2 || previous.clockValid!=true || dt>0.2f || forcedLost)
        val jump=anchored && ((innovationPosition>config.jumpMeters && impliedSpeed>config.maxSpeed) ||
            (predictedQ!=null && innovationAngle>config.jumpRadians))
        val oldPosition=stable.p
        if(recovery || jump) {
            // Preserve the continuous pose at measurement time, not frame arrival time.
            world=expected.compose(next.camera.inverse())
            stable=expected; discontinuities++; discontinuity=true
            lastJump=innovationPosition; lastJumpAngle=innovationAngle
            recoveringUntil=next.arrival+500_000_000L
            linearValid=false
        } else {
            stable=mapped
            if(anchored) stable=stable.copy(q=expectedQ.stepTo(mapped.q,config.anchorRadiansPerSecond*dt))
            linearValid=anchored && !reseed && dt in 0.001f..0.2f
            linear=if(linearValid) (stable.p-oldPosition)*(1f/dt) else Vec3()
        }
        if(config.gradualCorrection && !discontinuity && anchored && dt in 0f..0.2f) {
            val desired=targetWorld.compose(next.camera)
            val difference=desired.p-stable.p
            val step=if(difference.length()>0) difference*min(1f,config.translationRate*dt/difference.length()) else Vec3()
            val corrected=RigidPose(stable.p+step,stable.q.stepTo(desired.q,config.rotationRate*dt))
            if(step.length()>1e-7f || stable.q.angleTo(corrected.q)>1e-7f) {
                world=corrected.compose(next.camera.inverse()); stable=corrected; linearValid=false
            }
        }
        val desired=targetWorld.compose(next.camera)
        residualPosition=(desired.p-stable.p).length(); residualAngle=desired.q.angleTo(stable.q)
        if(!anchored) lastOutput=stable
        anchored=true; anchorOrientation=stable.q; anchorGyro=gyro.at(next.timestamp)
        anchorGeneration=gyro.generation; lastValidTime=next.timestamp; reseed=false; forcedLost=false
    }
    @Synchronized fun recenter(now: Long): Boolean {
        if(!anchored) return false
        val current=RigidPose(stable.p,orientation(now) ?: lastOutput.q)
        user=current.inverse(); revision++; linearValid=false; reseed=true
        // Commit compensation as the new reference: no pending correction moves the new origin.
        targetWorld=world; residualPosition=0f; residualAngle=0f
        return true
    }
    @Synchronized fun output(now: Long): AstraeusPose {
        val source=raw
        val q=orientation(now)
        val visualHealthy=source!=null && source.clockValid && source.state==2 &&
            now-source.arrival in 0..200_000_000L && !forcedLost
        val quality=when {
            !anchored -> TrackingQuality.UNAVAILABLE
            q==null -> TrackingQuality.DEGRADED
            !visualHealthy && now-lastValidTime>2_000_000_000L -> TrackingQuality.DEGRADED
            !visualHealthy -> TrackingQuality.INERTIAL_ONLY
            now<recoveringUntil -> TrackingQuality.RECOVERING
            config.gradualCorrection && (residualPosition>0.001f || residualAngle>0.001f) -> TrackingQuality.RECOVERING
            else -> TrackingQuality.FULL_6DOF
        }
        lastOutput=RigidPose(stable.p,q ?: if(anchored) lastOutput.q else Quat())
        val pose=user.compose(lastOutput)
        val angular=if(q!=null) pose.q.rotate(frameTransform.angularVelocity(gyro.omega)) else Vec3()
        val flags=(if(linearValid && visualHealthy) 1 else 0) or (if(q!=null) 2 else 0)
        return AstraeusPose(now,pose,Velocity(if(flags and 1!=0) user.q.rotate(linear) else Vec3(),angular,flags),quality)
    }
    @Synchronized fun rawInUserSpace() = user.compose(raw?.camera ?: RigidPose())
}
