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
    val clockValid: Boolean, val anchor: AnchorSample=AnchorSample())
data class AstraeusPose(val timestamp: Long, val pose: RigidPose, val velocity: Velocity,
    val quality: TrackingQuality)

/** One synchronized, platform-independent owner of world/fusion state. */
class TrackingEngine(val config: TrackingConfig) {
    val gyro = OrientationFusion()
    val frameTransform = SensorFrameTransform()
    // Maps anchor-local coordinates into persistent Astraeus coordinates.
    var alignment = RigidPose(); private set
    val world: RigidPose get() = alignment.compose((raw?.anchor?.pose ?: RigidPose()).inverse())
    var user = RigidPose(); private set
    private var targetAlignment = RigidPose()
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
    var cameraAnchor = RigidPose(); private set
    var event = TrackingEvent.NONE; private set
    var eventMask = 0; private set
    val steps = FloatArray(6)
    var stepFlags = 0; private set
    var rawWorldUpdates = 0; private set
    var relativeDiscontinuities = 0; private set
    var reacquisitions = 0; private set
    var anchorLosses = 0; private set
    private var referenceId = 0
    private var pendingSensorAnomaly = false
    private var correctionPending = false
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
        if(t<=gyro.timestamp) { pendingSensorAnomaly=true; mark(TrackingEvent.SENSOR_ANOMALY); return gyro.add(t,value) }
        val oldGeneration=gyro.generation
        if(trusted!=gyroTrusted) gyro.reset()
        gyroTrusted=trusted
        val accepted=gyro.add(t,value)
        if(!accepted || !trusted || gyro.generation!=oldGeneration) {
            pendingSensorAnomaly=true; mark(TrackingEvent.SENSOR_ANOMALY)
        }
        return accepted
    }
    private fun mark(value: TrackingEvent) { event=value; eventMask=eventMask or (1 shl value.ordinal) }
    private fun orientation(t: Long): Quat? {
        if(!gyroTrusted) return null
        val a=anchorGyro ?: return null
        if(anchorGeneration!=gyro.generation) return null
        val b=gyro.at(t) ?: return null
        return (anchorOrientation*frameTransform.delta(a,b)).normalized()
    }
    @Synchronized fun onVisual(next: RawArCorePose) {
        val previous=raw
        event=TrackingEvent.NONE; eventMask=0; discontinuity=false
        if(pendingSensorAnomaly) { mark(TrackingEvent.SENSOR_ANOMALY); pendingSensorAnomaly=false }
        steps.fill(0f); stepFlags=0
        if(previous!=null && next.frameTimestamp<=previous.frameTimestamp) {
            anomalies++; mark(TrackingEvent.CLOCK_ANOMALY)
            if(next.frameTimestamp==previous.frameTimestamp || next.timestamp<=previous.timestamp) return
            forcedLost=true; linearValid=false
        }
        raw=next
        if(previous?.anchor?.state==2 && next.anchor.state!=2) { anchorLosses++; mark(TrackingEvent.ANCHOR_LOST) }
        if(!next.clockValid) mark(TrackingEvent.CLOCK_ANOMALY)
        fun step(a: RigidPose,b: RigidPose,index: Int) {
            steps[index]=(b.p-a.p).length(); steps[index+1]=a.q.angleTo(b.q)
            stepFlags=stepFlags or (1 shl (index/2))
        }
        if(previous!=null && previous.state==2 && next.state==2) step(previous.camera,next.camera,0)
        if(previous!=null && previous.anchor.state==2 && next.anchor.state==2 && previous.anchor.id==next.anchor.id)
            step(previous.anchor.pose,next.anchor.pose,2)
        val usable=next.clockValid && next.state==2 && next.anchor.state==2 && next.anchor.id>0
        if(!usable) { linearValid=false; return }
        val relative=next.anchor.pose.inverse().compose(next.camera)
        if(previous!=null && previous.clockValid && previous.state==2 && previous.anchor.state==2 && previous.anchor.id==next.anchor.id)
            step(previous.anchor.pose.inverse().compose(previous.camera),relative,4)
        cameraAnchor=relative
        if(next.timestamp<=lastValidTime) { anomalies++; mark(TrackingEvent.CLOCK_ANOMALY); forcedLost=true; linearValid=false; return }
        frameTransform.calibrate(next.camera.q,next.sensorOrientation)
        val dt=if(lastValidTime==0L) 0f else (next.timestamp-lastValidTime)/1e9f
        val mapped=alignment.compose(relative)
        val predictedQ=orientation(next.timestamp)
        val expectedQ=predictedQ ?: if(anchored) lastOutput.q else stable.q
        val expected=RigidPose(stable.p,expectedQ)
        innovationPosition=if(anchored) (mapped.p-stable.p).length() else 0f
        innovationAngle=if(anchored) expectedQ.angleTo(mapped.q) else 0f
        impliedSpeed=if(dt>0) innovationPosition/dt else 0f
        val replacement=anchored && referenceId!=next.anchor.id
        val recovery=anchored && (replacement || previous?.state!=2 || previous.anchor.state!=2 || previous.clockValid!=true || dt>0.2f || forcedLost)
        val jump=anchored && ((innovationPosition>config.jumpMeters && impliedSpeed>config.maxSpeed) ||
            (predictedQ!=null && innovationAngle>config.jumpRadians))
        val oldPosition=stable.p
        if(recovery || jump) {
            // Preserve the continuous pose at measurement time, not frame arrival time.
            alignment=expected.compose(relative.inverse())
            stable=expected; discontinuities++; discontinuity=true
            when {
                replacement -> { mark(TrackingEvent.ANCHOR_REPLACED); reacquisitions++ }
                recovery -> { mark(TrackingEvent.TRACKING_REACQUISITION); reacquisitions++ }
                else -> { mark(TrackingEvent.ANCHOR_RELATIVE_DISCONTINUITY); relativeDiscontinuities++ }
            }
            // Only reacquisition of the SAME anchor can owe a bounded correction.
            // A rejected relative anomaly or replacement establishes a new alignment.
            correctionPending=recovery && !replacement
            if(!correctionPending) targetAlignment=alignment
            lastJump=innovationPosition; lastJumpAngle=innovationAngle
            recoveringUntil=next.arrival+500_000_000L
            linearValid=false
        } else {
            stable=mapped
            if(anchored) stable=stable.copy(q=expectedQ.stepTo(mapped.q,config.anchorRadiansPerSecond*dt))
            linearValid=anchored && !reseed && dt in 0.001f..0.2f
            linear=if(linearValid) (stable.p-oldPosition)*(1f/dt) else Vec3()
            if(!anchored) mark(TrackingEvent.ANCHOR_CREATED)
            else if(stepFlags==7 && (steps[0]>config.jumpMeters || steps[1]>config.jumpRadians) &&
                (steps[2]>config.jumpMeters || steps[3]>config.jumpRadians) &&
                steps[4]<config.jumpMeters && steps[5]<config.jumpRadians) {
                rawWorldUpdates++; mark(TrackingEvent.RAW_WORLD_UPDATE_COMPENSATED)
            }
        }
        if(config.gradualCorrection && correctionPending && !discontinuity && anchored && dt in 0f..0.2f) {
            val desired=targetAlignment.compose(relative)
            val difference=desired.p-stable.p
            val step=if(difference.length()>0) difference*min(1f,config.translationRate*dt/difference.length()) else Vec3()
            val corrected=RigidPose(stable.p+step,stable.q.stepTo(desired.q,config.rotationRate*dt))
            if(step.length()>1e-7f || stable.q.angleTo(corrected.q)>1e-7f) {
                alignment=corrected.compose(relative.inverse()); stable=corrected; linearValid=false
            }
        }
        val desired=targetAlignment.compose(relative)
        residualPosition=if(correctionPending) (desired.p-stable.p).length() else 0f
        residualAngle=if(correctionPending) desired.q.angleTo(stable.q) else 0f
        if(!anchored) lastOutput=stable
        anchored=true; referenceId=next.anchor.id; anchorOrientation=stable.q; anchorGyro=gyro.at(next.timestamp)
        anchorGeneration=gyro.generation; lastValidTime=next.timestamp; reseed=false; forcedLost=false
    }
    @Synchronized fun recenter(now: Long): Boolean {
        if(!anchored) return false
        val current=RigidPose(stable.p,orientation(now) ?: lastOutput.q)
        user=current.inverse(); revision++; linearValid=false; reseed=true
        // Commit compensation as the new reference: no pending correction moves the new origin.
        targetAlignment=alignment; correctionPending=false; residualPosition=0f; residualAngle=0f
        return true
    }
    @Synchronized fun sourceUnavailable(reason: Int, now: Long) {
        raw=raw?.copy(state=1,reason=reason,arrival=now)
        forcedLost=true; linearValid=false
    }
    @Synchronized fun output(now: Long): AstraeusPose {
        val source=raw
        val q=orientation(now)
        val visualHealthy=source!=null && source.clockValid && source.state==2 && source.anchor.state==2 &&
            now-source.arrival in 0..200_000_000L && !forcedLost
        val quality=when {
            !anchored -> TrackingQuality.UNAVAILABLE
            source?.anchor?.state==0 -> TrackingQuality.DEGRADED
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
