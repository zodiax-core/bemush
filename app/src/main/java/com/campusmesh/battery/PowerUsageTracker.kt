package com.campusmesh.battery

import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PowerUsageTracker @Inject constructor() {
    private val scanStartTime = AtomicLong(0L)
    private val scanDurationMs = AtomicLong(0L)
    private val advertisingStartTime = AtomicLong(0L)
    private val advertisingDurationMs = AtomicLong(0L)
    private val connectionCount = AtomicLong(0L)
    private val connectionDurationMs = AtomicLong(0L)

    fun startScan() {
        scanStartTime.set(System.currentTimeMillis())
    }

    fun stopScan() {
        val now = System.currentTimeMillis()
        val start = scanStartTime.get()
        if (start > 0) {
            scanDurationMs.addAndGet(now - start)
        }
        scanStartTime.set(0L)
    }

    fun startAdvertising() {
        advertisingStartTime.set(System.currentTimeMillis())
    }

    fun stopAdvertising() {
        val now = System.currentTimeMillis()
        val start = advertisingStartTime.get()
        if (start > 0) {
            advertisingDurationMs.addAndGet(now - start)
        }
        advertisingStartTime.set(0L)
    }

    fun startConnection() {
        connectionCount.incrementAndGet()
    }

    fun stopConnection() {
        val now = System.currentTimeMillis()
        val start = scanStartTime.get()
        if (start > 0) {
            connectionDurationMs.addAndGet(now - start)
        }
    }

    fun getScanDurationMs(): Long = scanDurationMs.get()
    fun getAdvertisingDurationMs(): Long = advertisingDurationMs.get()
    fun getConnectionCount(): Long = connectionCount.get()
    fun getConnectionDurationMs(): Long = connectionDurationMs.get()
}
