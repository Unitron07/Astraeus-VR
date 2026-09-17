package org.astraeus.tracker

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import android.view.WindowInsets
import android.widget.*
import com.google.ar.core.*
import java.util.EnumSet
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var root: LinearLayout
    private lateinit var host: FrameLayout
    private lateinit var status: TextView
    private lateinit var ip: EditText
    private lateinit var port: EditText
    private lateinit var transport: UdpTransport
    private var session: Session? = null
    private var surface: GLSurfaceView? = null
    private var tracker: ArCoreTracker? = null
    @Volatile private var logging = false
    private var installRequested = false
    private var lastUi = 0L
    private var frames = 0
    private var previousSent = 0L
    private var epoch = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        transport = UdpTransport()
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24,24,24,16) }
        val scroll = ScrollView(this).apply { addView(root) }
        host = FrameLayout(this).apply { addView(scroll) }
        setContentView(host)
        // Keep controls outside system bars with Android 15 edge-to-edge enforcement.
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(bars.left,bars.top,bars.right,bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(insets.systemWindowInsetLeft,insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight,insets.systemWindowInsetBottom)
            }
            insets
        }
        val prefs = getPreferences(MODE_PRIVATE)
        ip = EditText(this).apply { hint = "PC IPv4 address"; setText(prefs.getString("ip", "")); inputType = 3 }
        port = EditText(this).apply { hint = "UDP port"; setText(prefs.getString("port","4242")); inputType = 2 }
        root.addView(ip); root.addView(port)
        val buttons = LinearLayout(this)
        root.addView(buttons)
        fun button(label: String, action: () -> Unit) {
            buttons.addView(Button(this).apply { text = label; setOnClickListener { action() } })
        }
        button("Connect to PC") {
            try {
                transport.connect(ip.text.toString().trim(),port.text.toString().toInt())
                prefs.edit().putString("ip",ip.text.toString()).putString("port",port.text.toString()).apply()
                status.text = transport.status
            } catch(e: Exception) { status.text = "Connection error: ${e.message}" }
        }
        button("Start Tracking") { startTracking() }
        button("Stop Tracking") { stopTracking(); status.text = "Stopped; PC will mark stream stale" }
        button("Recenter / Set Origin") { tracker?.recenter?.set(true) }
        root.addView(CheckBox(this).apply {
            text = "Enable diagnostic Logcat pose logging"
            setOnCheckedChangeListener { _, checked -> logging = checked }
        })
        status = TextView(this).apply { text = "Stopped. Enter PC address, connect, then start."; textSize = 16f }
        root.addView(status)
    }
    private fun startTracking() {
        if (session != null) return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA),1); return
        }
        try {
            if (ArCoreApk.getInstance().requestInstall(this,!installRequested) == ArCoreApk.InstallStatus.INSTALL_REQUESTED) {
                installRequested = true; status.text = "Install Google Play Services for AR, then press Start."; return
            }
            val s = Session(this)
            session = s
            val choices = s.getSupportedCameraConfigs(CameraConfigFilter(s)
                .setTargetFps(EnumSet.of(CameraConfig.TargetFps.TARGET_FPS_60)))
            choices.minByOrNull { it.imageSize.width * it.imageSize.height }?.let { s.cameraConfig = it }
            s.configure(Config(s).apply {
                updateMode = Config.UpdateMode.BLOCKING
                planeFindingMode = Config.PlaneFindingMode.DISABLED
                lightEstimationMode = Config.LightEstimationMode.DISABLED
                focusMode = Config.FocusMode.AUTO
            })
            s.resume()
            val currentEpoch = ++epoch
            frames = 0; lastUi = SystemClock.elapsedRealtime(); previousSent = transport.sent.get()
            val renderer = ArCoreTracker(s, { sample, arStatus ->
                transport.offer(PosePacket.encode(sample))
                if (logging) Log.i("AstraeusPose", sample.toString())
                frames++
                val now = SystemClock.elapsedRealtime()
                if (now-lastUi >= 250) {
                    val dt = (now-lastUi)/1000f
                    val sent = transport.sent.get()
                    val text = String.format(Locale.US,
                        "ARCore: %s\nPosition XYZ (m): %.4f  %.4f  %.4f\nQuaternion XYZW: %.4f  %.4f  %.4f  %.4f\nUpdates: %.1f Hz | UDP: %.1f packets/s\n%s\nSequence: %s | Origin: %d | Queue drops: %d\nTimestamp: %d ns | Velocity flags: %d",
                        arStatus,sample.pose.p.x,sample.pose.p.y,sample.pose.p.z,
                        sample.pose.q.x,sample.pose.q.y,sample.pose.q.z,sample.pose.q.w,
                        frames/dt,(sent-previousSent)/dt,transport.status,
                        Integer.toUnsignedString(sample.sequence),sample.revision,transport.dropped.get(),sample.timestamp,sample.velocity.flags)
                    runOnUiThread { if(epoch == currentEpoch) status.text = text }
                    lastUi = now; frames = 0; previousSent = sent
                }
            }, { message -> runOnUiThread { if(epoch == currentEpoch) { stopTracking(); status.text = message } } })
            tracker = renderer
            surface = GLSurfaceView(this).apply {
                setEGLContextClientVersion(2)
                setRenderer(renderer)
                // Keep the camera GL surface attached even when diagnostics scroll.
                host.addView(this,0,FrameLayout.LayoutParams(1,1))
                onResume()
            }
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } catch(e: Exception) { stopTracking(); status.text = "ARCore setup failed: ${e.javaClass.simpleName}: ${e.message}" }
    }
    private fun stopTracking() {
        ++epoch
        surface?.onPause()
        surface?.let { host.removeView(it) }
        surface = null; tracker = null
        session?.pause(); session?.close(); session = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    override fun onPause() { stopTracking(); status.text = "Paused. Press Start to begin a new session; Reset PC stream."; super.onPause() }
    override fun onDestroy() { transport.close(); super.onDestroy() }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode,permissions,grantResults)
        if (requestCode == 1 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startTracking()
        else status.text = "Camera permission required. Enable it in Android app settings."
    }
}
