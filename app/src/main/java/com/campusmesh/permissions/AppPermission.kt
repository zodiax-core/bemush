package com.campusmesh.permissions

/**
 * Runtime permissions CampusMesh needs for BLE discovery (Phase 2).
 *
 * [minSdkInclusive] / [maxSdkInclusive] describe when the permission is
 * actually required at runtime on a given device API level.
 */
enum class AppPermission(
    val androidName: String,
    val title: String,
    val rationale: String,
    val minSdkInclusive: Int,
    val maxSdkInclusive: Int = Int.MAX_VALUE,
) {
    Bluetooth(
        androidName = "android.permission.BLUETOOTH",
        title = "Bluetooth",
        rationale = "Legacy Bluetooth access on Android 8–11.",
        minSdkInclusive = 26,
        maxSdkInclusive = 30,
    ),
    BluetoothAdmin(
        androidName = "android.permission.BLUETOOTH_ADMIN",
        title = "Bluetooth admin",
        rationale = "Legacy Bluetooth admin on Android 8–11.",
        minSdkInclusive = 26,
        maxSdkInclusive = 30,
    ),
    AccessFineLocation(
        androidName = "android.permission.ACCESS_FINE_LOCATION",
        title = "Location",
        rationale = "Required by Android for Bluetooth scanning on Android 8–11. CampusMesh does not use GPS.",
        minSdkInclusive = 26,
        maxSdkInclusive = 30,
    ),
    BluetoothScan(
        androidName = "android.permission.BLUETOOTH_SCAN",
        title = "Bluetooth scan",
        rationale = "Nearby device scanning on Android 12+.",
        minSdkInclusive = 31,
    ),
    BluetoothConnect(
        androidName = "android.permission.BLUETOOTH_CONNECT",
        title = "Bluetooth connect",
        rationale = "Connecting to nearby devices on Android 12+.",
        minSdkInclusive = 31,
    ),
    BluetoothAdvertise(
        androidName = "android.permission.BLUETOOTH_ADVERTISE",
        title = "Bluetooth advertise",
        rationale = "Advertising this device to peers on Android 12+.",
        minSdkInclusive = 31,
    ),
    PostNotifications(
        androidName = "android.permission.POST_NOTIFICATIONS",
        title = "Notifications",
        rationale = "Needed later for a mesh foreground-service notification. Not used in Phase 2.",
        minSdkInclusive = 33,
    );

    fun isRequiredOn(sdkInt: Int): Boolean =
        sdkInt in minSdkInclusive..maxSdkInclusive
}

enum class PermissionGrantState {
    Granted,
    Denied,
    NotRequiredOnThisApi,
}

data class PermissionStatus(
    val permission: AppPermission,
    val state: PermissionGrantState,
)

object RequiredPermissions {
    fun forSdk(sdkInt: Int): List<AppPermission> =
        AppPermission.entries.filter { it.isRequiredOn(sdkInt) }

    fun runtimeRequestNames(sdkInt: Int): Array<String> =
        forSdk(sdkInt).map { it.androidName }.toTypedArray()
}
