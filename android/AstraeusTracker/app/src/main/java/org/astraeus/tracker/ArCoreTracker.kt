package org.astraeus.tracker

import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** ARCore is a source; only TrackingEngine owns the public headset pose. */
class ArCoreTracker(private val session: Session, private val runtime: TrackingRuntime,
    private val error: (String)->Unit) : GLSurfaceView.Renderer {
    private var lastTimestamp=0L
    private var failed=false
    override fun onSurfaceCreated(gl: GL10?,config: EGLConfig?) {
        val textures=IntArray(1)
        GLES20.glGenTextures(1,textures,0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,textures[0])
        session.setCameraTextureName(textures[0])
    }
    override fun onSurfaceChanged(gl: GL10?,width: Int,height: Int) { GLES20.glViewport(0,0,width,height) }
    override fun onDrawFrame(gl: GL10?) {
        if(failed) return
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        try {
            val frame=session.update()
            if(frame.timestamp==0L || frame.timestamp==lastTimestamp) return
            lastTimestamp=frame.timestamp
            val camera=frame.camera
            val state=when(camera.trackingState) { TrackingState.TRACKING -> 2; TrackingState.PAUSED -> 1; else -> 0 }
            val p=if(state==2) camera.pose else com.google.ar.core.Pose.IDENTITY
            val sensor=if(state==2) frame.androidSensorPose else com.google.ar.core.Pose.IDENTITY
            runtime.visual(frame.timestamp,frame.androidCameraTimestamp,state,failureReasonCode(camera.trackingFailureReason),
                RigidPose(Vec3(p.tx(),p.ty(),p.tz()),Quat(p.qx(),p.qy(),p.qz(),p.qw())),
                Quat(sensor.qx(),sensor.qy(),sensor.qz(),sensor.qw()))
        } catch(e: Exception) { failed=true; error("ARCore error: ${e.javaClass.simpleName}: ${e.message}") }
    }
}
