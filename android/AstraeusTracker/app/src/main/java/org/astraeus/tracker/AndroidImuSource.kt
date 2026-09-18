package org.astraeus.tracker

import android.content.Context
import android.hardware.*
import android.os.Handler
import android.os.HandlerThread

data class ImuSample(val timestamp: Long=0, val value: Vec3=Vec3(), val bias: Vec3=Vec3(),
    val accuracy: Int=-1, val uncalibrated: Boolean=false)

class AndroidImuSource(context: Context, preferUncalibrated: Boolean,
    private val onGyro: (ImuSample)->Unit, private val onAccel: (ImuSample)->Unit) : SensorEventListener, AutoCloseable {
    private val manager=context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val thread=HandlerThread("astraeus-imu").apply { start() }
    private val gyro=if(preferUncalibrated)
        manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE_UNCALIBRATED) ?: manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        else manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) ?: manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE_UNCALIBRATED)
    private val accel=manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    val description: String
    init {
        val handler=Handler(thread.looper)
        val gyroOk=gyro?.let { manager.registerListener(this,it,SensorManager.SENSOR_DELAY_FASTEST,0,handler) } ?: false
        val accelOk=accel?.let { manager.registerListener(this,it,SensorManager.SENSOR_DELAY_FASTEST,0,handler) } ?: false
        description="Gyro ${gyro?.name ?: "missing"}, minDelay=${gyro?.minDelay} us, registered=$gyroOk; accel registered=$accelOk"
    }
    override fun onSensorChanged(event: SensorEvent) {
        val v=Vec3(event.values[0],event.values[1],event.values[2])
        val uncalibrated=event.sensor.type==Sensor.TYPE_GYROSCOPE_UNCALIBRATED
        val bias=if(uncalibrated && event.values.size>=6) Vec3(event.values[3],event.values[4],event.values[5]) else Vec3()
        val s=ImuSample(event.timestamp,v,bias,event.accuracy,uncalibrated)
        if(event.sensor.type==Sensor.TYPE_ACCELEROMETER) onAccel(s) else onGyro(s)
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    override fun close() { manager.unregisterListener(this); thread.quitSafely(); thread.join(1000) }
}
