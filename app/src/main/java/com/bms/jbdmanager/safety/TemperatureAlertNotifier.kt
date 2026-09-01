package com.bms.jbdmanager.safety

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.bms.jbdmanager.MainActivity
import com.bms.jbdmanager.R
import com.bms.jbdmanager.TemperatureEmergencyActivity
import com.bms.jbdmanager.model.TemperatureAlertLevel
import com.bms.jbdmanager.model.TemperatureSafetyAlert

internal class TemperatureAlertNotifier(private val context: Context) {
    init {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "电池温度安全警告",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "电池温度过高或快速升温时发出声音、震动和横幅警告"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 250, 500, 250, 900)
                setSound(
                    Settings.System.DEFAULT_ALARM_ALERT_URI,
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
            }
        )
    }

    @SuppressLint("MissingPermission")
    fun show(alert: TemperatureSafetyAlert): Boolean {
        val critical = alert.level == TemperatureAlertLevel.Critical
        val showFullScreen = critical && canPostNotifications() && canUseFullScreenAlert()
        val showOverlay = critical && !showFullScreen && Settings.canDrawOverlays(context)
        if (showOverlay) {
            TemperatureEmergencyOverlay.show(context, alert)
        } else {
            TemperatureEmergencyOverlay.dismiss()
        }
        if (!canPostNotifications()) return showOverlay
        val notificationMessage = if (critical) {
            "当前温度 %.1f℃，非常危险。%s".format(alert.maximumTemperatureC, alert.message)
        } else {
            alert.message
        }
        val openApp = PendingIntent.getActivity(
            context,
            40,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val fullScreenAlert = PendingIntent.getActivity(
            context,
            41,
            TemperatureEmergencyActivity.intent(
                context,
                alert.title,
                alert.message,
                alert.maximumTemperatureC,
                alert.id
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(alert.title)
            .setContentText(notificationMessage)
            .setStyle(NotificationCompat.BigTextStyle().bigText(notificationMessage))
            .setContentIntent(openApp)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setColorized(true)
            .setColor(if (critical) 0xFFD32F2F.toInt() else 0xFFFF8A3D.toInt())
        if (showFullScreen) {
            builder.setFullScreenIntent(fullScreenAlert, true)
        }
        val notification = builder.build()
        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        }
        if (showFullScreen) {
            // A full-screen notification can be reduced to a heads-up notification while the
            // device is already unlocked. Starting the same screen directly guarantees that a
            // foreground app still presents the red emergency screen; background/lock-screen
            // delivery continues to be handled by the notification full-screen intent.
            runCatching { context.startActivity(fullScreenAlertIntent(alert)) }
        }
        return showFullScreen || showOverlay
    }

    fun cancel() {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
        TemperatureEmergencyOverlay.dismiss()
    }

    fun acknowledge(alertId: Long) {
        TemperatureEmergencyOverlay.dismiss()
        context.sendBroadcast(
            Intent(ACTION_ALERT_ACKNOWLEDGED)
                .setPackage(context.packageName)
                .putExtra(EXTRA_ALERT_ID, alertId)
        )
    }

    fun dismissEmergencySurface() {
        TemperatureEmergencyOverlay.dismiss()
    }

    private fun fullScreenAlertIntent(alert: TemperatureSafetyAlert): Intent =
        TemperatureEmergencyActivity.intent(
            context,
            alert.title,
            alert.message,
            alert.maximumTemperatureC,
            alert.id
        )

    private fun canPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun canUseFullScreenAlert(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
            context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

    companion object {
        const val ACTION_ALERT_ACKNOWLEDGED = "com.bms.jbdmanager.TEMPERATURE_ALERT_ACKNOWLEDGED"
        const val EXTRA_ALERT_ID = "temperature_alert_id"

        const val CHANNEL_ID = "bms_temperature_safety"
        const val NOTIFICATION_ID = 4102
    }
}
