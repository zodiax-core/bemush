package com.campusmesh.platform

import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.campusmesh.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads device and Bluetooth *adapter* status only.
 *
 * Adapter presence/enabled only. Scanning and advertising live in BleDiscoveryController.
 */
@Singleton
class DeviceStatusProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun current(): DeviceStatus {
        val hasBleFeature = context.packageManager
            .hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
        val hasBluetoothFeature = context.packageManager
            .hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)

        val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
        val adapter = bluetoothManager?.adapter

        val present = adapter != null && hasBluetoothFeature
        val enabled = try {
            adapter?.isEnabled
        } catch (_: SecurityException) {
            null
        }

        val unsupportedReason = when {
            !hasBluetoothFeature -> "This device does not report Bluetooth hardware."
            !hasBleFeature -> "Bluetooth is present, but Bluetooth LE is not reported. Mesh work in later phases will not run on this device."
            adapter == null -> "Bluetooth adapter is unavailable."
            enabled == null -> "Bluetooth enabled state could not be read. Grant Bluetooth connect permission and refresh."
            else -> null
        }

        return DeviceStatus(
            applicationVersionName = BuildConfig.VERSION_NAME,
            applicationVersionCode = BuildConfig.VERSION_CODE.toLong(),
            androidRelease = Build.VERSION.RELEASE,
            apiLevel = Build.VERSION.SDK_INT,
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            bluetoothAdapterPresent = present,
            bluetoothEnabled = enabled,
            bluetoothUnsupportedReason = unsupportedReason,
        )
    }
}
