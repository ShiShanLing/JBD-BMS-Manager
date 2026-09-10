package com.bms.jbdmanager.update

import java.io.File
import java.net.HttpURLConnection
import java.net.URL

//MARK:更新请求
//AppUpdateClient 负责远端请求、响应校验和资源下载，用于处理应用更新。
internal class AppUpdateClient(
    private val versionUrl: String = AppUpdateConfig.VERSION_URL,
    private val userAgent: String = "JbdBmsManager"
) {
    //MARK:获取版本
    //fetchLatest 请求远端版本清单，解析可用版本并结合当前版本计算更新信息。
    fun fetchLatest(): AppUpdateInfo {
        val body = request(versionUrl, "application/json", TIMEOUT_MS) { connection ->
            connection.inputStream.bufferedReader().use { it.readText() }
        }
        return AppUpdateManifestParser.parse(body)
    }

    //MARK:下载更新
    //download 流式下载 APK 到指定文件，同时限制响应、校验长度并持续回报下载进度。
    fun download(apkUrl: String, destination: File, onProgress: (Int) -> Unit) {
        request(
            apkUrl,
            "application/vnd.android.package-archive, application/octet-stream",
            DOWNLOAD_TIMEOUT_MS
        ) { connection ->
            val total = connection.contentLengthLong.takeIf { it > 0L }
            val parent = destination.parentFile ?: error("安装包目录无效")
            parent.mkdirs()
            val temp = File(parent, "${destination.name}.part")
            temp.outputStream().buffered().use { output ->
                connection.inputStream.buffered().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var copied = 0L
                    var lastPercent = -1
                    while (true) {
                        if (total != null && copied >= total) break
                        val toRead = if (total != null) {
                            minOf(buffer.size.toLong(), total - copied).toInt()
                        } else {
                            buffer.size
                        }
                        if (toRead <= 0) break
                        val read = input.read(buffer, 0, toRead)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        val percent = if (total != null) {
                            ((copied * 100L) / total).toInt().coerceIn(0, 100)
                        } else {
                            0
                        }
                        if (percent != lastPercent) {
                            lastPercent = percent
                            onProgress(percent)
                        }
                    }
                    if (total != null && copied != total) {
                        error("安装包不完整（$copied / $total）")
                    }
                    if (copied <= 0L) error("安装包下载为空")
                }
            }
            if (destination.exists()) destination.delete()
            if (!temp.renameTo(destination)) {
                temp.copyTo(destination, overwrite = true)
                temp.delete()
            }
            onProgress(100)
        }
    }

    //MARK:请求网络数据
    //执行一次网络请求并把已连接对象交给调用块处理；无论成功失败最终都会断开连接。
    private fun <T> request(
        url: String,
        accept: String,
        timeoutMs: Int,
        read: (HttpURLConnection) -> T
    ): T {
        var current = url
        for (attempt in 0 until MAX_REDIRECTS) {
            val connection = open(current, accept, timeoutMs)
            try {
                val code = connection.responseCode
                if (code in REDIRECT_CODES) {
                    val location = connection.getHeaderField("Location")?.trim().orEmpty()
                    if (location.isEmpty()) error("服务器返回 $code，但没有跳转地址")
                    current = URL(URL(current), location).toString()
                    continue
                }
                if (code !in 200..299) error("服务器返回 $code")
                return read(connection)
            } finally {
                runCatching { connection.disconnect() }
            }
        }
        error("下载跳转次数过多")
    }

    //MARK:打开网络连接
    //创建禁用缓存、限制连接和读取时长且携带应用 User-Agent 的 HTTP 连接。
    private fun open(url: String, accept: String, timeoutMs: Int): HttpURLConnection {
        return (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = timeoutMs
            instanceFollowRedirects = false
            requestMethod = "GET"
            setRequestProperty("Accept", accept)
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("Connection", "close")
            setRequestProperty("User-Agent", userAgent)
        }
    }

    //MARK:常量配置
    //限定版本清单与 APK 最大响应体积，避免错误服务器响应耗尽内存或磁盘。
    private companion object {
        const val TIMEOUT_MS = 15_000
        const val DOWNLOAD_TIMEOUT_MS = 300_000
        const val DEFAULT_BUFFER_SIZE = 16 * 1024
        const val MAX_REDIRECTS = 8
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}
