package com.campusmesh.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshStateTest {

    @Test
    fun exposesExpectedStates() {
        assertTrue(MeshState.entries.contains(MeshState.NotStarted))
        assertTrue(MeshState.entries.contains(MeshState.Active))
        assertEquals("NotStarted", MeshState.NotStarted.name)
        assertEquals("Active", MeshState.Active.name)
    }
}
