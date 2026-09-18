package org.astraeus.tracker

import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** Session updates and origin changes are serialized on the GL thread. */
class ArCoreTracker(private val session: Session,
    private val sample: (PoseSample, String) -> Unit,
    private val error: (String) -> Unit) : GLSurfaceView.Renderer {
    private val origin = OriginTransform()
    private val velocity = VelocityEstimator()
    val recenter = AtomicBoolean()
    private val sessionId = SecureRandom().nextLong().ushr(1)
    private var sequence = 0
    private var lastTimestamp = 0L
    private var failed = false
    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textures[0])
        session.setCameraTextureName(textures[0])
    }
    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0,0,width,height)
    }
    override fun onDrawFrame(gl: GL10?) {
        if (failed) return
        GLES20.glClearColor(0.04f,0.06f,0.1f,1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        try {
            val frame = session.update()
            if (frame.timestamp == 0L || frame.timestamp == lastTimestamp) return
            lastTimestamp = frame.timestamp
            val camera = frame.camera
            val state = when(camera.trackingState) {
                TrackingState.TRACKING -> 2
                TrackingState.PAUSED -> 1
                else -> 0
            }
            var pose = RigidPose()
            var v = Velocity()
            if (state == 2) {
                val p = camera.pose
                val raw = RigidPose(Vec3(p.tx(),p.ty(),p.tz()),Quat(p.qx(),p.qy(),p.qz(),p.qw()))
                if (recenter.getAndSet(false)) { origin.recenter(raw); velocity.reset() }
                pose = origin.apply(raw)
                v = velocity.sample(pose,frame.timestamp)
            } else {
                velocity.reset()
                // Never apply a pending recenter to a later, unexpected location.
                recenter.set(false)
            }
            val reason = camera.trackingFailureReason
            sample(PoseSample(sequence++,sessionId,frame.timestamp,origin.revision,state,pose,v,
                failureReasonCode(reason)), "${camera.trackingState} / $reason")
        } catch (e: Exception) {
            failed = true
            error("ARCore error: ${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
