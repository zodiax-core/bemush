package com.campusmesh.ble

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryPreconditionsTest {

    private val readyHardware = DiscoveryHardware(
        bluetoothFeature = true,
        bleFeature = true,
        adapterPresent = true,
        advertiserPresent = true,
        bluetoothEnabled = true,
        locationEnabled = true,
    )
    private val readyPermissions = DiscoveryPermissionFlags(
        scanGranted = true,
        advertiseGranted = true,
        connectGranted = true,
    )

    @Test
    fun api31ReadyCanScanAndAdvertise() {
        val plan = DiscoveryPreconditions.plan(31, readyHardware, readyPermissions)
        assertTrue(plan.canScan)
        assertTrue(plan.canAdvertise)
    }

    @Test
    fun bluetoothOffBlocksBoth() {
        val plan = DiscoveryPreconditions.plan(
            sdkInt = 31,
            hardware = readyHardware.copy(bluetoothEnabled = false),
            permissions = readyPermissions,
        )
        assertFalse(plan.canScan)
        assertFalse(plan.canAdvertise)
        assertTrue(plan.blocks.contains(DiscoveryBlock.BluetoothOff))
    }

    @Test
    fun locationOffBlocksScanOnApi26NotAdvertise() {
        val plan = DiscoveryPreconditions.plan(
            sdkInt = 26,
            hardware = readyHardware.copy(locationEnabled = false),
            permissions = readyPermissions,
        )
        assertFalse(plan.canScan)
        assertTrue(plan.canAdvertise)
        assertTrue(plan.blocks.contains(DiscoveryBlock.LocationOff))
    }

    @Test
    fun locationOffDoesNotBlockScanOnApi31() {
        val plan = DiscoveryPreconditions.plan(
            sdkInt = 31,
            hardware = readyHardware.copy(locationEnabled = false),
            permissions = readyPermissions,
        )
        assertTrue(plan.canScan)
        assertTrue(plan.canAdvertise)
        assertFalse(plan.blocks.contains(DiscoveryBlock.LocationOff))
    }

    @Test
    fun missingScanPermissionStillAllowsAdvertise() {
        val plan = DiscoveryPreconditions.plan(
            sdkInt = 31,
            hardware = readyHardware,
            permissions = readyPermissions.copy(scanGranted = false),
        )
        assertFalse(plan.canScan)
        assertTrue(plan.canAdvertise)
    }

    @Test
    fun missingAdvertiserBlocksAdvertiseOnly() {
        val plan = DiscoveryPreconditions.plan(
            sdkInt = 31,
            hardware = readyHardware.copy(advertiserPresent = false),
            permissions = readyPermissions,
        )
        assertTrue(plan.canScan)
        assertFalse(plan.canAdvertise)
        assertTrue(plan.blocks.contains(DiscoveryBlock.AdvertiserMissing))
    }

    @Test
    fun missingBleHardwareBlocksBoth() {
        val plan = DiscoveryPreconditions.plan(
            sdkInt = 31,
            hardware = readyHardware.copy(bleFeature = false),
            permissions = readyPermissions,
        )
        assertFalse(plan.canScan)
        assertFalse(plan.canAdvertise)
        assertTrue(plan.blocks.contains(DiscoveryBlock.BleHardwareMissing))
    }
}
