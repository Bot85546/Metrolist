package com.metrolist.music.ui.screens.settings.integrations

import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.metrolist.music.LocalDeviceConnectManager
import com.metrolist.music.R
import com.metrolist.music.connect.DeviceConnectManager
import com.metrolist.music.connect.DeviceConnectProtocol
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.roundToLong

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceConnectScreen(navController: NavController) {
    val manager = LocalDeviceConnectManager.current ?: return
    val ui by manager.state.collectAsStateWithLifecycle()
    val permissions = remember { DeviceConnectManager.requiredPermissions() }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { manager.permissionStateChanged() }

    LaunchedEffect(Unit) { manager.permissionStateChanged() }
    DisposableEffect(Unit) {
        onDispose { manager.endPairing() }
    }

    ui.pairingRequest?.let { request ->
        AlertDialog(
            onDismissRequest = manager::rejectPairing,
            title = { Text(stringResource(R.string.device_connect_pair_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(request.deviceName)
                    Text(request.verificationCode, style = MaterialTheme.typography.headlineMedium)
                    Text(stringResource(R.string.device_connect_pair_description))
                }
            },
            confirmButton = {
                TextButton(onClick = manager::acceptPairing) {
                    Text(stringResource(R.string.device_connect_accept))
                }
            },
            dismissButton = {
                TextButton(onClick = manager::rejectPairing) {
                    Text(stringResource(R.string.device_connect_reject))
                }
            },
        )
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.device_connect)) },
            navigationIcon = {
                TextButton(onClick = navController::navigateUp) {
                    Text(stringResource(R.string.back_button_desc))
                }
            },
        )
    }) { padding ->
        Column(
            Modifier.padding(padding).padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(stringResource(R.string.device_connect_explanation))
            Text(ui.status, color = MaterialTheme.colorScheme.primary)

            if (!ui.available) {
                Text(stringResource(R.string.device_connect_unavailable))
                return@Column
            }
            if (!ui.permissionsGranted) {
                Button(onClick = { permissionLauncher.launch(permissions) }) {
                    Text(stringResource(R.string.device_connect_permissions))
                }
                return@Column
            }

            if (!ui.connected) {
                ui.trustedDeviceName?.let {
                    Text(it, style = MaterialTheme.typography.titleMedium)
                    OutlinedButton(onClick = manager::searchAgain) {
                        Text(stringResource(R.string.device_connect_search))
                    }
                }
                Button(onClick = manager::beginPairing) {
                    Text(stringResource(R.string.device_connect_pair))
                }
                if (ui.trustedDeviceName != null) {
                    TextButton(onClick = manager::forgetDevice) {
                        Text(stringResource(R.string.device_connect_forget))
                    }
                }
            } else {
                Text(ui.peerName.orEmpty(), style = MaterialTheme.typography.titleMedium)
                OutlinedButton(onClick = manager::disconnect) {
                    Text(stringResource(R.string.device_connect_disconnect))
                }
                val playback = ui.remotePlayback
                if (playback?.track != null) {
                    Text(playback.track.title, style = MaterialTheme.typography.headlineSmall)
                    Text(playback.track.artist)

                    var nowMs by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
                    LaunchedEffect(playback.playing) {
                        while (isActive && playback.playing) {
                            nowMs = SystemClock.elapsedRealtime()
                            delay(500)
                        }
                    }
                    val position = DeviceConnectProtocol.projectedPosition(playback, nowMs)
                    var dragging by remember { mutableStateOf(false) }
                    var draggedPosition by remember { mutableFloatStateOf(0f) }
                    val progress = if (playback.durationMs > 0) {
                        position.toFloat() / playback.durationMs.toFloat()
                    } else 0f
                    Slider(
                        value = (if (dragging) draggedPosition else progress).coerceIn(0f, 1f),
                        onValueChange = {
                            dragging = true
                            draggedPosition = it
                        },
                        onValueChangeFinished = {
                            manager.sendCommand(
                                "SEEK",
                                (draggedPosition * playback.durationMs).roundToLong(),
                            )
                            dragging = false
                        },
                        enabled = playback.durationMs > 0,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        OutlinedButton(onClick = { manager.sendCommand("PREVIOUS") }) {
                            Text(stringResource(R.string.device_connect_previous))
                        }
                        Button(onClick = {
                            manager.sendCommand(if (playback.playing) "PAUSE" else "PLAY")
                        }) {
                            Text(stringResource(if (playback.playing) R.string.device_connect_pause else R.string.device_connect_play))
                        }
                        OutlinedButton(onClick = { manager.sendCommand("NEXT") }) {
                            Text(stringResource(R.string.device_connect_next))
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = { manager.sendCommand("SHUFFLE") }) {
                            Text(stringResource(R.string.device_connect_shuffle) +
                                if (playback.shuffle) " ✓" else "")
                        }
                        OutlinedButton(onClick = { manager.sendCommand("REPEAT") }) {
                            Text(stringResource(R.string.device_connect_repeat) + ": ${playback.repeatMode}")
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(stringResource(R.string.device_connect_queue), style = MaterialTheme.typography.titleMedium)
                    playback.queue.forEach { track ->
                        Text(track.title + " — " + track.artist, style = MaterialTheme.typography.bodyMedium)
                    }
                } else {
                    Text(stringResource(R.string.device_connect_no_remote))
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
