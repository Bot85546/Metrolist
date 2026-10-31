package com.metrolist.music.connect

import android.content.Context
import java.util.UUID

interface DeviceConnectTransport {
    val available: Boolean
    var listener: Listener?

    interface Listener {
        fun discovered(endpointId: String, endpointName: String)
        fun pairingRequested(endpointId: String, endpointName: String, verificationCode: String)
        fun connected(endpointId: String)
        fun disconnected(endpointId: String)
        fun received(endpointId: String, bytes: ByteArray)
        fun failed(message: String)
    }

    fun advertise(localName: String)
    fun discover()
    fun stopAdvertising()
    fun stopDiscovery()
    fun connect(endpointId: String, localName: String)
    fun accept(endpointId: String)
    fun reject(endpointId: String)
    fun send(endpointId: String, bytes: ByteArray)
    fun disconnect(endpointId: String)
    fun close()
}

data class DeviceEndpoint(val id: String, val name: String) {
    fun encode(): String = "DC1|$id|$name"

    companion object {
        fun decode(raw: String): DeviceEndpoint? {
            val segments = raw.split('|', limit = 3)
            if (segments.size != 3 || segments[0] != "DC1") return null
            if (runCatching { UUID.fromString(segments[1]) }.isFailure) return null
            return DeviceEndpoint(segments[1], segments[2].take(55))
        }
    }
}
