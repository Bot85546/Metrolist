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
import com.metrolist.music.extensions.metadata
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
    val enabled: Boolean = false,
    val status: String = "Not connected",
    val trustedDeviceName: String? = null,
    val pairingMode: Boolean = false,
    val pairingRequest: DevicePairingRequest? = null,
    val connected: Boolean = false,
    val peerName: String? = null,
    val remotePlayback: DevicePlaybackState? = null,
)

data class DeviceSleepTimerSnapshot(
    val remainingMs: Long = -1L,
    val afterSong: Boolean = false,
    val stopAfterSong: Boolean = false,
    val fadeOut: Boolean = false,
)

@Singleton
class DeviceConnectManager @Inject constructor(
    @ApplicationContext private val context: Context,
) : DeviceConnectTransport.Listener {
    companion object {
        private const val DISCOVERY_WINDOW_MS = 12_000L
        private const val PAIRING_DISCOVERY_WINDOW_MS = 45_000L
        private const val HANDSHAKE_TIMEOUT_MS = 15_000L
        private const val WARM_DISCONNECT_MS = 120_000L

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
    private var manuallyDisconnected = preferences.getBoolean("manually_disconnected", false)
    private var trustedPeer = trust.trustedDevice()
    private val transport = DeviceConnectTransportFactory.create(context).also { it.listener = this }
    private val handler = Handler(Looper.getMainLooper())
    private val name = Build.MODEL.take(45).replace('|', ' ')
    private val ownEndpoint = DeviceEndpoint(trust.localId, name)
    private val _state = MutableStateFlow(
        DeviceConnectUiState(
            available = transport.available,
            enabled = trustedPeer != null && !manuallyDisconnected,
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
    var sleepTimerProvider: (() -> DeviceSleepTimerSnapshot)? = null
    var volumeProvider: (() -> Float)? = null
    var mutedProvider: (() -> Boolean)? = null
    var likedProvider: (() -> Boolean?)? = null
    var onRemoteStopCommand: (() -> Unit)? = null
    var onRemoteOutputReleased: (() -> Unit)? = null
    var onRemoteOutputHandoff: ((DevicePlaybackState, List<DeviceTrack>) -> Unit)? = null
    var onRemoteSleepTimerCommand: ((Int, Boolean, Boolean) -> Unit)? = null
    var onRemoteVolumeCommand: ((Float) -> Unit)? = null
    var onRemoteMuteCommand: (() -> Unit)? = null

    private data class IncomingQueue(
        val id: String,
        val title: String?,
        val startIndex: Int,
        val receivedAtMs: Long,
        val items: MutableList<DeviceTrack?>,
        var completed: Boolean = false,
    )

    private var incomingQueue: IncomingQueue? = null
    private data class IncomingMirrorQueue(
        val id: String,
        val revision: Long,
        val receivedAtMs: Long,
        val items: MutableList<DeviceTrack?>,
        var completed: Boolean = false,
    )
    private var incomingMirrorQueue: IncomingMirrorQueue? = null
    private var cachedMirrorQueue: Pair<Long, List<DeviceTrack>>? = null
    private var localQueueRevision = 1L
    private var sentQueueRevision = -1L
    private var acknowledgedQueueRevision = -1L
    private var queueRetryGeneration = 0L
    private var queueRetryCount = 0
    private var pendingTakeoverId: String? = null
    private var pendingTakeoverQueue: List<DeviceTrack>? = null
    private var pendingTakeoverQueueRevision = -1L
    private var lastLocalSnapshot: DevicePlaybackState? = null

    val isRemoteActive: Boolean
        get() = authenticated && syncEnabled && remoteOwnsPlayback
    private var foreground = false
    private var pairingMode = false
    // A manual disconnect suspends synchronization but keeps the already-authenticated Nearby
    // channel warm. Reconnecting within the same app process then needs no Bluetooth discovery.
    private var syncEnabled = true
    // A linked controller must not become a second audio output merely because a local
    // playback request slips through. Only a deliberate disconnect releases ownership.
    private var remoteOwnsPlayback = false
    private var suspendGeneration = 0L
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
                player.isPlaying && isRemoteActive
            ) {
                // Accidental local playback must never interrupt the remote owner.
                player.pause()
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
                    Player.EVENT_PLAYBACK_PARAMETERS_CHANGED,
                    Player.EVENT_VOLUME_CHANGED,
                )
            ) {
                if (events.contains(Player.EVENT_TIMELINE_CHANGED) ||
                    events.contains(Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED)
                ) {
                    localQueueRevision++
                    queueRetryCount = 0
                    acknowledgedQueueRevision = -1L
                }
                revision++
                updateTransport()
                if (!isRemoteActive) sendSnapshot()
            }
        }
    }

    fun attachPlayer(target: Player) {
        if (player === target) return
        player?.removeListener(playerListener)
        player = target
        remoteMirrorPlayer = RemoteMirrorPlayer(target, this)
        target.addListener(playerListener)
        localQueueRevision++
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
        discoveryUntilMs = SystemClock.elapsedRealtime() + PAIRING_DISCOVERY_WINDOW_MS
        _state.value = _state.value.copy(enabled = true, pairingMode = true, status = "Searching for your other Galaxy device…")
        // A fresh attempt must rediscover endpoints that Nearby may have cached before Forget.
        if (!connected) transport.stopDiscovery()
        updateTransport()
    }

    fun endPairing() {
        if (!pairingMode || connected) return
        pairingMode = false
        _state.value = _state.value.copy(pairingMode = false, pairingRequest = null)
        updateTransport()
    }

    fun searchAgain() {
        if (authenticated) {
            resumeSync()
            return
        }
        manuallyDisconnected = false
        preferences.edit().putBoolean("manually_disconnected", false).apply()
        reconnectAttempts = 0
        reconnectGeneration++
        discoveryUntilMs = SystemClock.elapsedRealtime() + DISCOVERY_WINDOW_MS
        _state.value = _state.value.copy(enabled = true, status = "Searching for paired device…")
        updateTransport()
    }

    fun connectToTrustedDevice() = searchAgain()

    fun setEnabled(enabled: Boolean) {
        if (enabled) {
            if (trustedPeer == null) beginPairing() else searchAgain()
        } else {
            disconnect()
        }
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
        preferences.edit().putBoolean("manually_disconnected", true).apply()
        _state.value = _state.value.copy(enabled = false)
        reconnectGeneration++
        if (authenticated) {
            send(DeviceConnectPacket(type = "SYNC_MODE", action = "OFF"))
            suspendSync()
            return
        }
        resetConnection(closeEndpoint = true)
        _state.value = _state.value.copy(status = "Disconnected by you")
        updateTransport()
    }

    fun forgetDevice() {
        disconnect()
        resetConnection(closeEndpoint = true)
        trust.forget()
        trustedPeer = null
        manuallyDisconnected = false
        preferences.edit().putBoolean("manually_disconnected", false).apply()
        _state.value = _state.value.copy(trustedDeviceName = null, status = "Device forgotten. Searching again…")
        beginPairing()
    }

    fun sendCommand(action: String, argument: Long = 0L) {
        if (!authenticated || !syncEnabled || _state.value.remotePlayback == null) return
        send(
            DeviceConnectPacket(
                type = "COMMAND",
                action = action,
                argument = argument,
                requestId = UUID.randomUUID().toString(),
            ),
        )
    }

    fun sendPlaybackParameters(speed: Float, pitch: Float) {
        if (!authenticated || !syncEnabled || !speed.isFinite() || !pitch.isFinite()) return
        if (speed !in 0.25f..4f || pitch !in 0.25f..4f) return
        send(DeviceConnectPacket(type = "COMMAND", action = "SET_PLAYBACK_PARAMETERS",
            speed = speed, pitch = pitch, requestId = UUID.randomUUID().toString()))
    }

    fun sendSleepTimer(minute: Int, stopAfterSong: Boolean, fadeOut: Boolean) {
        if (!authenticated || !syncEnabled || minute !in -1..120) return
        send(DeviceConnectPacket(type = "COMMAND", action = "SLEEP_TIMER",
            argument = minute.toLong(), stopAfterSong = stopAfterSong, fadeOut = fadeOut,
            requestId = UUID.randomUUID().toString()))
    }

    fun sendVolume(volume: Float) {
        if (!authenticated || !syncEnabled || !volume.isFinite()) return
        send(DeviceConnectPacket(type = "COMMAND", action = "SET_VOLUME",
            volume = volume.coerceIn(0f, 1f), requestId = UUID.randomUUID().toString()))
    }

    val canSwitchOutput: Boolean
        get() = isRemoteActive && _state.value.remotePlayback?.let { snapshot ->
            snapshot.track != null && snapshot.queueSize in 1..1500 &&
                snapshot.queue.size == snapshot.queueSize &&
                snapshot.queue.all { it.sourceIndex in 0 until snapshot.queueSize }
        } == true

    /** Release the current audio owner only after the controller has its full ordered queue. */
    fun requestOutputSwitch(): Boolean {
        if (!canSwitchOutput || pendingTakeoverId != null) {
            if (isRemoteActive) send(DeviceConnectPacket(type = "GET_QUEUE"))
            return false
        }
        val snapshot = _state.value.remotePlayback ?: return false
        val tracks = snapshot.queue
        val requestId = UUID.randomUUID().toString()
        pendingTakeoverId = requestId
        pendingTakeoverQueue = tracks
        pendingTakeoverQueueRevision = snapshot.queueRevision
        send(DeviceConnectPacket(type = "OUTPUT_TAKEOVER_REQUEST", requestId = requestId,
            queueRevision = snapshot.queueRevision))
        handler.postDelayed({
            if (pendingTakeoverId == requestId) {
                pendingTakeoverId = null
                pendingTakeoverQueue = null
                pendingTakeoverQueueRevision = -1L
                _state.value = _state.value.copy(status = "Output switch timed out — try again")
            }
        }, HANDSHAKE_TIMEOUT_MS)
        return true
    }

    fun refreshSnapshot() {
        if (!authenticated || !syncEnabled) return
        if (isRemoteActive) {
            send(DeviceConnectPacket(type = "GET_STATE"))
            return
        }
        revision++
        sendSnapshot()
    }

    private fun suspendSync() {
        if (!authenticated) return
        val previous = _state.value.remotePlayback
        syncEnabled = false
        remoteOwnsPlayback = false
        incomingQueue = null
        incomingMirrorQueue = null
        cachedMirrorQueue = null
        queueRetryGeneration++
        acknowledgedQueueRevision = -1L
        pendingTakeoverId = null
        pendingTakeoverQueue = null
        pendingTakeoverQueueRevision = -1L
        latestRemoteRevision = -1L
        _state.value = _state.value.copy(connected = false, remotePlayback = null,
            status = "Disconnected (ready to reconnect)")
        remoteMirrorPlayer?.notifyStateChanged(previous, null)
        // No command or heartbeat traffic while disconnected; release the Nearby radio after
        // a short grace period rather than keeping an idle link alive indefinitely.
        val generation = ++suspendGeneration
        handler.postDelayed({
            if (generation == suspendGeneration && authenticated && !syncEnabled) {
                resetConnection(closeEndpoint = true)
                _state.value = _state.value.copy(status = "Disconnected")
            }
        }, WARM_DISCONNECT_MS)
    }

    private fun resumeSync() {
        suspendGeneration++
        manuallyDisconnected = false
        preferences.edit().putBoolean("manually_disconnected", false).apply()
        syncEnabled = true
        remoteOwnsPlayback = false
        sentQueueRevision = -1L
        queueRetryCount = 0
        acknowledgedQueueRevision = -1L
        _state.value = _state.value.copy(enabled = true, connected = true, status = "Connected securely")
        send(DeviceConnectPacket(type = "SYNC_MODE",
            action = if (player?.isPlaying == true) "KEEP_SENDER" else "KEEP_RECEIVER"))
        send(DeviceConnectPacket(type = "GET_STATE"))
        sendSnapshot()
    }

    /** Transfers the selected playlist without decoding or streaming any audio on this device. */
    fun submitRemoteQueue(status: Queue.Status) {
        submitRemoteMediaItems(status.items, status.mediaItemIndex, status.title)
    }

    fun submitRemoteMediaItems(items: List<MediaItem>, startIndex: Int, title: String? = null) {
        if (!isRemoteActive || items.isEmpty()) return
        val source = items.take(1500)
        val transferId = UUID.randomUUID().toString()
        send(DeviceConnectPacket(
            type = "QUEUE_BEGIN", transferId = transferId,
            queueTitle = title.orEmpty(), queueTotal = source.size,
            queueStart = startIndex.coerceIn(0, source.lastIndex),
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
        item.metadata?.duration?.coerceAtLeast(0) ?: 0,
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
            "SYNC_MODE" -> {
                if (!authenticated) return
                when (packet.action) {
                    "OFF" -> {
                        manuallyDisconnected = true
                        preferences.edit().putBoolean("manually_disconnected", true).apply()
                        _state.value = _state.value.copy(enabled = false)
                        suspendSync()
                    }
                    "KEEP_SENDER", "KEEP_RECEIVER" -> {
                        manuallyDisconnected = false
                        preferences.edit().putBoolean("manually_disconnected", false).apply()
                        syncEnabled = true
                        _state.value = _state.value.copy(enabled = true, connected = true, status = "Connected securely")
                        // Reconnecting must never pause an already-playing audio owner.
                        sendSnapshot()
                    }
                }
            }
            "GET_STATE" -> if (authenticated && syncEnabled) {
                sentQueueRevision = -1L
                queueRetryCount = 0
                sendSnapshot()
            }
            "GET_QUEUE" -> if (authenticated && syncEnabled) {
                sentQueueRevision = -1L
                queueRetryCount = 0
                sendSnapshot()
            }
            "STATE" -> {
                if (!authenticated || !syncEnabled) return
                val snapshot = packet.state ?: return
                if (snapshot.revision < latestRemoteRevision) return
                latestRemoteRevision = snapshot.revision
                val oldSnapshot = _state.value.remotePlayback
                if (!remoteOwnsPlayback && snapshot.track != null &&
                    (snapshot.playing || player?.currentMediaItem == null) &&
                    player?.isPlaying != true
                ) {
                    remoteOwnsPlayback = true
                    // A local player may be buffering with playWhenReady=true. Prevent it
                    // from producing a second audio stream after remote ownership is chosen.
                    if (player?.playWhenReady == true) player?.pause()
                }
                val fullQueue = cachedMirrorQueue?.takeIf {
                    it.first == snapshot.queueRevision && it.second.size == snapshot.queueSize
                }?.second
                if (snapshot.track == null && pendingTakeoverId == null) cachedMirrorQueue = null
                _state.value = _state.value.copy(remotePlayback = snapshot.copy(
                    updatedAtMs = SystemClock.elapsedRealtime(),
                    queue = if (snapshot.track == null) emptyList() else fullQueue ?: snapshot.queue,
                    queueStartIndex = if (fullQueue != null || snapshot.track == null) 0 else snapshot.queueStartIndex,
                ))
                remoteMirrorPlayer?.notifyStateChanged(oldSnapshot, _state.value.remotePlayback)
            }
            "MIRROR_QUEUE_BEGIN" -> {
                if (!authenticated || !syncEnabled || packet.transferId.length !in 16..64 ||
                    packet.queueTotal !in 1..1500
                ) return
                incomingMirrorQueue = IncomingMirrorQueue(
                    packet.transferId, packet.queueRevision, SystemClock.elapsedRealtime(),
                    MutableList(packet.queueTotal) { null },
                )
            }
            "MIRROR_QUEUE_BATCH" -> {
                if (!authenticated || !syncEnabled) return
                val pending = incomingMirrorQueue ?: return
                if (pending.id != packet.transferId ||
                    SystemClock.elapsedRealtime() - pending.receivedAtMs > 30_000L ||
                    packet.queueItems.isEmpty() || packet.queueItems.size > 12 ||
                    packet.queueStart < 0 || packet.queueStart + packet.queueItems.size > pending.items.size
                ) return
                packet.queueItems.forEachIndexed { index, track ->
                    pending.items[packet.queueStart + index] = track
                }
                commitMirrorQueueIfReady(pending)
            }
            "MIRROR_QUEUE_END" -> {
                if (!authenticated || !syncEnabled) return
                val pending = incomingMirrorQueue ?: return
                if (pending.id != packet.transferId) return
                pending.completed = true
                commitMirrorQueueIfReady(pending)
            }
            "MIRROR_QUEUE_ACK" -> {
                if (!authenticated || !syncEnabled || packet.queueRevision != localQueueRevision) return
                acknowledgedQueueRevision = packet.queueRevision
                queueRetryGeneration++
            }
            "OUTPUT_TAKEOVER_REQUEST" -> {
                if (!authenticated || !syncEnabled || isRemoteActive ||
                    packet.requestId.length !in 16..64 || !handledCommands.add(packet.requestId)
                ) return
                val target = player ?: return
                if (target.currentMediaItem == null || target.mediaItemCount !in 1..1500) return
                if (packet.queueRevision != localQueueRevision) {
                    send(DeviceConnectPacket(type = "OUTPUT_TAKEOVER_REJECTED", requestId = packet.requestId))
                    sendSnapshot()
                    return
                }
                sendSnapshot()
                val handoff = lastLocalSnapshot ?: return
                target.pause()
                onRemoteOutputReleased?.invoke()
                target.stop()
                target.clearMediaItems()
                val prior = _state.value.remotePlayback
                remoteOwnsPlayback = true
                val pendingRemote = handoff.copy(playing = false, updatedAtMs = SystemClock.elapsedRealtime())
                _state.value = _state.value.copy(remotePlayback = pendingRemote, status = "Switching audio output…")
                remoteMirrorPlayer?.notifyStateChanged(prior, pendingRemote)
                send(DeviceConnectPacket(type = "OUTPUT_TAKEOVER_READY", requestId = packet.requestId,
                    state = handoff))
            }
            "OUTPUT_TAKEOVER_READY" -> {
                if (!authenticated || !syncEnabled || packet.requestId != pendingTakeoverId) return
                val handoff = packet.state ?: return
                val fullQueue = pendingTakeoverQueue ?: return
                if (handoff.track == null || handoff.queueRevision != pendingTakeoverQueueRevision ||
                    fullQueue.size != handoff.queueSize ||
                    fullQueue.none { it.sourceIndex == handoff.queueIndex }
                ) return
                pendingTakeoverId = null
                pendingTakeoverQueue = null
                pendingTakeoverQueueRevision = -1L
                val old = _state.value.remotePlayback
                remoteOwnsPlayback = false
                _state.value = _state.value.copy(remotePlayback = null, status = "Playing on this device")
                remoteMirrorPlayer?.notifyStateChanged(old, null)
                onRemoteOutputHandoff?.invoke(handoff, fullQueue)
            }
            "OUTPUT_TAKEOVER_REJECTED" -> {
                if (!authenticated || packet.requestId != pendingTakeoverId) return
                pendingTakeoverId = null
                pendingTakeoverQueue = null
                pendingTakeoverQueueRevision = -1L
                _state.value = _state.value.copy(status = "Queue changed — retry output switch")
                send(DeviceConnectPacket(type = "GET_QUEUE"))
            }
            "COMMAND" -> {
                if (!authenticated || !syncEnabled || packet.requestId.isBlank()) return
                applyCommand(packet)
            }
            "QUEUE_BEGIN" -> {
                if (!authenticated || !syncEnabled || packet.transferId.length !in 16..64 || packet.queueTotal !in 1..1500 ||
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
                if (!authenticated || !syncEnabled) return
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
                if (!authenticated || !syncEnabled) return
                val pending = incomingQueue ?: return
                if (pending.id != packet.transferId) return
                pending.completed = true
                commitIncomingQueueIfReady(pending)
            }
            "QUEUE_ADD" -> {
                if (!authenticated || !syncEnabled || packet.action !in listOf("NEXT", "END") ||
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
        } else if (!connected) {
            schedulePairingDiscoveryRetry()
        }
    }

    private fun schedulePairingDiscoveryRetry() {
        if (!pairingMode || !foreground || SystemClock.elapsedRealtime() >= discoveryUntilMs) return
        val generation = ++reconnectGeneration
        handler.postDelayed({
            if (generation == reconnectGeneration && pairingMode && foreground &&
                endpointId == null && SystemClock.elapsedRealtime() < discoveryUntilMs
            ) {
                transport.stopDiscovery()
                transport.stopAdvertising()
                updateTransport()
            }
        }, 1_500L)
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
            "STOP_AND_CLEAR" -> {
                onRemoteStopCommand?.invoke()
                target.stop()
                target.clearMediaItems()
            }
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
            "SET_PLAYBACK_PARAMETERS" -> {
                if (!packet.speed.isFinite() || !packet.pitch.isFinite() ||
                    packet.speed !in 0.25f..4f || packet.pitch !in 0.25f..4f) return
                target.playbackParameters = androidx.media3.common.PlaybackParameters(packet.speed, packet.pitch)
            }
            "SET_VOLUME" -> {
                if (!packet.volume.isFinite() || packet.volume !in 0f..1f) return
                onRemoteVolumeCommand?.invoke(packet.volume)
            }
            "TOGGLE_MUTE" -> onRemoteMuteCommand?.invoke()
            "SLEEP_TIMER" -> {
                val minutes = packet.argument.toInt()
                if (packet.argument != minutes.toLong() || minutes !in -1..120) return
                onRemoteSleepTimerCommand?.invoke(minutes, packet.stopAfterSong, packet.fadeOut)
            }
            "TOGGLE_LIKE", "TOGGLE_LIBRARY", "START_RADIO", "ADD_TO_TARGET_PLAYLIST" ->
                onRemoteLibraryCommand?.invoke(packet.action)
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

    private fun commitMirrorQueueIfReady(pending: IncomingMirrorQueue) {
        if (!pending.completed || pending.items.any { it == null }) return
        incomingMirrorQueue = null
        val fullQueue = pending.items.filterNotNull()
        cachedMirrorQueue = pending.revision to fullQueue
        send(DeviceConnectPacket(type = "MIRROR_QUEUE_ACK", queueRevision = pending.revision))
        val old = _state.value.remotePlayback ?: return
        if (old.queueRevision != pending.revision || old.queueSize != fullQueue.size || old.track == null) return
        val updated = old.copy(queue = fullQueue, queueStartIndex = 0)
        _state.value = _state.value.copy(remotePlayback = updated)
        remoteMirrorPlayer?.notifyStateChanged(old, updated)
    }

    private fun finishAuthentication() {
        if (authenticated) return
        authenticated = true
        syncEnabled = true
        remoteOwnsPlayback = false
        reconnectAttempts = 0
        reconnectGeneration++
        pairingMode = false
        pendingSecret = null
        sentPairingSecret = false
        latestRemoteRevision = -1L
        cachedMirrorQueue = null
        incomingMirrorQueue = null
        sentQueueRevision = -1L
        queueRetryCount = 0
        acknowledgedQueueRevision = -1L
        handledCommands.clear()
        _state.value = _state.value.copy(
            enabled = true,
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
        // The idle controller is not an audio owner. Advertising its local player's empty
        // state can overwrite the real owner's snapshot and destabilize background playback.
        if (!authenticated || !syncEnabled || isRemoteActive) return
        val target = player ?: return
        val timer = sleepTimerProvider?.invoke() ?: DeviceSleepTimerSnapshot()
        val current = target.currentMediaItem
        val currentIndex = target.currentMediaItemIndex
        val start = (currentIndex - 2).coerceAtLeast(0)
        val end = (start + 25).coerceAtMost(target.mediaItemCount)
        val queue = (start until end).map { index ->
            asDeviceTrack(target.getMediaItemAt(index)).copy(sourceIndex = index)
        }
        val snapshot = DevicePlaybackState(
                    revision = revision,
                    track = current?.let(::asDeviceTrack),
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
                    queueRevision = localQueueRevision,
                    nextQueueIndex = target.nextMediaItemIndex,
                    previousQueueIndex = target.previousMediaItemIndex,
                    liked = likedProvider?.invoke(),
                    speed = target.playbackParameters.speed,
                    pitch = target.playbackParameters.pitch,
                    volume = volumeProvider?.invoke()?.coerceIn(0f, 1f) ?: 1f,
                    muted = mutedProvider?.invoke() == true,
                    sleepTimerRemainingMs = timer.remainingMs,
                    sleepTimerAfterSong = timer.afterSong,
                    sleepTimerStopAfterSong = timer.stopAfterSong,
                    sleepTimerFadeOut = timer.fadeOut,
                )
        lastLocalSnapshot = snapshot
        send(DeviceConnectPacket(type = "STATE", state = snapshot))
        sendMirrorQueueIfChanged(target)
    }

    /** Send the full queue only when the owner changes its timeline, not for position/timer updates. */
    private fun sendMirrorQueueIfChanged(target: Player) {
        if (sentQueueRevision == localQueueRevision) return
        sentQueueRevision = localQueueRevision
        val size = target.mediaItemCount
        if (size !in 1..1500) return
        // The owner's visible queue follows its shuffle traversal, not its physical indices.
        // Preserve both playback order and absolute indices for precise remote commands.
        val timeline = target.currentTimeline
        val playbackIndices = mutableListOf<Int>()
        val visited = BooleanArray(size)
        var index = timeline.getFirstWindowIndex(target.shuffleModeEnabled)
        while (index in 0 until size && !visited[index]) {
            visited[index] = true
            playbackIndices.add(index)
            index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, target.shuffleModeEnabled)
        }
        val ordered = if (playbackIndices.size == size) playbackIndices else (0 until size).toList()
        val transferId = UUID.randomUUID().toString()
        send(DeviceConnectPacket(type = "MIRROR_QUEUE_BEGIN", transferId = transferId,
            queueRevision = localQueueRevision, queueTotal = size))
        ordered.withIndex().toList().chunked(12).forEach { entries ->
            send(DeviceConnectPacket(type = "MIRROR_QUEUE_BATCH", transferId = transferId,
                queueStart = entries.first().index,
                queueItems = entries.map { entry ->
                    asDeviceTrack(target.getMediaItemAt(entry.value)).copy(sourceIndex = entry.value)
                }))
        }
        send(DeviceConnectPacket(type = "MIRROR_QUEUE_END", transferId = transferId))
        val attempt = queueRetryCount
        val generation = ++queueRetryGeneration
        handler.postDelayed({
            if (generation == queueRetryGeneration && authenticated && syncEnabled &&
                acknowledgedQueueRevision != localQueueRevision && queueRetryCount < 3
            ) {
                queueRetryCount++
                sentQueueRevision = -1L
                sendSnapshot()
            }
        }, 3_000L + attempt * 2_000L)
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
            val deadline = discoveryUntilMs
            handler.postDelayed({
                if (deadline == discoveryUntilMs &&
                    SystemClock.elapsedRealtime() >= deadline && !connected && endpointId == null
                ) {
                    transport.stopDiscovery()
                    if (pairingMode) {
                        pairingMode = false
                        _state.value = _state.value.copy(pairingMode = false,
                            status = "No nearby device found. Tap Pair to try again.")
                        updateTransport()
                    }
                }
            }, (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
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
        suspendGeneration++
        val previous = _state.value.remotePlayback
        val id = endpointId
        if (id != null && closeEndpoint) transport.disconnect(id)
        endpointId = null
        peer = null
        connected = false
        authenticated = false
        syncEnabled = true
        remoteOwnsPlayback = false
        acceptedUntrustedEndpoint = null
        pendingSecret = null
        sentPairingSecret = false
        localNonce = ""
        peerNonce = ""
        latestRemoteRevision = -1L
        handledCommands.clear()
        incomingQueue = null
        incomingMirrorQueue = null
        cachedMirrorQueue = null
        sentQueueRevision = -1L
        queueRetryGeneration++
        acknowledgedQueueRevision = -1L
        queueRetryCount = 0
        pendingTakeoverId = null
        pendingTakeoverQueue = null
        pendingTakeoverQueueRevision = -1L
        _state.value = _state.value.copy(
            connected = false, peerName = null, remotePlayback = null, pairingRequest = null,
        )
        remoteMirrorPlayer?.notifyStateChanged(previous, null)
        scheduleReconnect()
        // Nearby may cache a failed endpoint; recover while pairing remains active.
        schedulePairingDiscoveryRetry()
    }

    private fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.encodeToByteArray(), b.encodeToByteArray())
}
