package com.noki.vpn.data

import android.content.Context
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.RSAKeyGenParameterSpec
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object DeviceIdentity {
    // The server has one single-use device nonce shared by main and YouTube admission.
    private val challengeMutex = Mutex()

    internal suspend fun <T> withChallengeTransaction(action: suspend () -> T): T =
        challengeMutex.withLock { action() }

    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS = "noki_device_identity_v1"
    private const val SIGNATURE_ALGORITHM = "SHA256withRSA"
    private const val STABLE_DEVICE_KEY_PREFIX = "dev_"

    fun publicKeyBase64(): String {
        val entry = keyStore().getEntry(KEY_ALIAS, null) as? KeyStore.PrivateKeyEntry
            ?: generateKeyPair()
        return Base64.encodeToString(entry.certificate.publicKey.encoded, Base64.NO_WRAP)
    }

    fun signChallenge(nonce: String): String {
        val entry = keyStore().getEntry(KEY_ALIAS, null) as? KeyStore.PrivateKeyEntry
            ?: generateKeyPair()
        val signature = Signature.getInstance(SIGNATURE_ALGORITHM)
        signature.initSign(entry.privateKey)
        signature.update(nonce.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(signature.sign(), Base64.NO_WRAP)
    }

    fun deviceClaims(context: Context): List<String> {
        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID,
        ).orEmpty()
        return buildList {
            add(stableDeviceKey(context).removePrefix(STABLE_DEVICE_KEY_PREFIX))
            if (androidId.isNotBlank()) {
                add(sha256Hex("android_id:${context.packageName}:$androidId"))
            }
        }.distinct().take(8)
    }

    fun stableDeviceKey(context: Context): String {
        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID,
        ).orEmpty().trim()
        val deviceSource = if (androidId.isNotBlank()) {
            "android_id:${context.packageName}:$androidId"
        } else {
            "public_key:${sha256Hex(publicKeyBase64())}"
        }
        return STABLE_DEVICE_KEY_PREFIX + sha256Hex("noki-device-key-v1:$deviceSource")
    }

    fun isStableDeviceKey(value: String): Boolean {
        val raw = value.trim()
        if (!raw.startsWith(STABLE_DEVICE_KEY_PREFIX)) return false
        val hash = raw.removePrefix(STABLE_DEVICE_KEY_PREFIX)
        return hash.length == 64 && hash.all { it in '0'..'9' || it in 'a'..'f' }
    }

    private fun keyStore(): KeyStore =
        KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }

    private fun generateKeyPair(): KeyStore.PrivateKeyEntry {
        val generator = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_RSA,
            KEYSTORE_PROVIDER,
        )
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
        )
            .setAlgorithmParameterSpec(RSAKeyGenParameterSpec(2048, RSAKeyGenParameterSpec.F4))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
            .setUserAuthenticationRequired(false)
            .build()
        generator.initialize(spec)
        generator.generateKeyPair()
        return keyStore().getEntry(KEY_ALIAS, null) as KeyStore.PrivateKeyEntry
    }

    private fun sha256Hex(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
        return bytes.joinToString(separator = "") { "%02x".format(it) }
    }
}
