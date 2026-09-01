package com.campusmesh.permissions

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PermissionStatusProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun currentStatuses(sdkInt: Int = Build.VERSION.SDK_INT): List<PermissionStatus> {
        return AppPermission.entries.map { permission ->
            if (!permission.isRequiredOn(sdkInt)) {
                PermissionStatus(permission, PermissionGrantState.NotRequiredOnThisApi)
            } else {
                val granted = ContextCompat.checkSelfPermission(
                    context,
                    permission.androidName,
                ) == PackageManager.PERMISSION_GRANTED
                PermissionStatus(
                    permission = permission,
                    state = if (granted) {
                        PermissionGrantState.Granted
                    } else {
                        PermissionGrantState.Denied
                    },
                )
            }
        }
    }

    fun isGranted(permission: AppPermission, sdkInt: Int = Build.VERSION.SDK_INT): Boolean {
        if (!permission.isRequiredOn(sdkInt)) return true
        return ContextCompat.checkSelfPermission(
            context,
            permission.androidName,
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun scanGranted(sdkInt: Int = Build.VERSION.SDK_INT): Boolean {
        return if (sdkInt >= Build.VERSION_CODES.S) {
            isGranted(AppPermission.BluetoothScan, sdkInt)
        } else {
            isGranted(AppPermission.Bluetooth, sdkInt) &&
                isGranted(AppPermission.BluetoothAdmin, sdkInt) &&
                isGranted(AppPermission.AccessFineLocation, sdkInt)
        }
    }

    fun advertiseGranted(sdkInt: Int = Build.VERSION.SDK_INT): Boolean {
        return if (sdkInt >= Build.VERSION_CODES.S) {
            isGranted(AppPermission.BluetoothAdvertise, sdkInt)
        } else {
            isGranted(AppPermission.Bluetooth, sdkInt) &&
                isGranted(AppPermission.BluetoothAdmin, sdkInt)
        }
    }

    fun connectGranted(sdkInt: Int = Build.VERSION.SDK_INT): Boolean {
        return if (sdkInt >= Build.VERSION_CODES.S) {
            isGranted(AppPermission.BluetoothConnect, sdkInt)
        } else {
            isGranted(AppPermission.Bluetooth, sdkInt)
        }
    }
}
