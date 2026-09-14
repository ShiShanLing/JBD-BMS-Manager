package com.bms.jbdmanager

import android.Manifest
import android.app.PictureInPictureParams
import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.provider.Settings
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.bms.jbdmanager.model.ConnectionPhase
import com.bms.jbdmanager.model.BmsUiState
import com.bms.jbdmanager.safety.TemperatureAlertNotifier
import com.bms.jbdmanager.ui.BmsApp
import com.bms.jbdmanager.ui.theme.JbdBmsTheme
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

//MARK:应用主页面
//MainActivity 承接应用入口、运行时权限、画中画、文件导出分享、APK 安装和一键退出生命周期。
class MainActivity : ComponentActivity() {
    private val viewModel: BmsViewModel by viewModels()
    private var inPictureInPicture by mutableStateOf(false)
    private var minimizeOnPipClose = false
    private var allowActivityDestroy = false
    private val temperatureAlertAcknowledgedReceiver = object : BroadcastReceiver() {
        //MARK:接收广播
        //onReceive 接收onReceive回调，只同步当前生命周期仍然有效的状态和后续任务。
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != TemperatureAlertNotifier.ACTION_ALERT_ACKNOWLEDGED) return
            viewModel.acknowledgeTemperatureSafetyAlert(
                intent.getLongExtra(TemperatureAlertNotifier.EXTRA_ALERT_ID, -1L)
            )
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { viewModel.setPermissionsGranted(hasBluetoothPermissions()) }

