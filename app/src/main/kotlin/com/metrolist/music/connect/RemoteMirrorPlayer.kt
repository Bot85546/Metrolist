package com.metrolist.music.connect

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.FlagSet
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.models.MediaMetadata as MetrolittMetadata

/** A read-only view of the other device's playback, with transport commands forwarded to it. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class RemoteMirrorPlayer(
    localPlayer: Player,
    private val connect: DeviceConnectManager,
) : ForwardingPlayer(localPlayer) {
    private val remote: DevicePlaybackState?
        get() = connect.state.value.remotePlayback

    private var cachedState: DevicePlaybackState? = null
    private var cachedTimeline: Timeline = Timeline.EMPTY
    private val observers = LinkedHashSet<Player.Listener>()

    // The inactive local ExoPlayer has no seek/skip commands. The remote session must advertise
    // its own capabilities or Android's media controls hide the previous/next buttons.
    private val remoteCommands =
        super.getAvailableCommands().buildUpon()
            .add(Player.COMMAND_GET_TIMELINE)
            .add(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)
            .add(Player.COMMAND_GET_METADATA)
            .add(Player.COMMAND_PLAY_PAUSE)
            .add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
            .add(Player.COMMAND_SEEK_TO_MEDIA_ITEM)
            .add(Player.COMMAND_SEEK_TO_NEXT)
            .add(Player.COMMAND_SEEK_TO_PREVIOUS)
            .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            .add(Player.COMMAND_SEEK_BACK)
            .add(Player.COMMAND_SEEK_FORWARD)
            .add(Player.COMMAND_SET_REPEAT_MODE)
            .add(Player.COMMAND_SET_SHUFFLE_MODE)
            .add(Player.COMMAND_SET_SPEED_AND_PITCH)
            .add(Player.COMMAND_CHANGE_MEDIA_ITEMS)
            .build()

    private fun timeline(): Timeline {
        val snapshot = remote
        if (snapshot !== cachedState) {
            cachedState = snapshot
            val entries = snapshot?.queue.orEmpty().ifEmpty { listOfNotNull(snapshot?.track) }
            cachedTimeline = if (entries.isEmpty()) Timeline.EMPTY else RemoteQueueTimeline(
                entries,
                snapshot?.durationMs ?: 0L,
                if (snapshot?.queue.isNullOrEmpty()) 0 else
                    ((snapshot?.queueIndex ?: 0) - (snapshot?.queueStartIndex ?: 0)).coerceIn(0, entries.lastIndex),
            )
        }
        return cachedTimeline
    }

    override fun addListener(listener: Player.Listener) {
        observers.add(listener)
    }

    override fun removeListener(listener: Player.Listener) {
        observers.remove(listener)
    }

    fun notifyStateChanged(previous: DevicePlaybackState?, current: DevicePlaybackState?) {
        cachedState = null
        // A full playlist may contain hundreds of songs. Do not rebuild every queue window
        // on every pause, timer, like or position update when its contents are unchanged.
        val queueChanged = previous?.queue !== current?.queue ||
            previous?.queueStartIndex != current?.queueStartIndex ||
            (previous?.track == null) != (current?.track == null)
        val itemChanged = previous?.track != current?.track || previous?.queueIndex != current?.queueIndex
        val flagsBuilder = FlagSet.Builder()
            .apply { if (itemChanged) add(Player.EVENT_MEDIA_ITEM_TRANSITION) }
            .apply { if (itemChanged) add(Player.EVENT_MEDIA_METADATA_CHANGED) }
            .apply { if (queueChanged) add(Player.EVENT_TIMELINE_CHANGED) }
            .add(Player.EVENT_IS_PLAYING_CHANGED)
            .add(Player.EVENT_PLAY_WHEN_READY_CHANGED)
            .add(Player.EVENT_PLAYBACK_STATE_CHANGED)
            .add(Player.EVENT_POSITION_DISCONTINUITY)
            .add(Player.EVENT_REPEAT_MODE_CHANGED)
            .add(Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED)
            .add(Player.EVENT_AVAILABLE_COMMANDS_CHANGED)
            .add(Player.EVENT_PLAYBACK_PARAMETERS_CHANGED)
        val flags = Player.Events(flagsBuilder.build())
        observers.toList().forEach { listener ->
            if (queueChanged) listener.onTimelineChanged(currentTimeline, Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED)
            if (itemChanged) {
                listener.onMediaItemTransition(currentMediaItem, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
            }
            if (itemChanged) listener.onMediaMetadataChanged(mediaMetadata)
            listener.onPlaybackStateChanged(playbackState)
            listener.onPlayWhenReadyChanged(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            listener.onIsPlayingChanged(isPlaying)
            listener.onShuffleModeEnabledChanged(shuffleModeEnabled)
            listener.onRepeatModeChanged(repeatMode)
            listener.onPlaybackParametersChanged(playbackParameters)
            listener.onAvailableCommandsChanged(availableCommands)
            listener.onEvents(this, flags)
        }
    }

    override fun getCurrentTimeline(): Timeline = timeline()

    override fun getMediaItemCount(): Int = timeline().windowCount

    override fun getCurrentMediaItemIndex(): Int {
        val snapshot = remote ?: return 0
        return if (snapshot.queue.isEmpty()) 0
        else (snapshot.queueIndex - snapshot.queueStartIndex).coerceIn(0, (mediaItemCount - 1).coerceAtLeast(0))
    }

    // One period per remote window. ForwardingPlayer's default reads the *local* player period,
    // which becomes invalid as soon as the remote playlist advances past index zero. Media3's
    // MediaSession PlayerWrapper checks this invariant and otherwise throws IllegalStateException.
    override fun getCurrentPeriodIndex(): Int = currentMediaItemIndex

    override fun getAvailableCommands(): Player.Commands = remoteCommands

    // Preserve the controller's Android output route and hardware-volume keys. Remote
    // playback metadata must not advertise a second, synthetic volume device.

    override fun getPlaybackParameters(): PlaybackParameters = PlaybackParameters(
        remote?.speed ?: 1f,
        remote?.pitch ?: 1f,
    )

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        connect.sendPlaybackParameters(playbackParameters.speed, playbackParameters.pitch)
    }

    override fun getMediaItemAt(index: Int): MediaItem = timeline().getWindow(index, Timeline.Window()).mediaItem

    override fun getCurrentMediaItem(): MediaItem? =
        if (mediaItemCount > 0) getMediaItemAt(currentMediaItemIndex) else null

    override fun getMediaMetadata(): MediaMetadata = currentMediaItem?.mediaMetadata ?: MediaMetadata.EMPTY

    override fun getCurrentPosition(): Long = remote?.let {
        DeviceConnectProtocol.projectedPosition(it, SystemClock.elapsedRealtime())
    } ?: 0L

    override fun getBufferedPosition(): Long = currentPosition

    override fun getContentPosition(): Long = currentPosition

    override fun getContentBufferedPosition(): Long = currentPosition

    override fun getTotalBufferedDuration(): Long = 0L

    override fun getDuration(): Long = remote?.durationMs ?: C.TIME_UNSET

    override fun getContentDuration(): Long = duration

    override fun isCurrentMediaItemSeekable(): Boolean = remote?.track != null

    override fun isCurrentMediaItemDynamic(): Boolean = false

    override fun isCurrentMediaItemLive(): Boolean = false

    override fun getPlaybackState(): Int = if (remote?.track != null) Player.STATE_READY else Player.STATE_IDLE

    override fun getPlaybackSuppressionReason(): Int = Player.PLAYBACK_SUPPRESSION_REASON_NONE

    override fun getPlayWhenReady(): Boolean = remote?.playing == true

    override fun isPlaying(): Boolean = remote?.playing == true

    override fun getRepeatMode(): Int = remote?.repeatMode ?: Player.REPEAT_MODE_OFF

    override fun getShuffleModeEnabled(): Boolean = remote?.shuffle == true

    override fun getNextMediaItemIndex(): Int = remote?.nextQueueIndex?.takeIf { it >= 0 }
        ?.let { (it - (remote?.queueStartIndex ?: 0)).takeIf { index -> index in 0 until mediaItemCount }
            ?: currentMediaItemIndex } ?: C.INDEX_UNSET

    override fun getPreviousMediaItemIndex(): Int = remote?.previousQueueIndex?.takeIf { it >= 0 }
        ?.let { (it - (remote?.queueStartIndex ?: 0)).takeIf { index -> index in 0 until mediaItemCount }
            ?: currentMediaItemIndex } ?: C.INDEX_UNSET

    override fun setPlayWhenReady(playWhenReady: Boolean) = connect.sendCommand(if (playWhenReady) "PLAY" else "PAUSE")

    override fun play() = connect.sendCommand("PLAY")

    override fun pause() = connect.sendCommand("PAUSE")

    override fun prepare() = Unit

    override fun seekTo(positionMs: Long) = connect.sendCommand("SEEK", positionMs)

    override fun seekBack() = seekTo((currentPosition - seekBackIncrement).coerceAtLeast(0L))

    override fun seekForward() = seekTo((currentPosition + seekForwardIncrement).coerceAtMost(duration))

    override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
        val snapshot = remote ?: return
        val actualIndex = if (snapshot.queue.isEmpty()) snapshot.queueIndex else snapshot.queueStartIndex + mediaItemIndex
        connect.sendCommand("SEEK_QUEUE", actualIndex.toLong())
        if (positionMs > 0) connect.sendCommand("SEEK", positionMs)
    }

    override fun seekToDefaultPosition(mediaItemIndex: Int) = seekTo(mediaItemIndex, 0L)

    override fun seekToNext() = connect.sendCommand("NEXT")

    override fun seekToNextMediaItem() = connect.sendCommand("NEXT")

    override fun seekToPrevious() = connect.sendCommand("PREVIOUS")

    override fun seekToPreviousMediaItem() = connect.sendCommand("PREVIOUS")

    override fun setShuffleModeEnabled(shuffleModeEnabled: Boolean) {
        if (shuffleModeEnabled != this.shuffleModeEnabled) connect.sendCommand("SHUFFLE")
    }

    override fun setRepeatMode(repeatMode: Int) {
        if (repeatMode != this.repeatMode) connect.sendCommand("SET_REPEAT", repeatMode.toLong())
    }

    override fun stop() = connect.sendCommand("STOP_AND_CLEAR")

    override fun clearMediaItems() = Unit

    override fun setMediaItems(mediaItems: List<MediaItem>) =
        connect.submitRemoteMediaItems(mediaItems, 0)

    override fun setMediaItems(mediaItems: List<MediaItem>, resetPosition: Boolean) =
        connect.submitRemoteMediaItems(mediaItems, if (resetPosition) 0 else currentMediaItemIndex)

    override fun setMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long) =
        connect.submitRemoteMediaItems(mediaItems, startIndex)

    override fun setMediaItem(mediaItem: MediaItem) = setMediaItems(listOf(mediaItem))

    override fun setMediaItem(mediaItem: MediaItem, resetPosition: Boolean) =
        setMediaItems(listOf(mediaItem), resetPosition)

    override fun setMediaItem(mediaItem: MediaItem, startPositionMs: Long) =
        setMediaItems(listOf(mediaItem))

    override fun addMediaItems(mediaItems: List<MediaItem>) =
        connect.submitRemoteQueueAddition(mediaItems, playNext = false)

    override fun addMediaItems(index: Int, mediaItems: List<MediaItem>) =
        connect.submitRemoteQueueAddition(mediaItems, playNext = index <= currentMediaItemIndex + 1)

    override fun removeMediaItems(fromIndex: Int, toIndex: Int) {
        for (index in (fromIndex until toIndex).reversed()) removeMediaItem(index)
    }

    override fun moveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int) = Unit

    override fun replaceMediaItem(index: Int, mediaItem: MediaItem) = Unit

    override fun replaceMediaItems(fromIndex: Int, toIndex: Int, mediaItems: List<MediaItem>) = Unit

    override fun removeMediaItem(index: Int) {
        val snapshot = remote ?: return
        connect.sendCommand("REMOVE_QUEUE", (snapshot.queueStartIndex + index).toLong())
    }

    override fun addMediaItem(mediaItem: MediaItem) = addMediaItems(listOf(mediaItem))

    override fun addMediaItem(index: Int, mediaItem: MediaItem) = addMediaItems(index, listOf(mediaItem))

    override fun moveMediaItem(currentIndex: Int, newIndex: Int) {
        val start = remote?.queueStartIndex ?: return
        val from = (start + currentIndex).toLong()
        val to = (start + newIndex).toLong()
        connect.sendCommand("MOVE_QUEUE", (from shl 32) or (to and 0xffffffffL))
    }
}

internal fun DeviceTrack.asMediaItem(durationMs: Long): MediaItem =
    MetrolittMetadata(
        id = id,
        title = title,
        artists = listOf(MetrolittMetadata.Artist(null, artist)),
        duration = (durationMs / 1000L).toInt(),
        thumbnailUrl = artwork,
    ).toMediaItem()

private class RemoteQueueTimeline(
    private val entries: List<DeviceTrack>,
    private val currentDurationMs: Long,
    private val currentIndex: Int,
) : Timeline() {
    override fun getWindowCount(): Int = entries.size

    override fun getPeriodCount(): Int = entries.size

    override fun getWindow(windowIndex: Int, window: Window, defaultPositionProjectionUs: Long): Window {
        val durationUs = if (windowIndex == currentIndex && currentDurationMs > 0) currentDurationMs * 1000 else C.TIME_UNSET
        return window.set(
            windowIndex,
            entries[windowIndex].asMediaItem(if (durationUs > 0) currentDurationMs else 0),
            null,
            C.TIME_UNSET,
            C.TIME_UNSET,
            C.TIME_UNSET,
            true,
            false,
            null,
            0L,
            durationUs,
            windowIndex,
            windowIndex,
            0L,
        )
    }

    override fun getPeriod(periodIndex: Int, period: Period, setIds: Boolean): Period =
        period.set(if (setIds) periodIndex else null, periodIndex, periodIndex, C.TIME_UNSET, 0L)

    override fun getIndexOfPeriod(uid: Any): Int = (uid as? Int)?.takeIf { it in entries.indices } ?: C.INDEX_UNSET

    override fun getUidOfPeriod(periodIndex: Int): Any = periodIndex.also { require(it in entries.indices) }
}
