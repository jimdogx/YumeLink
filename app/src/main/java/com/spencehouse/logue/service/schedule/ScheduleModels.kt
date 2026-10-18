package com.spencehouse.logue.service.schedule

import kotlinx.serialization.Serializable
import java.util.Calendar

@Serializable
enum class ChargeAction {
    START,
    STOP
}

@Serializable
data class DaySchedule(
    val dayOfWeek: Int, // Calendar.SUNDAY (1) through Calendar.SATURDAY (7)
    val enabled: Boolean = false,
    val startHour: Int = 20, // 8:00 PM default
    val startMinute: Int = 0,
    val stopHour: Int = 16,  // 4:00 PM default
    val stopMinute: Int = 0,
    val startEnabled: Boolean = true,
    val stopEnabled: Boolean = true,
)

@Serializable
data class WeeklyChargeSchedule(
    val vin: String = "",
    val isScheduleEnabled: Boolean = false,
    val notificationsEnabled: Boolean = true,
    val days: List<DaySchedule> = defaultWeeklySchedule(),
)

data class ScheduledChargeEvent(
    val action: ChargeAction,
    val triggerTimeMillis: Long,
    val dayOfWeek: Int,
    val targetHour: Int,
    val targetMinute: Int,
)

fun defaultWeeklySchedule(): List<DaySchedule> = listOf(
    DaySchedule(dayOfWeek = Calendar.SUNDAY, enabled = false, startHour = 20, startMinute = 0, stopHour = 16, stopMinute = 0, startEnabled = true, stopEnabled = true),
    DaySchedule(dayOfWeek = Calendar.MONDAY, enabled = false, startHour = 20, startMinute = 0, stopHour = 16, stopMinute = 0, startEnabled = true, stopEnabled = true),
    DaySchedule(dayOfWeek = Calendar.TUESDAY, enabled = false, startHour = 20, startMinute = 0, stopHour = 16, stopMinute = 0, startEnabled = true, stopEnabled = true),
    DaySchedule(dayOfWeek = Calendar.WEDNESDAY, enabled = false, startHour = 20, startMinute = 0, stopHour = 16, stopMinute = 0, startEnabled = true, stopEnabled = true),
    DaySchedule(dayOfWeek = Calendar.THURSDAY, enabled = false, startHour = 20, startMinute = 0, stopHour = 16, stopMinute = 0, startEnabled = true, stopEnabled = true),
    DaySchedule(dayOfWeek = Calendar.FRIDAY, enabled = false, startHour = 20, startMinute = 0, stopHour = 16, stopMinute = 0, startEnabled = true, stopEnabled = true),
    DaySchedule(dayOfWeek = Calendar.SATURDAY, enabled = false, startHour = 20, startMinute = 0, stopHour = 16, stopMinute = 0, startEnabled = true, stopEnabled = true),
)

fun getDayName(dayOfWeek: Int): String = when (dayOfWeek) {
    Calendar.SUNDAY -> "Sunday"
    Calendar.MONDAY -> "Monday"
    Calendar.TUESDAY -> "Tuesday"
    Calendar.WEDNESDAY -> "Wednesday"
    Calendar.THURSDAY -> "Thursday"
    Calendar.FRIDAY -> "Friday"
    Calendar.SATURDAY -> "Saturday"
    else -> "Unknown"
}

fun getShortDayName(dayOfWeek: Int): String = when (dayOfWeek) {
    Calendar.SUNDAY -> "Sun"
    Calendar.MONDAY -> "Mon"
    Calendar.TUESDAY -> "Tue"
    Calendar.WEDNESDAY -> "Wed"
    Calendar.THURSDAY -> "Thu"
    Calendar.FRIDAY -> "Fri"
    Calendar.SATURDAY -> "Sat"
    else -> ""
}

fun formatDurationText(startHour: Int, startMinute: Int, stopHour: Int, stopMinute: Int): String {
    val startTotalMinutes = startHour * 60 + startMinute
    val stopTotalMinutes = stopHour * 60 + stopMinute
    val isNextDay = stopTotalMinutes <= startTotalMinutes
    val durationMinutes = if (isNextDay) {
        (24 * 60 - startTotalMinutes) + stopTotalMinutes
    } else {
        stopTotalMinutes - startTotalMinutes
    }
    val hours = durationMinutes / 60
    val minutes = durationMinutes % 60
    val durationStr = when {
        hours == 0 && minutes == 0 -> "24 hrs"
        minutes == 0 -> "$hours hrs"
        hours == 0 -> "$minutes mins"
        else -> "$hours hrs $minutes mins"
    }
    return if (isNextDay) {
        "$durationStr window · Stops next day"
    } else {
        "$durationStr window · Same day"
    }
}

/**
 * Calculates the next upcoming scheduled charge event (START or STOP).
 * Start and Stop triggers are evaluated independently on each enabled day.
 * Returns null if the schedule is disabled or no enabled days have upcoming events.
 */
