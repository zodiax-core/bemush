package com.campusmesh.ble

data class DiscoveryHardware(
    val bluetoothFeature: Boolean,
    val bleFeature: Boolean,
    val adapterPresent: Boolean,
    val advertiserPresent: Boolean,
    val bluetoothEnabled: Boolean?,
    val locationEnabled: Boolean,
)

data class DiscoveryPermissionFlags(
    val scanGranted: Boolean,
    val advertiseGranted: Boolean,
    val connectGranted: Boolean,
)

enum class DiscoveryBlock {
    BluetoothHardwareMissing,
    BleHardwareMissing,
    AdapterMissing,
    AdvertiserMissing,
    BluetoothOff,
    BluetoothStateUnknown,
    MissingScanPermission,
    MissingAdvertisePermission,
    LocationOff,
}

data class DiscoveryPlan(
    val canScan: Boolean,
    val canAdvertise: Boolean,
    val blocks: List<DiscoveryBlock>,
)

object DiscoveryPreconditions {
    fun plan(
        sdkInt: Int,
        hardware: DiscoveryHardware,
        permissions: DiscoveryPermissionFlags,
    ): DiscoveryPlan {
        val blocks = mutableListOf<DiscoveryBlock>()

        if (!hardware.bluetoothFeature) blocks += DiscoveryBlock.BluetoothHardwareMissing
        if (!hardware.bleFeature) blocks += DiscoveryBlock.BleHardwareMissing
        if (!hardware.adapterPresent) blocks += DiscoveryBlock.AdapterMissing

        val bluetoothReady = hardware.bluetoothFeature &&
            hardware.bleFeature &&
            hardware.adapterPresent &&
            hardware.bluetoothEnabled == true

        when (hardware.bluetoothEnabled) {
            false -> blocks += DiscoveryBlock.BluetoothOff
            null -> if (hardware.adapterPresent) blocks += DiscoveryBlock.BluetoothStateUnknown
            true -> Unit
        }

        val locationRequired = sdkInt <= 30
        if (locationRequired && !hardware.locationEnabled) {
            blocks += DiscoveryBlock.LocationOff
        }

        val scanPermissionOk = permissions.scanGranted && permissions.connectGranted
        val advertisePermissionOk = permissions.advertiseGranted && permissions.connectGranted
        if (!scanPermissionOk) blocks += DiscoveryBlock.MissingScanPermission
        if (!advertisePermissionOk) blocks += DiscoveryBlock.MissingAdvertisePermission

        val canScan = bluetoothReady &&
            scanPermissionOk &&
            (!locationRequired || hardware.locationEnabled)

        val canAdvertise = bluetoothReady &&
            advertisePermissionOk &&
            hardware.advertiserPresent

        if (bluetoothReady && permissions.advertiseGranted && permissions.connectGranted && !hardware.advertiserPresent) {
            if (DiscoveryBlock.AdvertiserMissing !in blocks) {
                blocks += DiscoveryBlock.AdvertiserMissing
            }
        }

        return DiscoveryPlan(
            canScan = canScan,
            canAdvertise = canAdvertise,
            blocks = blocks.distinct(),
        )
    }
}
