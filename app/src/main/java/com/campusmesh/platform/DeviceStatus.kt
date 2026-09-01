package com.campusmesh.platform

data class DeviceStatus(
    val applicationVersionName: String,
    val applicationVersionCode: Long,
    val androidRelease: String,
    val apiLevel: Int,
    val manufacturer: String,
    val model: String,
    val bluetoothAdapterPresent: Boolean,
    val bluetoothEnabled: Boolean?,
    val bluetoothUnsupportedReason: String?,
)
