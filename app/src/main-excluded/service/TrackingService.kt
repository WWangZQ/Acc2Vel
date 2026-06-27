package com.av.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.av.ui.MainActivity
import com.av.R
import com.av.data.GpsFix
import com.av.data.ImuSample
import com.av.data.NavState
import com.av.data.SensorInfo
import com.av.data.StationEvent
import com.av.data.TrackingStatus
import com.av.ekf.ExtendedKalmanFilter
import com.av.AvApplication
import com.av.sensor.SensorCollector
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Foreground service that owns the IMU → velocity pipeline.
 * Keeps processing even when the screen is off.
 */
class TrackingService : LifecycleService() {

    private lateinit var sensorCollector: SensorCollector

    private val _status = MutableStateFlow(TrackingStatus.IDLE)
    val status: StateFlow<TrackingStatus> = _status.asStateFlow()

    private val _navState = MutableStateFlow(NavState.ZERO)
    val navState: StateFlow<NavState> = _navState.asStateFlow()

    private val _latestImu = MutableStateFlow<ImuSample?>(null)
    val latestImu: StateFlow<ImuSample?> = _latestImu.asStateFlow()

    private val _latestGps = MutableStateFlow<GpsFix?>(null)
    val latestGps: StateFlow<GpsFix?> = _latestGps.asStateFlow()

    private val _imuCount = MutableStateFlow(0L)
    val imuCount: StateFlow<Long> = _imuCount.asStateFlow()

    private var collectionJob: Job? = null
    private var gpsJob: Job? = null

    // Full EKF pipeline
    private val ekf = ExtendedKalmanFilter()
    private var isCalibrated = false
    private var calibrationSamples = mutableListOf<ImuSample>()
    private val CALIBRATION_COUNT = 400  // ~2 seconds at 200Hz

    private val _stationEvents = MutableSharedFlow<StationEvent>(extraBufferCapacity = 8)
    val stationEvents: SharedFlow<StationEvent> = _stationEvents.asSharedFlow()

    private val speedHistory = ArrayDeque<Float>(300)  // ~3s at 100Hz display rate
    val speedHistoryList: List<Float> get() = speedHistory.toList()

    companion object {
        private const val TAG = "TrackingService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "tracking_channel"

        fun start(context: Context) {
            val intent = Intent(context, TrackingService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, TrackingService::class.java)
            context.stopService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        sensorCollector = (application as AvApplication).sensorCollector
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startForeground(
            NOTIFICATION_ID,
            buildNotification("Collecting sensor data..."),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        )
        startCollecting()
        return START_STICKY
    }

    fun getSensorInfo(): SensorInfo = sensorCollector.getSensorInfo()

    fun startCollecting() {
        if (collectionJob?.isActive == true) return
        _status.value = TrackingStatus.CALIBRATING
        isCalibrated = false
        calibrationSamples.clear()
        ekf.reset()
        Log.i(TAG, "Starting IMU collection — calibrating...")

        collectionJob = lifecycleScope.launch {
            var count = 0L
            sensorCollector.imuFlow().collect { sample ->
                _latestImu.value = sample
                count++
                _imuCount.value = count

                if (!isCalibrated) {
                    // Calibration phase: collect stationary samples to estimate biases
                    calibrationSamples.add(sample)
                    if (calibrationSamples.size >= CALIBRATION_COUNT) {
                        finishCalibration()
                    }
                } else {
                    // Tracking phase: run full EKF pipeline
                    runPipeline(sample)
                }
            }
        }

        gpsJob = lifecycleScope.launch {
            sensorCollector.gpsFlow().collect { fix ->
                _latestGps.value = fix
                if (isCalibrated) {
                    ekf.updateGps(fix)
                }
            }
        }
    }

    private fun finishCalibration() {
        // Compute mean accel and gyro during stationary period
        val accelMean = floatArrayOf(0f, 0f, 0f)
        val gyroMean = floatArrayOf(0f, 0f, 0f)
        for (s in calibrationSamples) {
            accelMean[0] += s.accel[0]; accelMean[1] += s.accel[1]; accelMean[2] += s.accel[2]
            gyroMean[0] += s.gyro[0]; gyroMean[1] += s.gyro[1]; gyroMean[2] += s.gyro[2]
        }
        val n = calibrationSamples.size.toFloat()
        accelMean[0] /= n; accelMean[1] /= n; accelMean[2] /= n
        gyroMean[0] /= n; gyroMean[1] /= n; gyroMean[2] /= n

        ekf.initialize(accelMean, gyroMean)
        isCalibrated = true
        _status.value = TrackingStatus.TRACKING
        calibrationSamples.clear()
        Log.i(TAG, "Calibration complete. Tracking active.")
    }

    private fun runPipeline(sample: ImuSample) {
        // EKF predict step
        ekf.predict(sample)

        // ZUPT detection
        val isZupt = ekf.getZuptDetector().update(sample)
        if (isZupt) {
            ekf.updateZupt()
        }

        // Station detection
        val state = ekf.getState()
        // StationDetector wiring would go here in Phase 6

        // Update nav state
        _navState.value = state

        // Record speed history (decimated to ~10Hz for chart)
        if (_imuCount.value % 20 == 0L) {
            speedHistory.addLast(state.speedKmh)
            if (speedHistory.size > 300) speedHistory.removeFirst()
        }

        // Update notification
        if (_imuCount.value % 100 == 0L) {
            updateNotification(
                "%.1f km/h %s".format(
                    state.speedKmh,
                    if (state.isZupt) "· stopped" else ""
                )
            )
        }
    }

    fun stopCollecting() {
        collectionJob?.cancel()
        gpsJob?.cancel()
        collectionJob = null
        gpsJob = null
        _status.value = TrackingStatus.IDLE
        isCalibrated = false
        Log.i(TAG, "Stopped collection. Total samples: ${_imuCount.value}")
    }

    override fun onDestroy() {
        stopCollecting()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return LocalBinder()
    }

    inner class LocalBinder : Binder() {
        fun getService(): TrackingService = this@TrackingService
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Velocity Tracking",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows when velocity tracking is active"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("A→V Velocity Tracker")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}
