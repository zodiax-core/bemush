package com.campusmesh.battery

import org.junit.Assert.assertEquals
import org.junit.Test

class BatteryTest {

    @Test
    fun testPowerUsageTrackerScanDuration() {
        val tracker = PowerUsageTracker()
        tracker.startScan()
        Thread.sleep(100)
        tracker.stopScan()
        assert(tracker.getScanDurationMs() >= 100L)
    }

    @Test
    fun testPowerUsageTrackerAdvertisingDuration() {
        val tracker = PowerUsageTracker()
        tracker.startAdvertising()
        Thread.sleep(100)
        tracker.stopAdvertising()
        assert(tracker.getAdvertisingDurationMs() >= 100L)
    }

    @Test
    fun testPowerUsageTrackerConnectionCount() {
        val tracker = PowerUsageTracker()
        tracker.startConnection()
        tracker.startConnection()
        assertEquals(2L, tracker.getConnectionCount())
    }
}
