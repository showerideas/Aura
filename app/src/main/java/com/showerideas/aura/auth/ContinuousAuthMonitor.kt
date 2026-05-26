package com.showerideas.aura.auth

import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Binder
import android.os.IBinder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.LinkedList
import javax.inject.Inject

/**
 * Task 64 — Continuous Behavioral Authentication Monitor.
 *
 * Bound service that reads IMU data (accelerometer + gyroscope at 50 Hz) during
 * active AURA sessions. Computes 36-dimensional feature vectors over 5-second windows
 * with 50% overlap (new window every 2.5 seconds). A lightweight on-device MLP
 * (loaded from [BEHAVIORAL_MODEL_FILE]) classifies each window as OWNER or UNKNOWN.
 *
 * ## Session event flow
 * 1. [ExchangeFragment] binds this service when exchange starts.
 * 2. [start] arms the IMU listener. Sensor data feeds into [accelWindow] + [gyroWindow].
 * 3. Every [OVERLAP_INTERVAL_MS], [processWindow] fires, extracts features, runs inference.
 * 4. Confidence score flows via [confidence]. If below [CONFIDENCE_THRESHOLD] for
 *    [ANOMALY_WINDOW_COUNT] consecutive windows, [sessionEvent] emits [SessionEvent.BehavioralAnomalyDetected].
 * 5. [ExchangeFragment] observes [sessionEvent] and shows a silent re-auth prompt.
 * 6. [stop] / service unbind releases the IMU listener — no resource leak.
 *
 * ## Privacy
 * - IMU data never leaves the device.
 * - [ExchangeAuditLog] records [behavioralConfidence] score only (not raw IMU).
 * - Model stored in [filesDir/behavioral/model.tflite] — excluded from backup.
 *
 * ## Enterprise
 * - [require_continuous_auth] MDM key enables this service (default off).
 * - Even when off, the service lifecycle is harmless — [start] is a no-op without binding.
 *
 * See: [ncbi.nlm.nih.gov/pmc/articles/PMC11769222] — continual learning for gait
 * See: [abuhamad.cs.luc.edu/pub/Sensor-Based_Continuous_Authentication]
 */
@AndroidEntryPoint
class ContinuousAuthMonitor : Service(), SensorEventListener {

    // ── Constants ──────────────────────────────────────────────────────────

    companion object {
        /** Model file relative to [Context.getFilesDir]. */
        const val BEHAVIORAL_MODEL_FILE = "behavioral/model.tflite"

        /** Confidence threshold below which a window is flagged as anomalous. */
        const val CONFIDENCE_THRESHOLD = 0.6f

        /**
         * Number of consecutive sub-threshold windows before [SessionEvent.BehavioralAnomalyDetected]
         * is emitted. At 2.5 s per window: 10 × 2.5 s = 25 s of continuous anomaly before alert.
         */
        const val ANOMALY_WINDOW_COUNT = 10

        /** Window overlap interval in ms. Window every 2.5 s = 50% overlap of 5 s window. */
        const val OVERLAP_INTERVAL_MS = 2_500L

        fun createBindIntent(context: Context) = Intent(context, ContinuousAuthMonitor::class.java)
    }

    // ── Binder ─────────────────────────────────────────────────────────────

    inner class LocalBinder : Binder() {
        fun getService(): ContinuousAuthMonitor = this@ContinuousAuthMonitor
    }

    private val binder = LocalBinder()

    override fun onBind(intent: Intent?): IBinder = binder

    // ── State ──────────────────────────────────────────────────────────────

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private lateinit var sensorManager: SensorManager
    private var accelSensor: Sensor? = null
    private var gyroSensor: Sensor? = null

    /** Sliding window buffer for accelerometer (max [BehavioralFeatureExtractor.WINDOW_SIZE]). */
    private val accelWindow = LinkedList<BehavioralFeatureExtractor.ImuSample>()

    /** Sliding window buffer for gyroscope. */
    private val gyroWindow = LinkedList<BehavioralFeatureExtractor.ImuSample>()

    private val _confidence = MutableStateFlow(1.0f)
    /** Current behavioral authentication confidence [0.0–1.0]. Observe reactively. */
    val confidence: StateFlow<Float> = _confidence.asStateFlow()

    private val _sessionEvent = MutableStateFlow<SessionEvent>(SessionEvent.Idle)
    /** Session security events. Observe in [ExchangeFragment]. */
    val sessionEvent: StateFlow<SessionEvent> = _sessionEvent.asStateFlow()

    private var consecutiveAnomalyCount = 0
    private var isMonitoring = false

