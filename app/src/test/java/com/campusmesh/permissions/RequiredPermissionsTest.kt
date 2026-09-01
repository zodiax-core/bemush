package com.campusmesh.permissions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RequiredPermissionsTest {

    @Test
    fun api26RequiresLegacyBluetoothAndLocation() {
        val names = RequiredPermissions.runtimeRequestNames(26).toSet()

        assertTrue(names.contains("android.permission.BLUETOOTH"))
        assertTrue(names.contains("android.permission.BLUETOOTH_ADMIN"))
        assertTrue(names.contains("android.permission.ACCESS_FINE_LOCATION"))
        assertFalse(names.contains("android.permission.BLUETOOTH_SCAN"))
        assertFalse(names.contains("android.permission.POST_NOTIFICATIONS"))
    }

    @Test
    fun api31RequiresNearbyDevicePermissionsNotLocation() {
        val names = RequiredPermissions.runtimeRequestNames(31).toSet()

        assertTrue(names.contains("android.permission.BLUETOOTH_SCAN"))
        assertTrue(names.contains("android.permission.BLUETOOTH_CONNECT"))
        assertTrue(names.contains("android.permission.BLUETOOTH_ADVERTISE"))
        assertFalse(names.contains("android.permission.ACCESS_FINE_LOCATION"))
        assertFalse(names.contains("android.permission.BLUETOOTH"))
        assertFalse(names.contains("android.permission.POST_NOTIFICATIONS"))
    }

    @Test
    fun api33AddsNotifications() {
        val names = RequiredPermissions.runtimeRequestNames(33).toSet()

        assertTrue(names.contains("android.permission.POST_NOTIFICATIONS"))
        assertTrue(names.contains("android.permission.BLUETOOTH_SCAN"))
        assertEquals(4, names.size)
    }

    @Test
    fun notRequiredPermissionsStayOutOfRuntimeRequestList() {
        AppPermission.entries.forEach { permission ->
            if (!permission.isRequiredOn(35)) {
                assertFalse(
                    RequiredPermissions.runtimeRequestNames(35).contains(permission.androidName),
                )
            }
        }
    }
}
