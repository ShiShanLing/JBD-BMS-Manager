package com.bms.jbdmanager.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val BmsColors = darkColorScheme(
    primary = Color(0xFF63E6BE),
    onPrimary = Color(0xFF00382C),
    primaryContainer = Color(0xFF145A49),
    onPrimaryContainer = Color(0xFFB7F7DF),
    secondary = Color(0xFFFFD166),
    background = Color(0xFF071B17),
    onBackground = Color(0xFFE5F4EF),
    surface = Color(0xFF102923),
    onSurface = Color(0xFFE5F4EF),
    surfaceVariant = Color(0xFF193730),
    onSurfaceVariant = Color(0xFFB8CEC7),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005)
)

@Composable
//MARK:应用主题
//JbdBmsTheme 应用深色 BMS 配色和 Material 主题，为全部 Compose 页面提供统一颜色与排版。
fun JbdBmsTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = BmsColors, content = content)
}