fun WeeklyChargeSchedule.getNextEvent(nowMillis: Long = System.currentTimeMillis()): ScheduledChargeEvent? {
    if (!isScheduleEnabled || days.none { it.enabled }) return null

    val candidates = mutableListOf<ScheduledChargeEvent>()
    val nowCal = Calendar.getInstance().apply { timeInMillis = nowMillis }

    // Check each day starting from today (offset 0) through 7 days ahead
    for (offset in 0..7) {
        val checkCal = (nowCal.clone() as Calendar).apply {
            add(Calendar.DAY_OF_YEAR, offset)
        }
        val checkDayOfWeek = checkCal.get(Calendar.DAY_OF_WEEK)
        val daySchedule = days.find { it.dayOfWeek == checkDayOfWeek && it.enabled } ?: continue

        // 1. Independent Stop event for checkDayOfWeek
        if (daySchedule.stopEnabled) {
            val stopCal = (checkCal.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, daySchedule.stopHour)
                set(Calendar.MINUTE, daySchedule.stopMinute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            if (stopCal.timeInMillis > nowMillis) {
                candidates.add(
                    ScheduledChargeEvent(
                        action = ChargeAction.STOP,
                        triggerTimeMillis = stopCal.timeInMillis,
                        dayOfWeek = checkDayOfWeek,
                        targetHour = daySchedule.stopHour,
                        targetMinute = daySchedule.stopMinute,
                    )
                )
            }
        }

        // 2. Independent Start event for checkDayOfWeek
        if (daySchedule.startEnabled) {
            val startCal = (checkCal.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, daySchedule.startHour)
                set(Calendar.MINUTE, daySchedule.startMinute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            if (startCal.timeInMillis > nowMillis) {
                candidates.add(
                    ScheduledChargeEvent(
                        action = ChargeAction.START,
                        triggerTimeMillis = startCal.timeInMillis,
                        dayOfWeek = checkDayOfWeek,
                        targetHour = daySchedule.startHour,
                        targetMinute = daySchedule.startMinute,
                    )
                )
            }
        }
    }

    return candidates.minByOrNull { it.triggerTimeMillis }
}

@Serializable
data class ClimateTimeSlot(
    val id: Int,
    val hour: Int = 7,
    val minute: Int = 30,
    val enabled: Boolean = true,
)

@Serializable
data class ClimateDaySchedule(
    val dayOfWeek: Int, // Calendar.SUNDAY (1) through Calendar.SATURDAY (7)
    val enabled: Boolean = false,
    val slots: List<ClimateTimeSlot> = defaultClimateDaySlots(),
)

@Serializable
data class WeeklyClimateSchedule(
    val vin: String = "",
    val isScheduleEnabled: Boolean = false,
    val notificationsEnabled: Boolean = true,
    val targetTemperatureFahrenheit: Int = 72,
    val targetTemperatureCelsius: Int = 22,
    val days: List<ClimateDaySchedule> = defaultWeeklyClimateSchedule(),
)

data class ScheduledClimateEvent(
    val triggerTimeMillis: Long,
    val dayOfWeek: Int,
    val targetHour: Int,
    val targetMinute: Int,
    val slotId: Int = 1,
)

fun defaultClimateDaySlots(): List<ClimateTimeSlot> = listOf(
    ClimateTimeSlot(id = 1, hour = 7, minute = 30, enabled = true),
    ClimateTimeSlot(id = 2, hour = 17, minute = 0, enabled = false),
)

fun defaultWeeklyClimateSchedule(): List<ClimateDaySchedule> = listOf(
    ClimateDaySchedule(dayOfWeek = Calendar.SUNDAY, enabled = false, slots = defaultClimateDaySlots()),
    ClimateDaySchedule(dayOfWeek = Calendar.MONDAY, enabled = false, slots = defaultClimateDaySlots()),
    ClimateDaySchedule(dayOfWeek = Calendar.TUESDAY, enabled = false, slots = defaultClimateDaySlots()),
    ClimateDaySchedule(dayOfWeek = Calendar.WEDNESDAY, enabled = false, slots = defaultClimateDaySlots()),
    ClimateDaySchedule(dayOfWeek = Calendar.THURSDAY, enabled = false, slots = defaultClimateDaySlots()),
    ClimateDaySchedule(dayOfWeek = Calendar.FRIDAY, enabled = false, slots = defaultClimateDaySlots()),
    ClimateDaySchedule(dayOfWeek = Calendar.SATURDAY, enabled = false, slots = defaultClimateDaySlots()),
)

/**
 * Calculates the next upcoming scheduled climate start event across all enabled days and slots.
 * Returns null if the schedule is disabled or no enabled days have enabled start times.
 */
fun WeeklyClimateSchedule.getNextEvent(nowMillis: Long = System.currentTimeMillis()): ScheduledClimateEvent? {
    if (!isScheduleEnabled || days.none { it.enabled }) return null

    val candidates = mutableListOf<ScheduledClimateEvent>()
    val nowCal = Calendar.getInstance().apply { timeInMillis = nowMillis }

    // Check each day starting from today (offset 0) through 7 days ahead
    for (offset in 0..7) {
        val checkCal = (nowCal.clone() as Calendar).apply {
            add(Calendar.DAY_OF_YEAR, offset)
        }
        val checkDayOfWeek = checkCal.get(Calendar.DAY_OF_WEEK)
        val daySchedule = days.find { it.dayOfWeek == checkDayOfWeek && it.enabled } ?: continue

        for (slot in daySchedule.slots) {
            if (!slot.enabled) continue
            val slotCal = (checkCal.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, slot.hour)
                set(Calendar.MINUTE, slot.minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            if (slotCal.timeInMillis > nowMillis) {
                candidates.add(
                    ScheduledClimateEvent(
                        triggerTimeMillis = slotCal.timeInMillis,
                        dayOfWeek = checkDayOfWeek,
                        targetHour = slot.hour,
                        targetMinute = slot.minute,
                        slotId = slot.id,
                    )
                )
            }
        }
    }

    return candidates.minByOrNull { it.triggerTimeMillis }
}

