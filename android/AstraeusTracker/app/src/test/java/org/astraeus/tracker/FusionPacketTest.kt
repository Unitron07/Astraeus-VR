package org.astraeus.tracker

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.DataInputStream

class FusionPacketTest {
    private fun fixture(name: String)=javaClass.getResource("/$name")!!.readText().split(Regex("\\s+"))
        .filter { it.isNotBlank() }.map { it.toInt(16).toByte() }.toByteArray()
    private val sample=PoseSample(42,7,1000000000,0,2,RigidPose(Vec3(1f,2f,-3f)),Velocity(angular=Vec3(0f,0.5f,0f),flags=2))
    @Test fun publicPoseMatchesIndependentFixture() {
        assertArrayEquals(fixture("golden_fused.hex"),FusionPacket.pose(sample,TrackingQuality.RECOVERING,999000000,970000000))
    }
    @Test fun diagnosticsMatchIndependentFixture() {
        val raw=RawArCorePose(999999990,970000000,1000000000,2,0,RigidPose(Vec3(2f,2f,-3f)),Quat(),true)
        val d=FusionDiagnostics(raw,raw.camera,RigidPose(Vec3(-1f,0f,0f)),RigidPose(),
            ImuSample(999000000,Vec3(0f,0.5f,0f),accuracy=3),ImuSample(998000000,Vec3(0f,9.81f,0f),accuracy=3),
            floatArrayOf(200f,200f,30f,120f,1f,0.2f,30f,1f,0.2f,1f,0.2f),1,true,true,10,30000000,0,0,0,0,32f,0.1f)
        assertArrayEquals(fixture("golden_diagnostics.hex"),FusionPacket.diagnostics(sample,TrackingQuality.RECOVERING,d))
    }
    @Test fun binaryLogDrainsAndPreservesRecords() {
        val file=File.createTempFile("astraeus-test", ".bin")
        try {
            DiagnosticLog(file).use { log -> log.offer(fixture("golden_fused.hex")); log.offer(FusionPacket.imu(ImuSample(123),true)) }
            DataInputStream(file.inputStream()).use { input ->
                assertEquals(112,input.readInt()); val packet=ByteArray(112); input.readFully(packet)
                assertArrayEquals(fixture("golden_fused.hex"),packet)
                assertEquals(48,input.readInt()); val imu=ByteArray(48); input.readFully(imu)
                assertEquals("AIMU",String(imu.copyOfRange(0,4))); assertEquals(-1,input.read())
            }
        } finally { file.delete() }
    }
}
