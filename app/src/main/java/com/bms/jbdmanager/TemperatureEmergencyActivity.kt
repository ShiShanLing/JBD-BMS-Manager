package com.bms.jbdmanager

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bms.jbdmanager.safety.TemperatureAlertNotifier
import com.bms.jbdmanager.safety.TemperatureEmergencyOverlay
import com.bms.jbdmanager.ui.theme.JbdBmsTheme

class TemperatureEmergencyActivity : ComponentActivity() {
    private var alertId: Long = -1L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )
        window.statusBarColor = Color.rgb(88, 0, 8)
        window.navigationBarColor = Color.rgb(88, 0, 8)
        enableEdgeToEdge()
        TemperatureEmergencyOverlay.dismiss()
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "电池高温危险"
        val message = intent.getStringExtra(EXTRA_MESSAGE) ?: "检测到电池危险高温，请立即停止使用。"
        val temperature = intent.getDoubleExtra(EXTRA_TEMPERATURE, Double.NaN)
        alertId = intent.getLongExtra(TemperatureAlertNotifier.EXTRA_ALERT_ID, -1L)
        setContent {
            JbdBmsTheme {
                EmergencyContent(title, message, temperature, onAcknowledge = ::acknowledge)
            }
        }
    }

    @Composable
    private fun EmergencyContent(
        title: String,
        message: String,
        temperature: Double,
        onAcknowledge: () -> Unit
    ) {
        BackHandler(enabled = true) { }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(ComposeColor(0xFF74000C))
                .padding(horizontal = 28.dp, vertical = 44.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("⚠", fontSize = 72.sp, color = ComposeColor.White)
            Text(
                title,
                fontSize = 32.sp,
                fontWeight = FontWeight.Black,
                color = ComposeColor.White,
                textAlign = TextAlign.Center
            )
            if (!temperature.isNaN()) {
                Spacer(Modifier.height(14.dp))
                Text(
                    "当前温度 %.1f℃，非常危险".format(temperature),
                    fontSize = 25.sp,
                    fontWeight = FontWeight.Bold,
                    color = ComposeColor(0xFFFFDE78)
                )
            }
            Spacer(Modifier.height(18.dp))
            Text(
                message,
                fontSize = 18.sp,
                lineHeight = 28.sp,
                color = ComposeColor.White,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(36.dp))
            Button(
                onClick = onAcknowledge,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ComposeColor.White,
                    contentColor = ComposeColor(0xFF74000C)
                )
            ) {
                Text("我已停车并知晓", fontSize = 17.sp, fontWeight = FontWeight.Bold)
            }
        }
    }

    private fun acknowledge() {
        TemperatureAlertNotifier(this).acknowledge(alertId)
        finish()
    }

    companion object {
        private const val EXTRA_TITLE = "temperature_alert_title"
        private const val EXTRA_MESSAGE = "temperature_alert_message"
        private const val EXTRA_TEMPERATURE = "temperature_alert_value"

        fun intent(
            context: Context,
            title: String,
            message: String,
            temperature: Double,
            alertId: Long
        ): Intent =
            Intent(context, TemperatureEmergencyActivity::class.java)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_MESSAGE, message)
                .putExtra(EXTRA_TEMPERATURE, temperature)
                .putExtra(TemperatureAlertNotifier.EXTRA_ALERT_ID, alertId)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
    }
}
