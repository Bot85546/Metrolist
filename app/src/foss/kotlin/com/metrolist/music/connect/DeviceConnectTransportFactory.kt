package com.metrolist.music.connect

import android.content.Context

object DeviceConnectTransportFactory {
    fun create(context: Context): DeviceConnectTransport = UnsupportedDeviceConnectTransport
}

internal object UnsupportedDeviceConnectTransport : DeviceConnectTransport {
    override val available = false
    override var listener: DeviceConnectTransport.Listener? = null
    override fun advertise(localName: String) = Unit
    override fun discover() = Unit
    override fun stopAdvertising() = Unit
    override fun stopDiscovery() = Unit
    override fun connect(endpointId: String, localName: String) = Unit
    override fun accept(endpointId: String) = Unit
    override fun reject(endpointId: String) = Unit
    override fun send(endpointId: String, bytes: ByteArray) = Unit
    override fun disconnect(endpointId: String) = Unit
    override fun close() = Unit
}
