package org.astraeus.tracker

import org.junit.Assert.*
import org.junit.Test

class AnchorTrackingTest {
    private val start=1_000_000_000L
    private fun poseClose(a: RigidPose,b: RigidPose) {
        assertEquals(0f,(a.p-b.p).length(),0.00002f)
        assertEquals(0f,a.q.angleTo(b.q),0.00002f)
    }
    private fun frame(t: Long,c: RigidPose=RigidPose(),a: RigidPose=RigidPose(),state: Int=2,
        anchorState: Int=2,id: Int=1)=RawArCorePose(t,t,t,state,0,c,c.q,true,AnchorSample(id,anchorState,a))
    private fun seed(config: TrackingConfig=TrackingConfig())=TrackingEngine(config).also {
        it.onGyro(start,Vec3()); it.onVisual(frame(start)); it.output(start)
    }
    @Test fun sharedRigidWorldChangeCancelsWithNontrivialAnchorAndCamera() {
        val a=RigidPose(Vec3(3f,-1f,2f),rotationVector(Vec3(.4f,-.3f,.2f)))
        val c=RigidPose(Vec3(2f,1f,4f),rotationVector(Vec3(-.2f,.5f,.1f)))
        val j=RigidPose(Vec3(-5f,3f,1f),rotationVector(Vec3(.7f,.8f,-.4f)))
        poseClose(a.inverse().compose(c),j.compose(a).inverse().compose(j.compose(c)))
        val e=TrackingEngine(TrackingConfig(gradualCorrection=true))
        e.onGyro(start,Vec3()); e.onVisual(frame(start,c,a)); val before=e.output(start)
        e.onGyro(start+33_000_000,Vec3()); e.onVisual(frame(start+33_000_000,j.compose(c),j.compose(a)))
        poseClose(before.pose,e.output(start+33_000_000).pose)
        assertEquals(0f,e.residualPosition,0f); assertEquals(0f,e.residualAngle,0f)
        assertEquals(TrackingQuality.FULL_6DOF,e.output(start+33_000_000).quality)
    }
    @Test fun runFiveWorldUpdateCreatesNoDebtOrDelayedCreepEvenWithCorrectionEnabled() {
        val e=seed(TrackingConfig(gradualCorrection=true))
        val j=RigidPose(Vec3(.319f,0f,0f),rotationVector(Vec3(0f,Math.toRadians(55.7).toFloat(),0f)))
        for(i in 1..300) {
            val t=start+i*33_000_000L; e.onGyro(t,Vec3()); e.onVisual(frame(t,j,j))
            val out=e.output(t)
            poseClose(RigidPose(),out.pose)
            assertEquals(TrackingQuality.FULL_6DOF,out.quality)
            assertEquals(0f,e.residualPosition,0f); assertEquals(0f,e.residualAngle,0f)
            assertTrue(out.velocity.linear.length()<0.001f)
            if(i==1) {
                assertEquals(TrackingEvent.RAW_WORLD_UPDATE_COMPENSATED,e.event)
                assertEquals(.319f,e.steps[0],1e-5f)
                assertEquals(Math.toRadians(55.7).toFloat(),e.steps[1],1e-5f)
                assertEquals(0f,e.steps[4],1e-5f)
            }
        }
        assertEquals(1,e.rawWorldUpdates); assertEquals(0,e.discontinuities)
    }
    @Test fun trueRelativeJumpProtectsContinuityAndIsNotPaidBack() {
        val e=seed(TrackingConfig(gradualCorrection=true))
        for(i in 1..40) {
            val t=start+i*33_000_000L; e.onGyro(t,Vec3())
            e.onVisual(frame(t,RigidPose(Vec3(1.5f,0f,0f))))
            poseClose(RigidPose(),e.output(t).pose)
            if(i==1) {
                assertEquals(TrackingEvent.ANCHOR_RELATIVE_DISCONTINUITY,e.event)
                assertEquals(0,e.output(t).velocity.flags and 1)
                assertEquals(TrackingQuality.RECOVERING,e.output(t).quality)
            }
        }
        assertEquals(1,e.relativeDiscontinuities); assertEquals(0,e.reacquisitions)
        assertEquals(0f,e.residualPosition,0f)
    }
    @Test fun pauseRotationAndReacquisitionAreSeparateFromWorldUpdate() {
        val e=seed(); val w=Vec3(0f,.5f,0f)
        e.onVisual(frame(start+1,state=1))
        for(i in 1..300) { val t=start+i*10_000_000L; e.onGyro(t,w); e.output(t) }
        val before=e.output(start+3_000_000_000L)
        assertEquals(0f,before.pose.p.length(),0f); assertTrue(before.pose.q.angleTo(Quat())>1f)
        e.onVisual(frame(start+3_000_000_000L,RigidPose(Vec3(.4f,0f,0f),before.pose.q)))
        poseClose(before.pose,e.output(start+3_000_000_000L).pose)
        assertEquals(TrackingEvent.TRACKING_REACQUISITION,e.event)
        assertEquals(1,e.reacquisitions); assertEquals(0,e.rawWorldUpdates)
        assertEquals(0,e.output(start+3_000_000_000L).velocity.flags and 1)
        assertEquals(TrackingQuality.RECOVERING,e.output(start+3_000_000_000L).quality)
    }
    @Test fun recenterChangesOnlyUserReference() {
        val e=seed(); val c=RigidPose(Vec3(.02f,0f,0f))
        val t=start+33_000_000L; e.onGyro(t,Vec3()); e.onVisual(frame(t,c))
        val alignment=e.alignment; val anchor=e.raw!!.anchor
        assertTrue(e.recenter(t)); poseClose(RigidPose(),e.output(t).pose)
        assertEquals(anchor,e.raw!!.anchor); assertEquals(alignment,e.alignment); assertEquals(1,e.revision)
        assertEquals(0,e.output(t).velocity.flags and 1)
    }
    @Test fun anchorStopAndReplacementAreExplicitAndPreservePublicSpace() {
        val e=seed(); e.onGyro(start+33_000_000,Vec3())
        e.onVisual(frame(start+33_000_000,anchorState=0))
        assertEquals(TrackingEvent.ANCHOR_LOST,e.event); assertEquals(1,e.anchorLosses)
        assertEquals(TrackingQuality.DEGRADED,e.output(start+33_000_000).quality)
        val a=RigidPose(Vec3(5f,2f,1f),rotationVector(Vec3(.4f,1f,0f)))
        val c=a.compose(RigidPose(Vec3(1f,0f,0f),rotationVector(Vec3(0f,.4f,0f))))
        e.onGyro(start+66_000_000,Vec3()); e.onVisual(frame(start+66_000_000,c,a,id=2))
        assertEquals(TrackingEvent.ANCHOR_REPLACED,e.event)
        poseClose(RigidPose(),e.output(start+66_000_000).pose)
        assertEquals(0,e.output(start+66_000_000).velocity.flags and 1)
        assertEquals(0f,e.residualPosition,0f)
        assertTrue(e.alignment.p.length()>.9f)
    }
    @Test fun lifecycleRequiresThreeHealthyFramesRetainsPauseAndDetachesStop() {
        var created=0; var detached=0; var state=2
        val reference=AnchorReference { p -> created++; object: AnchorHandle {
            override fun sample(id: Int)=AnchorSample(id,state,p)
            override fun detach() { detached++ }
        } }
        repeat(2) { assertEquals(0,reference.update(true,RigidPose()).id) }
        reference.update(false,RigidPose())
        repeat(2) { assertEquals(0,reference.update(true,RigidPose()).id) }
        assertEquals(1,reference.update(true,RigidPose()).id)
        state=1; repeat(10) { assertEquals(1,reference.update(true,RigidPose()).state) }
        assertEquals(1,created); assertEquals(0,detached)
        state=0; assertEquals(0,reference.update(true,RigidPose()).state); assertEquals(1,detached)
        state=2; repeat(2) { assertEquals(1,reference.update(true,RigidPose()).id) }
        assertEquals(2,reference.update(true,RigidPose()).id)
        reference.close(); assertEquals(2,detached)
    }
    @Test fun clockAndSensorAnomaliesHaveExplicitEventBits() {
        val e=seed(); e.onGyro(start,Vec3())
        e.onVisual(frame(start+33_000_000).copy(clockValid=false))
        assertTrue(e.eventMask and (1 shl TrackingEvent.SENSOR_ANOMALY.ordinal)!=0)
        assertTrue(e.eventMask and (1 shl TrackingEvent.CLOCK_ANOMALY.ordinal)!=0)
        assertEquals(0,e.output(start+33_000_000).velocity.flags and 1)
    }
}
