package com.spencehouse.logue.service.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import com.spencehouse.logue.di.ScheduledChargeEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ScheduledClimateReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ScheduledClimateReceiver"
        const val ACTION_SCHEDULED_CLIMATE = "com.spencehouse.logue.action.SCHEDULED_CLIMATE"
        const val EXTRA_VIN = "extra_vin"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null || intent.action != ACTION_SCHEDULED_CLIMATE) return

        val vin = intent.getStringExtra(EXTRA_VIN)

        if (vin.isNullOrEmpty()) {
            Log.e(TAG, "Missing VIN ($vin) for scheduled climate alarm")
            return
        }

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "logue:ScheduledClimateWakeLock",
        ).apply {
            acquire(60_000L) // 60s maximum timeout
        }

        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.i(TAG, "Scheduled climate alarm fired! Executing start climate for VIN: $vin")
                val entryPoint = EntryPointAccessors.fromApplication(
                    context.applicationContext,
                    ScheduledChargeEntryPoint::class.java,
                )
                val manager = entryPoint.scheduledClimateManager()
                manager.executeScheduledEvent(vin)
            } catch (e: Exception) {
                Log.e(TAG, "Error executing scheduled climate alarm", e)
            } finally {
                if (wakeLock.isHeld) {
                    wakeLock.release()
                }
                pendingResult.finish()
            }
        }
    }
}
