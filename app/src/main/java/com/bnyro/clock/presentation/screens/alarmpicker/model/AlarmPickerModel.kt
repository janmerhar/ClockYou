package com.bnyro.clock.presentation.screens.alarmpicker.model

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.bnyro.clock.App
import com.bnyro.clock.domain.model.Alarm
import com.bnyro.clock.domain.usecase.CreateUpdateDeleteAlarmUseCase
import com.bnyro.clock.navigation.NavRoutes
import com.bnyro.clock.util.AlarmHelper
import kotlinx.coroutines.withContext
import com.bnyro.clock.util.TimeHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class AlarmPickerModel(application: Application, savedStateHandle: SavedStateHandle) :
    AndroidViewModel(application) {
    private val id: Long = savedStateHandle[NavRoutes.AlarmPicker.ALARM_ID] ?: 0L
    val advanced: Boolean = savedStateHandle[NavRoutes.AlarmPicker.ADVANCED] ?: false

    private val alarmRepository = (application as App).container.alarmRepository
    private val createUpdateDeleteAlarmUseCase =
        CreateUpdateDeleteAlarmUseCase(application.applicationContext, alarmRepository)

    var alarm: Alarm

    init {
        alarm = if (id == 0L) {
            Alarm(time = TimeHelper.currentDayMillis)
        } else {
            runBlocking(Dispatchers.IO) {
                alarmRepository.getAlarmById(id)!!
            }
        }
    }

    fun createAlarm(alarm: Alarm) {
        getApplication<App>().applicationScope.launch {
            val saved = createUpdateDeleteAlarmUseCase.createAlarm(alarm)
            withContext(Dispatchers.Main) { AlarmHelper.showAlarmScheduledToast(getApplication(), saved) }
        }
    }

    fun updateAlarm(alarm: Alarm) {
        getApplication<App>().applicationScope.launch {
            val saved = createUpdateDeleteAlarmUseCase.updateAlarm(alarm)
            if (saved != null) withContext(Dispatchers.Main) { AlarmHelper.showAlarmScheduledToast(getApplication(), saved) }
        }
    }

    fun deleteAlarm(alarm: Alarm) {
        getApplication<App>().applicationScope.launch {
            createUpdateDeleteAlarmUseCase.deleteAlarm(alarm)
        }
    }
}
