package com.iamahsana.ossstudio.stream.rtmp

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class RtmpDestinationProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val serverUrl: String,
    val streamKey: String,
    val enabled: Boolean = true
) {
    init {
        require(name.isNotBlank() && name.length <= 60) { "Destination name must contain 1–60 characters" }
        require(serverUrl == serverUrl.trim() && serverUrl.length <= 512 && isValidServerUrl(serverUrl)) {
            "Server URL must use rtmp:// or rtmps:// and must not contain credentials or a stream key"
        }
        require(streamKey.isNotBlank() && streamKey == streamKey.trim() && streamKey.length <= 1024) { "Stream key must contain 1–1024 characters" }
    }

    fun endpoint(): String = "${serverUrl.trim().trimEnd('/')}/${streamKey.trimStart('/')}"

    companion object {
        private fun isValidServerUrl(value: String): Boolean {
            val uri = runCatching { Uri.parse(value.trim()) }.getOrNull() ?: return false
            return (uri.scheme.equals("rtmp", true) || uri.scheme.equals("rtmps", true)) &&
                !uri.host.isNullOrBlank() && uri.userInfo == null &&
                uri.query == null && uri.fragment == null
        }
    }
}

/**
 * Encrypts RTMP profiles with an AES-256-GCM key held by Android Keystore.
 * The encrypted preference blob never contains a plaintext URL or stream key.
 */
object RtmpDestinationProfileStore {
    private const val PREFS = "oss_rtmp_destination_vault"
    private const val BLOB = "encrypted_profiles"
    private const val KEY_ALIAS = "oss.rtmp.destination.v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val GCM_TAG_BYTES = GCM_TAG_BITS / 8
    private const val GCM_IV_BYTES = 12
    private const val FORMAT_VERSION = 1

    @Synchronized
    fun load(context: Context): List<RtmpDestinationProfile> {
        val encoded = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(BLOB, null) ?: return emptyList()
        val envelope = JSONObject(String(Base64.decode(encoded, Base64.NO_WRAP), Charsets.UTF_8))
        val version = envelope.optInt("version", FORMAT_VERSION)
        require(version == FORMAT_VERSION) { "Unsupported RTMP vault format version" }
        val iv = Base64.decode(envelope.getString("iv"), Base64.NO_WRAP)
        val ciphertext = Base64.decode(envelope.getString("data"), Base64.NO_WRAP)
        require(iv.size == GCM_IV_BYTES) { "Invalid RTMP vault IV length" }
        require(ciphertext.size >= GCM_TAG_BYTES) { "Invalid RTMP vault authentication tag" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        val plain = cipher.doFinal(ciphertext)
        val array = JSONArray(String(plain, Charsets.UTF_8))
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(
                    RtmpDestinationProfile(
                        id = item.getString("id"),
                        name = item.getString("name"),
                        serverUrl = item.getString("serverUrl"),
                        streamKey = item.getString("streamKey"),
                        enabled = item.optBoolean("enabled", true)
                    )
                )
            }
        }
    }

    @Synchronized
    fun save(context: Context, profiles: List<RtmpDestinationProfile>) {
        require(profiles.size <= 16) { "At most 16 RTMP destination profiles can be stored" }
        require(profiles.map { it.id }.distinct().size == profiles.size) { "Destination IDs must be unique" }
        val array = JSONArray()
        profiles.forEach { profile ->
            array.put(
                JSONObject()
                    .put("id", profile.id)
                    .put("name", profile.name)
                    .put("serverUrl", profile.serverUrl.trim())
                    .put("streamKey", profile.streamKey)
                    .put("enabled", profile.enabled)
            )
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ciphertext = cipher.doFinal(array.toString().toByteArray(Charsets.UTF_8))
        val envelope = JSONObject()
            .put("version", FORMAT_VERSION)
            .put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .put("data", Base64.encodeToString(ciphertext, Base64.NO_WRAP))
        val encoded = Base64.encodeToString(envelope.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        check(
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(BLOB, encoded).commit()
        ) { "Unable to persist encrypted RTMP profiles" }
    }

    @Synchronized
    fun clear(context: Context) {
        check(
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().remove(BLOB).commit()
        ) { "Unable to clear encrypted RTMP profiles" }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}
