package com.metrolist.music.connect

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class DeviceTrack(
    val id: String,
    val title: String,
    val artist: String,
    val artwork: String? = null,
)

@Serializable
data class DevicePlaybackState(
    val revision: Long,
    val track: DeviceTrack? = null,
    val playing: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val updatedAtMs: Long = 0,
    val shuffle: Boolean = false,
    val repeatMode: Int = 0,
    val queueIndex: Int = -1,
    val queueSize: Int = 0,
    val queueStartIndex: Int = 0,
    val queue: List<DeviceTrack> = emptyList(),
)

@Serializable
data class DeviceConnectPacket(
    val type: String,
    val deviceId: String = "",
    val deviceName: String = "",
    val nonce: String = "",
    val proof: String = "",
    val secret: String = "",
    val action: String = "",
    val argument: Long = 0,
    val requestId: String = "",
    val state: DevicePlaybackState? = null,
    val transferId: String = "",
    val queueTotal: Int = 0,
    val queueStart: Int = 0,
    val queueTitle: String = "",
    val queueItems: List<DeviceTrack> = emptyList(),
)

object DeviceConnectProtocol {
    const val MAX_PAYLOAD_BYTES = 30_000
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    fun encode(packet: DeviceConnectPacket): ByteArray =
        json.encodeToString(DeviceConnectPacket.serializer(), packet).encodeToByteArray().also {
            require(it.size <= MAX_PAYLOAD_BYTES) { "Device Connect packet is too large" }
        }

    fun decode(bytes: ByteArray): DeviceConnectPacket? {
        if (bytes.isEmpty() || bytes.size > MAX_PAYLOAD_BYTES) return null
        return runCatching {
            json.decodeFromString(DeviceConnectPacket.serializer(), bytes.decodeToString())
        }.getOrNull()
    }

    fun authenticationData(
        senderId: String,
        senderNonce: String,
        receiverId: String,
        receiverNonce: String,
    ): String = "metrolitt-device-connect-v1|$senderId|$senderNonce|$receiverId|$receiverNonce"

    fun projectedPosition(state: DevicePlaybackState, nowElapsedMs: Long): Long {
        val delta = if (state.playing) (nowElapsedMs - state.updatedAtMs).coerceAtLeast(0) else 0
        val position = (state.positionMs + delta).coerceAtLeast(0)
        return if (state.durationMs > 0) position.coerceAtMost(state.durationMs) else position
    }
}
