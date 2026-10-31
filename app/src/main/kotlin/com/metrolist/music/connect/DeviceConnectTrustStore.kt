package com.metrolist.music.connect

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class TrustedDevice(val id: String, val name: String, val secret: String)

class DeviceConnectTrustStore(context: Context) {
    private val preferences = context.getSharedPreferences("device_connect_trust", Context.MODE_PRIVATE)
    private val random = SecureRandom()

    val localId: String
        get() = synchronized(preferences) {
            preferences.getString("local_id", null) ?: UUID.randomUUID().toString().also {
                preferences.edit().putString("local_id", it).apply()
            }
        }

    fun trustedDevice(): TrustedDevice? {
        val id = preferences.getString("trusted_id", null) ?: return null
        val encrypted = preferences.getString("trusted_key", null) ?: return null
        val secret = runCatching { decrypt(encrypted) }.getOrNull() ?: return null
        return TrustedDevice(id, preferences.getString("trusted_name", "Galaxy device").orEmpty(), secret)
    }

    fun save(peer: TrustedDevice) {
        val encrypted = encrypt(peer.secret)
        check(preferences.edit()
            .putString("trusted_id", peer.id)
            .putString("trusted_name", peer.name)
            .putString("trusted_key", encrypted)
            .commit()) { "Could not save Device Connect pairing" }
    }

    fun forget() {
        preferences.edit().remove("trusted_id").remove("trusted_name").remove("trusted_key").apply()
    }

    fun newSecret(): String = ByteArray(32).also(random::nextBytes).let(::base64)
    fun newNonce(): String = ByteArray(16).also(random::nextBytes).let(::base64)

    fun proof(secret: String, message: String): String {
        val hmac = Mac.getInstance("HmacSHA256")
        hmac.init(SecretKeySpec(Base64.decode(secret, Base64.NO_WRAP), "HmacSHA256"))
        return base64(hmac.doFinal(message.encodeToByteArray()))
    }

    private fun key(): SecretKey {
        val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val alias = "metrolitt_device_connect_pairing_v1"
        (keystore.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }

    private fun encrypt(secret: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return base64(cipher.iv + cipher.doFinal(secret.encodeToByteArray()))
    }

    private fun decrypt(encrypted: String): String {
        val bytes = Base64.decode(encrypted, Base64.NO_WRAP)
        require(bytes.size > 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return cipher.doFinal(bytes.copyOfRange(12, bytes.size)).decodeToString()
    }

    private fun base64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
}
