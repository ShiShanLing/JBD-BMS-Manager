package com.bms.jbdmanager.trip

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.bms.jbdmanager.MainActivity
import com.bms.jbdmanager.R
import com.bms.jbdmanager.model.TripState
import com.bms.jbdmanager.model.isStationaryCharging
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class TripTrackingService : Service(), LocationListener {
    private lateinit var locationManager: LocationManager
    private var lastAcceptedLocation: Location? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var notificationJob: Job? = null
    private var locationUpdatesRequested = false
    private var foregroundStarted = false
    private var lastNotificationUpdateAtMillis = 0L
    private val speedSamples = ArrayDeque<Pair<Long, Double>>()
    private var average5SecondSpeedKmh = 0.0

    override fun onCreate() {
        super.onCreate()
        TripTracker.initialize(this)
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopTracking("已从通知结束行程", suppressAutoRestart = true)
            return START_NOT_STICKY
        }
        if (!TripTracker.state.value.isTracking) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification(TripTracker.state.value))
        foregroundStarted = true
        observeTripUpdates()
        requestLocationUpdates()
        return START_STICKY
    }

    private fun requestLocationUpdates() {
        if (locationUpdatesRequested) return
        val hasFineLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!hasFineLocation) {
            TripTracker.updateGpsStatus("需要精确位置权限")
            stopSelf()
            return
        }
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            TripTracker.updateGpsStatus("请开启手机定位服务")
        }
        runCatching {
            if (locationUpdatesRequested) locationManager.removeUpdates(this)
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                LOCATION_INTERVAL_MS,
                0f,
                this
            )
            locationUpdatesRequested = true
        }.onFailure {
            TripTracker.updateGpsStatus("GPS 启动失败：${it.message.orEmpty()}")
        }
    }

    override fun onLocationChanged(location: Location) {
        if (!location.hasAccuracy() || location.accuracy > MAX_ACCEPTED_ACCURACY_METERS) {
            TripTracker.updateGpsStatus("GPS 信号较弱，等待更准确定位")
            return
        }

        val previous = lastAcceptedLocation
        lastAcceptedLocation = location
        if (previous == null) {
            val speed = location.plausibleSpeedOrZero()
            updateAverageSpeed(location.time, speed * 3.6)
            TripTracker.updateLocation(
                0.0, speed, location.accuracy, location.time, 0.0, location.speedAccuracyOrNull()
            )
            return
        }

        val elapsedSeconds = (location.elapsedRealtimeNanos - previous.elapsedRealtimeNanos) / 1_000_000_000.0
        if (elapsedSeconds <= 0.0 || elapsedSeconds > MAX_LOCATION_GAP_SECONDS) {
            val speed = location.plausibleSpeedOrZero()
            updateAverageSpeed(location.time, speed * 3.6)
            TripTracker.updateLocation(
                0.0, speed, location.accuracy, location.time, 0.0, location.speedAccuracyOrNull()
            )
            return
        }

        val segmentMeters = previous.distanceTo(location).toDouble()
        val segmentSpeed = segmentMeters / elapsedSeconds
        val measuredSpeed = if (location.hasSpeed()) location.speed.toDouble() else segmentSpeed
        val plausible = segmentSpeed <= MAX_PLAUSIBLE_SPEED_MPS &&
            measuredSpeed <= MAX_PLAUSIBLE_SPEED_MPS
        val acceptedSpeed = if (plausible) measuredSpeed.coerceAtLeast(0.0) else 0.0
        val moving = measuredSpeed >= MIN_MOVING_SPEED_MPS && segmentMeters >= MIN_SEGMENT_METERS
        val acceptedDistance = if (plausible && moving) segmentMeters else 0.0
        updateAverageSpeed(location.time, acceptedSpeed * 3.6)

        TripTracker.updateLocation(
            addedDistanceMeters = acceptedDistance,
            speedMetersPerSecond = acceptedSpeed.toFloat(),
            accuracyMeters = location.accuracy,
            timestampMillis = location.time,
            elapsedSeconds = elapsedSeconds,
            speedAccuracyMetersPerSecond = location.speedAccuracyOrNull()
        )
    }

    override fun onProviderDisabled(provider: String) {
        if (provider == LocationManager.GPS_PROVIDER) TripTracker.updateGpsStatus("手机定位服务已关闭")
    }

    override fun onProviderEnabled(provider: String) {
        if (provider == LocationManager.GPS_PROVIDER) TripTracker.updateGpsStatus("正在等待 GPS 定位")
    }

    @Deprecated("Deprecated in Android")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

    override fun onDestroy() {
        runCatching { locationManager.removeUpdates(this) }
        locationUpdatesRequested = false
        serviceScope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun stopTracking(message: String, suppressAutoRestart: Boolean = false) {
        runCatching { locationManager.removeUpdates(this) }
        locationUpdatesRequested = false
        if (suppressAutoRestart) TripTracker.suppressUntilNextConnection(message) else TripTracker.finish(message)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun observeTripUpdates() {
        if (notificationJob != null) return
        notificationJob = serviceScope.launch {
            TripTracker.state.collect { state ->
                if (!foregroundStarted || !state.isTracking) return@collect
                val now = SystemClock.elapsedRealtime()
                if (
                    canPostNotifications() &&
                    now - lastNotificationUpdateAtMillis >= NOTIFICATION_UPDATE_INTERVAL_MS
                ) {
                    lastNotificationUpdateAtMillis = now
                    postNotificationUpdate(state)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun postNotificationUpdate(state: TripState) {
        if (!canPostNotifications()) return
        runCatching {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(state))
        }
    }

    private fun canPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun updateAverageSpeed(timestampMillis: Long, speedKmh: Double) {
        speedSamples.addLast(timestampMillis to speedKmh.coerceAtLeast(0.0))
        while (speedSamples.isNotEmpty() && timestampMillis - speedSamples.first().first > 5_000L) {
            speedSamples.removeFirst()
        }
        average5SecondSpeedKmh = speedSamples.map { it.second }.average().takeUnless { it.isNaN() } ?: 0.0
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "行程记录",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "在后台持续统计电池行程" }
        )
    }

    private fun buildNotification(state: TripState): android.app.Notification {
        val soc = state.currentSocPercent ?: state.startSocPercent ?: 0
        val currentText = when {
            state.currentA < -0.05 -> "放电 ${decimal(state.currentA, 1)}A"
            isStationaryCharging(state.currentA, state.currentSpeedKmh) ->
                "充电 +${decimal(state.currentA, 1)}A"
            state.currentA > 0.05 -> "回收 +${decimal(state.currentA, 1)}A"
            else -> "静置 0.0A"
        }
        val rangeText = state.estimatedRemainingKm?.let { "${decimal(it, 1)} km" } ?: "采集中"
        val summary = "近5秒均速 ${decimal(average5SecondSpeedKmh, 1)} km/h · 剩余续航 $rangeText"
        val details = "$summary\n本次行驶 ${decimal(state.distanceKm, 1)} km · 剩余容量 " +
            (state.currentRemainingAh?.let { "${decimal(it, 2)} Ah" } ?: "--")
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, TripTrackingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val exitAllIntent = PendingIntent.getActivity(
            this,
            2,
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_EXIT_ALL, true)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("SOC $soc% · $currentText")
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(details))
            .setShortCriticalText("SOC $soc%")
            .setRequestPromotedOngoing(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openAppIntent)
            .addAction(0, "结束行程", stopIntent)
            .addAction(0, "退出全部", exitAllIntent)
            .build()
    }

    private fun decimal(value: Double, digits: Int): String =
        "%.${digits}f".format(Locale.US, value).trimEnd('0').trimEnd('.')

    private fun Location.speedOrZero(): Float = if (hasSpeed()) speed.coerceAtLeast(0f) else 0f

    private fun Location.plausibleSpeedOrZero(): Float =
        speedOrZero().takeIf { it <= MAX_PLAUSIBLE_SPEED_MPS } ?: 0f

    private fun Location.speedAccuracyOrNull(): Float? =
        if (hasSpeedAccuracy()) speedAccuracyMetersPerSecond else null

    companion object {
        const val ACTION_START = "com.bms.jbdmanager.trip.START"
        const val ACTION_STOP = "com.bms.jbdmanager.trip.STOP"
        private const val NOTIFICATION_CHANNEL_ID = "bms_trip_tracking"
        private const val NOTIFICATION_ID = 3202
        private const val NOTIFICATION_UPDATE_INTERVAL_MS = 5_000L
        private const val LOCATION_INTERVAL_MS = 1_000L
        private const val MAX_ACCEPTED_ACCURACY_METERS = 25f
        private const val MAX_LOCATION_GAP_SECONDS = 30.0
        private const val MAX_PLAUSIBLE_SPEED_MPS = 35.0
        private const val MIN_MOVING_SPEED_MPS = 0.7
        private const val MIN_SEGMENT_METERS = 1.5
    }
}
