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
    fun show(alert: TemperatureSafetyAlert) {
        if (alert.level == TemperatureAlertLevel.Critical) {
            TemperatureEmergencyOverlay.show(context, alert)
        }
        if (!canPostNotifications()) return
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
                alert.maximumTemperatureC
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(alert.title)
            .setContentText(alert.message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(alert.message))
            .setContentIntent(openApp)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setColorized(true)
            .setColor(if (alert.level == TemperatureAlertLevel.Critical) 0xFFD32F2F.toInt() else 0xFFFF8A3D.toInt())
        if (alert.level == TemperatureAlertLevel.Critical) {
            builder.setFullScreenIntent(fullScreenAlert, true)
        }
        val notification = builder.build()
        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        }
    }

    fun cancel() {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
        TemperatureEmergencyOverlay.dismiss()
    }

    private fun canPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private companion object {
        const val CHANNEL_ID = "bms_temperature_safety"
        const val NOTIFICATION_ID = 4102
    }
}
