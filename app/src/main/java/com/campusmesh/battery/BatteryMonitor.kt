package com.campusmesh.battery

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BatteryMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

    private val _batteryState = MutableStateFlow(BatteryState())
    val batteryState: StateFlow<BatteryState> = _batteryState.asStateFlow()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_BATTERY_CHANGED) {
                updateBatteryState(intent)
            }
        }
    }

    init {
        updateBatteryState()
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    private fun updateBatteryState(intent: Intent? = null) {
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val temperature = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)?.toFloat()?.div(10f) ?: 0f
        val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
        val isCharging = plugged != 0
        val isOnBattery = plugged == 0
        val isLowBattery = level != null && level <= 15

        _batteryState.update {
            it.copy(
                level = level ?: 0,
                temperature = temperature,
                isCharging = isCharging,
                isOnBattery = isOnBattery,
                isLowBattery = isLowBattery,
                lastUpdated = System.currentTimeMillis(),
            )
        }
        Timber.i("Battery: level=%d%%, temp=%.1fC, charging=%s", level, temperature, isCharging)
    }

    fun isBatteryOptimizationNeeded(): Boolean {
        val state = _batteryState.value
        return state.isOnBattery && state.isLowBattery
    }

    fun getScanIntervalMs(): Long {
        return if (isBatteryOptimizationNeeded()) {
            30_000L // 30 seconds when battery is low
        } else {
            10_000L // 10 seconds when charging or battery is good
        }
    }

    fun getAdvertisingIntervalMs(): Long {
        return if (isBatteryOptimizationNeeded()) {
            10_000L // 10 seconds when battery is low
        } else {
            5_000L // 5 seconds when charging or battery is good
        }
    }
}

data class BatteryState(
    val level: Int = 0,
    val temperature: Float = 0f,
    val isCharging: Boolean = false,
    val isOnBattery: Boolean = true,
    val isLowBattery: Boolean = false,
    val lastUpdated: Long = 0L,
)

