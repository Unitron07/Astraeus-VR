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
        assertEquals(0f,result.angular.x,1e-6f)
        assertEquals(0f,result.angular.y,1e-6f)
        assertEquals(0f,result.angular.z,1e-6f)
    }
    @Test fun matchesIndependentBinaryFixture() {
        val fixture = javaClass.getResource("/golden_pose.hex")!!.readText().filterNot { it.isWhitespace() }
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        fixture[4] = 2 // v2 reuses the v1 layout and adds NONE at byte 92.
        val encoded = PosePacket.encode(PoseSample(42,7,1000000000,0,2,RigidPose(Vec3(1f,2f,-3f))))
        assertArrayEquals(fixture,encoded)
    }
    @Test fun encodesAllArCoreFailureReasonsWithStableIds() {
        val reasons = listOf(
            com.google.ar.core.TrackingFailureReason.NONE,
            com.google.ar.core.TrackingFailureReason.BAD_STATE,
            com.google.ar.core.TrackingFailureReason.INSUFFICIENT_LIGHT,
            com.google.ar.core.TrackingFailureReason.EXCESSIVE_MOTION,
            com.google.ar.core.TrackingFailureReason.INSUFFICIENT_FEATURES,
            com.google.ar.core.TrackingFailureReason.CAMERA_UNAVAILABLE)
        reasons.forEachIndexed { id, reason ->
            assertEquals(id, failureReasonCode(reason))
            val bytes = PosePacket.encode(PoseSample(42,7,1000000000,0,1,RigidPose(),
                trackingFailureReason=failureReasonCode(reason)))
            assertEquals(2,bytes[4].toInt())
            assertEquals(id,bytes[92].toInt())
            assertArrayEquals(byteArrayOf(0,0,0),bytes.copyOfRange(93,96))
        }
    }
    @Test fun angularVelocityUsesShortestArcAndOriginAxes() {
        val v = VelocityEstimator()
        v.sample(RigidPose(),1000000000)
        val q = Quat(0f,sqrt(0.5f),0f,sqrt(0.5f))
        val result = v.sample(RigidPose(q=q),1100000000)
        assertEquals((Math.PI/0.2).toFloat(),result.angular.y,1e-4f)
        assertEquals(0f,result.angular.x,1e-6f)
        assertEquals(0f,result.angular.z,1e-6f)
        assertEquals(3,result.flags)
    }
    @Test fun udpSenderDeliversEncodedPose() {
        java.net.DatagramSocket(0,java.net.InetAddress.getByName("127.0.0.1")).use { receiver ->
            receiver.soTimeout = 2000
            UdpTransport().use { sender ->
                sender.connect("127.0.0.1",receiver.localPort)
                val bytes = PosePacket.encode(PoseSample(42,7,1000000000,0,2,RigidPose(Vec3(1f,2f,-3f))))
                sender.offer(bytes)
                val packet = java.net.DatagramPacket(ByteArray(128),128)
                receiver.receive(packet)
                assertArrayEquals(bytes,packet.data.copyOf(packet.length))
            }
        }
    }
}
