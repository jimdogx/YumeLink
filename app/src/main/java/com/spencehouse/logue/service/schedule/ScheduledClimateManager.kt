package com.spencehouse.logue.service.schedule

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.glance.appwidget.updateAll
import com.spencehouse.logue.MainActivity
import com.spencehouse.logue.R
import com.spencehouse.logue.service.AuthService
import com.spencehouse.logue.service.SessionManager
import com.spencehouse.logue.service.VehicleService
import com.spencehouse.logue.widget.ClimateWidget
import com.spencehouse.logue.widget.CommandsWidget
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Calendar
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScheduledClimateManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val sessionManager: SessionManager,
    private val vehicleService: VehicleService,
    private val authService: AuthService,
    private val json: Json,
) {
    companion object {
        private const val TAG = "ScheduledClimateManager"
        private const val NOTIFICATION_CHANNEL_ID = "vehicle_status"
        private const val NOTIFICATION_ID = 1003
    }

    private fun requestCodeForVin(vin: String): Int =
        ((vin.hashCode() xor 0x5A5A5A5A) and 0x7FFFFFFF)

    fun getSchedule(vin: String): WeeklyClimateSchedule {
        val jsonStr = sessionManager.getWeeklyClimateScheduleJson(vin) ?: return WeeklyClimateSchedule(
            vin = vin,
            isScheduleEnabled = false,
            days = defaultWeeklyClimateSchedule(),
        )
        return try {
            json.decodeFromString<WeeklyClimateSchedule>(jsonStr)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode climate schedule for $vin, returning default", e)
            WeeklyClimateSchedule(vin = vin, isScheduleEnabled = false, days = defaultWeeklyClimateSchedule())
        }
    }

    fun saveSchedule(schedule: WeeklyClimateSchedule) {
        Log.i(TAG, "Saving climate schedule for VIN ${schedule.vin}. Enabled: ${schedule.isScheduleEnabled}")
        val jsonStr = json.encodeToString(schedule)
        sessionManager.saveWeeklyClimateScheduleJson(schedule.vin, jsonStr)
        scheduleNextAlarm(schedule.vin)
    }

    fun cancelSchedule(vin: String) {
        Log.i(TAG, "Cancelling scheduled climate alarm for VIN $vin")
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ScheduledClimateReceiver::class.java).apply {
            action = ScheduledClimateReceiver.ACTION_SCHEDULED_CLIMATE
            putExtra(ScheduledClimateReceiver.EXTRA_VIN, vin)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCodeForVin(vin),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        alarmManager.cancel(pendingIntent)
    }

    fun scheduleNextAlarm(vin: String) {
        val schedule = getSchedule(vin)
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        val intent = Intent(context, ScheduledClimateReceiver::class.java).apply {
            action = ScheduledClimateReceiver.ACTION_SCHEDULED_CLIMATE
            putExtra(ScheduledClimateReceiver.EXTRA_VIN, vin)
        }
        val requestCode = requestCodeForVin(vin)
        val basePendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        if (!schedule.isScheduleEnabled) {
            Log.d(TAG, "Scheduled climate is disabled for $vin. Cancelling any pending alarm.")
            alarmManager.cancel(basePendingIntent)
            return
        }

        val nextEvent = schedule.getNextEvent()
        if (nextEvent == null) {
            Log.d(TAG, "No upcoming scheduled climate events found for $vin. Cancelling alarm.")
            alarmManager.cancel(basePendingIntent)
            return
        }

        val updatedPendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        Log.i(
            TAG,
            "Scheduling next climate wakeup for $vin: START (slot ${nextEvent.slotId}) at ${Date(nextEvent.triggerTimeMillis)} (in ${(nextEvent.triggerTimeMillis - System.currentTimeMillis()) / 1000}s)",
        )

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        nextEvent.triggerTimeMillis,
                        updatedPendingIntent,
                    )
                } else {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        nextEvent.triggerTimeMillis,
                        updatedPendingIntent,
                    )
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    nextEvent.triggerTimeMillis,
                    updatedPendingIntent,
                )
            } else {
                alarmManager.setExact(
                    AlarmManager.RTC_WAKEUP,
                    nextEvent.triggerTimeMillis,
                    updatedPendingIntent,
                )
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException setting exact climate alarm, falling back to inexact alarm", e)
            alarmManager.set(
                AlarmManager.RTC_WAKEUP,
                nextEvent.triggerTimeMillis,
                updatedPendingIntent,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Exception scheduling climate alarm", e)
        }
    }

    suspend fun executeScheduledEvent(vin: String): Result<String> {
        val vehicle = vehicleService.resolveVehicle(vin)
        val vehicleName = vehicle.aliasName?.takeIf { it.isNotEmpty() } ?: vehicle.modelCode.ifEmpty { "Clarity PHEV" }
        Log.i(TAG, "Executing scheduled climate start event for $vehicleName ($vin)")

        if (authService.getSelectedVehicle(vin) == null) {
            Log.d(TAG, "Selected vehicle metadata not in memory, performing silent login")
            authService.login()
        }

        val schedule = getSchedule(vin)
        val temperature = if (sessionManager.useCelsius) {
            schedule.targetTemperatureCelsius
        } else {
            schedule.targetTemperatureFahrenheit
        }
        val pin = sessionManager.pin.orEmpty()

        val result = vehicleService.startClimate(vehicle, pin, temperature)

        result.onSuccess {
            Log.i(TAG, "Scheduled climate start successfully executed for $vin: $it")
            sessionManager.cachedClimateStatus = "ON"
            if (schedule.notificationsEnabled) {
                sendNotification(isSuccess = true, vehicleName = vehicleName)
            }
        }.onFailure {
            Log.e(TAG, "Scheduled climate start failed for $vin", it)
            if (schedule.notificationsEnabled) {
                sendNotification(
                    isSuccess = false,
                    errorMessage = it.message,
                    vehicleName = vehicleName,
                )
            }
        }

        // Re-schedule for the next upcoming event
        scheduleNextAlarm(vin)

        // Refresh widgets
        try {
            ClimateWidget().updateAll(context)
            CommandsWidget().updateAll(context)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update widgets after scheduled climate start", e)
        }

        return result
    }

    fun getScheduleSummary(vin: String): String? {
        val schedule = getSchedule(vin)
        if (!schedule.isScheduleEnabled) return null
        val nextEvent = schedule.getNextEvent() ?: return "Active (no times enabled)"
        val cal = Calendar.getInstance().apply { timeInMillis = nextEvent.triggerTimeMillis }
        val timeStr = android.text.format.DateFormat.getTimeFormat(context).format(cal.time)

        val nowCal = Calendar.getInstance()
        val isToday = nowCal.get(Calendar.YEAR) == cal.get(Calendar.YEAR) &&
                nowCal.get(Calendar.DAY_OF_YEAR) == cal.get(Calendar.DAY_OF_YEAR)

        val tomorrowCal = (nowCal.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }
        val isTomorrow = tomorrowCal.get(Calendar.YEAR) == cal.get(Calendar.YEAR) &&
                tomorrowCal.get(Calendar.DAY_OF_YEAR) == cal.get(Calendar.DAY_OF_YEAR)

        val dayLabel = when {
            isToday -> "Today"
            isTomorrow -> "Tomorrow"
            else -> getShortDayName(cal.get(Calendar.DAY_OF_WEEK))
        }

        return "Next: $dayLabel at $timeStr (Start)"
    }

    private fun sendNotification(
        isSuccess: Boolean,
        errorMessage: String? = null,
        vehicleName: String = "Vehicle",
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "POST_NOTIFICATIONS permission not granted, skipping notification")
                return
            }
        }

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val title = if (isSuccess) {
            "Scheduled Climate Started"
        } else {
            "Scheduled Climate Failed"
        }
        val message = if (isSuccess) {
            "Successfully sent start climate command for $vehicleName."
        } else {
            "Failed to start climate for $vehicleName: ${errorMessage ?: "Unknown error"}"
        }

        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_thermostat)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }
}
