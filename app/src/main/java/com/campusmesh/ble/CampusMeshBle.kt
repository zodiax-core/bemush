package com.campusmesh.ble

import android.os.ParcelUuid
import java.util.UUID

/**
 * CampusMesh BLE identity for discovery and direct transport.
 */
object CampusMeshBle {
    val SERVICE_UUID: UUID = UUID.fromString("c5e50001-6d65-7368-6361-6d7075730001")
    val SERVICE_PARCEL_UUID: ParcelUuid = ParcelUuid(SERVICE_UUID)

    val TRANSPORT_SERVICE_UUID: UUID = UUID.fromString("c5e50002-6d65-7368-6361-6d7075730001")
    val MESSAGE_CHARACTERISTIC_UUID: UUID = UUID.fromString("c5e50003-6d65-7368-6361-6d7075730001")
    val PUBLIC_KEY_CHARACTERISTIC_UUID: UUID = UUID.fromString("c5e50004-6d65-7368-6361-6d7075730001")

    const val MANUFACTURER_ID: Int = 0xFFFF
}
