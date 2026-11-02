package com.bnyro.clock.presentation.screens.alarmpicker.components

import android.text.format.DateFormat
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.bnyro.clock.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationRuleSheet(awayTime: Long?, onDone: (Long?) -> Unit, onCancel: () -> Unit) {
    var enabled by rememberSaveable { mutableStateOf(awayTime != null) }
    val time = rememberTimePickerState(
        initialHour = ((awayTime ?: 36_000_000L) / 3_600_000).toInt(),
        initialMinute = (((awayTime ?: 36_000_000L) / 60_000) % 60).toInt(),
        is24Hour = DateFormat.is24HourFormat(LocalContext.current)
    )
    val enableLabel = stringResource(R.string.loc_enable_rule)
    ModalBottomSheet(onDismissRequest = onCancel, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.loc_rule), style = MaterialTheme.typography.titleLarge)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(enableLabel, Modifier.weight(1f))
                Switch(enabled, { enabled = it }, Modifier.semantics { contentDescription = enableLabel })
            }
            if (enabled) {
                Text(stringResource(R.string.loc_away_time), style = MaterialTheme.typography.titleMedium)
                TimeInput(time)
            }
            Text(stringResource(R.string.loc_rule_explanation), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.loc_home_hint), style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onCancel) { Text(stringResource(android.R.string.cancel)) }
                TextButton(onClick = { onDone(if (enabled) (time.hour * 60 + time.minute) * 60_000L else null) }) {
                    Text(stringResource(R.string.loc_done))
                }
            }
        }
    }
}
