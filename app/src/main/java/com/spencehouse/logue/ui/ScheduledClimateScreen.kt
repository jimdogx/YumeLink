package com.spencehouse.logue.ui

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.spencehouse.logue.service.schedule.ClimateDaySchedule
import com.spencehouse.logue.service.schedule.ClimateTimeSlot
import com.spencehouse.logue.service.schedule.getDayName
import com.spencehouse.logue.service.schedule.getShortDayName
import com.spencehouse.logue.ui.model.SchedulePreset
import com.spencehouse.logue.ui.model.ScheduledClimateViewModel
import java.util.Calendar

private fun formatClimateTime(context: Context, hour: Int, minute: Int): String {
    val cal = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
    }
    return android.text.format.DateFormat.getTimeFormat(context).format(cal.time)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduledClimateScreen(
    onNavigateBack: () -> Unit,
    viewModel: ScheduledClimateViewModel = hiltViewModel(),
) {
    val uiState = viewModel.uiState
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val snackbarHostState = remember { SnackbarHostState() }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { isGranted ->
        viewModel.setNotificationsEnabled(isGranted)
    }

    val alarmManager = remember { context.getSystemService(Context.ALARM_SERVICE) as AlarmManager }
    var canScheduleExact by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                alarmManager.canScheduleExactAlarms()
            } else {
                true
            },
        )
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    canScheduleExact = alarmManager.canScheduleExactAlarms()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(uiState.saveSuccess) {
        if (uiState.saveSuccess) {
            snackbarHostState.showSnackbar("Climate schedule saved successfully!")
            viewModel.resetSaveSuccess()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Schedule Climate",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        if (uiState.vehicleName.isNotEmpty()) {
                            Text(
                                text = uiState.vehicleName,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                actions = {
                    TextButton(
                        onClick = { viewModel.saveSchedule() },
                        enabled = uiState.vin.isNotEmpty(),
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("SAVE", fontWeight = FontWeight.Bold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
        ) {
            // Alarm Permission Banner (if Android 12+ and not granted)
            if (!canScheduleExact) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                        ),
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    "Exact Alarm Permission Needed",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                "To ensure your vehicle starts climate at the exact minute while your phone is asleep, please allow 'Alarms & reminders' for Logue in system settings.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Button(
                                onClick = {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                        val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                                            data = Uri.parse("package:${context.packageName}")
                                        }
                                        context.startActivity(intent)
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                    contentColor = MaterialTheme.colorScheme.onError,
                                ),
                            ) {
                                Text("Open Settings")
                            }
                        }
                    }
                }
            }

            // Master Switch & Status Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(
                        containerColor = if (uiState.schedule.isScheduleEnabled) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                    ),
                    border = if (uiState.schedule.isScheduleEnabled) {
                        BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
                    } else null,
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (uiState.schedule.isScheduleEnabled) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)
                                            },
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        Icons.Default.Schedule,
                                        contentDescription = null,
                                        tint = if (uiState.schedule.isScheduleEnabled) {
                                            MaterialTheme.colorScheme.onPrimary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        "Enable Schedule",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    Text(
                                        if (uiState.schedule.isScheduleEnabled) "Automated climate is active" else "Automated climate is paused",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }

                            Switch(
                                checked = uiState.schedule.isScheduleEnabled,
                                onCheckedChange = { viewModel.setScheduleEnabled(it) },
                            )
                        }

                        if (uiState.schedule.isScheduleEnabled && !uiState.nextEventSummary.isNullOrEmpty()) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        Icons.Default.AccessTime,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = uiState.nextEventSummary ?: "",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Command Notifications Toggle Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(
                        containerColor = if (uiState.schedule.notificationsEnabled) {
                            MaterialTheme.colorScheme.surfaceVariant
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        },
                    ),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (uiState.schedule.notificationsEnabled) {
                                            MaterialTheme.colorScheme.secondaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)
                                        },
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    if (uiState.schedule.notificationsEnabled) Icons.Default.NotificationsActive else Icons.Default.NotificationsOff,
                                    contentDescription = null,
                                    tint = if (uiState.schedule.notificationsEnabled) {
                                        MaterialTheme.colorScheme.onSecondaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    "Command Notifications",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    if (uiState.schedule.notificationsEnabled) {
                                        "Notify when Start Climate commands are sent"
                                    } else {
                                        "Command notifications disabled"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }

                        Switch(
                            checked = uiState.schedule.notificationsEnabled,
                            onCheckedChange = { enable ->
                                if (enable) {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                                    ) {
                                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    } else {
                                        viewModel.setNotificationsEnabled(true)
                                    }
                                } else {
                                    viewModel.setNotificationsEnabled(false)
                                }
                            },
                        )
                    }
                }
            }

            // Target Temperature Card (only for vehicles that support target temperature in startClimate)
            if (!uiState.isClarityPhev) {
                item {
                    val tempValue = if (uiState.useCelsius) {
                        uiState.schedule.targetTemperatureCelsius
                    } else {
                        uiState.schedule.targetTemperatureFahrenheit
                    }
                    val unitSuffix = if (uiState.useCelsius) "°C" else "°F"

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.large,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primaryContainer),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        Icons.Default.Thermostat,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        "Target Temperature",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    Text(
                                        "Temperature for scheduled starts",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { viewModel.adjustTargetTemperature(-1) }) {
                                    Icon(Icons.Default.Remove, contentDescription = "Decrease temperature")
                                }
                                Text(
                                    text = "$tempValue$unitSuffix",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                )
                                IconButton(onClick = { viewModel.adjustTargetTemperature(1) }) {
                                    Icon(Icons.Default.Add, contentDescription = "Increase temperature")
                                }
                            }
                        }
                    }
                }
            }

            // Quick Presets
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            "QUICK PRESETS",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            SuggestionChip(
                                onClick = { viewModel.applyPreset(SchedulePreset.WEEKDAYS) },
                                label = { Text("Weekdays") },
                                modifier = Modifier.weight(1f),
                            )
                            SuggestionChip(
                                onClick = { viewModel.applyPreset(SchedulePreset.WEEKENDS) },
                                label = { Text("Weekends") },
                                modifier = Modifier.weight(1f),
                            )
                            SuggestionChip(
                                onClick = { viewModel.applyPreset(SchedulePreset.ALL_DAYS) },
                                label = { Text("All 7 Days") },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        OutlinedButton(
                            onClick = { viewModel.applyPreset(SchedulePreset.COPY_SUNDAY_TO_ALL) },
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Copy Sunday to All Days (Enables All)", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }

            item {
                Text(
                    "WEEKLY SCHEDULE",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                )
            }

            // 7 Days of the Week
            items(uiState.schedule.days, key = { it.dayOfWeek }) { day ->
                ClimateDayScheduleCard(
                    day = day,
                    context = context,
                    onToggleEnabled = { viewModel.setDayEnabled(day.dayOfWeek, it) },
                    onToggleSlotEnabled = { slotId, enabled -> viewModel.setSlotEnabled(day.dayOfWeek, slotId, enabled) },
                    onEditSlotTime = { slotId -> viewModel.openTimePicker(day.dayOfWeek, slotId) },
                    onAddSlot = { viewModel.addTimeSlot(day.dayOfWeek) },
                    onRemoveSlot = { slotId -> viewModel.removeTimeSlot(day.dayOfWeek, slotId) },
                )
            }

            // Bottom Save Button
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { viewModel.saveSchedule() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Icon(Icons.Default.Check, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("SAVE CLIMATE SCHEDULE", fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    // Material 3 TimePicker Dialog
    if (uiState.showTimePicker) {
        ClimateTimePickerDialog(
            title = "Set Start Climate Time",
            initialHour = uiState.pickerHour,
            initialMinute = uiState.pickerMinute,
            onDismiss = { viewModel.dismissTimePicker() },
            onConfirm = { h, m -> viewModel.confirmTimePicker(h, m) },
        )
    }
}

@Composable
private fun ClimateDayScheduleCard(
    day: ClimateDaySchedule,
    context: Context,
    onToggleEnabled: (Boolean) -> Unit,
    onToggleSlotEnabled: (Int, Boolean) -> Unit,
    onEditSlotTime: (Int) -> Unit,
    onAddSlot: () -> Unit,
    onRemoveSlot: (Int) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = if (day.enabled) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
        ),
        border = BorderStroke(
            1.dp,
            if (day.enabled) MaterialTheme.colorScheme.outlineVariant else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
        ),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Day Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(
                                if (day.enabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = getShortDayName(day.dayOfWeek).uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (day.enabled) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = getDayName(day.dayOfWeek),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (day.enabled) FontWeight.Bold else FontWeight.Normal,
                        color = if (day.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Switch(
                    checked = day.enabled,
                    onCheckedChange = onToggleEnabled,
                )
            }

            if (day.enabled) {
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Spacer(modifier = Modifier.height(12.dp))

                // Render time slots in rows of 2 (matching Scheduled Charging's 2-column look)
                val rows = day.slots.withIndex().chunked(2)
                rows.forEachIndexed { rowIndex, rowSlots ->
                    if (rowIndex > 0) {
                        Spacer(modifier = Modifier.height(10.dp))
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        rowSlots.forEach { (slotIndex, slot) ->
                            ClimateTimeSlotBox(
                                slot = slot,
                                slotNumber = slotIndex + 1,
                                canRemove = slotIndex >= 2,
                                context = context,
                                modifier = Modifier.weight(1f),
                                onToggleEnabled = { onToggleSlotEnabled(slot.id, it) },
                                onEditTime = { onEditSlotTime(slot.id) },
                                onRemove = { onRemoveSlot(slot.id) },
                            )
                        }
                        if (rowSlots.size == 1) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Summary Info & Optional "+ Add Time" button
                val activeSlots = day.slots.filter { it.enabled }.sortedBy { it.hour * 60 + it.minute }
                val summaryInfo = when {
                    activeSlots.isEmpty() -> "No start times enabled for this day"
                    activeSlots.size == 1 -> "Starts climate at ${formatClimateTime(context, activeSlots[0].hour, activeSlots[0].minute)}"
                    else -> "Starts climate at ${activeSlots.joinToString(", ") { formatClimateTime(context, it.hour, it.minute) }}"
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = summaryInfo,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    if (day.slots.size < 4) {
                        TextButton(
                            onClick = onAddSlot,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            modifier = Modifier.height(28.dp),
                        ) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = "Add start time",
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Add Time",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            } else {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "No climate start scheduled for this day",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }
        }
    }
}

@Composable
private fun ClimateTimeSlotBox(
    slot: ClimateTimeSlot,
    slotNumber: Int,
    canRemove: Boolean,
    context: Context,
    modifier: Modifier = Modifier,
    onToggleEnabled: (Boolean) -> Unit,
    onEditTime: () -> Unit,
    onRemove: () -> Unit,
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onEditTime),
        color = if (slot.enabled) {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
        },
        border = BorderStroke(
            1.dp,
            if (slot.enabled) MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f),
        ),
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Thermostat,
                        contentDescription = null,
                        tint = if (slot.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        "START $slotNumber",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (slot.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        fontWeight = FontWeight.Bold,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (canRemove) {
                        IconButton(
                            onClick = onRemove,
                            modifier = Modifier.size(20.dp),
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Remove start time",
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    Checkbox(
                        checked = slot.enabled,
                        onCheckedChange = onToggleEnabled,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            if (slot.enabled) {
                Text(
                    text = formatClimateTime(context, slot.hour, slot.minute),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Tap to edit",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            } else {
                Text(
                    text = "Disabled",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
                Text(
                    text = "Tap box to enable",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClimateTimePickerDialog(
    title: String,
    initialHour: Int,
    initialMinute: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int, Int) -> Unit,
) {
    val context = LocalContext.current
    val is24Hour = android.text.format.DateFormat.is24HourFormat(context)
    val timePickerState = rememberTimePickerState(
        initialHour = initialHour,
        initialMinute = initialMinute,
        is24Hour = is24Hour,
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        },
        text = {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                TimePicker(state = timePickerState)
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(timePickerState.hour, timePickerState.minute)
                },
            ) {
                Text("OK", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
