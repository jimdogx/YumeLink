package com.spencehouse.logue.ui.model

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.spencehouse.logue.service.AuthService
import com.spencehouse.logue.service.schedule.ClimateTimeSlot
import com.spencehouse.logue.service.schedule.ScheduledClimateManager
import com.spencehouse.logue.service.schedule.WeeklyClimateSchedule
import com.spencehouse.logue.service.schedule.getNextEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Calendar
import javax.inject.Inject

data class ScheduledClimateUiState(
    val vin: String = "",
    val vehicleName: String = "",
    val isClarityPhev: Boolean = true,
    val useCelsius: Boolean = false,
    val schedule: WeeklyClimateSchedule = WeeklyClimateSchedule(),
    val nextEventSummary: String? = null,
    val showTimePicker: Boolean = false,
    val activePickerDayOfWeek: Int? = null,
    val activePickerSlotId: Int? = null,
    val pickerHour: Int = 7,
    val pickerMinute: Int = 30,
    val saveSuccess: Boolean = false,
)

@HiltViewModel
class ScheduledClimateViewModel @Inject constructor(
    private val authService: AuthService,
    private val scheduledClimateManager: ScheduledClimateManager,
) : ViewModel() {

    var uiState by mutableStateOf(ScheduledClimateUiState())
        private set

    init {
        loadCurrentVehicleSchedule()
    }

    fun loadCurrentVehicleSchedule() {
        val vin = authService.selectedVin ?: authService.sessionManager.vin.orEmpty()
        val vehicle = authService.getSelectedVehicle(vin)
        val name = vehicle?.aliasName?.takeIf { it.isNotEmpty() }
            ?: vehicle?.modelCode?.ifEmpty { "Clarity PHEV" }
            ?: "Clarity PHEV"

        val isClarity = vehicle?.modelCode?.uppercase()?.contains("CLARITY") == true ||
            vehicle?.modelCode?.uppercase()?.contains("PHEV") == true ||
            name.uppercase().contains("CLARITY")

        val schedule = scheduledClimateManager.getSchedule(vin).copy(vin = vin)
        val summary = scheduledClimateManager.getScheduleSummary(vin)

        uiState = uiState.copy(
            vin = vin,
            vehicleName = name,
            isClarityPhev = isClarity,
            useCelsius = authService.sessionManager.useCelsius,
            schedule = schedule,
            nextEventSummary = summary,
        )
    }

    fun setScheduleEnabled(enabled: Boolean) {
        val updated = uiState.schedule.copy(isScheduleEnabled = enabled)
        uiState = uiState.copy(
            schedule = updated,
            nextEventSummary = if (enabled) updated.getNextEvent()?.let { scheduledClimateManager.getScheduleSummary(uiState.vin) } else null,
        )
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        val updated = uiState.schedule.copy(notificationsEnabled = enabled)
        uiState = uiState.copy(schedule = updated)
    }

    fun adjustTargetTemperature(delta: Int) {
        val updated = if (uiState.useCelsius) {
            val nextTemp = (uiState.schedule.targetTemperatureCelsius + delta).coerceIn(15, 30)
            uiState.schedule.copy(targetTemperatureCelsius = nextTemp)
        } else {
            val nextTemp = (uiState.schedule.targetTemperatureFahrenheit + delta).coerceIn(60, 85)
            uiState.schedule.copy(targetTemperatureFahrenheit = nextTemp)
        }
        uiState = uiState.copy(schedule = updated)
    }

    fun setDayEnabled(dayOfWeek: Int, enabled: Boolean) {
        val updatedDays = uiState.schedule.days.map { day ->
            if (day.dayOfWeek == dayOfWeek) {
                val updatedSlots = if (enabled && day.slots.none { it.enabled } && day.slots.isNotEmpty()) {
                    day.slots.mapIndexed { index, slot ->
                        if (index == 0) slot.copy(enabled = true) else slot
                    }
                } else {
                    day.slots
                }
                day.copy(enabled = enabled, slots = updatedSlots)
            } else {
                day
            }
        }
        val updatedSchedule = uiState.schedule.copy(days = updatedDays)
        uiState = uiState.copy(schedule = updatedSchedule)
    }

    fun setSlotEnabled(dayOfWeek: Int, slotId: Int, enabled: Boolean) {
        val updatedDays = uiState.schedule.days.map { day ->
            if (day.dayOfWeek == dayOfWeek) {
                val updatedSlots = day.slots.map { slot ->
                    if (slot.id == slotId) slot.copy(enabled = enabled) else slot
                }
                day.copy(slots = updatedSlots)
            } else {
                day
            }
        }
        uiState = uiState.copy(schedule = uiState.schedule.copy(days = updatedDays))
    }

    fun openTimePicker(dayOfWeek: Int, slotId: Int) {
        val day = uiState.schedule.days.find { it.dayOfWeek == dayOfWeek } ?: return
        val slot = day.slots.find { it.id == slotId } ?: return

        // If user tapped a disabled slot box, enable it right away so they can edit it
        val updatedDays = if (!slot.enabled) {
            uiState.schedule.days.map { d ->
                if (d.dayOfWeek == dayOfWeek) {
                    d.copy(
                        slots = d.slots.map { s ->
                            if (s.id == slotId) s.copy(enabled = true) else s
                        }
                    )
                } else {
                    d
                }
            }
        } else {
            uiState.schedule.days
        }

        uiState = uiState.copy(
            schedule = uiState.schedule.copy(days = updatedDays),
            showTimePicker = true,
            activePickerDayOfWeek = dayOfWeek,
            activePickerSlotId = slotId,
            pickerHour = slot.hour,
            pickerMinute = slot.minute,
        )
    }

    fun dismissTimePicker() {
        uiState = uiState.copy(showTimePicker = false)
    }

    fun confirmTimePicker(hour: Int, minute: Int) {
        val dayOfWeek = uiState.activePickerDayOfWeek ?: return
        val slotId = uiState.activePickerSlotId ?: return

        val updatedDays = uiState.schedule.days.map { day ->
            if (day.dayOfWeek == dayOfWeek) {
                val updatedSlots = day.slots.map { slot ->
                    if (slot.id == slotId) {
                        slot.copy(hour = hour, minute = minute, enabled = true)
                    } else {
                        slot
                    }
                }
                day.copy(slots = updatedSlots)
            } else {
                day
            }
        }

        uiState = uiState.copy(
            schedule = uiState.schedule.copy(days = updatedDays),
            showTimePicker = false,
        )
    }

    fun addTimeSlot(dayOfWeek: Int) {
        val day = uiState.schedule.days.find { it.dayOfWeek == dayOfWeek } ?: return
        if (day.slots.size >= 4) return

        val nextId = (day.slots.maxOfOrNull { it.id } ?: 0) + 1
        val defaultHour = when (day.slots.size) {
            0 -> 7
            1 -> 17
            2 -> 12
            else -> 20
        }
        val newSlot = ClimateTimeSlot(
            id = nextId,
            hour = defaultHour,
            minute = 0,
            enabled = true,
        )

        val updatedDays = uiState.schedule.days.map { d ->
            if (d.dayOfWeek == dayOfWeek) {
                d.copy(slots = d.slots + newSlot)
            } else {
                d
            }
        }

        uiState = uiState.copy(
            schedule = uiState.schedule.copy(days = updatedDays),
            showTimePicker = true,
            activePickerDayOfWeek = dayOfWeek,
            activePickerSlotId = nextId,
            pickerHour = newSlot.hour,
            pickerMinute = newSlot.minute,
        )
    }

    fun removeTimeSlot(dayOfWeek: Int, slotId: Int) {
        val updatedDays = uiState.schedule.days.map { day ->
            if (day.dayOfWeek == dayOfWeek && day.slots.size > 2) {
                day.copy(slots = day.slots.filterNot { it.id == slotId })
            } else {
                day
            }
        }
        uiState = uiState.copy(schedule = uiState.schedule.copy(days = updatedDays))
    }

    fun applyPreset(preset: SchedulePreset) {
        val currentDays = uiState.schedule.days
        val updatedDays = when (preset) {
            SchedulePreset.ALL_DAYS -> currentDays.map { it.copy(enabled = true) }
            SchedulePreset.WEEKDAYS -> currentDays.map { day ->
                val isWeekday = day.dayOfWeek in Calendar.MONDAY..Calendar.FRIDAY
                day.copy(enabled = isWeekday)
            }
            SchedulePreset.WEEKENDS -> currentDays.map { day ->
                val isWeekend = day.dayOfWeek == Calendar.SUNDAY || day.dayOfWeek == Calendar.SATURDAY
                day.copy(enabled = isWeekend)
            }
            SchedulePreset.COPY_SUNDAY_TO_ALL -> {
                val sun = currentDays.find { it.dayOfWeek == Calendar.SUNDAY }
                if (sun != null) {
                    currentDays.map { day ->
                        day.copy(
                            enabled = true,
                            slots = sun.slots.map { it.copy() },
                        )
                    }
                } else {
                    currentDays.map { it.copy(enabled = true) }
                }
            }
        }

        uiState = uiState.copy(
            schedule = uiState.schedule.copy(
                isScheduleEnabled = true,
                days = updatedDays,
            ),
        )
    }

    fun saveSchedule() {
        if (uiState.vin.isEmpty()) return
        scheduledClimateManager.saveSchedule(uiState.schedule)
        val summary = scheduledClimateManager.getScheduleSummary(uiState.vin)
        uiState = uiState.copy(
            nextEventSummary = summary,
            saveSuccess = true,
        )
    }

    fun resetSaveSuccess() {
        uiState = uiState.copy(saveSuccess = false)
    }
}
