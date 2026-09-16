package org.astraeus.tracker

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class PoseSample(val sequence: Int, val session: Long, val timestamp: Long,
    val revision: Int, val state: Int, val pose: RigidPose, val velocity: Velocity = Velocity())

object PosePacket {
    fun encode(s: PoseSample): ByteArray {
        val b = ByteBuffer.allocate(96).order(ByteOrder.LITTLE_ENDIAN)
        b.put(byteArrayOf(65,83,84,82)).put(1).put(1).putShort(96)
        b.putInt(s.sequence).putInt(0).putLong(s.session).putLong(s.timestamp).putInt(s.revision)
        b.put(1).put(1).put(s.state.toByte()).put(s.velocity.flags.toByte())
        fun vector(v: Vec3) { b.putFloat(v.x).putFloat(v.y).putFloat(v.z) }
        vector(s.pose.p)
        with(s.pose.q) { b.putFloat(x).putFloat(y).putFloat(z).putFloat(w) }
        vector(s.velocity.linear); vector(s.velocity.angular); b.putInt(0)
        return b.array()
    }
}
