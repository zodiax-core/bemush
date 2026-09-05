package com.campusmesh.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.campusmesh.identity.LocalNodeIdStore
import com.campusmesh.permissions.PermissionStatusProvider
import com.campusmesh.platform.EpochClock
import com.campusmesh.platform.LocationStatusProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import timber.log.Timber
import javax.inject.Inject

/**
 * Starts BLE advertising and scanning for other CampusMesh phones.
 *
 * No GATT connections, messaging, or mesh forwarding.
 */
class BleDiscoveryController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val localNodeIdStore: LocalNodeIdStore,
    private val permissions: PermissionStatusProvider,
    private val locationStatusProvider: LocationStatusProvider,
    private val peerRegistry: PeerRegistry,
    private val clock: EpochClock,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)

    private var scanner: BluetoothLeScanner? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanning = false
    private var advertising = false
    private var receiverRegistered = false

    private val _snapshot = MutableStateFlow(newSnapshot())
    val snapshot: StateFlow<DiscoverySnapshot> = _snapshot.asStateFlow()

    private fun newSnapshot(): DiscoverySnapshot {
        val nodeId = localNodeIdStore.nodeId
        return DiscoverySnapshot(
            localNodeId = nodeId,
            localNodeLabel = AdvertisePayload.shortLabel(nodeId),
            wantedRunning = true,
            foreground = false,
            scan = RadioOpState.Idle,
            advertise = RadioOpState.Idle,
            scanDetail = "Not started",
            advertiseDetail = "Not started",
            blocks = emptyList(),
            lastError = null,
            peers = emptyList(),
            nowEpochMs = clock.nowMillis(),
        )
    }

    private val adapterReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                Timber.i("Bluetooth adapter state changed")
                mainHandler.post { syncRadios("adapter-state") }
            }
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            if (result == null) return
            val nodeId = AdvertisePayload.decode(
                result.scanRecord?.getManufacturerSpecificData(CampusMeshBle.MANUFACTURER_ID),
            )
            if (nodeId == null) {
                Timber.v("Ignoring BLE advertisement without CampusMesh payload")
                return
            }
            peerRegistry.upsert(nodeId = nodeId, deviceAddress = result.device.address, rssiDbm = result.rssi, localNodeId = localNodeIdStore.nodeId)
            publishPeers()
        }

        override fun onScanFailed(errorCode: Int) {
            Timber.e("BLE scan failed: %s", scanError(errorCode))
            mainHandler.post {
                scanning = false
                updateSnapshot(
                    scan = RadioOpState.Failed,
                    scanDetail = scanError(errorCode),
                    lastError = scanError(errorCode),
                )
            }
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            Timber.i("BLE advertising started")
            mainHandler.post {
                advertising = true
                updateSnapshot(
                    advertise = RadioOpState.Running,
                    advertiseDetail = "Advertising CampusMesh identity",
                )
            }
        }

        override fun onStartFailure(errorCode: Int) {
            Timber.e("BLE advertise failed: %s", advertiseError(errorCode))
            mainHandler.post {
                advertising = false
                updateSnapshot(
                    advertise = RadioOpState.Failed,
                    advertiseDetail = advertiseError(errorCode),
                    lastError = advertiseError(errorCode),
                )
            }
        }
    }

    fun setForeground(foreground: Boolean) {
        if (_snapshot.value.foreground == foreground) {
            if (foreground) syncRadios("foreground-already")
            return
        }
        updateSnapshot(foreground = foreground)
        syncRadios("foreground-change")
    }

    fun setWantedRunning(wanted: Boolean) {
        updateSnapshot(wantedRunning = wanted)
        if (!wanted) {
            stopRadios()
            peerRegistry.clear()
            updateSnapshot(
                scan = RadioOpState.Idle,
                advertise = RadioOpState.Idle,
                scanDetail = "Stopped",
                advertiseDetail = "Stopped",
                lastError = null,
            )
            publishPeers()
        } else {
            syncRadios("user-start")
        }
    }

    fun refresh() {
        syncRadios("refresh")
    }

    fun tick() {
        peerRegistry.pruneStale()
        publishPeers()
        updateSnapshot(nowEpochMs = clock.nowMillis())
    }

    fun release() {
        setWantedRunning(false)
        unregisterReceiver()
    }

    private fun syncRadios(reason: String) {
        val plan = currentPlan()
        Timber.i(
            "Discovery sync (%s): canScan=%s canAdvertise=%s wanted=%s foreground=%s blocks=%s",
            reason,
            plan.canScan,
            plan.canAdvertise,
            _snapshot.value.wantedRunning,
            _snapshot.value.foreground,
            plan.blocks,
        )
        updateSnapshot(blocks = plan.blocks, lastError = null)

        val shouldRun = _snapshot.value.wantedRunning
        if (!shouldRun) {
            stopRadios()
            updateSnapshot(
                scan = RadioOpState.Idle,
                advertise = RadioOpState.Idle,
                scanDetail = "Stopped",
                advertiseDetail = "Stopped",
            )
            return
        }

        if (plan.canScan) {
            startScan()
        } else {
            stopScan()
            updateSnapshot(
                scan = RadioOpState.Blocked,
                scanDetail = scanBlockMessage(plan),
            )
        }

        if (plan.canAdvertise) {
            startAdvertise()
        } else {
            stopAdvertise()
            updateSnapshot(
                advertise = RadioOpState.Blocked,
                advertiseDetail = advertiseBlockMessage(plan),
            )
        }
    }

    private fun currentPlan(): DiscoveryPlan {
        val adapter = bluetoothManager?.adapter
        val hardware = DiscoveryHardware(
            bluetoothFeature = context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH),
            bleFeature = context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE),
            adapterPresent = adapter != null,
            advertiserPresent = try {
                adapter?.bluetoothLeAdvertiser != null
            } catch (_: SecurityException) {
                false
            },
            bluetoothEnabled = try {
                adapter?.isEnabled
            } catch (_: SecurityException) {
                null
            },
            locationEnabled = locationStatusProvider.isLocationEnabled(),
        )
        val flags = DiscoveryPermissionFlags(
            scanGranted = permissions.scanGranted(),
            advertiseGranted = permissions.advertiseGranted(),
            connectGranted = permissions.connectGranted(),
        )
        return DiscoveryPreconditions.plan(Build.VERSION.SDK_INT, hardware, flags)
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        if (scanning) {
            updateSnapshot(scan = RadioOpState.Running, scanDetail = "Scanning for CampusMesh advertisements")
            return
        }
        val adapter = bluetoothManager?.adapter
        val leScanner = adapter?.bluetoothLeScanner
        if (leScanner == null) {
            updateSnapshot(scan = RadioOpState.Failed, scanDetail = "BLE scanner unavailable")
            return
        }
        updateSnapshot(scan = RadioOpState.Starting, scanDetail = "Starting scan")
        val filter = ScanFilter.Builder()
            .setManufacturerData(
                CampusMeshBle.MANUFACTURER_ID,
                AdvertisePayload.filterPrefix,
                AdvertisePayload.filterMask,
            )
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .build()
        try {
            registerReceiver()
            leScanner.startScan(listOf(filter), settings, scanCallback)
            scanner = leScanner
            scanning = true
            updateSnapshot(scan = RadioOpState.Running, scanDetail = "Scanning for CampusMesh advertisements")
            Timber.i("BLE scan started")
        } catch (security: SecurityException) {
            scanning = false
            updateSnapshot(
                scan = RadioOpState.Failed,
                scanDetail = "Scan permission rejected by the OS",
                lastError = security.message,
            )
        } catch (error: Exception) {
            scanning = false
            updateSnapshot(
                scan = RadioOpState.Failed,
                scanDetail = error.message ?: "Scan failed to start",
                lastError = error.message,
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertise() {
        if (advertising) {
            updateSnapshot(advertise = RadioOpState.Running, advertiseDetail = "Advertising CampusMesh identity")
            return
        }
        val adapter = bluetoothManager?.adapter
        val leAdvertiser = adapter?.bluetoothLeAdvertiser
        if (leAdvertiser == null) {
            updateSnapshot(advertise = RadioOpState.Failed, advertiseDetail = "BLE advertiser unavailable")
            return
        }
        updateSnapshot(advertise = RadioOpState.Starting, advertiseDetail = "Starting advertise")
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val payload = AdvertisePayload.encode(localNodeIdStore.nodeId)
        val data = AdvertiseData.Builder()
            .addManufacturerData(CampusMeshBle.MANUFACTURER_ID, payload)
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .build()
        val scanResponse = AdvertiseData.Builder()
            .addServiceUuid(CampusMeshBle.SERVICE_PARCEL_UUID)
            .setIncludeDeviceName(false)
            .build()
        try {
            registerReceiver()
            leAdvertiser.startAdvertising(settings, data, scanResponse, advertiseCallback)
            advertiser = leAdvertiser
            Timber.i("BLE advertise requested, payloadBytes=%d", payload.size)
        } catch (security: SecurityException) {
            advertising = false
            updateSnapshot(
                advertise = RadioOpState.Failed,
                advertiseDetail = "Advertise permission rejected by the OS",
                lastError = security.message,
            )
        } catch (error: Exception) {
            advertising = false
            updateSnapshot(
                advertise = RadioOpState.Failed,
                advertiseDetail = error.message ?: "Advertise failed to start",
                lastError = error.message,
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopRadios() {
        stopScan()
        stopAdvertise()
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (!scanning && scanner == null) return
        try {
            scanner?.stopScan(scanCallback)
        } catch (error: Exception) {
            Timber.w(error, "stopScan")
        }
        scanner = null
        scanning = false
    }

    @SuppressLint("MissingPermission")
    private fun stopAdvertise() {
        if (!advertising && advertiser == null) return
        try {
            advertiser?.stopAdvertising(advertiseCallback)
        } catch (error: Exception) {
            Timber.w(error, "stopAdvertising")
        }
        advertiser = null
        advertising = false
    }

    private fun registerReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        ContextCompat.registerReceiver(context, adapterReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
    }

    private fun unregisterReceiver() {
        if (!receiverRegistered) return
        try {
            context.unregisterReceiver(adapterReceiver)
        } catch (_: IllegalArgumentException) {
            // Already unregistered.
        }
        receiverRegistered = false
    }

    private fun publishPeers() {
        updateSnapshot(peers = peerRegistry.peers.value, nowEpochMs = clock.nowMillis())
    }

    private fun updateSnapshot(
        wantedRunning: Boolean = _snapshot.value.wantedRunning,
        foreground: Boolean = _snapshot.value.foreground,
        scan: RadioOpState = _snapshot.value.scan,
        advertise: RadioOpState = _snapshot.value.advertise,
        scanDetail: String = _snapshot.value.scanDetail,
        advertiseDetail: String = _snapshot.value.advertiseDetail,
        blocks: List<DiscoveryBlock> = _snapshot.value.blocks,
        lastError: String? = _snapshot.value.lastError,
        peers: List<NearbyPeer> = _snapshot.value.peers,
        nowEpochMs: Long = _snapshot.value.nowEpochMs,
    ) {
        _snapshot.update {
            it.copy(
                wantedRunning = wantedRunning,
                foreground = foreground,
                scan = scan,
                advertise = advertise,
                scanDetail = scanDetail,
                advertiseDetail = advertiseDetail,
                blocks = blocks,
                lastError = lastError,
                peers = peers,
                nowEpochMs = nowEpochMs,
            )
        }
    }

    private fun scanBlockMessage(plan: DiscoveryPlan): String {
        return when {
            DiscoveryBlock.BluetoothOff in plan.blocks -> "Bluetooth is off"
            DiscoveryBlock.MissingScanPermission in plan.blocks -> "Bluetooth scan permission is missing"
            DiscoveryBlock.LocationOff in plan.blocks -> "Location must be on for BLE scanning on Android 8–11"
            DiscoveryBlock.BleHardwareMissing in plan.blocks -> "This device does not report Bluetooth LE"
            DiscoveryBlock.BluetoothHardwareMissing in plan.blocks -> "This device does not report Bluetooth"
            DiscoveryBlock.AdapterMissing in plan.blocks -> "Bluetooth adapter is unavailable"
            DiscoveryBlock.BluetoothStateUnknown in plan.blocks -> "Bluetooth state unknown (need connect permission)"
            else -> "Scan blocked"
        }
    }

    private fun advertiseBlockMessage(plan: DiscoveryPlan): String {
        return when {
            DiscoveryBlock.BluetoothOff in plan.blocks -> "Bluetooth is off"
            DiscoveryBlock.MissingAdvertisePermission in plan.blocks -> "Bluetooth advertise permission is missing"
            DiscoveryBlock.AdvertiserMissing in plan.blocks -> "This device cannot BLE advertise"
            DiscoveryBlock.BleHardwareMissing in plan.blocks -> "This device does not report Bluetooth LE"
            DiscoveryBlock.BluetoothHardwareMissing in plan.blocks -> "This device does not report Bluetooth"
            DiscoveryBlock.AdapterMissing in plan.blocks -> "Bluetooth adapter is unavailable"
            DiscoveryBlock.BluetoothStateUnknown in plan.blocks -> "Bluetooth state unknown (need connect permission)"
            else -> "Advertise blocked"
        }
    }

    private fun scanError(code: Int): String = when (code) {
        ScanCallback.SCAN_FAILED_ALREADY_STARTED -> "Scan already started"
        ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "Scan registration failed"
        ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> "Scan feature unsupported"
        ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> "Scan internal error"
        ScanCallback.SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES -> "Scan out of hardware resources"
        else -> "Scan failed ($code)"
    }

    private fun advertiseError(code: Int): String = when (code) {
        AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> "Advertise already started"
        AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> "Advertise payload too large"
        AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "Too many advertisers"
        AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> "Advertise internal error"
        AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "Advertise unsupported on this hardware"
        else -> "Advertise failed ($code)"
    }
}




