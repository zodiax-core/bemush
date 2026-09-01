package com.campusmesh.mesh

import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MeshMetricsTracker @Inject constructor() {
    private val packetsForwardedCount = AtomicLong(0L)
    private val duplicatesDiscardedCount = AtomicLong(0L)
    private val hopCountSum = AtomicLong(0L)
    private val hopCountSamples = AtomicLong(0L)
    private val deliveryTimeSum = AtomicLong(0L)
    private val deliveryTimeSamples = AtomicLong(0L)
    private val expiredCount = AtomicLong(0L)

    fun recordForwarded() {
        packetsForwardedCount.incrementAndGet()
    }

    fun recordDuplicateDiscarded() {
        duplicatesDiscardedCount.incrementAndGet()
    }

    fun recordHopCount(hops: Int) {
        if (hops >= 0) {
            hopCountSum.addAndGet(hops.toLong())
            hopCountSamples.incrementAndGet()
        }
    }

    fun recordDelivery(createdAt: Long, deliveredAt: Long) {
        if (deliveredAt > createdAt) {
            deliveryTimeSum.addAndGet(deliveredAt - createdAt)
            deliveryTimeSamples.incrementAndGet()
        }
    }

    fun recordExpired(count: Int = 1) {
        expiredCount.addAndGet(count.toLong())
    }

    fun getPacketsForwarded(): Long = packetsForwardedCount.get()
    fun getDuplicatesDiscarded(): Long = duplicatesDiscardedCount.get()
    fun getAverageHopCount(): Float {
        val samples = hopCountSamples.get()
        return if (samples > 0) hopCountSum.get().toFloat() / samples else 0f
    }
    fun getAverageDeliveryTimeMs(): Long {
        val samples = deliveryTimeSamples.get()
        return if (samples > 0) deliveryTimeSum.get() / samples else 0L
    }
    fun getExpiredCount(): Long = expiredCount.get()
}
