package com.campusmesh.platform

fun interface EpochClock {
    fun nowMillis(): Long
}
