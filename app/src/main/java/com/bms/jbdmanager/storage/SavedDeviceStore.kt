package com.bms.jbdmanager.storage

import android.content.Context
import com.bms.jbdmanager.model.SavedDevice

internal data class SavedDeviceSnapshot(
    val lastAddress: String?,
    val lastName: String?,
    val devices: List<SavedDevice>
)

internal class SavedDeviceStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

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

    private companion object {
        const val PREFERENCES_NAME = "jbd_bms_preferences"
        const val LAST_DEVICE_ADDRESS = "last_device_address"
        const val LAST_DEVICE_NAME = "last_device_name"
        const val SAVED_DEVICE_ADDRESSES = "saved_device_addresses"
        const val SAVED_DEVICE_NAME_PREFIX = "saved_device_name_"
        const val SAVED_DEVICE_SOC_PREFIX = "saved_device_soc_"
    }
}
