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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

//MARK:行程服务
//TripTrackingService 在后台生命周期内持续执行并同步状态，用于处理行程跟踪。
class TripTrackingService : Service(), LocationListener {
    private lateinit var locationManager: LocationManager
    private var lastAcceptedLocation: Location? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var notificationJob: Job? = null
    private var locationWatchdogJob: Job? = null
    private var locationUpdatesRequested = false
    private var lastLocationCallbackAtElapsedMillis = 0L
    private var foregroundStarted = false
    private var lastNotificationUpdateAtMillis = 0L
    private var lastCountdownAlertAtMillis: Long? = null
    private val speedSamples = ArrayDeque<Pair<Long, Double>>()
    private var average5SecondSpeedKmh = 0.0

    //MARK:创建组件
    //初始化定位管理器、通知渠道和状态观察任务，并标记行程服务已运行。
    override fun onCreate() {
        super.onCreate()
        isRunning = true
        TripTracker.initialize(this)
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        createNotificationChannel()
    }

    //MARK:处理服务指令
    //onStartCommand 计算或控制onStartCommand，并保持 BMS 行程与纯 GPS 行程的数据边界。
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
        observeLocationHealth()
        return START_STICKY
    }

    //MARK:请求定位
    //requestLocationUpdates 维护定位所需的后台定位、通知或状态观察流程。
    private fun requestLocationUpdates(force: Boolean = false) {
        if (locationUpdatesRequested && !force) return
        val hasFineLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!hasFineLocation) {
            TripTracker.finish("精确位置权限不可用，行程已停止")
            stopSelf()
            return
        }
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            TripTracker.updateGpsStatus("请开启手机定位服务")
        }
        runCatching {
            if (force && locationUpdatesRequested) {
                locationManager.removeUpdates(this)
                locationUpdatesRequested = false
                lastAcceptedLocation = null
                speedSamples.clear()
                average5SecondSpeedKmh = 0.0
            }
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                LOCATION_INTERVAL_MS,
                0f,
                this
            )
            locationUpdatesRequested = true
            lastLocationCallbackAtElapsedMillis = SystemClock.elapsedRealtime()
        }.onFailure {
            locationUpdatesRequested = false
            TripTracker.updateGpsStatus("GPS 启动失败：${it.message.orEmpty()}")
        }
    }

    //MARK:定位更新
    //onLocationChanged 使用过滤后的定位或 BMS 样本更新定位，拒绝异常时间间隔和不可信数据。
    override fun onLocationChanged(location: Location) {
        lastLocationCallbackAtElapsedMillis = SystemClock.elapsedRealtime()
        // 精度差的定位点会产生几十米跳点，车辆静止时也可能累计里程，因此在参与计算前直接丢弃。
        if (!location.hasAccuracy() || location.accuracy > MAX_ACCEPTED_ACCURACY_METERS) {
            TripTracker.updateGpsStatus("GPS 信号较弱，等待更准确定位")
            return
        }

        val previous = lastAcceptedLocation
        lastAcceptedLocation = location
        if (previous == null) {
            // 第一个有效点只能建立基线，不能凭单点推算距离；但设备提供的速度仍可用于即时显示。
            val speed = location.plausibleSpeedOrZero()
            updateAverageSpeed(location.time, speed * 3.6)
            TripTracker.updateLocation(
                0.0, speed, location.accuracy, location.time, 0.0, location.speedAccuracyOrNull()
            )
            return
        }

        val elapsedSeconds = (location.elapsedRealtimeNanos - previous.elapsedRealtimeNanos) / 1_000_000_000.0
        if (elapsedSeconds <= 0.0 || elapsedSeconds > MAX_LOCATION_GAP_SECONDS) {
            // 时间倒退或定位中断后不连接前后两个点，防止恢复定位时把两点直线距离误记为真实骑行。
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
        // 速度和最小位移必须同时成立：过滤 GPS 漂移，也过滤超出车辆能力的瞬时跳点。
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

    //MARK:定位源关闭
    //onProviderDisabled 计算或控制onProviderDisabled，并保持 BMS 行程与纯 GPS 行程的数据边界。
    override fun onProviderDisabled(provider: String) {
        if (provider == LocationManager.GPS_PROVIDER) TripTracker.updateGpsStatus("手机定位服务已关闭")
    }

    //MARK:定位源开启
    //onProviderEnabled 计算或控制onProviderEnabled，并保持 BMS 行程与纯 GPS 行程的数据边界。
    override fun onProviderEnabled(provider: String) {
        if (provider == LocationManager.GPS_PROVIDER) {
            TripTracker.updateGpsStatus("正在等待 GPS 定位")
            requestLocationUpdates(force = true)
        }
    }

    @Deprecated("Deprecated in Android")
    //MARK:状态回调
    //onStatusChanged 计算或控制状态，并保持 BMS 行程与纯 GPS 行程的数据边界。
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

    //MARK:销毁组件
    //onDestroy 解除系统监听并释放后台任务或资源，防止组件销毁后继续收到回调。
    override fun onDestroy() {
        runCatching { locationManager.removeUpdates(this) }
        locationUpdatesRequested = false
        serviceScope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        isRunning = false
        super.onDestroy()
    }

    //MARK:绑定服务
    //onBind 该服务不提供绑定接口，因此返回空值并只通过启动式前台服务运行。
    override fun onBind(intent: Intent?): IBinder? = null

    //MARK:停止定位跟踪
    //stopTracking 结束或暂停跟踪，保存已有累计值并停止继续接收实时数据。
    private fun stopTracking(message: String, suppressAutoRestart: Boolean = false) {
        runCatching { locationManager.removeUpdates(this) }
        locationUpdatesRequested = false
        if (suppressAutoRestart) TripTracker.suppressUntilNextConnection(message) else TripTracker.finish(message)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    //MARK:观察行程
    //observeTripUpdates 维护行程所需的后台定位、通知或状态观察流程。
    private fun observeTripUpdates() {
        if (notificationJob != null) return
        notificationJob = serviceScope.launch {
            TripTracker.state.collect { state ->
                if (!foregroundStarted || !state.isTracking) return@collect
                postCountdownAlertIfNeeded(state)
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

    //MARK:定位看门狗
    //observeLocationHealth 维护定位健康所需的后台定位、通知或状态观察流程。
    private fun observeLocationHealth() {
        if (locationWatchdogJob != null) return
        locationWatchdogJob = serviceScope.launch {
            while (isActive) {
                delay(LOCATION_WATCHDOG_INTERVAL_MS)
                if (!TripTracker.state.value.isTracking) continue
                if (!locationUpdatesRequested) {
                    TripTracker.updateGpsStatus("GPS 监听未运行，正在重新启动")
                    requestLocationUpdates(force = true)
                    continue
                }
                val silentFor = SystemClock.elapsedRealtime() - lastLocationCallbackAtElapsedMillis
                if (silentFor >= LOCATION_CALLBACK_TIMEOUT_MS) {
                    // 服务仍存活但系统不再回调时主动重新注册定位，这是蓝牙重连后 GPS 偶发无数据的兜底。
                    TripTracker.updateGpsStatus("GPS 长时间无数据，正在重新连接定位")
                    requestLocationUpdates(force = true)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    //MARK:更新行程通知
    //postNotificationUpdate 维护通知更新所需的后台定位、通知或状态观察流程。
    private fun postNotificationUpdate(state: TripState) {
        if (!canPostNotifications()) return
        runCatching {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(state))
        }
    }

    //MARK:检查通知权
    //canPostNotifications 计算或控制canPostNotifications，并保持 BMS 行程与纯 GPS 行程的数据边界。
    private fun canPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    //MARK:计算均速
    //updateAverageSpeed 使用过滤后的定位或 BMS 样本更新更新速度，拒绝异常时间间隔和不可信数据。
    private fun updateAverageSpeed(timestampMillis: Long, speedKmh: Double) {
        speedSamples.addLast(timestampMillis to speedKmh.coerceAtLeast(0.0))
        // 使用滚动时间窗而不是固定样本数，避免不同手机定位频率不同导致平均时长发生变化。
        while (speedSamples.isNotEmpty() && timestampMillis - speedSamples.first().first > 5_000L) {
            speedSamples.removeFirst()
        }
        average5SecondSpeedKmh = speedSamples.map { it.second }.average().takeUnless { it.isNaN() } ?: 0.0
    }

    //MARK:创建通知
    //createNotificationChannel 维护通知所需的后台定位、通知或状态观察流程。
    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "行程记录",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "在后台持续统计骑行行程" }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                COUNTDOWN_ALERT_CHANNEL_ID,
                "换电里程提醒",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "达到设定的换电里程时播放声音并弹出提醒"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 250, 500, 250, 800)
            }
        )
    }

    //MARK:构建通知
    //buildNotification 维护通知所需的后台定位、通知或状态观察流程。
    private fun buildNotification(state: TripState): android.app.Notification {
        if (state.isMileageOnly) return buildMileageOnlyNotification(state)
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
            .setShortCriticalText("$soc%")
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

    //MARK:纯GPS通知
    //buildMileageOnlyNotification 维护里程通知所需的后台定位、通知或状态观察流程。
    private fun buildMileageOnlyNotification(state: TripState): android.app.Notification {
        val speedText = decimal(state.currentSpeedKmh, 1)
        val distanceText = decimal(state.distanceKm, 1)
        val countdownText = if (state.mileageCountdownReached) {
            "已到 ${state.mileageCountdownTargetKm} km"
        } else {
            "换电剩余 ${decimal(state.mileageCountdownRemainingKm, 1)} km"
        }
        val summary = "当前 $speedText km/h · $countdownText"
        val details = "$summary\n近5秒均速 ${decimal(average5SecondSpeedKmh, 1)} km/h · 本次行驶 $distanceText km"
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
            .setContentTitle("GPS行程 · $distanceText km")
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(details))
            .setShortCriticalText("GPS")
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

    @SuppressLint("MissingPermission")
    //MARK:倒计时提醒
    //postCountdownAlertIfNeeded 维护倒计时警报所需的后台定位、通知或状态观察流程。
    private fun postCountdownAlertIfNeeded(state: TripState) {
        if (!state.isMileageOnly) return
        val reachedAt = state.mileageCountdownReachedAtMillis
        if (reachedAt == null) {
            lastCountdownAlertAtMillis = null
            NotificationManagerCompat.from(this).cancel(COUNTDOWN_ALERT_NOTIFICATION_ID)
            return
        }
        if (state.mileageCountdownAcknowledged) return
        if (lastCountdownAlertAtMillis == reachedAt || !canPostNotifications()) return
        lastCountdownAlertAtMillis = reachedAt
        val openAppIntent = PendingIntent.getActivity(
            this,
            3,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val distanceText = decimal(state.distanceKm, 1)
        val notification = NotificationCompat.Builder(this, COUNTDOWN_ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("换电里程已达到 ${state.mileageCountdownTargetKm} km")
            .setContentText("本块电池已行驶 $distanceText km，请及时寻找换电站")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "本块电池已行驶 $distanceText km，已经达到设定的换电提醒里程，请及时寻找换电站。"
                )
            )
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setDefaults(android.app.Notification.DEFAULT_ALL)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent)
            .build()
        NotificationManagerCompat.from(this).notify(COUNTDOWN_ALERT_NOTIFICATION_ID, notification)
    }

    //MARK:格式化小数
    //decimal 计算或控制decimal，并保持 BMS 行程与纯 GPS 行程的数据边界。
    private fun decimal(value: Double, digits: Int): String =
        "%.${digits}f".format(Locale.US, value).trimEnd('0').trimEnd('.')

    //MARK:读取定位速度
    //speedOrZero 计算或控制速度，并保持 BMS 行程与纯 GPS 行程的数据边界。
    private fun Location.speedOrZero(): Float = if (hasSpeed()) speed.coerceAtLeast(0f) else 0f

    //MARK:过滤异常速度
    //plausibleSpeedOrZero 计算或控制速度，并保持 BMS 行程与纯 GPS 行程的数据边界。
    private fun Location.plausibleSpeedOrZero(): Float =
        speedOrZero().takeIf { it <= MAX_PLAUSIBLE_SPEED_MPS } ?: 0f

    //MARK:读取速度精度
    //speedAccuracyOrNull 计算或控制速度，并保持 BMS 行程与纯 GPS 行程的数据边界。
    private fun Location.speedAccuracyOrNull(): Float? =
        if (hasSpeedAccuracy()) speedAccuracyMetersPerSecond else null

    //MARK:常量配置
    //定义服务动作、通知渠道、定位频率、精度与速度过滤阈值以及 GPS 看门狗超时时间。
    companion object {
        @Volatile
        var isRunning: Boolean = false
            private set

        const val ACTION_START = "com.bms.jbdmanager.trip.START"
        const val ACTION_STOP = "com.bms.jbdmanager.trip.STOP"
        private const val NOTIFICATION_CHANNEL_ID = "bms_trip_tracking"
        private const val NOTIFICATION_ID = 3202
        private const val COUNTDOWN_ALERT_CHANNEL_ID = "battery_swap_mileage_alerts"
        private const val COUNTDOWN_ALERT_NOTIFICATION_ID = 3203
        private const val NOTIFICATION_UPDATE_INTERVAL_MS = 5_000L
        private const val LOCATION_INTERVAL_MS = 1_000L
        private const val LOCATION_WATCHDOG_INTERVAL_MS = 15_000L
        private const val LOCATION_CALLBACK_TIMEOUT_MS = 45_000L
        private const val MAX_ACCEPTED_ACCURACY_METERS = 25f
        private const val MAX_LOCATION_GAP_SECONDS = 30.0
        private const val MAX_PLAUSIBLE_SPEED_MPS = 35.0
        private const val MIN_MOVING_SPEED_MPS = 0.7
        private const val MIN_SEGMENT_METERS = 1.5
    }
}
