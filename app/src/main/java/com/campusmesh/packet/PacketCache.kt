package com.campusmesh.packet

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PacketCache @Inject constructor() {
    private val seenPacketIds = LinkedHashSet<String>()
    private val maxCacheSize = 1000

    fun hasSeen(packetId: String): Boolean {
        return synchronized(seenPacketIds) {
            seenPacketIds.contains(packetId)
        }
    }

    fun markSeen(packetId: String) {
        synchronized(seenPacketIds) {
            if (seenPacketIds.size >= maxCacheSize) {
                val oldest = seenPacketIds.iterator().next()
                seenPacketIds.remove(oldest)
            }
            seenPacketIds.add(packetId)
        }
    }
}