    // ── Lifecycle ──────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroSensor  = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        Timber.d("ContinuousAuth: service created — accel=${accelSensor != null} gyro=${gyroSensor != null}")
    }

    override fun onDestroy() {
        stop()
        serviceScope.cancel()
        super.onDestroy()
    }

    // ── Public API ─────────────────────────────────────────────────────────

    /**
     * Start IMU monitoring for the current session.
     * Safe to call multiple times — only arms sensors once.
     */
    fun start() {
        if (isMonitoring) return
        if (accelSensor == null || gyroSensor == null) {
            Timber.w("ContinuousAuth: IMU sensors unavailable — monitoring disabled")
            return
        }
        accelWindow.clear(); gyroWindow.clear()
        consecutiveAnomalyCount = 0
        _confidence.value = 1.0f
        _sessionEvent.value = SessionEvent.Idle

        sensorManager.registerListener(this, accelSensor, SensorManager.SENSOR_DELAY_GAME)
        sensorManager.registerListener(this, gyroSensor,  SensorManager.SENSOR_DELAY_GAME)
        isMonitoring = true
        scheduleWindowProcessing()
        Timber.i("ContinuousAuth: IMU monitoring started")
    }

    /**
     * Stop IMU monitoring and release sensor resources.
     * Call when the exchange session ends.
     */
    fun stop() {
        if (!isMonitoring) return
        sensorManager.unregisterListener(this)
        isMonitoring = false
        accelWindow.clear(); gyroWindow.clear()
        Timber.i("ContinuousAuth: IMU monitoring stopped")
    }

    /** Last confidence score for audit-log recording at session end. */
    fun lastConfidence(): Float = _confidence.value

    // ── SensorEventListener ────────────────────────────────────────────────

    override fun onSensorChanged(event: SensorEvent) {
        val sample = BehavioralFeatureExtractor.ImuSample(
            x = event.values[0],
            y = event.values[1],
            z = event.values[2],
        )
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                synchronized(accelWindow) {
                    accelWindow.addLast(sample)
                    if (accelWindow.size > BehavioralFeatureExtractor.WINDOW_SIZE) {
                        accelWindow.removeFirst()
                    }
                }
            }
            Sensor.TYPE_GYROSCOPE -> {
                synchronized(gyroWindow) {
                    gyroWindow.addLast(sample)
                    if (gyroWindow.size > BehavioralFeatureExtractor.WINDOW_SIZE) {
                        gyroWindow.removeFirst()
                    }
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) { /* not needed */ }

    // ── Window processing ──────────────────────────────────────────────────

    /**
     * Schedule periodic window processing at [OVERLAP_INTERVAL_MS].
     * Uses coroutine delay — no Handler/AlarmManager needed.
     */
    private fun scheduleWindowProcessing() {
        serviceScope.launch {
            while (isMonitoring) {
                kotlinx.coroutines.delay(OVERLAP_INTERVAL_MS)
                if (isMonitoring) processWindow()
            }
        }
    }

    /**
     * Extract features from the current window and run MLP inference.
     * Updates [confidence] and fires [SessionEvent.BehavioralAnomalyDetected] if needed.
     */
    private fun processWindow() {
        val accelSnapshot = synchronized(accelWindow) { accelWindow.toList() }
        val gyroSnapshot  = synchronized(gyroWindow)  { gyroWindow.toList()  }

        if (accelSnapshot.size < BehavioralFeatureExtractor.WINDOW_SIZE / 2 ||
            gyroSnapshot.size  < BehavioralFeatureExtractor.WINDOW_SIZE / 2) {
            Timber.d("ContinuousAuth: insufficient samples — skipping window")
            return
        }

        val features = BehavioralFeatureExtractor.extract(accelSnapshot, gyroSnapshot)
        val score = runInference(features)
        _confidence.value = score

        Timber.v("ContinuousAuth: window confidence=$score anomalyCount=$consecutiveAnomalyCount")

        if (score < CONFIDENCE_THRESHOLD) {
            consecutiveAnomalyCount++
            if (consecutiveAnomalyCount >= ANOMALY_WINDOW_COUNT) {
                Timber.w(
                    "ContinuousAuth: anomaly detected — $consecutiveAnomalyCount consecutive " +
                    "sub-threshold windows (score=$score)"
                )
                _sessionEvent.value = SessionEvent.BehavioralAnomalyDetected(score)
            }
        } else {
            consecutiveAnomalyCount = 0
            if (_sessionEvent.value is SessionEvent.BehavioralAnomalyDetected) {
                _sessionEvent.value = SessionEvent.Idle
            }
        }
    }

    /**
     * Run on-device MLP inference on the 36-dim feature vector.
     *
     * Production path: loads [BEHAVIORAL_MODEL_FILE] via TFLite and runs
     * [NNAPIDelegate] for hardware acceleration. Returns confidence in [0, 1].
     *
     * Stub implementation: returns 1.0 (fully trusted) until the behavioral
     * enrollment flow (Task 64 training) generates a device-specific model.
     * Replace with TFLite interpreter call once model file is confirmed present.
     *
     * @param features 36-dimensional feature vector from [BehavioralFeatureExtractor.extract].
     * @return Confidence score in [0.0, 1.0]. Higher = more likely to be device owner.
     */
    private fun runInference(features: FloatArray): Float {
        val modelFile = java.io.File(filesDir, BEHAVIORAL_MODEL_FILE)
        if (!modelFile.exists()) {
            // No model yet — enrollment required. Trust by default until enrolled.
            return 1.0f
        }
        // TODO: Load TFLite model + run inference
        // val interpreter = Interpreter(modelFile, Interpreter.Options().apply {
        //     addDelegate(NnApiDelegate())
        // })
        // val input = Array(1) { features }
        // val output = Array(1) { FloatArray(1) }
        // interpreter.run(input, output)
        // return output[0][0].coerceIn(0f, 1f)
        return 1.0f  // stub until enrollment flow generates model
    }

    // ── Session events ─────────────────────────────────────────────────────

    sealed class SessionEvent {
        object Idle : SessionEvent()
        data class BehavioralAnomalyDetected(val confidence: Float) : SessionEvent()
    }
}