    private val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { viewModel.refreshBluetoothState() }

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { viewModel.setLocationPermissionGranted(hasPreciseLocationPermission()) }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    private val createBackupLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri -> uri?.let(viewModel::exportFullBackup) }

    private val restoreBackupLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(viewModel::prepareDataRestore) }

    private val exportCsvLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri -> uri?.let(viewModel::exportCsvPackage) }

    private val exportHealthPdfLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri -> uri?.let(viewModel::exportBatteryHealthPdf) }

    private var pendingInstallAfterPermission = false

    //MARK:创建组件
    //处理通知退出指令，初始化权限和画中画观察，注册温度确认广播并创建 Compose 页面。
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 通知中的“一键退出”通过新 Intent 启动入口页；必须在创建 UI 前处理，避免退出时短暂闪现主界面。
        if (intent.getBooleanExtra(EXTRA_EXIT_ALL, false)) {
            viewModel.shutdownAll()
            allowActivityDestroy = true
            finishAndRemoveTask()
            return
        }
        // 骑行查看数据期间保持屏幕常亮；该标志只在本 Activity 可见时生效，不会永久修改系统设置。
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()
        inPictureInPicture = isInPictureInPictureMode
        viewModel.setPermissionsGranted(hasBluetoothPermissions())
        viewModel.setLocationPermissionGranted(hasPreciseLocationPermission())
        refreshTemperatureEmergencyPermissions()
        observePictureInPictureEligibility()
        ContextCompat.registerReceiver(
            this,
            temperatureAlertAcknowledgedReceiver,
            IntentFilter(TemperatureAlertNotifier.ACTION_ALERT_ACKNOWLEDGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        setContent {
            JbdBmsTheme {
                BmsApp(
                    viewModel = viewModel,
                    inPictureInPicture = inPictureInPicture,
                    enterPictureInPicture = ::enterPictureInPictureFromUi,
                    exitApp = {
                        viewModel.shutdownAll()
                        allowActivityDestroy = true
                        finishAndRemoveTask()
                    },
                    requestPermissions = {
                        permissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.BLUETOOTH_SCAN,
                                Manifest.permission.BLUETOOTH_CONNECT
                            )
                        )
                    },
                    requestEnableBluetooth = {
                        enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                    },
                    requestLocationPermission = {
                        locationPermissionLauncher.launch(
                            buildList {
                                add(Manifest.permission.ACCESS_COARSE_LOCATION)
                                add(Manifest.permission.ACCESS_FINE_LOCATION)
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    add(Manifest.permission.POST_NOTIFICATIONS)
                                }
                            }.toTypedArray()
                        )
                    },
                    requestFullScreenTemperaturePermission = ::requestFullScreenTemperaturePermission,
                    requestOverlayTemperaturePermission = ::requestOverlayTemperaturePermission,
                    requestCreateBackup = {
                        createBackupLauncher.launch("电动BMS完整备份_${archiveTimestamp()}.zip")
                    },
                    requestRestoreBackup = {
                        restoreBackupLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
                    },
                    requestExportCsv = {
                        exportCsvLauncher.launch("电动BMS数据_${archiveTimestamp()}.zip")
                    },
                    requestExportHealthPdf = {
                        exportHealthPdfLauncher.launch("电池健康报告_${archiveTimestamp()}.pdf")
                    },
                    shareHealthPdf = ::shareHealthPdf,
                    installApk = ::installApk
                )
            }
        }
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            hasPreciseLocationPermission() &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    //MARK:恢复页面
    //onResume 页面重新进入前台时刷新权限和系统状态，处理此前等待继续的操作。
    override fun onResume() {
        super.onResume()
        viewModel.setPermissionsGranted(hasBluetoothPermissions())
        viewModel.setLocationPermissionGranted(hasPreciseLocationPermission())
        refreshTemperatureEmergencyPermissions()
        if (pendingInstallAfterPermission && packageManager.canRequestPackageInstalls()) {
            pendingInstallAfterPermission = false
            viewModel.retryAppUpdateInstall()
        }
    }

    //MARK:页面停止
    //onStop 页面离开前台时保存最后一次有效状态，再交由系统继续生命周期切换。
    override fun onStop() {
        // Activity 进入后台、锁屏或画中画切换前均刷新最后状态，避免进程随后被系统回收而丢失快照。
        viewModel.saveLastSnapshot()
        super.onStop()
    }

    //MARK:销毁组件
    //onDestroy 解除系统监听并释放后台任务或资源，防止组件销毁后继续收到回调。
    override fun onDestroy() {
        runCatching { unregisterReceiver(temperatureAlertAcknowledgedReceiver) }
        super.onDestroy()
    }

    //MARK:画中画变化
    //onPictureInPictureModeChanged 接收onPictureInPictureModeChanged回调，只同步当前生命周期仍然有效的状态和后续任务。
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPictureInPicture = isInPictureInPictureMode
        if (isInPictureInPictureMode) {
            minimizeOnPipClose = true
            return
        }
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            minimizeOnPipClose = false
        }
        val state = viewModel.uiState.value
        updatePictureInPictureParams(
            enabled = isPictureInPictureEligible(state)
        )
    }

    //MARK:结束行程
    //finish 结束当前行程，先归档完整累计数据，再停止实时速度和续航测试状态。
    override fun finish() {
        if (!allowActivityDestroy && minimizeOnPipClose) {
            // 用户关闭画中画时只把任务移到后台，让蓝牙与 GPS 前台服务继续；明确退出才销毁任务。
            minimizeOnPipClose = false
            inPictureInPicture = false
            moveTaskToBack(true)
            return
        }
        super.finish()
    }

    //MARK:观察画中画
    //observePictureInPictureEligibility 在页面可见期间观察 BMS 状态，并持续更新系统画中画自动进入条件。
    private fun observePictureInPictureEligibility() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    updatePictureInPictureParams(
                        enabled = isPictureInPictureEligible(state)
                    )
                }
            }
        }
    }

    //MARK:进入画中画
    //enterPictureInPictureFromUi 确认设备支持画中画后，以 16:9 参数主动进入骑行小窗口。
    private fun enterPictureInPictureFromUi() {
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return
        try {
            enterPictureInPictureMode(pictureInPictureParams(autoEnter = true))
        } catch (_: IllegalStateException) {
        }
    }

    //MARK:判断画中画
    //isPictureInPictureEligible 仅在已有真实 BMS 数据或纯 GPS 行程运行时允许进入画中画。
    private fun isPictureInPictureEligible(state: BmsUiState): Boolean =
        // 有真实 BMS 数据或正在进行纯 GPS 行程时才允许小窗，扫描页和空白详情不自动进入。
        (state.phase == ConnectionPhase.Ready && state.basicInfo != null) ||
            (state.trip.isTracking && state.trip.trackingMode != com.bms.jbdmanager.model.TripTrackingMode.Bms)

    //MARK:更新更新参数
    //updatePictureInPictureParams 把当前是否允许自动进入画中画写入 Activity 参数，并忽略结束阶段的系统异常。
    private fun updatePictureInPictureParams(enabled: Boolean) {
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return
        try {
            setPictureInPictureParams(pictureInPictureParams(autoEnter = enabled))
        } catch (_: IllegalStateException) {
            // Activity is finishing or not in a state that can enter PiP.
        }
    }

    //MARK:画中画参数
    //pictureInPictureParams 创建固定 16:9 比例和指定自动进入状态的画中画参数。
    private fun pictureInPictureParams(autoEnter: Boolean): PictureInPictureParams =
        PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))
            .setAutoEnterEnabled(autoEnter)
            .build()

    //MARK:处理指令
    //onNewIntent 处理复用现有 Activity 时收到的新指令，例如通知触发的一键退出。
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_EXIT_ALL, false)) {
            viewModel.shutdownAll()
            allowActivityDestroy = true
            finishAndRemoveTask()
        }
    }

    //MARK:判断蓝牙
    //hasBluetoothPermissions 同时检查附近设备扫描与连接权限，二者都授予才返回 true。
    private fun hasBluetoothPermissions(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    //MARK:判断定位
    //hasPreciseLocationPermission 检查精确定位权限；仅有粗略定位不足以参与行程累计。
    private fun hasPreciseLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    //MARK:刷新温度
    //refreshTemperatureEmergencyPermissions 重新读取全屏 Intent 与悬浮窗授权并同步到 ViewModel。
    private fun refreshTemperatureEmergencyPermissions() {
        val fullScreenGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
        } else {
            true
        }
        viewModel.setTemperatureEmergencyPermissions(
            fullScreenGranted = fullScreenGranted,
            overlayGranted = Settings.canDrawOverlays(this)
        )
    }

    //MARK:请求满充温度
    //requestFullScreenTemperaturePermission 打开系统全屏警报专用授权页面；旧系统无需此单独权限。
    private fun requestFullScreenTemperaturePermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            refreshTemperatureEmergencyPermissions()
            return
        }
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                    Uri.parse("package:$packageName")
                )
            )
        }
    }

    //MARK:请求温度
    //requestOverlayTemperaturePermission 打开本应用悬浮窗授权页面，作为全屏警报不可用时的危险提醒备选。
    private fun requestOverlayTemperaturePermission() {
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }
    }

    //MARK:安装更新
    //installApk 通过 FileProvider 暴露已下载 APK 并启动系统安装器；缺少来源权限时先引导授权。
    private fun installApk(path: String) {
        val apk = File(path).takeIf { it.isFile } ?: return
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", apk)
        if (packageManager.canRequestPackageInstalls()) {
            startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } else {
            pendingInstallAfterPermission = true
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:$packageName")
                )
            )
        }
    }

    //MARK:分享健康报告
    //shareHealthPdf 通过 FileProvider 生成只读 PDF URI，并打开系统分享面板。
    private fun shareHealthPdf(path: String) {
        val report = File(path).takeIf { it.isFile } ?: return
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", report)
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND)
                    .setType("application/pdf")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                "分享电池健康报告"
            )
        )
    }

    //MARK:常量配置
    //声明一键退出 Intent 参数和导出文件名时间格式，供通知与文件选择器共同使用。
    companion object {
        const val EXTRA_EXIT_ALL = "com.bms.jbdmanager.EXIT_ALL"
        private val archiveTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmm")
        //MARK:计算归档时间
        //archiveTimestamp 生成适合文件名的当前时间文本，用于区分每次备份和数据导出。
        private fun archiveTimestamp(): String = LocalDateTime.now().format(archiveTimeFormatter)
    }
}
