package com.metrolist.music.connect

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.Player
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class DevicePairingRequest(
    val endpointId: String,
    val deviceName: String,
    val verificationCode: String,
)

data class DeviceConnectUiState(
    val available: Boolean = false,
    val permissionsGranted: Boolean = false,
    val status: String = "Not connected",
    val trustedDeviceName: String? = null,
    val pairingMode: Boolean = false,
    val pairingRequest: DevicePairingRequest? = null,
    val connected: Boolean = false,
    val peerName: String? = null,
    val remotePlayback: DevicePlaybackState? = null,
)

@Singleton
class DeviceConnectManager @Inject constructor(
    @ApplicationContext private val context: Context,
) : DeviceConnectTransport.Listener {
    companion object {
        private const val DISCOVERY_WINDOW_MS = 12_000L
        private const val HANDSHAKE_TIMEOUT_MS = 15_000L

        fun requiredPermissions(): Array<String> = when {
            Build.VERSION.SDK_INT >= 33 -> arrayOf(
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.NEARBY_WIFI_DEVICES,
            )
            Build.VERSION.SDK_INT >= 31 -> arrayOf(
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.ACCESS_FINE_LOCATION,
            )
            Build.VERSION.SDK_INT >= 29 -> arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            else -> arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    }

    private val trust = DeviceConnectTrustStore(context)
    private val transport = DeviceConnectTransportFactory.create(context).also { it.listener = this }
    private val handler = Handler(Looper.getMainLooper())
    private val name = Build.MODEL.take(45).replace('|', ' ')
    private val ownEndpoint = DeviceEndpoint(trust.localId, name)
    private val _state = MutableStateFlow(
        DeviceConnectUiState(
            available = transport.available,
            trustedDeviceName = trust.trustedDevice()?.name,
        ),
    )
    val state = _state.asStateFlow()

    private var player: Player? = null
    private var foreground = false
    private var pairingMode = false
    private var manuallyDisconnected = false
    private var discoveryUntilMs = 0L
    private var endpointId: String? = null
    private var peer: DeviceEndpoint? = null
    private var acceptedUntrustedEndpoint: String? = null
    private var connected = false
    private var authenticated = false
    private var localNonce = ""
    private var peerNonce = ""
    private var pendingSecret: String? = null
    private var sentPairingSecret = false
    private var revision = 0L
    private var latestRemoteRevision = -1L
    private val handledCommands = LinkedHashSet<String>()

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.containsAny(
                    Player.EVENT_IS_PLAYING_CHANGED,
                    Player.EVENT_PLAY_WHEN_READY_CHANGED,
                    Player.EVENT_MEDIA_ITEM_TRANSITION,
                    Player.EVENT_TIMELINE_CHANGED,
                    Player.EVENT_POSITION_DISCONTINUITY,
                    Player.EVENT_REPEAT_MODE_CHANGED,
                    Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
                    Player.EVENT_PLAYBACK_STATE_CHANGED,
                )
            ) {
                revision++
                updateTransport()
                sendSnapshot()
            }
        }
    }

    fun attachPlayer(target: Player) {
        if (player === target) return
        player?.removeListener(playerListener)
        player = target
        target.addListener(playerListener)
        revision++
        updateTransport()
    }

    fun detachPlayer(target: Player) {
        if (player !== target) return
        target.removeListener(playerListener)
        player = null
        revision++
        if (authenticated) {
            send(DeviceConnectPacket(type = "STATE", state = DevicePlaybackState(revision = revision)))
        }
        updateTransport()
    }

    fun setForeground(value: Boolean) {
        if (foreground == value) return
        foreground = value
        if (value) {
            manuallyDisconnected = false
            discoveryUntilMs = SystemClock.elapsedRealtime() + DISCOVERY_WINDOW_MS
        } else {
            pairingMode = false
            _state.value = _state.value.copy(pairingMode = false, pairingRequest = null)
            handler.postDelayed({
                if (!foreground && player?.isPlaying != true && endpointId != null) {
                    resetConnection(closeEndpoint = true)
                    updateTransport()
                }
            }, 45_000L)
        }
        updateTransport()
    }

    fun permissionStateChanged() {
        _state.value = _state.value.copy(permissionsGranted = hasPermissions())
        updateTransport()
    }

    fun beginPairing() {
        if (!transport.available) return
        pairingMode = true
        manuallyDisconnected = false
        discoveryUntilMs = SystemClock.elapsedRealtime() + DISCOVERY_WINDOW_MS
        _state.value = _state.value.copy(pairingMode = true, status = "Searching for your other Galaxy device…")
        updateTransport()
    }

    fun endPairing() {
        if (!pairingMode || connected) return
        pairingMode = false
        _state.value = _state.value.copy(pairingMode = false, pairingRequest = null)
        updateTransport()
    }

    fun searchAgain() {
        manuallyDisconnected = false
        discoveryUntilMs = SystemClock.elapsedRealtime() + DISCOVERY_WINDOW_MS
        _state.value = _state.value.copy(status = "Searching for paired device…")
        updateTransport()
    }

    fun acceptPairing() {
        val request = _state.value.pairingRequest ?: return
        acceptedUntrustedEndpoint = request.endpointId
        _state.value = _state.value.copy(pairingRequest = null, status = "Securing connection…")
        transport.accept(request.endpointId)
    }

    fun rejectPairing() {
        val request = _state.value.pairingRequest ?: return
        _state.value = _state.value.copy(pairingRequest = null, status = "Pairing cancelled")
        transport.reject(request.endpointId)
    }

    fun disconnect() {
        manuallyDisconnected = true
        resetConnection(closeEndpoint = true)
        _state.value = _state.value.copy(status = "Disconnected by you")
        updateTransport()
    }

    fun forgetDevice() {
        disconnect()
        trust.forget()
        _state.value = _state.value.copy(trustedDeviceName = null, status = "Device forgotten")
    }

    fun sendCommand(action: String, argument: Long = 0L) {
        if (!authenticated || _state.value.remotePlayback == null) return
        send(
            DeviceConnectPacket(
                type = "COMMAND",
                action = action,
                argument = argument,
                requestId = UUID.randomUUID().toString(),
            ),
        )
    }

    override fun discovered(endpointId: String, endpointName: String) {
        if (this.endpointId != null || manuallyDisconnected || !foreground) return
        val remote = DeviceEndpoint.decode(endpointName) ?: return
        if (remote.id == ownEndpoint.id) return
        val trusted = trust.trustedDevice()?.id == remote.id
        if (!trusted && !pairingMode) return
        if (pairingMode && !trusted && ownEndpoint.id > remote.id) return
        if (player?.isPlaying == true && !pairingMode) return
        this.endpointId = endpointId
        peer = remote
        _state.value = _state.value.copy(status = "Connecting to ${remote.name}…")
        transport.connect(endpointId, ownEndpoint.encode())
    }

    override fun pairingRequested(endpointId: String, endpointName: String, verificationCode: String) {
        val remote = DeviceEndpoint.decode(endpointName) ?: run {
            transport.reject(endpointId)
            return
        }
        if (remote.id == ownEndpoint.id || manuallyDisconnected) {
            transport.reject(endpointId)
            return
        }
        if (this.endpointId != null && this.endpointId != endpointId) {
            transport.reject(endpointId)
            return
        }
        this.endpointId = endpointId
        peer = remote
        if (trust.trustedDevice()?.id == remote.id) {
            _state.value = _state.value.copy(status = "Verifying paired device…")
            transport.accept(endpointId)
        } else if (pairingMode && foreground) {
            _state.value = _state.value.copy(
                pairingRequest = DevicePairingRequest(endpointId, remote.name, verificationCode),
                status = "Compare the pairing code on both devices",
            )
        } else {
            transport.reject(endpointId)
            resetConnection(closeEndpoint = false)
        }
    }

    override fun connected(endpointId: String) {
        if (this.endpointId != endpointId) {
            transport.disconnect(endpointId)
            return
        }
        connected = true
        localNonce = trust.newNonce()
        peerNonce = ""
        transport.stopDiscovery()
        transport.stopAdvertising()
        send(DeviceConnectPacket(type = "HELLO", deviceId = ownEndpoint.id, deviceName = name, nonce = localNonce))
        handler.postDelayed({
            if (this.endpointId == endpointId && !authenticated) {
                _state.value = _state.value.copy(status = "Secure handshake timed out")
                resetConnection(closeEndpoint = true)
                updateTransport()
            }
        }, HANDSHAKE_TIMEOUT_MS)
    }

    override fun disconnected(endpointId: String) {
        if (this.endpointId != endpointId) return
        resetConnection(closeEndpoint = false)
        _state.value = _state.value.copy(status = "Connection lost")
        updateTransport()
    }

    override fun received(endpointId: String, bytes: ByteArray) {
        if (this.endpointId != endpointId || !connected) return
        val packet = DeviceConnectProtocol.decode(bytes) ?: return
        val remote = peer ?: return
        when (packet.type) {
            "HELLO" -> {
                if (packet.deviceId != remote.id || packet.nonce.length !in 16..128) {
                    resetConnection(closeEndpoint = true)
                    return
                }
                peerNonce = packet.nonce
                val saved = trust.trustedDevice()
                if (saved?.id == remote.id) {
                    send(
                        DeviceConnectPacket(
                            type = "AUTH",
                            proof = trust.proof(
                                saved.secret,
                                DeviceConnectProtocol.authenticationData(ownEndpoint.id, localNonce, remote.id, peerNonce),
                            ),
                        ),
                    )
                } else if (acceptedUntrustedEndpoint == endpointId &&
                    pairingMode && ownEndpoint.id < remote.id && !sentPairingSecret
                ) {
                    pendingSecret = trust.newSecret()
                    sentPairingSecret = true
                    send(DeviceConnectPacket(type = "PAIR", secret = pendingSecret!!))
                }
            }
            "AUTH" -> {
                val saved = trust.trustedDevice() ?: return
                if (saved.id != remote.id || peerNonce.isBlank()) return
                val expected = trust.proof(
                    saved.secret,
                    DeviceConnectProtocol.authenticationData(remote.id, peerNonce, ownEndpoint.id, localNonce),
                )
                if (constantTimeEquals(expected, packet.proof)) finishAuthentication()
                else resetConnection(closeEndpoint = true)
            }
            "PAIR" -> {
                if (acceptedUntrustedEndpoint != endpointId || !pairingMode || trust.trustedDevice() != null) return
                if (runCatching { android.util.Base64.decode(packet.secret, android.util.Base64.NO_WRAP) }.getOrNull()?.size != 32) {
                    resetConnection(closeEndpoint = true)
                    return
                }
                runCatching { trust.save(TrustedDevice(remote.id, remote.name, packet.secret)) }
                    .onSuccess {
                        send(DeviceConnectPacket(
                            type = "PAIR_ACK",
                            proof = trust.proof(packet.secret, "paired|${ownEndpoint.id}|${remote.id}"),
                        ))
                        finishAuthentication()
                    }.onFailure { failed("Could not store secure pairing") }
            }
            "PAIR_ACK" -> {
                val secret = pendingSecret ?: return
                if (!constantTimeEquals(trust.proof(secret, "paired|${remote.id}|${ownEndpoint.id}"), packet.proof)) {
                    resetConnection(closeEndpoint = true)
                    return
                }
                runCatching { trust.save(TrustedDevice(remote.id, remote.name, secret)) }
                    .onSuccess { finishAuthentication() }
                    .onFailure { failed("Could not store secure pairing") }
            }
            "GET_STATE" -> if (authenticated) sendSnapshot()
            "STATE" -> {
                if (!authenticated) return
                val snapshot = packet.state ?: return
                if (snapshot.revision < latestRemoteRevision) return
                latestRemoteRevision = snapshot.revision
                _state.value = _state.value.copy(
                    remotePlayback = snapshot.copy(updatedAtMs = SystemClock.elapsedRealtime()),
                )
            }
            "COMMAND" -> {
                if (!authenticated || packet.requestId.isBlank()) return
                applyCommand(packet)
            }
            "DISCONNECT" -> {
                resetConnection(closeEndpoint = true)
                _state.value = _state.value.copy(status = "Other device disconnected")
                updateTransport()
            }
        }
    }

    override fun failed(message: String) {
        _state.value = _state.value.copy(status = message.take(120))
        if (!connected && endpointId != null) {
            resetConnection(closeEndpoint = true)
            updateTransport()
        }
    }

    private fun applyCommand(packet: DeviceConnectPacket) {
        if (!handledCommands.add(packet.requestId)) return
        if (handledCommands.size > 64) handledCommands.remove(handledCommands.first())
        val target = player ?: return
        // The owner alone changes playback; a remote-controlled idle device never starts a stream.
        if (target.currentMediaItem == null) return
        when (packet.action) {
            "PLAY" -> target.play()
            "PAUSE" -> target.pause()
            "NEXT" -> if (target.hasNextMediaItem()) target.seekToNextMediaItem()
            "PREVIOUS" -> target.seekToPreviousMediaItem()
            "SEEK" -> target.seekTo(packet.argument.coerceIn(0, target.duration.takeIf { it > 0 } ?: Long.MAX_VALUE))
            "SHUFFLE" -> target.shuffleModeEnabled = !target.shuffleModeEnabled
            "REPEAT" -> target.repeatMode = (target.repeatMode + 1) % 3
            else -> return
        }
        revision++
        sendSnapshot()
    }

    private fun finishAuthentication() {
        if (authenticated) return
        authenticated = true
        pairingMode = false
        pendingSecret = null
        sentPairingSecret = false
        latestRemoteRevision = -1L
        handledCommands.clear()
        _state.value = _state.value.copy(
            connected = true,
            peerName = peer?.name,
            trustedDeviceName = trust.trustedDevice()?.name,
            pairingMode = false,
            pairingRequest = null,
            status = "Connected securely",
        )
        send(DeviceConnectPacket(type = "GET_STATE"))
        sendSnapshot()
    }

    private fun sendSnapshot() {
        if (!authenticated) return
        val target = player ?: return
        val current = target.currentMediaItem
        val currentIndex = target.currentMediaItemIndex
        val start = (currentIndex - 2).coerceAtLeast(0)
        val end = (start + 25).coerceAtMost(target.mediaItemCount)
        val queue = (start until end).map { index ->
            val item = target.getMediaItemAt(index)
            DeviceTrack(
                item.mediaId,
                item.mediaMetadata.title?.toString().orEmpty(),
                item.mediaMetadata.artist?.toString().orEmpty(),
                item.mediaMetadata.artworkUri?.toString(),
            )
        }
        send(
            DeviceConnectPacket(
                type = "STATE",
                state = DevicePlaybackState(
                    revision = revision,
                    track = current?.let {
                        DeviceTrack(
                            it.mediaId,
                            it.mediaMetadata.title?.toString().orEmpty(),
                            it.mediaMetadata.artist?.toString().orEmpty(),
                            it.mediaMetadata.artworkUri?.toString(),
                        )
                    },
                    playing = target.isPlaying,
                    positionMs = target.currentPosition.coerceAtLeast(0),
                    durationMs = target.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0,
                    updatedAtMs = SystemClock.elapsedRealtime(),
                    shuffle = target.shuffleModeEnabled,
                    repeatMode = target.repeatMode,
                    queueIndex = currentIndex,
                    queueSize = target.mediaItemCount,
                    queue = queue,
                ),
            ),
        )
    }

    private fun send(packet: DeviceConnectPacket) {
        val current = endpointId ?: return
        runCatching { DeviceConnectProtocol.encode(packet) }
            .onSuccess { transport.send(current, it) }
            .onFailure { failed("Playback metadata too large to synchronize") }
    }

    private fun updateTransport() {
        val permitted = hasPermissions()
        _state.value = _state.value.copy(permissionsGranted = permitted)
        if (!transport.available || !permitted || manuallyDisconnected || connected) {
            transport.stopAdvertising()
            transport.stopDiscovery()
            return
        }
        val hasSession = player?.isPlaying == true || player?.playWhenReady == true
        if (trust.trustedDevice() != null && hasSession || pairingMode) {
            transport.advertise(ownEndpoint.encode())
        } else {
            transport.stopAdvertising()
        }
        if (foreground && (pairingMode || !hasSession) &&
            SystemClock.elapsedRealtime() < discoveryUntilMs
        ) {
            transport.discover()
            handler.postDelayed({
                if (SystemClock.elapsedRealtime() >= discoveryUntilMs && !connected) {
                    transport.stopDiscovery()
                }
            }, DISCOVERY_WINDOW_MS)
        } else {
            transport.stopDiscovery()
        }
    }

    private fun hasPermissions(): Boolean =
        requiredPermissions().all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

    private fun resetConnection(closeEndpoint: Boolean) {
        val id = endpointId
        if (id != null && closeEndpoint) transport.disconnect(id)
        endpointId = null
        peer = null
        connected = false
        authenticated = false
        acceptedUntrustedEndpoint = null
        pendingSecret = null
        sentPairingSecret = false
        localNonce = ""
        peerNonce = ""
        latestRemoteRevision = -1L
        handledCommands.clear()
        _state.value = _state.value.copy(
            connected = false, peerName = null, remotePlayback = null, pairingRequest = null,
        )
    }

    private fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.encodeToByteArray(), b.encodeToByteArray())
}
