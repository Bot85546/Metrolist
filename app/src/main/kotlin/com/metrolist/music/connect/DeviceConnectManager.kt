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
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.metrolist.music.playback.queues.Queue
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
    private val preferences = context.getSharedPreferences("device_connect_settings", Context.MODE_PRIVATE)
    private var trustedPeer = trust.trustedDevice()
    private val transport = DeviceConnectTransportFactory.create(context).also { it.listener = this }
    private val handler = Handler(Looper.getMainLooper())
    private val name = Build.MODEL.take(45).replace('|', ' ')
    private val ownEndpoint = DeviceEndpoint(trust.localId, name)
    private val _state = MutableStateFlow(
        DeviceConnectUiState(
            available = transport.available,
            trustedDeviceName = trustedPeer?.name,
        ),
    )
    val state = _state.asStateFlow()

    private var player: Player? = null
    var remoteMirrorPlayer: RemoteMirrorPlayer? = null
        private set
    var onRemoteLibraryCommand: ((String) -> Unit)? = null
    var onRemoteQueue: ((String?, List<DeviceTrack>, Int) -> Unit)? = null
    var onRemoteQueueAdd: ((Boolean, List<DeviceTrack>) -> Unit)? = null
    var queueTitleProvider: (() -> String?)? = null

    private data class IncomingQueue(
        val id: String,
        val title: String?,
        val startIndex: Int,
        val receivedAtMs: Long,
        val items: MutableList<DeviceTrack?>,
        var completed: Boolean = false,
    )

    private var incomingQueue: IncomingQueue? = null

    val isRemoteActive: Boolean
        get() = authenticated && _state.value.remotePlayback?.track != null &&
            player?.isPlaying != true && player?.playWhenReady != true
    private var foreground = false
    private var pairingMode = false
    private var manuallyDisconnected = preferences.getBoolean("manually_disconnected", false)
    private var discoveryUntilMs = 0L
    private var reconnectAttempts = 0
    private var reconnectGeneration = 0L
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
            if (events.contains(Player.EVENT_IS_PLAYING_CHANGED) &&
                player.isPlaying && _state.value.remotePlayback?.playing == true
            ) {
                // Starting playback here transfers the active role to this device.
                sendCommand("PAUSE")
            }
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
        remoteMirrorPlayer = RemoteMirrorPlayer(target, this)
        target.addListener(playerListener)
        revision++
        updateTransport()
    }

    fun detachPlayer(target: Player) {
        if (player !== target) return
        target.removeListener(playerListener)
        remoteMirrorPlayer = null
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
            reconnectAttempts = 0
            reconnectGeneration++
            discoveryUntilMs = SystemClock.elapsedRealtime() + DISCOVERY_WINDOW_MS
        } else {
            pairingMode = false
            _state.value = _state.value.copy(pairingMode = false, pairingRequest = null)
            // Keep an established remote-control link after the UI is closed.
            // Only discovery is stopped: it is unnecessary once paired.
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
        preferences.edit().putBoolean("manually_disconnected", false).apply()
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
        preferences.edit().putBoolean("manually_disconnected", false).apply()
        reconnectAttempts = 0
        reconnectGeneration++
        discoveryUntilMs = SystemClock.elapsedRealtime() + DISCOVERY_WINDOW_MS
        _state.value = _state.value.copy(status = "Searching for paired device…")
        updateTransport()
    }

    fun connectToTrustedDevice() = searchAgain()

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
        preferences.edit().putBoolean("manually_disconnected", true).apply()
        reconnectGeneration++
        resetConnection(closeEndpoint = true)
        _state.value = _state.value.copy(status = "Disconnected by you")
        updateTransport()
    }

    fun forgetDevice() {
        disconnect()
        trust.forget()
        trustedPeer = null
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

    /** Transfers the selected playlist without decoding or streaming any audio on this device. */
    fun submitRemoteQueue(status: Queue.Status) {
        if (!isRemoteActive || status.items.isEmpty()) return
        val source = status.items.take(1500)
        val transferId = UUID.randomUUID().toString()
        send(DeviceConnectPacket(
            type = "QUEUE_BEGIN", transferId = transferId,
            queueTitle = status.title.orEmpty(), queueTotal = source.size,
            queueStart = status.mediaItemIndex.coerceIn(0, source.lastIndex),
        ))
        source.chunked(12).forEachIndexed { chunk, items ->
            send(DeviceConnectPacket(
                type = "QUEUE_BATCH", transferId = transferId, queueStart = chunk * 12,
                queueItems = items.map(::asDeviceTrack),
            ))
        }
        send(DeviceConnectPacket(type = "QUEUE_END", transferId = transferId))
    }

    fun submitRemoteQueueAddition(items: List<MediaItem>, playNext: Boolean) {
        if (!isRemoteActive) return
        val batches = items.take(1500).chunked(12)
        val orderedBatches = if (playNext) batches.reversed() else batches
        orderedBatches.forEach { batch ->
            send(DeviceConnectPacket(
                type = "QUEUE_ADD",
                action = if (playNext) "NEXT" else "END",
                queueItems = batch.map(::asDeviceTrack),
            ))
        }
    }

    private fun asDeviceTrack(item: MediaItem): DeviceTrack = DeviceTrack(
        item.mediaId,
        item.mediaMetadata.title?.toString().orEmpty(),
        item.mediaMetadata.artist?.toString().orEmpty(),
        item.mediaMetadata.artworkUri?.toString(),
    )

    override fun discovered(endpointId: String, endpointName: String) {
        if (this.endpointId != null || manuallyDisconnected || !foreground) return
        val remote = DeviceEndpoint.decode(endpointName) ?: return
        if (remote.id == ownEndpoint.id) return
        val trusted = trustedPeer?.id == remote.id
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
        if (trustedPeer?.id == remote.id) {
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
                val saved = trustedPeer
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
                val saved = trustedPeer ?: return
                if (saved.id != remote.id || peerNonce.isBlank()) return
                val expected = trust.proof(
                    saved.secret,
                    DeviceConnectProtocol.authenticationData(remote.id, peerNonce, ownEndpoint.id, localNonce),
                )
                if (constantTimeEquals(expected, packet.proof)) finishAuthentication()
                else resetConnection(closeEndpoint = true)
            }
            "PAIR" -> {
                if (acceptedUntrustedEndpoint != endpointId || !pairingMode || trustedPeer != null) return
                if (runCatching { android.util.Base64.decode(packet.secret, android.util.Base64.NO_WRAP) }.getOrNull()?.size != 32) {
                    resetConnection(closeEndpoint = true)
                    return
                }
                runCatching { trust.save(TrustedDevice(remote.id, remote.name, packet.secret)) }
                    .onSuccess {
                        trustedPeer = TrustedDevice(remote.id, remote.name, packet.secret)
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
                    .onSuccess {
                        trustedPeer = TrustedDevice(remote.id, remote.name, secret)
                        finishAuthentication()
                    }
                    .onFailure { failed("Could not store secure pairing") }
            }
            "GET_STATE" -> if (authenticated) sendSnapshot()
            "STATE" -> {
                if (!authenticated) return
                val snapshot = packet.state ?: return
                if (snapshot.revision < latestRemoteRevision) return
                latestRemoteRevision = snapshot.revision
                val oldSnapshot = _state.value.remotePlayback
                _state.value = _state.value.copy(
                    remotePlayback = snapshot.copy(updatedAtMs = SystemClock.elapsedRealtime()),
                )
                remoteMirrorPlayer?.notifyStateChanged(oldSnapshot, _state.value.remotePlayback)
            }
            "COMMAND" -> {
                if (!authenticated || packet.requestId.isBlank()) return
                applyCommand(packet)
            }
            "QUEUE_BEGIN" -> {
                if (!authenticated || packet.transferId.length !in 16..64 || packet.queueTotal !in 1..1500 ||
                    packet.queueStart !in 0 until packet.queueTotal
                ) return
                incomingQueue = IncomingQueue(
                    id = packet.transferId,
                    title = packet.queueTitle.take(160).ifBlank { null },
                    startIndex = packet.queueStart,
                    receivedAtMs = SystemClock.elapsedRealtime(),
                    items = MutableList(packet.queueTotal) { null },
                )
            }
            "QUEUE_BATCH" -> {
                if (!authenticated) return
                val pending = incomingQueue ?: return
                if (pending.id != packet.transferId ||
                    SystemClock.elapsedRealtime() - pending.receivedAtMs > 30_000L ||
                    packet.queueItems.isEmpty() || packet.queueItems.size > 12 ||
                    packet.queueStart < 0 || packet.queueStart + packet.queueItems.size > pending.items.size
                ) return
                packet.queueItems.forEachIndexed { index, track ->
                    pending.items[packet.queueStart + index] = track
                }
                commitIncomingQueueIfReady(pending)
            }
            "QUEUE_END" -> {
                if (!authenticated) return
                val pending = incomingQueue ?: return
                if (pending.id != packet.transferId) return
                pending.completed = true
                commitIncomingQueueIfReady(pending)
            }
            "QUEUE_ADD" -> {
                if (!authenticated || packet.action !in listOf("NEXT", "END") ||
                    packet.queueItems.isEmpty() || packet.queueItems.size > 12
                ) return
                onRemoteQueueAdd?.invoke(packet.action == "NEXT", packet.queueItems)
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
            "PLAY" -> {
                if (target.playbackState == Player.STATE_IDLE) target.prepare()
                target.play()
            }
            "PAUSE" -> target.pause()
            "NEXT" -> if (target.hasNextMediaItem()) target.seekToNextMediaItem()
            "PREVIOUS" -> target.seekToPreviousMediaItem()
            "SEEK_QUEUE" -> {
                val index = packet.argument.toInt()
                if (packet.argument != index.toLong() || index !in 0 until target.mediaItemCount) return
                target.seekTo(index, 0L)
            }
            "REMOVE_QUEUE" -> {
                val index = packet.argument.toInt()
                if (index.toLong() != packet.argument || index !in 0 until target.mediaItemCount) return
                target.removeMediaItem(index)
            }
            "MOVE_QUEUE" -> {
                val from = (packet.argument ushr 32).toInt()
                val to = packet.argument.toInt()
                if (from !in 0 until target.mediaItemCount || to !in 0 until target.mediaItemCount) return
                target.moveMediaItem(from, to)
            }
            "SEEK" -> target.seekTo(packet.argument.coerceIn(0, target.duration.takeIf { it > 0 } ?: Long.MAX_VALUE))
            "SHUFFLE" -> target.shuffleModeEnabled = !target.shuffleModeEnabled
            "REPEAT" -> target.repeatMode = (target.repeatMode + 1) % 3
            "SET_REPEAT" -> if (packet.argument in 0L..2L) target.repeatMode = packet.argument.toInt()
            "TOGGLE_LIKE", "TOGGLE_LIBRARY" -> onRemoteLibraryCommand?.invoke(packet.action)
            else -> return
        }
        revision++
        sendSnapshot()
    }

    private fun commitIncomingQueueIfReady(pending: IncomingQueue) {
        if (!pending.completed || pending.items.any { it == null }) return
        incomingQueue = null
        onRemoteQueue?.invoke(pending.title, pending.items.filterNotNull(), pending.startIndex)
    }

    private fun finishAuthentication() {
        if (authenticated) return
        authenticated = true
        reconnectAttempts = 0
        reconnectGeneration++
        pairingMode = false
        pendingSecret = null
        sentPairingSecret = false
        latestRemoteRevision = -1L
        handledCommands.clear()
        _state.value = _state.value.copy(
            connected = true,
            peerName = peer?.name,
            trustedDeviceName = trustedPeer?.name,
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
                    queueStartIndex = start,
                    queue = queue,
                    queueTitle = queueTitleProvider?.invoke()?.take(160),
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
        if ((trustedPeer != null && (hasSession || foreground)) || pairingMode) {
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

    private fun scheduleReconnect() {
        if (!foreground || manuallyDisconnected || trustedPeer == null || endpointId != null) return
        if (reconnectAttempts >= 4) {
            _state.value = _state.value.copy(status = "Connection lost. Tap Search again to retry.")
            return
        }
        val attempt = ++reconnectAttempts
        val generation = ++reconnectGeneration
        val delayMs = (1_000L shl (attempt - 1)).coerceAtMost(8_000L)
        handler.postDelayed({
            if (generation != reconnectGeneration || !foreground || manuallyDisconnected ||
                connected || endpointId != null
            ) return@postDelayed
            discoveryUntilMs = SystemClock.elapsedRealtime() + DISCOVERY_WINDOW_MS
            updateTransport()
        }, delayMs)
    }

    private fun hasPermissions(): Boolean =
        requiredPermissions().all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

    private fun resetConnection(closeEndpoint: Boolean) {
        val previous = _state.value.remotePlayback
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
        incomingQueue = null
        _state.value = _state.value.copy(
            connected = false, peerName = null, remotePlayback = null, pairingRequest = null,
        )
        remoteMirrorPlayer?.notifyStateChanged(previous, null)
        scheduleReconnect()
    }

    private fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.encodeToByteArray(), b.encodeToByteArray())
}
