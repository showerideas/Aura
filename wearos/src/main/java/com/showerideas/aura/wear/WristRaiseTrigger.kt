package com.showerideas.aura.wear

import android.app.Activity
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.core.content.getSystemService
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import timber.log.Timber
import kotlin.math.abs

/**
 * Wear OS wrist-raise gesture trigger for AURA exchange.
 *
 * Detects the characteristic "raise wrist to view" motion using the watch's
 * accelerometer and gyroscope:
 *
 * 1. The watch is in rest position (face down or at side) — baseline recorded.
 * 2. User rotates wrist upward: gyroscope detects angular velocity spike on
 *    the X axis (pitch), while accelerometer confirms the watch face rotates
 *    toward vertical.
 * 3. If the motion exceeds [RAISE_ANGULAR_VELOCITY_THRESHOLD] rad/s and the
 *    final Z-acceleration is above [VERTICAL_ACCEL_THRESHOLD] (gravity
 *    component confirming face-up orientation), a wrist-raise event fires.
 *
 * The trigger is debounced by [DEBOUNCE_MS] to prevent spurious re-fires.
 *
 * Integration
 * Register this trigger in the WearOS activity or service that owns the
 * exchange lifecycle. Connect its [wristRaiseEvents] flow to start
 * advertising mode automatically.
 */
class WristRaiseTrigger(private val context: Context) : SensorEventListener {

    companion object {
        /** Minimum gyroscope angular velocity (rad/s) on the pitch axis to qualify. */
        const val RAISE_ANGULAR_VELOCITY_THRESHOLD = 2.0f

        /** Minimum Z-acceleration (m/s²) for the face-up confirmation check. */
        const val VERTICAL_ACCEL_THRESHOLD = 7.0f   // ≈ 0.7g upward

        /** Minimum time between consecutive wrist-raise events (ms). */
        const val DEBOUNCE_MS = 2_000L

        /** Sampling rate for raise detection (μs). */
        private const val SENSOR_DELAY_US = SensorManager.SENSOR_DELAY_GAME
    }

    // State

    private val sensorManager   = context.getSystemService<SensorManager>()!!
    private val gyroscope       = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val accelerometer   = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val _events         = Channel<Unit>(capacity = Channel.CONFLATED)
    val wristRaiseEvents: Flow<Unit> = _events.receiveAsFlow()

    private var lastEventMs     = 0L
    private var lastAccelZ      = 0f

    // Lifecycle

    /**
     * Start listening for wrist-raise gestures.
     * Call this from [Activity.onResume] or service start.
     */
    fun start() {
        if (gyroscope != null) {
            sensorManager.registerListener(this, gyroscope, SENSOR_DELAY_US)
        } else {
            Timber.w("WristRaiseTrigger: no gyroscope sensor available")
        }
        if (accelerometer != null) {
            sensorManager.registerListener(this, accelerometer, SENSOR_DELAY_US)
        }
        Timber.d("WristRaiseTrigger: started")
    }

    /**
     * Stop listening.
     * Call from [Activity.onPause] or service stop.
     */
    fun stop() {
        sensorManager.unregisterListener(this)
        Timber.d("WristRaiseTrigger: stopped")
    }

    // SensorEventListener

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                lastAccelZ = event.values[2]   // Z = perpendicular to watch face
            }
            Sensor.TYPE_GYROSCOPE -> {
                val pitchVelocity = event.values[0]   // rotation around X axis
                if (abs(pitchVelocity) >= RAISE_ANGULAR_VELOCITY_THRESHOLD &&
                    lastAccelZ >= VERTICAL_ACCEL_THRESHOLD) {
                    debounceAndFire()
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        // Not used — accuracy changes do not affect raise detection.
    }

    // Private

    private fun debounceAndFire() {
        val now = System.currentTimeMillis()
        if (now - lastEventMs < DEBOUNCE_MS) return
        lastEventMs = now
        _events.trySend(Unit)
        Timber.i("WristRaiseTrigger: wrist-raise detected")
    }
}
