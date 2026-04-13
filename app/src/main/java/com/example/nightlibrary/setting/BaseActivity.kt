package com.example.nightlibrary.setting

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.example.nightlibrary.preferences.SecurityPreferenceManager
import kotlin.math.sqrt

abstract class BaseActivity : AppCompatActivity(), SensorEventListener {

    private var sensorManager: SensorManager? = null

    private var acceleration = 0f
    private var currentAcceleration = 0f
    private var lastAcceleration = 0f

    private var initialized = false
    private var lastShakeTime = 0L

    private val SHAKE_THRESHOLD = 12f
    private val SHAKE_COOLDOWN = 1000L

    override fun onResume() {
        super.onResume()

        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        val prefs = SecurityPreferenceManager(this)

        if (prefs.isEmergencyLockEnabled) {
            sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager

            sensorManager?.registerListener(
                this,
                sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER),
                SensorManager.SENSOR_DELAY_UI
            )

            acceleration = 0f
            initialized = false
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return

        val now = System.currentTimeMillis()

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        val magnitude = sqrt((x * x + y * y + z * z).toDouble()).toFloat()

        if (!initialized) {
            currentAcceleration = magnitude
            lastAcceleration = magnitude
            initialized = true
            return
        }

        lastAcceleration = currentAcceleration
        currentAcceleration = magnitude

        val delta = currentAcceleration - lastAcceleration
        acceleration = acceleration * 0.9f + delta

        if (acceleration > SHAKE_THRESHOLD) {
            if (now - lastShakeTime > SHAKE_COOLDOWN) {
                lastShakeTime = now
                performEmergencyExit()
            }
        }
    }

    private fun performEmergencyExit() {
        VaultSessionManager.lock()
        finishAffinity()
        android.os.Process.killProcess(android.os.Process.myPid())
        System.exit(0)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}