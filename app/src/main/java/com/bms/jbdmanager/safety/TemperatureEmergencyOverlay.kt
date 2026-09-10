package com.bms.jbdmanager.safety

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.bms.jbdmanager.model.TemperatureSafetyAlert

//MARK:应用内高温层
//TemperatureEmergencyOverlay 提供进程内共享的温度紧急警报能力，并集中维护其状态、常量或纯计算入口。
internal object TemperatureEmergencyOverlay {
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null

    //MARK:显示警报
    //拥有悬浮窗权限时移除旧警报并创建覆盖屏幕的红色危险层，展示温度、原因和确认按钮。
    fun show(context: Context, alert: TemperatureSafetyAlert) {
        if (!Settings.canDrawOverlays(context)) return
        dismiss()
        val appContext = context.applicationContext
        val density = appContext.resources.displayMetrics.density
        val content = LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding((28 * density).toInt(), (36 * density).toInt(), (28 * density).toInt(), (36 * density).toInt())
            setBackgroundColor(Color.rgb(116, 0, 12))
            addView(TextView(appContext).apply {
                text = "⚠"
                textSize = 58f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
            })
            addView(TextView(appContext).apply {
                text = alert.title
                textSize = 30f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setTypeface(typeface, Typeface.BOLD)
            })
            addView(TextView(appContext).apply {
                text = "当前温度 %.1f℃，非常危险".format(alert.maximumTemperatureC)
                textSize = 24f
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(255, 222, 120))
                setPadding(0, (12 * density).toInt(), 0, (12 * density).toInt())
            })
            addView(TextView(appContext).apply {
                text = alert.message
                textSize = 18f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setLineSpacing(0f, 1.25f)
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            ))
            addView(Button(appContext).apply {
                text = "我已停车并知晓"
                textSize = 17f
                isAllCaps = false
                setOnClickListener { TemperatureAlertNotifier(appContext).acknowledge(alert.id) }
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (56 * density).toInt()
            ))
        }
        val manager = appContext.getSystemService(WindowManager::class.java)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER }
        runCatching {
            manager.addView(content, params)
            windowManager = manager
            overlayView = content
        }
    }

    //MARK:关闭警报
    //dismiss 关闭或确认dismiss并清理去重状态，避免同一警报被重复展示。
    fun dismiss() {
        val view = overlayView ?: return
        runCatching { windowManager?.removeView(view) }
        overlayView = null
        windowManager = null
    }
}
