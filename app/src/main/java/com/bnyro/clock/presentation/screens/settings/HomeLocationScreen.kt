package com.bnyro.clock.presentation.screens.settings

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.bnyro.clock.App
import com.bnyro.clock.R
import com.bnyro.clock.domain.model.HomeLocation
import com.bnyro.clock.util.location.LocationPreferences
import com.bnyro.clock.util.location.LocationState
import kotlinx.coroutines.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeLocationScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as App
    val tracker = app.locationTracker
    val storage = remember { LocationPreferences() }
    val status by tracker.status.collectAsState()
    var home by remember { mutableStateOf(storage.getHome()) }
    var previewLatitude by rememberSaveable { mutableStateOf<Double?>(null) }
    var previewLongitude by rememberSaveable { mutableStateOf<Double?>(null) }
    var busy by remember { mutableStateOf(false) }
    var capturing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var removeConfirmation by rememberSaveable { mutableStateOf(false) }
    var backgroundExplanation by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var captureJob by remember { mutableStateOf<Job?>(null) }

    fun refresh() {
        app.applicationScope.launch { tracker.refresh(); tracker.updateRegistration() }
    }
    fun capture() {
        captureJob?.cancel()
        captureJob = scope.launch {
            capturing = true; error = null
            try {
                val fix = tracker.captureHome()
                if (fix == null) error = context.getString(R.string.loc_capture_failed)
                else { previewLatitude = fix.latitude; previewLongitude = fix.longitude }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = context.getString(R.string.loc_capture_failed) }
            finally { capturing = false }
        }
    }
    fun persist(newHome: HomeLocation?) {
        busy = true; error = null
        // Application scope finishes the short write even after navigating Back.
        app.applicationScope.launch {
            try {
                if (newHome == null) storage.clearHome() else storage.saveHome(newHome)
                tracker.refresh(); tracker.updateRegistration()
                withContext(Dispatchers.Main) {
                    home = storage.getHome(); previewLatitude = null; previewLongitude = null
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) { error = context.getString(R.string.loc_save_failed) }
            } finally { withContext(Dispatchers.Main) { busy = false } }
        }
    }

    val foregroundPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (tracker.hasForegroundPermission()) capture()
        else { error = null; refresh() }
    }
    val backgroundPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
            if (event == Lifecycle.Event.ON_STOP) captureJob?.cancel()
        }
        owner.lifecycle.addObserver(observer)
        refresh()
        onDispose { owner.lifecycle.removeObserver(observer); captureJob?.cancel() }
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.loc_home_location)) }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.loc_back)) }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(if (home == null) R.string.loc_not_set else R.string.loc_saved), style = MaterialTheme.typography.titleMedium)
            home?.let { Text(stringResource(R.string.loc_coordinates, it.latitude, it.longitude), style = MaterialTheme.typography.bodySmall) }
            Text(stringResource(R.string.loc_area))
            val stateLabel = stringResource(when (status.state) {
                LocationState.HOME -> R.string.loc_state_home
                LocationState.AWAY -> R.string.loc_state_away
                LocationState.UNKNOWN -> R.string.loc_state_unknown
            })
            Text(stringResource(R.string.loc_status, stateLabel))
            val sample = status.sample
            Text(if (sample == null) stringResource(R.string.loc_no_fix) else stringResource(R.string.loc_last_fix,
                android.text.format.DateFormat.format("yyyy-MM-dd HH:mm", sample.timestampMillis).toString(), sample.accuracyMeters), style = MaterialTheme.typography.bodySmall)
            if (!status.foregroundPermission) Text(stringResource(R.string.loc_permission_denied))
            else if (!status.networkAvailable) Text(stringResource(R.string.loc_network_unavailable))
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(enabled = !busy && !capturing, onClick = {
                if (tracker.hasForegroundPermission()) capture()
                else foregroundPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }) { Text(stringResource(if (capturing) R.string.loc_capturing else R.string.loc_use_current)) }
            if (previewLatitude != null && previewLongitude != null) {
                Text(stringResource(R.string.loc_preview, previewLatitude!!, previewLongitude!!))
                Button(enabled = !busy && !capturing, onClick = { persist(HomeLocation(previewLatitude!!, previewLongitude!!)) }) {
                    Text(stringResource(if (home == null) R.string.loc_save_home else R.string.loc_update_home))
                }
            }
            OutlinedButton(onClick = ::refresh, enabled = !busy) { Text(stringResource(R.string.loc_refresh)) }
            if (status.foregroundPermission && !status.backgroundPermission) {
                OutlinedButton(onClick = { backgroundExplanation = true }) { Text(stringResource(R.string.loc_background_access)) }
            }
            if (home != null) TextButton(enabled = !busy, onClick = { removeConfirmation = true }) { Text(stringResource(R.string.loc_remove_home)) }
            Text(stringResource(R.string.loc_rule_explanation), style = MaterialTheme.typography.bodySmall)
        }
    }
    if (removeConfirmation) AlertDialog(onDismissRequest = { removeConfirmation = false },
        title = { Text(stringResource(R.string.loc_remove_home)) }, text = { Text(stringResource(R.string.loc_confirm_remove)) },
        confirmButton = { TextButton(onClick = { removeConfirmation = false; persist(null) }) { Text(stringResource(R.string.loc_remove)) } },
        dismissButton = { TextButton(onClick = { removeConfirmation = false }) { Text(stringResource(android.R.string.cancel)) } })
    if (backgroundExplanation) {
        val optionLabel = if (Build.VERSION.SDK_INT >= 30) context.packageManager.backgroundPermissionOptionLabel.toString() else stringResource(R.string.loc_allow_all_time)
        AlertDialog(onDismissRequest = { backgroundExplanation = false },
            title = { Text(stringResource(R.string.loc_background_access)) },
            text = { Text(stringResource(R.string.loc_background_explanation, optionLabel)) },
            confirmButton = { TextButton(onClick = {
                backgroundExplanation = false
                if (Build.VERSION.SDK_INT >= 30) context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()))
                else if (Build.VERSION.SDK_INT == 29) backgroundPermission.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }) { Text(stringResource(R.string.loc_open_settings)) } },
            dismissButton = { TextButton(onClick = { backgroundExplanation = false }) { Text(stringResource(android.R.string.cancel)) } })
    }
}
