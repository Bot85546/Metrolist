package com.metrolist.music.connect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceConnectProtocolTest {
    @Test
    fun snapshotRoundTripPreservesQueueAndControls() {
        val snapshot = DevicePlaybackState(
            revision = 14,
            track = DeviceTrack("id1", "Song", "Artist"),
            playing = true,
            positionMs = 12_000,
            durationMs = 180_000,
            updatedAtMs = 100,
            shuffle = true,
            repeatMode = 2,
            queueIndex = 8,
            queueSize = 24,
            queueStartIndex = 7,
            queue = listOf(DeviceTrack("id1", "Song", "Artist", durationSeconds = 180, sourceIndex = 8),
                DeviceTrack("id2", "Next", "Artist", durationSeconds = 210, sourceIndex = 9)),
            queueTitle = "Liked",
            queueRevision = 12,
            nextQueueIndex = 9,
            previousQueueIndex = 6,
            liked = true,
            speed = 1.25f,
            pitch = 1.1f,
            volume = 0.45f,
            muted = true,
            sleepTimerRemainingMs = 123_000L,
            sleepTimerAfterSong = false,
            sleepTimerStopAfterSong = true,
            sleepTimerFadeOut = true,
        )
        val packet = DeviceConnectPacket(type = "STATE", state = snapshot)
        assertEquals(packet, DeviceConnectProtocol.decode(DeviceConnectProtocol.encode(packet)))
    }

    @Test
    fun playlistTransferRoundTripPreservesOrderedTracksAndStartPosition() {
        val tracks = listOf(
            DeviceTrack("song1", "First", "Singer", "https://example.com/1.jpg"),
            DeviceTrack("song2", "Second", "Singer", "https://example.com/2.jpg"),
        )
        val begin = DeviceConnectPacket(type = "QUEUE_BEGIN", transferId = "queue-token-123456789", queueTotal = 2, queueStart = 1)
        val batch = DeviceConnectPacket(type = "QUEUE_BATCH", transferId = begin.transferId, queueItems = tracks)
        assertEquals(begin, DeviceConnectProtocol.decode(DeviceConnectProtocol.encode(begin)))
        assertEquals(batch, DeviceConnectProtocol.decode(DeviceConnectProtocol.encode(batch)))
    }

    @Test
    fun mirrorQueueTransferPreservesOwnerRevisionAndOrdering() {
        val tracks = (0 until 12).map {
            DeviceTrack("track-$it", "Song $it", "Artist", sourceIndex = 23 - it)
        }
        val begin = DeviceConnectPacket(type = "MIRROR_QUEUE_BEGIN", transferId = "mirror-token-123456789",
            queueRevision = 17, queueTotal = 24)
        val batch = DeviceConnectPacket(type = "MIRROR_QUEUE_BATCH", transferId = begin.transferId,
            queueRevision = 17, queueStart = 12, queueItems = tracks)
        val end = DeviceConnectPacket(type = "MIRROR_QUEUE_END", transferId = begin.transferId)
        listOf(begin, batch, end).forEach { packet ->
            assertEquals(packet, DeviceConnectProtocol.decode(DeviceConnectProtocol.encode(packet)))
        }
    }

    @Test
    fun rejectsInvalidAndOversizePayloads() {
        assertNull(DeviceConnectProtocol.decode(byteArrayOf()))
        assertNull(DeviceConnectProtocol.decode("{".encodeToByteArray()))
        assertNull(DeviceConnectProtocol.decode(ByteArray(DeviceConnectProtocol.MAX_PAYLOAD_BYTES + 1)))
    }

    @Test
    fun progressAdvancesLocallyWithoutPolls() {
        val state = DevicePlaybackState(
            revision = 1, playing = true, positionMs = 10_000, durationMs = 13_000, updatedAtMs = 100,
        )
        assertEquals(12_000, DeviceConnectProtocol.projectedPosition(state, 2_100))
        assertEquals(13_000, DeviceConnectProtocol.projectedPosition(state, 20_000))
        assertEquals(10_000, DeviceConnectProtocol.projectedPosition(state.copy(playing = false), 20_000))
    }

    @Test
    fun remoteSleepTimerCountdownRequiresNoNetworkPolling() {
        val state = DevicePlaybackState(
            revision = 4, sleepTimerRemainingMs = 90_000L, updatedAtMs = 100,
        )
        assertEquals(90_000L, DeviceConnectProtocol.projectedSleepTimerRemainingMs(state, 100))
        assertEquals(30_000L, DeviceConnectProtocol.projectedSleepTimerRemainingMs(state, 60_100))
        assertEquals(0L, DeviceConnectProtocol.projectedSleepTimerRemainingMs(state, 200_100))
        assertEquals(-1L, DeviceConnectProtocol.projectedSleepTimerRemainingMs(state.copy(sleepTimerRemainingMs = -1L), 1_000))
    }

    @Test
    fun remotePlaybackSettingsCommandsRoundTrip() {
        listOf(
            DeviceConnectPacket(type = "COMMAND", action = "SET_PLAYBACK_PARAMETERS", speed = 1.25f,
                pitch = 1.5f, requestId = "speed-1"),
            DeviceConnectPacket(type = "COMMAND", action = "SLEEP_TIMER", argument = 45,
                stopAfterSong = true, fadeOut = true, requestId = "timer-1"),
            DeviceConnectPacket(type = "COMMAND", action = "SET_VOLUME", volume = 0.6f,
                requestId = "volume-1"),
            DeviceConnectPacket(type = "COMMAND", action = "STOP_AND_CLEAR", requestId = "stop-1"),
            DeviceConnectPacket(type = "SYNC_MODE", action = "KEEP_SENDER"),
        ).forEach { command ->
            assertEquals(command, DeviceConnectProtocol.decode(DeviceConnectProtocol.encode(command)))
        }
    }

    @Test
    fun mirroredQueuePreservesLogicalPlaybackOrderAndPhysicalIndices() {
        // Shuffled playback order can differ from the owner's physical playlist indices.
        val playbackOrder = listOf(4, 1, 3, 0, 2)
        val tracks = playbackOrder.mapIndexed { index, sourceIndex ->
            DeviceTrack("song-$sourceIndex", "Title $index", "Artist", sourceIndex = sourceIndex)
        }
        val packet = DeviceConnectPacket(type = "MIRROR_QUEUE_BATCH",
            transferId = "mirror-shuffle-123456789", queueStart = 0, queueItems = tracks)
        val decoded = DeviceConnectProtocol.decode(DeviceConnectProtocol.encode(packet))!!
        assertEquals(playbackOrder, decoded.queueItems.map { it.sourceIndex })
        assertEquals("song-4", decoded.queueItems.first().id)
    }

    @Test
    fun reliableQueueAndPlaybackHandoffPacketsRoundTrip() {
        val playback = DevicePlaybackState(
            revision = 8, queueRevision = 4, track = DeviceTrack("track", "Song", "Artist"),
            playing = true, positionMs = 42_000, queueIndex = 2, queueSize = 3,
            shuffle = true, repeatMode = 2, speed = 1.15f,
        )
        listOf(
            DeviceConnectPacket(type = "GET_QUEUE"),
            DeviceConnectPacket(type = "MIRROR_QUEUE_ACK", queueRevision = 4),
            DeviceConnectPacket(type = "OUTPUT_TAKEOVER_REQUEST", requestId = "request-1234567890",
                queueRevision = 4),
            DeviceConnectPacket(type = "OUTPUT_TAKEOVER_READY", requestId = "request-1234567890",
                state = playback),
            DeviceConnectPacket(type = "OUTPUT_TAKEOVER_REJECTED", requestId = "request-1234567890"),
        ).forEach { packet ->
            assertEquals(packet, DeviceConnectProtocol.decode(DeviceConnectProtocol.encode(packet)))
        }
    }

    @Test
    fun challengeBindsBothDeviceIdentitiesAndNonces() {
        val original = DeviceConnectProtocol.authenticationData("phone", "a", "tab", "b")
        val tampered = DeviceConnectProtocol.authenticationData("phone", "a", "tab", "c")
        assert(original != tampered)
    }
}
