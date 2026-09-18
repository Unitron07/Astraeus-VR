package org.astraeus.tracker

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraCharacteristics
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Build
import android.view.WindowManager
import android.view.WindowInsets
import android.widget.*
import com.google.ar.core.*
import java.util.EnumSet

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
    private var runtime: TrackingRuntime? = null
    private lateinit var rate: EditText
    private lateinit var maxSpeed: EditText
    private lateinit var jumpMeters: EditText
    private lateinit var jumpDegrees: EditText
    private lateinit var anchorRate: EditText
    private lateinit var translationRate: EditText
    private lateinit var rotationRate: EditText
    private lateinit var gradual: CheckBox
    private lateinit var uncalibrated: CheckBox
    @Volatile private var logging = false
    private var installRequested = false
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
        button("Recenter / Set Origin") { runtime?.recenter() }
        root.addView(TextView(this).apply { text="Configuration applies on next Start. Rates are requested, not guaranteed." })
        fun field(label: String, value: String): EditText {
            val row=LinearLayout(this)
            row.addView(TextView(this).apply { text=label },LinearLayout.LayoutParams(360,64))
            val edit=EditText(this).apply { setText(value); inputType=8194 }
            row.addView(edit,LinearLayout.LayoutParams(180,72)); root.addView(row)
            return edit
        }
        rate=field("Output Hz (120 or 240)","120")
        maxSpeed=field("Jump speed threshold (m/s)","8")
        jumpMeters=field("Minimum jump distance (m)","0.15")
        jumpDegrees=field("Gyro/AR angle threshold (deg)","35")
        anchorRate=field("Normal orientation correction (deg/s)","0.5")
        translationRate=field("Gradual translation cap (m/s)","0.01")
        rotationRate=field("Gradual rotation cap (deg/s)","0.25")
        gradual=CheckBox(this).apply { text="Enable gradual world correction (experimental)" }; root.addView(gradual)
        uncalibrated=CheckBox(this).apply { text="Prefer uncalibrated gyro, subtract reported bias" }; root.addView(uncalibrated)
        root.addView(CheckBox(this).apply {
            text = "Enable buffered binary pose + raw IMU logging"
            setOnCheckedChangeListener { _, checked ->
                logging = checked
                try { runtime?.setLogging(checked) } catch(e: Exception) { status.text="Log error: ${e.message}" }
            }
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
            val characteristics=(getSystemService(CAMERA_SERVICE) as CameraManager).getCameraCharacteristics(s.cameraConfig.cameraId)
            val realtime=characteristics.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)==CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
            fun radians(edit: EditText)=Math.toRadians(edit.text.toString().toDouble()).toFloat()
            val config=TrackingConfig(rate.text.toString().toInt(),jumpMeters.text.toString().toFloat(),
                maxSpeed.text.toString().toFloat(),radians(jumpDegrees),radians(anchorRate),gradual.isChecked,
                translationRate.text.toString().toFloat(),radians(rotationRate))
            val onError: (String)->Unit = { message -> runOnUiThread {
                if(epoch==currentEpoch) { stopTracking(); status.text=message }
            } }
            val pipeline=TrackingRuntime(this,config,transport,realtime,uncalibrated.isChecked,
                { text -> runOnUiThread { if(epoch==currentEpoch) status.text=text } },onError)
            runtime=pipeline; pipeline.setLogging(logging)
            val renderer=ArCoreTracker(s,pipeline,onError)
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
        runtime?.close(); runtime=null
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
