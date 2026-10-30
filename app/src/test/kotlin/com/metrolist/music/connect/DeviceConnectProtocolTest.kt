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
            queue = listOf(DeviceTrack("id1", "Song", "Artist"), DeviceTrack("id2", "Next", "Artist")),
            queueTitle = "Liked",
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
    fun challengeBindsBothDeviceIdentitiesAndNonces() {
        val original = DeviceConnectProtocol.authenticationData("phone", "a", "tab", "b")
        val tampered = DeviceConnectProtocol.authenticationData("phone", "a", "tab", "c")
        assert(original != tampered)
    }
}
