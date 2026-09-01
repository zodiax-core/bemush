package com.campusmesh.identity

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stable local node identifier used in BLE advertisements.
 *
 * This is not a user profile. It exists so peers can recognize a phone after
 * BLE address randomization.
 */
@Singleton
class LocalNodeIdStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val nodeId: UUID by lazy {
        synchronized(this) {
            val existing = prefs.getString(KEY_NODE_ID, null)
            if (existing != null) {
                UUID.fromString(existing)
            } else {
                UUID.randomUUID().also { generated ->
                    prefs.edit().putString(KEY_NODE_ID, generated.toString()).apply()
                }
            }
        }
    }

    companion object {
        private const val PREFS_NAME = "campusmesh_identity"
        private const val KEY_NODE_ID = "local_node_id"
    }
}
