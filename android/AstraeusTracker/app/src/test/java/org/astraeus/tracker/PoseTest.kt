package org.astraeus.tracker

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sqrt

class PoseTest {
    @Test fun recenterRotatesTranslationAndOrientation() {
        val origin = OriginTransform()
        val q = Quat(0f,sqrt(0.5f),0f,sqrt(0.5f))
        origin.recenter(RigidPose(Vec3(3f,2f,1f),q))
        val result = origin.apply(RigidPose(Vec3(2f,2f,1f),q))
        assertEquals(-1f,result.p.z,1e-5f)
        assertEquals(0f,result.p.x,1e-5f)
        assertEquals(1f,result.q.w,1e-5f)
        assertEquals(1,origin.revision)
    }
    @Test fun velocityInvalidatesAcrossDiscontinuities() {
        val v = VelocityEstimator()
        assertEquals(0,v.sample(RigidPose(),1000000000).flags)
        val result = v.sample(RigidPose(Vec3(0.1f,0f,0f)),1100000000)
        assertEquals(1f,result.linear.x,1e-5f)
        v.reset()
        assertEquals(0,v.sample(RigidPose(),1200000000).flags)
        assertEquals(0,v.sample(RigidPose(),2000000000).flags)
    }
    @Test fun quaternionSignDoesNotCreateAngularVelocity() {
        val v = VelocityEstimator()
        v.sample(RigidPose(),1000000000)
        val result = v.sample(RigidPose(q=Quat(0f,0f,0f,-1f)),1010000000)
        assertEquals(Vec3(),result.angular)
    }
    @Test fun matchesIndependentBinaryFixture() {
        val fixture = javaClass.getResource("/golden_pose.hex")!!.readText().filterNot { it.isWhitespace() }
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val encoded = PosePacket.encode(PoseSample(42,7,1000000000,0,2,RigidPose(Vec3(1f,2f,-3f))))
        assertArrayEquals(fixture,encoded)
    }
}
