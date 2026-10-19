package com.mcd.tv.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Spotify tokens on this TV only. They are encrypted with an AES-256-GCM key that lives in the Android Keystore
 * (it never leaves the TV's secure hardware or keystore service) and kept in their own file, spotify_vault.xml.
 * That file is not in Prefs, so HouseSync, ProfileSync, the Control page and web_init never see it, and the backup
 * rules (res/xml) leave it out of backups. A TV that loses its Keystore key simply asks J to connect again.
 */
object SpotifyVault {
    private const val ALIAS = "jarvis_spotify_tokens"
    private const val FILE = "spotify_vault"
    private const val ITEM = "tokens"

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(ctx: Context): SpotifyTokens? = runCatching {
        val stored = prefs(ctx).getString(ITEM, null) ?: return null
        val all = Base64.decode(stored, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, all, 0, 12))
        SpotifyTokens.fromJson(String(c.doFinal(all, 12, all.size - 12), Charsets.UTF_8))
    }.getOrElse {
        clear(ctx) // unreadable (Keystore reset, restored from elsewhere): start over
        null
    }

    fun save(ctx: Context, t: SpotifyTokens) {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val iv = c.iv
        val body = c.doFinal(t.toJson().toByteArray(Charsets.UTF_8))
        prefs(ctx).edit().putString(ITEM, Base64.encodeToString(iv + body, Base64.NO_WRAP)).apply()
    }

    fun clear(ctx: Context) {
        prefs(ctx).edit().clear().apply()
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }
}
