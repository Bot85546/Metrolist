package com.metrolist.music.connect

import android.annotation.SuppressLint
import android.content.Context
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionOptions
import com.google.android.gms.nearby.connection.ConnectionType
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy

object DeviceConnectTransportFactory {
    fun create(context: Context): DeviceConnectTransport = NearbyDeviceConnectTransport(context)
}

@SuppressLint("MissingPermission")
private class NearbyDeviceConnectTransport(context: Context) : DeviceConnectTransport {
    private val serviceId = context.packageName + ".deviceconnect.v1"
    private val client: ConnectionsClient = Nearby.getConnectionsClient(context)
    private val strategy = Strategy.P2P_POINT_TO_POINT
    override val available =
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
    override var listener: DeviceConnectTransport.Listener? = null
    private var advertising = false
    private var discovering = false

    private val connectionCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            listener?.pairingRequested(endpointId, info.endpointName, info.authenticationDigits)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) listener?.connected(endpointId)
            else listener?.failed("Nearby connection failed: ${result.status.statusCode}")
        }

        override fun onDisconnected(endpointId: String) {
            listener?.disconnected(endpointId)
        }
    }

    private val discoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            listener?.discovered(endpointId, info.endpointName)
        }

        override fun onEndpointLost(endpointId: String) = Unit
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type == Payload.Type.BYTES) {
                payload.asBytes()?.let { listener?.received(endpointId, it) }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) = Unit
    }

    override fun advertise(localName: String) {
        if (!available || advertising) return
        advertising = true
        client.startAdvertising(
            localName,
            serviceId,
            connectionCallback,
            AdvertisingOptions.Builder()
                .setStrategy(strategy)
                .setConnectionType(ConnectionType.NON_DISRUPTIVE)
                .build(),
        ).addOnFailureListener {
            advertising = false
            listener?.failed("Nearby advertising unavailable: ${it.message}")
        }
    }

    override fun discover() {
        if (!available || discovering) return
        discovering = true
        client.startDiscovery(
            serviceId,
            discoveryCallback,
            DiscoveryOptions.Builder().setStrategy(strategy).build(),
        ).addOnFailureListener {
            discovering = false
            listener?.failed("Nearby discovery unavailable: ${it.message}")
        }
    }

    override fun stopAdvertising() {
        if (advertising) {
            advertising = false
            client.stopAdvertising()
        }
    }

    override fun stopDiscovery() {
        if (discovering) {
            discovering = false
            client.stopDiscovery()
        }
    }

    override fun connect(endpointId: String, localName: String) {
        client.requestConnection(
            localName,
            endpointId,
            connectionCallback,
            ConnectionOptions.Builder()
                .setConnectionType(ConnectionType.NON_DISRUPTIVE)
                .build(),
        )
            .addOnFailureListener { listener?.failed("Nearby connect: ${it.message}") }
    }

    override fun accept(endpointId: String) {
        client.acceptConnection(endpointId, payloadCallback)
            .addOnFailureListener { listener?.failed("Nearby accept: ${it.message}") }
    }

    override fun reject(endpointId: String) {
        client.rejectConnection(endpointId)
    }

    override fun send(endpointId: String, bytes: ByteArray) {
        client.sendPayload(endpointId, Payload.fromBytes(bytes))
            .addOnFailureListener { listener?.failed("Nearby send: ${it.message}") }
    }

    override fun disconnect(endpointId: String) {
        client.disconnectFromEndpoint(endpointId)
    }

    override fun close() {
        stopAdvertising()
        stopDiscovery()
        client.stopAllEndpoints()
        listener = null
    }
}
