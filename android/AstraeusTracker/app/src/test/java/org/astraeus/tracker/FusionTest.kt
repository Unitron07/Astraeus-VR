package org.astraeus.tracker

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class FusionTest {
    private val start=1_000_000_000L
    private fun close(a: Quat,b: Quat,tolerance: Float=0.0001f) = assertTrue("angle ${a.angleTo(b)}",a.angleTo(b)<tolerance)
    private fun visual(t: Long,p: RigidPose=RigidPose(),state: Int=2,reason: Int=0,frame: Long=t) =
        RawArCorePose(frame,t,t,state,reason,p,p.q,true)
    private fun seeded(config: TrackingConfig=TrackingConfig(),omega: Vec3=Vec3()): TrackingEngine =
        TrackingEngine(config).also { it.onGyro(start,omega); it.onVisual(visual(start)) }

    @Test fun signedYawPitchRollAndDifferentSteps() {
        for(axis in listOf(Vec3(0f,1f,0f),Vec3(0f,-1f,0f),Vec3(1f,0f,0f),Vec3(0f,0f,1f))) {
            for(count in listOf(20,100,240,1000)) {
                val f=OrientationFusion(); val w=axis*(PI.toFloat()/2)
                for(i in 0..count) f.add(start+i*1_000_000_000L/count,w)
                close(rotationVector(w),f.at(start+1_000_000_000L)!!)
            }
        }
    }
    @Test fun combinedRotationsUseBodyRightMultiplication() {
        val f=OrientationFusion(); val w=Vec3(0.3f,0.9f,-0.4f)
        for(i in 0..100) f.add(start+i*10_000_000L,w)
        close(rotationVector(w),f.at(start+1_000_000_000L)!!)
        val base=rotationVector(Vec3(0f,0f,1f))
        val e=seeded(omega=w)
        e.onVisual(visual(start+1,RigidPose(q=base)))
        // Independent frame conversion: sensor X becomes camera Y under +90 degree Z.
        val transform=SensorFrameTransform()
        transform.calibrate(Quat(),rotationVector(Vec3(0f,0f,PI.toFloat()/2)))
        val v=transform.angularVelocity(Vec3(1f,0f,0f))
        assertEquals(0f,v.x,1e-5f); assertEquals(1f,v.y,1e-5f)
        close(transform.delta(Quat(),rotationVector(Vec3(1f,0f,0f))),rotationVector(Vec3(0f,1f,0f)))
    }
    @Test fun noMotionAndSignEquivalence() {
        val f=OrientationFusion()
        for(i in 0..100) f.add(start+i*10_000_000L,Vec3())
        close(Quat(),f.at(start+1_000_000_000L)!!)
        close(Quat(),Quat(0f,0f,0f,-1f))
    }
    @Test fun recenterDoesNotCountAsWorldJumpAndWorksDuringLoss() {
        val e=seeded(); e.onGyro(start+10_000_000L,Vec3())
        e.onVisual(visual(start+10_000_000L,RigidPose(Vec3(0.01f,0f,0f))))
        val before=e.discontinuities
        e.onVisual(visual(start+11_000_000L,state=1))
        assertTrue(e.recenter(start+12_000_000L))
        val out=e.output(start+12_000_000L)
        assertEquals(0f,out.pose.p.length(),1e-6f); close(Quat(),out.pose.q)
        assertEquals(before,e.discontinuities); assertEquals(1,e.revision)
    }
    @Test fun pausedQuarterSecondKeepsOrientationAndFreezesPosition() {
        val omega=Vec3(0f,PI.toFloat()/2,0f); val e=seeded(omega=omega)
        e.onVisual(visual(start+1,state=1,reason=4))
        for(i in 1..25) e.onGyro(start+i*10_000_000L,omega)
        val out=e.output(start+250_000_000L)
        close(rotationVector(omega*0.25f),out.pose.q)
        assertEquals(0f,out.pose.p.length(),1e-6f)
        assertEquals(TrackingQuality.INERTIAL_ONLY,out.quality)
        assertEquals(2,out.velocity.flags)
    }
    @Test fun shiftedReacquisitionPreservesPoseAndResetsVelocity() {
        val e=seeded(); e.onVisual(visual(start+1,state=1))
        for(i in 1..25) e.onGyro(start+i*10_000_000L,Vec3())
        val shift=RigidPose(Vec3(1.5f,0f,0f),rotationVector(Vec3(0f,Math.toRadians(20.0).toFloat(),0f)))
        e.onVisual(visual(start+250_000_000L,shift))
        val out=e.output(start+250_000_000L)
        assertEquals(0f,out.pose.p.length(),1e-5f); close(Quat(),out.pose.q)
        assertEquals(1,e.discontinuities); assertEquals(0,out.velocity.flags and 1)
        assertEquals(1.5f,e.raw!!.camera.p.x,1e-5f)
        assertEquals(TrackingQuality.RECOVERING,out.quality)
        assertTrue(e.residualPosition>1f)
    }
    @Test fun impossibleJumpWhileTrackingIsAbsorbed() {
        val e=seeded()
        for(i in 1..3) e.onGyro(start+i*11_000_000L,Vec3())
        e.onVisual(visual(start+33_000_000L,RigidPose(Vec3(1.5f,0f,0f))))
        assertEquals(1,e.discontinuities); assertTrue(e.impliedSpeed>40)
        assertEquals(0f,e.output(start+33_000_000L).pose.p.length(),1e-5f)
    }
    @Test fun gradualTranslationIsCappedAndResidualDecreases() {
        val e=seeded(TrackingConfig(gradualCorrection=true,translationRate=0.01f,rotationRate=0f))
        e.onGyro(start+33_000_000L,Vec3()); e.onVisual(visual(start+33_000_000L,RigidPose(Vec3(1.5f,0f,0f))))
        val residual=e.residualPosition
        var old=e.output(start+33_000_000L).pose.p
        for(i in 2..31) {
            val t=start+i*33_000_000L; e.onGyro(t,Vec3()); e.onVisual(visual(t,RigidPose(Vec3(1.5f,0f,0f))))
            val next=e.output(t).pose.p
            assertTrue((next-old).length()<=0.000331f); old=next
        }
        assertEquals(residual-0.0099f,e.residualPosition,2e-5f)
    }
    @Test fun fastLegitimateRotationAndWalkingAreNotWorldJumps() {
        val w=Vec3(0f,6f,0f); val e=seeded(omega=w)
        for(i in 1..100) {
            val t=start+i*10_000_000L; e.onGyro(t,w)
            if(i%3==0) e.onVisual(visual(t,RigidPose(Vec3(i*0.02f,0f,0f),rotationVector(w*(i*0.01f)))))
            e.output(t)
        }
        assertEquals(0,e.discontinuities)
    }
    @Test fun anomaliesDoNotIntegrateInvalidDeltasAndCanRestart() {
        val f=OrientationFusion(); assertTrue(f.add(start,Vec3(0f,1f,0f)))
        assertFalse(f.add(start,Vec3())); assertFalse(f.add(start-1,Vec3()))
        assertTrue(f.add(start+500_000_000L,Vec3(0f,1f,0f)))
        close(Quat(),f.at(start+500_000_000L)!!)
        assertNull(f.at(start+600_000_000L))
        f.reset(); assertTrue(f.add(1,Vec3())); close(Quat(),f.at(1)!!)
        val e=seeded(); e.onVisual(visual(start)); assertEquals(1,e.anomalies)
        e.onGyro(start+33_000_000L,Vec3())
        e.onVisual(visual(start+33_000_000L,RigidPose(Vec3(1f,0f,0f)),frame=10))
        assertEquals(1,e.discontinuities)
        assertEquals(0f,e.output(start+33_000_000L).pose.p.length(),1e-6f)
    }
    @Test fun clockMappingUsesCameraDomainAndFailsClosed() {
        val m=CameraTimeMapper(true)
        assertEquals(start,m.map(5,start,start+20_000_000L))
        assertEquals(start-5,m.offsetNs)
        assertNull(m.map(5,start,start+21_000_000L))
        assertNull(CameraTimeMapper(false).map(start,start,start+1))
        assertNull(m.map(10,start+33_000_000L,start+34_000_000L))
        assertFalse(m.valid)
        assertNull(CameraTimeMapper(true).map(1,start,start-1))
    }
    @Test fun staleImuAndVisualInputsExposeDegradedQuality() {
        val e=seeded()
        assertEquals(TrackingQuality.DEGRADED,e.output(start+100_000_000L).quality)
        val noClock=TrackingEngine(TrackingConfig())
        noClock.onVisual(visual(start).copy(clockValid=false))
        assertEquals(TrackingQuality.UNAVAILABLE,noClock.output(start).quality)
    }
    @Test fun delayedArCoreCorrectionUsesMeasurementTime() {
        val w=Vec3(0f,2f,0f); val e=seeded(omega=w)
        for(i in 1..10) e.onGyro(start+i*10_000_000L,w)
        e.onVisual(visual(start+50_000_000L,RigidPose(q=rotationVector(w*0.05f))).copy(arrival=start+100_000_000L))
        close(rotationVector(w*0.1f),e.output(start+100_000_000L).pose.q)
        assertEquals(0,e.discontinuities)
    }
    @Test fun sequentialRotationsAreNonCommutative() {
        val f=OrientationFusion(); val x=Vec3(PI.toFloat(),0f,0f); val y=Vec3(0f,PI.toFloat(),0f)
        for(i in 0..100) f.add(start+i*5_000_000L,x)
        f.add(start+500_000_001L,y)
        for(i in 1..100) f.add(start+500_000_001L+i*5_000_000L,y)
        close(rotationVector(x*0.5f)*rotationVector(y*0.5f),f.at(start+1_000_000_001L)!!)
    }
    @Test fun gyroAccuracyLossInvalidatesVelocityUntilReanchored() {
        val e=seeded(); e.onGyro(start+10_000_000L,Vec3(0f,1f,0f),false)
        val out=e.output(start+10_000_000L)
        assertEquals(TrackingQuality.DEGRADED,out.quality); assertEquals(0,out.velocity.flags and 2)
        e.onGyro(start+20_000_000L,Vec3(),true)
        e.onVisual(visual(start+20_000_000L))
        assertEquals(TrackingQuality.FULL_6DOF,e.output(start+20_000_000L).quality)
    }
    @Test fun gradualRotationIsCappedAndRecenterCancelsPendingCorrection() {
        val rate=0.01f
        val e=seeded(TrackingConfig(gradualCorrection=true,translationRate=0f,rotationRate=rate,anchorRadiansPerSecond=0f))
        e.onVisual(visual(start+1,state=1))
        val shift=RigidPose(q=rotationVector(Vec3(0f,0.4f,0f)))
        e.onGyro(start+33_000_000L,Vec3()); e.onVisual(visual(start+33_000_000L,shift))
        val before=e.residualAngle
        e.onGyro(start+66_000_000L,Vec3()); e.onVisual(visual(start+66_000_000L,shift))
        assertEquals(before-rate*0.033f,e.residualAngle,1e-5f)
        assertTrue(e.recenter(start+66_000_000L))
        close(Quat(),e.output(start+66_000_000L).pose.q)
        assertEquals(0f,e.residualAngle,1e-6f)
    }
    @Test fun recoverySettlesAndPublicOrientationUpdatesBetweenVisualFrames() {
        val w=Vec3(0f,1f,0f); val e=seeded(omega=w)
        e.onVisual(visual(start+1,state=1))
        for(i in 1..200) {
            val t=start+i*5_000_000L; e.onGyro(t,w)
            if(i%6==0) e.onVisual(visual(t,RigidPose(q=rotationVector(w*(i*0.005f)))))
            val output=e.output(t)
            if(i>6) assertTrue(output.pose.q.angleTo(Quat())>0)
        }
        assertEquals(TrackingQuality.FULL_6DOF,e.output(start+1_000_000_000L).quality)
        assertEquals(1,e.discontinuities)
    }
}
