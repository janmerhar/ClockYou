package com.bnyro.clock.presentation.screens.alarmpicker

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bnyro.clock.presentation.screens.alarmpicker.components.AlarmPicker
import com.bnyro.clock.presentation.screens.alarmpicker.model.AlarmPickerModel

@Composable
fun AlarmPickerScreen(onNavigateBack: () -> Unit) {
    val viewModel: AlarmPickerModel = viewModel()
    AlarmPicker(
        onCancel = { onNavigateBack.invoke() },
        currentAlarm = viewModel.alarm,
        advanced = viewModel.advanced,
        onDelete = { alarm ->
            viewModel.deleteAlarm(alarm)
            onNavigateBack.invoke()
        },
        onSave = { alarm ->
            if (alarm.id == 0L) {
                // Create New Alarm and enable by default
                viewModel.createAlarm(alarm.copy(enabled = true))
            } else {
                // Update Alarm and enable automatically
                viewModel.updateAlarm(alarm.copy(enabled = true))
            }
            onNavigateBack.invoke()
        })
}
