package com.bms.jbdmanager.ui

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.io.File

@Composable
internal fun BatteryHealthPdfPreviewScreen(
    filePath: String,
    onClose: () -> Unit,
    onSave: () -> Unit,
    onShare: (String) -> Unit
) {
    BackHandler(onBack = onClose)
    val file = remember(filePath) { File(filePath) }
    val pageCount = remember(filePath) { readPdfPageCount(file) }
    var pageIndex by remember(filePath) { mutableIntStateOf(0) }
    var scale by remember(filePath, pageIndex) { mutableFloatStateOf(1f) }
    var translation by remember(filePath, pageIndex) { mutableStateOf(Offset.Zero) }
    val pageBitmap = remember(filePath, pageIndex) {
        if (pageCount > 0) renderPdfPage(file, pageIndex) else null
    }
    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        val nextScale = (scale * zoomChange).coerceIn(1f, 4f)
        scale = nextScale
        translation = if (nextScale == 1f) Offset.Zero else translation + panChange
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            TextButton(onClick = onClose) { Text("关闭") }
            Text(
                text = "电池健康报告",
                modifier = Modifier.weight(1f),
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            TextButton(onClick = onSave) { Text("保存") }
            TextButton(onClick = { onShare(filePath) }, enabled = file.isFile) { Text("分享") }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .background(androidx.compose.ui.graphics.Color(0xFFCBD5D1)),
            contentAlignment = Alignment.Center
        ) {
            if (pageBitmap != null) {
                Image(
                    bitmap = pageBitmap.asImageBitmap(),
                    contentDescription = "健康报告第${pageIndex + 1}页",
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(6.dp)
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = translation.x,
                            translationY = translation.y
                        )
                        .transformable(transformState),
                    contentScale = ContentScale.Fit
                )
            } else {
                Text("报告无法打开", color = MaterialTheme.colorScheme.error)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = { pageIndex -= 1 },
                enabled = pageIndex > 0,
                modifier = Modifier.weight(1f)
            ) { Text("上一页") }
            Text(
                text = if (pageCount > 0) "${pageIndex + 1} / $pageCount" else "0 / 0",
                color = MaterialTheme.colorScheme.onBackground
            )
            Button(
                onClick = { pageIndex += 1 },
                enabled = pageIndex + 1 < pageCount,
                modifier = Modifier.weight(1f)
            ) { Text("下一页") }
        }
    }
}

private fun readPdfPageCount(file: File): Int = runCatching {
    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
        PdfRenderer(descriptor).use { renderer -> renderer.pageCount }
    }
}.getOrDefault(0)

private fun renderPdfPage(file: File, pageIndex: Int): Bitmap? = runCatching {
    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
        PdfRenderer(descriptor).use { renderer ->
            renderer.openPage(pageIndex).use { page ->
                val bitmap = Bitmap.createBitmap(page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(android.graphics.Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap
            }
        }
    }
}.getOrNull()
