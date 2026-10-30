package com.metrolist.music.ui.player

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import coil3.compose.AsyncImage
import com.metrolist.music.R
import com.metrolist.music.connect.DeviceConnectManager
import com.metrolist.music.connect.DeviceConnectProtocol
import com.metrolist.music.connect.DeviceTrack
import com.metrolist.music.constants.MiniPlayerHeight
import com.metrolist.music.ui.component.BottomSheet
import com.metrolist.music.ui.component.BottomSheetState
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.roundToLong

/**
 * The normal player surface for a paired device controlling remote playback.
 * No local ExoPlayer or audio stream is created for the remote session.
 */
@Composable
fun RemoteBottomSheetPlayer(
    state: BottomSheetState,
    manager: DeviceConnectManager,
    peerName: String,
    modifier: Modifier = Modifier,
) {
    val ui by manager.state.collectAsStateWithLifecycle()
    val playback = ui.remotePlayback ?: return
    val track = playback.track ?: return
    val lifecycleOwner = LocalLifecycleOwner.current
    var nowMs by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(playback.playing, playback.updatedAtMs, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            nowMs = SystemClock.elapsedRealtime()
            while (isActive && playback.playing) {
                delay(750L)
                nowMs = SystemClock.elapsedRealtime()
            }
        }
    }
    val position = DeviceConnectProtocol.projectedPosition(playback, nowMs)
    val fraction =
        if (playback.durationMs > 0) (position.toFloat() / playback.durationMs).coerceIn(0f, 1f)
        else 0f
    val artwork = track.artwork?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
    var showQueue by rememberSaveable { mutableStateOf(false) }
    var dragging by remember(track.id) { mutableStateOf(false) }
    var draggedProgress by remember(track.id) { mutableFloatStateOf(0f) }

    BottomSheet(
        state = state,
        modifier = modifier,
        background = {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer))
        },
        collapsedContent = {
            Column(
                Modifier.fillMaxWidth().height(MiniPlayerHeight)
                    .background(MaterialTheme.colorScheme.surfaceContainer)
            ) {
                Row(
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    RemoteArtwork(artwork, Modifier.size(48.dp))
                    Column(Modifier.weight(1f)) {
                        Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleSmall)
                        Text(track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = {
                        manager.sendCommand(if (playback.playing) "PAUSE" else "PLAY")
                    }) {
                        Icon(painterResource(if (playback.playing) R.drawable.pause else R.drawable.play),
                            contentDescription = stringResource(
                                if (playback.playing) R.string.device_connect_pause else R.string.device_connect_play
                            ))
                    }
                    IconButton(onClick = { manager.sendCommand("NEXT") }) {
                        Icon(painterResource(R.drawable.skip_next),
                            contentDescription = stringResource(R.string.device_connect_next))
                    }
                }
                Box(Modifier.fillMaxWidth().height(2.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant)) {
                    Box(Modifier.fillMaxWidth(fraction).height(2.dp)
                        .background(MaterialTheme.colorScheme.primary))
                }
            }
        },
    ) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(R.string.device_connect_playing_on, peerName),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(24.dp))
            RemoteArtwork(
                artwork,
                Modifier.fillMaxWidth(0.82f).widthIn(max = 360.dp).aspectRatio(1f),
            )
            Spacer(Modifier.height(25.dp))
            Text(track.title, style = MaterialTheme.typography.headlineSmall,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(3.dp))
            Text(track.artist, style = MaterialTheme.typography.bodyLarge,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(20.dp))
            Slider(
                value = if (dragging) draggedProgress else fraction,
                onValueChange = {
                    dragging = true
                    draggedProgress = it
                },
                onValueChangeFinished = {
                    manager.sendCommand("SEEK", (draggedProgress * playback.durationMs).roundToLong())
                    dragging = false
                },
                enabled = playback.durationMs > 0,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatRemoteTime(if (dragging) (draggedProgress * playback.durationMs).toLong() else position),
                    style = MaterialTheme.typography.labelSmall)
                Text(formatRemoteTime(playback.durationMs), style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { manager.sendCommand("SHUFFLE") }) {
                    Icon(painterResource(if (playback.shuffle) R.drawable.shuffle_on else R.drawable.shuffle),
                        contentDescription = stringResource(R.string.device_connect_shuffle),
                        tint = if (playback.shuffle) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface)
                }
                IconButton(onClick = { manager.sendCommand("PREVIOUS") }, modifier = Modifier.size(56.dp)) {
                    Icon(painterResource(R.drawable.skip_previous),
                        contentDescription = stringResource(R.string.device_connect_previous),
                        modifier = Modifier.size(34.dp))
                }
                IconButton(onClick = {
                    manager.sendCommand(if (playback.playing) "PAUSE" else "PLAY")
                }, modifier = Modifier.size(72.dp)) {
                    Icon(painterResource(if (playback.playing) R.drawable.pause else R.drawable.play),
                        contentDescription = stringResource(
                            if (playback.playing) R.string.device_connect_pause else R.string.device_connect_play
                        ),
                        modifier = Modifier.size(46.dp),
                        tint = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = { manager.sendCommand("NEXT") }, modifier = Modifier.size(56.dp)) {
                    Icon(painterResource(R.drawable.skip_next),
                        contentDescription = stringResource(R.string.device_connect_next),
                        modifier = Modifier.size(34.dp))
                }
                IconButton(onClick = { manager.sendCommand("REPEAT") }) {
                    Icon(painterResource(if (playback.repeatMode == 2) R.drawable.repeat_one else R.drawable.repeat),
                        contentDescription = stringResource(R.string.device_connect_repeat),
                        tint = if (playback.repeatMode != 0) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface)
                }
            }
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = { showQueue = !showQueue }) {
                Icon(painterResource(R.drawable.queue_music), contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.device_connect_queue))
            }
            if (showQueue) {
                Spacer(Modifier.height(10.dp))
                playback.queue.forEachIndexed { index, queuedTrack ->
                    val absoluteIndex = playback.queueStartIndex + index
                    RemoteQueueRow(
                        track = queuedTrack,
                        selected = absoluteIndex == playback.queueIndex,
                        onClick = { manager.sendCommand("SEEK_QUEUE", absoluteIndex.toLong()) },
                    )
                }
                if (playback.queueSize > playback.queue.size) {
                    Text(
                        stringResource(R.string.device_connect_queue_window, playback.queue.size, playback.queueSize),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            OutlinedButton(onClick = manager::disconnect) {
                Text(stringResource(R.string.device_connect_disconnect))
            }
            Spacer(Modifier.height(44.dp))
        }
    }
}

@Composable
private fun RemoteArtwork(url: String?, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(12.dp))
        .background(MaterialTheme.colorScheme.surfaceVariant)) {
        AsyncImage(
            model = url,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun RemoteQueueRow(track: DeviceTrack, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RemoteArtwork(track.artwork, Modifier.size(40.dp))
        Column(Modifier.weight(1f)) {
            Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) MaterialTheme.colorScheme.primary else Color.Unspecified)
            Text(track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun formatRemoteTime(ms: Long): String {
    val seconds = ms.coerceAtLeast(0L) / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
