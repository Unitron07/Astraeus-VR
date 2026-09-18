package org.astraeus.tracker

import android.content.Context
import android.os.Process
import android.os.SystemClock
import java.io.File
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class TrackingRuntime(private val context: Context, val config: TrackingConfig,
    private val transport: UdpTransport, realtimeCamera: Boolean, uncalibrated: Boolean,
    private val ui: (String)->Unit, private val error: (String)->Unit) : AutoCloseable {
    private val lock=Any()
    private val engine=TrackingEngine(config)
    private val mapper=CameraTimeMapper(realtimeCamera)
    private val session=SecureRandom().nextLong().ushr(1)
    private val gyroRate=MeasuredRate(); private val accelRate=MeasuredRate()
    private val arRate=MeasuredRate(); private val outputRate=MeasuredRate()
    private var gyro=ImuSample(); private var accel=ImuSample()
    private var log: DiagnosticLog?=null
    private var sequence=0
    private var lastOutput=0L; private var lastDiagnostics=0L; private var lastUi=0L
    private var lastCpu=Process.getElapsedCpuTime(); private var cpu=0f
    private var pendingDiscontinuity=false
    private var closed=false
    private var sourceError=""
    private val imu=AndroidImuSource(context,uncalibrated,{ s -> synchronized(lock) {
        if(!closed && engine.onGyro(s.timestamp,s.value-s.bias,s.accuracy>0)) {
            gyro=s; gyroRate.add(s.timestamp); log?.offer(FusionPacket.imu(s,true))
        }
    } },{ s -> synchronized(lock) {
        if(!closed && s.timestamp>accel.timestamp) {
            accel=s; accelRate.add(s.timestamp); log?.offer(FusionPacket.imu(s,false))
        }
    } })
    private val executor=Executors.newSingleThreadScheduledExecutor { r -> Thread(r,"astraeus-output") }
    init { executor.scheduleAtFixedRate({
        try { tick() } catch(e: Exception) { error("Tracking output failed: ${e.message}") }
    },0,1_000_000_000L/config.outputHz,TimeUnit.NANOSECONDS) }
    fun visual(frame: Long,cameraTimestamp: Long,state: Int,reason: Int,pose: RigidPose,sensor: Quat) = synchronized(lock) {
        val now=SystemClock.elapsedRealtimeNanos()
        val mapped=mapper.map(frame,cameraTimestamp,now)
        arRate.add(frame)
        val raw=RawArCorePose(frame,mapped ?: cameraTimestamp,now,state,reason,pose,sensor,mapped!=null)
        engine.onVisual(raw)
        log?.offer(FusionPacket.raw(raw))
        pendingDiscontinuity=pendingDiscontinuity || engine.discontinuity
    }
    fun recenter() = synchronized(lock) { engine.recenter(SystemClock.elapsedRealtimeNanos()) }
    fun sourceFailed(message: String) = synchronized(lock) {
        sourceError=message+"; Stop/Start to restart ARCore"
        engine.sourceUnavailable(255,SystemClock.elapsedRealtimeNanos())
    }
    fun setLogging(enabled: Boolean) {
        val old=synchronized(lock) {
            if(enabled && log==null) log=DiagnosticLog(File(context.getExternalFilesDir(null),"astraeus-$session-${System.currentTimeMillis()}.bin"))
            if(!enabled) log.also { log=null } else null
        }
        old?.close()
    }
    private fun tick() {
        var text: String?=null
        synchronized(lock) {
            if(closed) return
            val now=SystemClock.elapsedRealtimeNanos()
            if(now-lastOutput<800_000_000L/config.outputHz) return // No catch-up bursts after scheduling stalls.
            lastOutput=now; outputRate.add(now)
            val output=engine.output(now); val raw=engine.raw
            val sample=PoseSample(sequence++,session,now,engine.revision,raw?.state ?: 0,
                output.pose,output.velocity,raw?.reason ?: 255)
            val packet=FusionPacket.pose(sample,output.quality,gyro.timestamp,raw?.timestamp ?: 0)
            val batch=mutableListOf(packet); log?.offer(packet)
            if(now-lastDiagnostics>=100_000_000L) {
                val processCpu=Process.getElapsedCpuTime()
                cpu=if(lastDiagnostics==0L) 0f else (processCpu-lastCpu)*1e6f/(now-lastDiagnostics)
                lastCpu=processCpu; lastDiagnostics=now
                val arHz=if(raw!=null && now-raw.arrival<1_000_000_000L) arRate.hz else 0f
                val values=floatArrayOf(gyroRate.at(now),accelRate.at(now),arHz,outputRate.at(now),
                    engine.innovationPosition,engine.innovationAngle,engine.impliedSpeed,engine.residualPosition,
                    engine.residualAngle,engine.lastJump,engine.lastJumpAngle)
                val rt=Runtime.getRuntime()
                val d=FusionDiagnostics(raw,engine.rawInUserSpace(),engine.world,engine.user,gyro,accel,values,
                    engine.discontinuities,mapper.valid,pendingDiscontinuity,mapper.offsetNs,mapper.ageNs,
                    engine.gyro.anomalies,engine.anomalies,mapper.anomalies,log?.drops?.get() ?: 0,
                    (rt.totalMemory()-rt.freeMemory())/1048576f,cpu)
                val diagnostic=FusionPacket.diagnostics(sample,output.quality,d)
                batch.add(diagnostic); log?.offer(diagnostic); pendingDiscontinuity=false
            }
            transport.offerBatch(batch)
            if(now-lastUi>=250_000_000L) {
                lastUi=now
                text=String.format(Locale.US,
                    "ARCore: %s / %s | %.1f Hz\nIMU: gyro %.1f Hz, accel %.1f Hz | accuracy %d / %d\nGyro t=%d | Accel t=%d\nAstraeus: %s | output %.1f / requested %d Hz\nPosition: %.4f %.4f %.4f | Quaternion: %.3f %.3f %.3f %.3f\nWorld: %s | jumps %d, last %.3f m / %.2f deg\nRemaining %.3f m / %.2f deg | clock compatible=%s, camera age %.1f ms\n%s | queue drops %d\nHeap %.1f MB | CPU %.1f%% of one core | logging %s\n%s",
                    arCoreStateName(raw?.state ?: 0),failureReasonName(raw?.reason ?: 255),
                    if(raw!=null && now-raw.arrival<1_000_000_000L) arRate.hz else 0f,
                    gyroRate.at(now),accelRate.at(now),gyro.accuracy,accel.accuracy,
                    gyro.timestamp,accel.timestamp,output.quality,outputRate.at(now),config.outputHz,
                    output.pose.p.x,output.pose.p.y,output.pose.p.z,output.pose.q.x,output.pose.q.y,output.pose.q.z,output.pose.q.w,
                    if(engine.discontinuity) "RECOVERING" else if(config.gradualCorrection) "CORRECTION ENABLED" else "CONTINUITY HOLD",
                    engine.discontinuities,engine.lastJump,Math.toDegrees(engine.lastJumpAngle.toDouble()),
                    engine.residualPosition,Math.toDegrees(engine.residualAngle.toDouble()),mapper.valid,mapper.ageNs/1e6,
                    transport.status,transport.dropped.get(),(Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory())/1048576f,
                    cpu*100,log?.let { "ON drops=${it.drops.get()} ${it.error}" } ?: "OFF",imu.description+"\n"+sourceError)
            }
        }
        text?.let(ui)
    }
    override fun close() {
        synchronized(lock) { closed=true }
        executor.shutdownNow(); imu.close(); setLogging(false)
    }
}
