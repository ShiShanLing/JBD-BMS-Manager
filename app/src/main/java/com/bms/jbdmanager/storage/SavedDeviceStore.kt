package com.bms.jbdmanager.storage

import android.content.Context
import com.bms.jbdmanager.model.SavedDevice

//MARK:设备保存快照
//SavedDeviceSnapshot 将已保存设备快照相关字段组合为不可变值，避免跨层传递时出现部分字段不同步。
internal data class SavedDeviceSnapshot(
    val lastAddress: String?,
    val lastName: String?,
    val devices: List<SavedDevice>
)

//MARK:设备存储
//SavedDeviceStore 封装本地持久化、兼容解析和写回规则，用于处理已保存设备。
internal class SavedDeviceStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    //MARK:读取记录
    //读取所有历史连接设备、最后设备及各自最近 SOC，并兼容早期只保存单个设备的格式。
    fun load(): SavedDeviceSnapshot {
        val lastAddress = preferences.getString(LAST_DEVICE_ADDRESS, null)
        val lastName = preferences.getString(LAST_DEVICE_NAME, null)
        val addresses = preferences.getStringSet(SAVED_DEVICE_ADDRESSES, emptySet())
            .orEmpty()
            .toMutableSet()
            .apply { lastAddress?.let(::add) }
        val devices = addresses.map { address ->
            SavedDevice(
                address = address,
                name = preferences.getString("$SAVED_DEVICE_NAME_PREFIX$address", null)
                    ?: (if (address == lastAddress) lastName else null)
                    ?: address,
                lastSocPercent = if (preferences.contains("$SAVED_DEVICE_SOC_PREFIX$address")) {
                    preferences.getInt("$SAVED_DEVICE_SOC_PREFIX$address", 0).coerceIn(0, 100)
                } else null
            )
        }.sortedByDescending { it.address == lastAddress }
        return SavedDeviceSnapshot(lastAddress, lastName, devices)
    }

    //MARK:保存状态
    //按地址新增或更新设备名称和最近 SOC，同时把该地址设为下次自动连接目标。
    fun save(address: String, name: String, lastSocPercent: Int?): SavedDeviceSnapshot {
        val savedAddresses = preferences.getStringSet(SAVED_DEVICE_ADDRESSES, emptySet())
            .orEmpty()
            .toMutableSet()
            .apply { add(address) }
        val editor = preferences.edit()
            .putString(LAST_DEVICE_ADDRESS, address)
            .putString(LAST_DEVICE_NAME, name)
            .putStringSet(SAVED_DEVICE_ADDRESSES, savedAddresses)
            .putString("$SAVED_DEVICE_NAME_PREFIX$address", name)
        lastSocPercent?.let { editor.putInt("$SAVED_DEVICE_SOC_PREFIX$address", it.coerceIn(0, 100)) }
        editor.apply()
        return load()
    }

    //MARK:常量配置
    //声明历史设备、最后设备、名称和 SOC 的存储键，兼容单设备旧格式与多设备新格式。
    private companion object {
        const val PREFERENCES_NAME = "jbd_bms_preferences"
        const val LAST_DEVICE_ADDRESS = "last_device_address"
        const val LAST_DEVICE_NAME = "last_device_name"
        const val SAVED_DEVICE_ADDRESSES = "saved_device_addresses"
        const val SAVED_DEVICE_NAME_PREFIX = "saved_device_name_"
        const val SAVED_DEVICE_SOC_PREFIX = "saved_device_soc_"
    }
}
