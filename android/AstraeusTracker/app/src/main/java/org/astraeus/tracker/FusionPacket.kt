package org.astraeus.tracker

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class FusionDiagnostics(val raw: RawArCorePose?, val rawUser: RigidPose,
    val world: RigidPose, val user: RigidPose, val gyro: ImuSample, val accel: ImuSample,
    val values: FloatArray, val count: Int, val clockValid: Boolean, val discontinuity: Boolean,
    val clockOffset: Long, val cameraAge: Long, val gyroAnomalies: Int, val anomalies: Int,
    val clockAnomalies: Int, val logDrops: Int, val heapMb: Float, val cpu: Float)

object FusionPacket {
    fun anchorDiagnostics(s: PoseSample,quality: TrackingQuality,d: FusionDiagnostics,e: TrackingEngine): ByteArray {
        val b=ByteBuffer.allocate(488).order(ByteOrder.LITTLE_ENDIAN)
        b.put(diagnostics(s,quality,d)); b.put(4,4); b.putShort(6,488)
        b.pose(e.raw?.anchor?.pose ?: RigidPose()); b.pose(e.cameraAnchor); b.pose(e.alignment)
        e.steps.forEach { b.putFloat(it) }
        b.putInt(e.raw?.anchor?.id ?: 0)
        b.put((e.raw?.anchor?.state ?: 0).toByte()).put(e.event.ordinal.toByte())
        b.put(e.stepFlags.toByte()).put(0)
        b.putInt(e.rawWorldUpdates).putInt(e.relativeDiscontinuities).putInt(e.reacquisitions).putInt(e.anchorLosses)
        b.putInt(e.eventMask)
        return b.array()
    }
    private fun header(size: Int,type: Int,s: PoseSample) = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).apply {
        put(byteArrayOf(65,83,84,82)).put(3).put(type.toByte()).putShort(size.toShort())
        putInt(s.sequence).putInt(0).putLong(s.session).putLong(s.timestamp).putInt(s.revision)
        put(1).put(1).put(s.state.toByte()).put(s.velocity.flags.toByte())
    }
    private fun ByteBuffer.vector(v: Vec3) { putFloat(v.x).putFloat(v.y).putFloat(v.z) }
    private fun ByteBuffer.pose(p: RigidPose) { vector(p.p); with(p.q) { putFloat(x).putFloat(y).putFloat(z).putFloat(w) } }
    fun pose(s: PoseSample,quality: TrackingQuality,gyroTimestamp: Long,visualTimestamp: Long): ByteArray {
        val b=header(112,1,s)
        b.pose(s.pose); b.vector(s.velocity.linear); b.vector(s.velocity.angular)
        b.put(s.trackingFailureReason.toByte()).put(quality.ordinal.toByte()).putShort(0)
        b.putLong(gyroTimestamp).putLong(visualTimestamp)
        return b.array()
    }
    fun diagnostics(s: PoseSample,quality: TrackingQuality,d: FusionDiagnostics): ByteArray {
        val b=header(352,2,s)
        b.putLong(d.raw?.frameTimestamp ?: 0).putLong(d.raw?.timestamp ?: 0)
        b.putLong(d.gyro.timestamp).putLong(d.accel.timestamp)
        b.pose(d.raw?.camera ?: RigidPose()); b.pose(d.rawUser); b.pose(d.world); b.pose(d.user)
        b.vector(d.gyro.value); b.vector(d.gyro.bias); b.vector(d.accel.value)
        require(d.values.size==11)
        d.values.forEach { b.putFloat(it) }
        b.putInt(d.count).putInt(d.gyro.accuracy).putInt(d.accel.accuracy)
        b.put(if(d.clockValid) 1 else 0).put(if(d.gyro.uncalibrated) 1 else 0)
        b.put(if(d.discontinuity) 1 else 0).put(quality.ordinal.toByte())
        b.putLong(d.clockOffset).putLong(d.cameraAge)
        b.putInt(d.gyroAnomalies).putInt(d.anomalies).putInt(d.clockAnomalies).putInt(d.logDrops)
        b.putFloat(d.heapMb).putFloat(d.cpu); b.pose(s.pose)
        b.put(s.trackingFailureReason.toByte()).put(quality.ordinal.toByte()).putShort(0)
        return b.array()
    }
    fun raw(s: RawArCorePose): ByteArray = ByteBuffer.allocate(80).order(ByteOrder.LITTLE_ENDIAN).apply {
        put(byteArrayOf(65,82,65,87)).put(1).put(1).putShort(80)
        putLong(s.frameTimestamp).putLong(s.timestamp).putLong(s.arrival)
        put(s.state.toByte()).put(s.reason.toByte()).put(if(s.clockValid) 1 else 0).put(0)
        pose(s.camera)
        with(s.sensorOrientation) { putFloat(x).putFloat(y).putFloat(z).putFloat(w) }
    }.array()
    fun imu(s: ImuSample,gyro: Boolean): ByteArray = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN).apply {
        put(byteArrayOf(65,73,77,85)).put(1).put(if(gyro) 1 else 2).putShort(48)
        putLong(s.timestamp); vector(s.value); vector(s.bias); putInt(s.accuracy); putInt(if(s.uncalibrated) 1 else 0)
    }.array()
}
