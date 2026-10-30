package com.metrolist.music.connect

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.FlagSet
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.models.MediaMetadata as MetrolittMetadata

/** A read-only view of the other device's playback, with transport commands forwarded to it. */
class RemoteMirrorPlayer(
    localPlayer: Player,
    private val connect: DeviceConnectManager,
) : ForwardingPlayer(localPlayer) {
    private val remote: DevicePlaybackState?
        get() = connect.state.value.remotePlayback

    private var cachedState: DevicePlaybackState? = null
    private var cachedTimeline: Timeline = Timeline.EMPTY
    private val observers = LinkedHashSet<Player.Listener>()

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
        val flags = Player.Events(FlagSet.Builder()
            .add(Player.EVENT_MEDIA_ITEM_TRANSITION)
            .add(Player.EVENT_MEDIA_METADATA_CHANGED)
            .add(Player.EVENT_TIMELINE_CHANGED)
            .add(Player.EVENT_IS_PLAYING_CHANGED)
            .add(Player.EVENT_PLAY_WHEN_READY_CHANGED)
            .add(Player.EVENT_PLAYBACK_STATE_CHANGED)
            .add(Player.EVENT_POSITION_DISCONTINUITY)
            .add(Player.EVENT_REPEAT_MODE_CHANGED)
            .add(Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED)
            .build())
        observers.toList().forEach { listener ->
            listener.onTimelineChanged(currentTimeline, Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED)
            if (previous?.track != current?.track) {
                listener.onMediaItemTransition(currentMediaItem, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
            }
            listener.onMediaMetadataChanged(mediaMetadata)
            listener.onPlaybackStateChanged(playbackState)
            listener.onPlayWhenReadyChanged(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            listener.onIsPlayingChanged(isPlaying)
            listener.onShuffleModeEnabledChanged(shuffleModeEnabled)
            listener.onRepeatModeChanged(repeatMode)
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

    override fun getMediaItemAt(index: Int): MediaItem = timeline().getWindow(index, Timeline.Window()).mediaItem

    override fun getCurrentMediaItem(): MediaItem? =
        if (mediaItemCount > 0) getMediaItemAt(currentMediaItemIndex) else null

    override fun getMediaMetadata(): MediaMetadata = currentMediaItem?.mediaMetadata ?: MediaMetadata.EMPTY

    override fun getCurrentPosition(): Long = remote?.let {
        DeviceConnectProtocol.projectedPosition(it, SystemClock.elapsedRealtime())
    } ?: 0L

    override fun getBufferedPosition(): Long = currentPosition

    override fun getDuration(): Long = remote?.durationMs ?: C.TIME_UNSET

    override fun getPlaybackState(): Int = if (remote?.track != null) Player.STATE_READY else Player.STATE_IDLE

    override fun getPlaybackSuppressionReason(): Int = Player.PLAYBACK_SUPPRESSION_REASON_NONE

    override fun getPlayWhenReady(): Boolean = remote?.playing == true

    override fun isPlaying(): Boolean = remote?.playing == true

    override fun getRepeatMode(): Int = remote?.repeatMode ?: Player.REPEAT_MODE_OFF

    override fun getShuffleModeEnabled(): Boolean = remote?.shuffle == true

    override fun getNextMediaItemIndex(): Int = when {
        currentMediaItemIndex + 1 < mediaItemCount -> currentMediaItemIndex + 1
        remote?.let { it.queueIndex < it.queueSize - 1 } == true -> currentMediaItemIndex
        else -> C.INDEX_UNSET
    }

    override fun getPreviousMediaItemIndex(): Int = when {
        currentMediaItemIndex > 0 -> currentMediaItemIndex - 1
        remote?.let { it.queueIndex > 0 } == true -> currentMediaItemIndex
        else -> C.INDEX_UNSET
    }

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

    override fun stop() = connect.sendCommand("PAUSE")

    override fun clearMediaItems() = Unit

    override fun removeMediaItem(index: Int) {
        val snapshot = remote ?: return
        connect.sendCommand("REMOVE_QUEUE", (snapshot.queueStartIndex + index).toLong())
    }

    override fun addMediaItem(mediaItem: MediaItem) = Unit

    override fun addMediaItem(index: Int, mediaItem: MediaItem) = Unit

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
