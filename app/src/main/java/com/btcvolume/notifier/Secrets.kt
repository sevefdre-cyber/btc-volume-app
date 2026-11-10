package com.btcvolume.notifier

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stockage chiffré (AES-GCM, clé dans l'Android Keystore) des clés API Binance. */
object Secrets {
    private const val ALIAS = "volscanner_secrets"

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore")
        ks.load(null)
        val existing = ks.getKey(ALIAS, null)
        if (existing is SecretKey) return existing
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(
                ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private fun sp(c: Context) = c.getSharedPreferences("secrets", Context.MODE_PRIVATE)

    fun put(c: Context, name: String, value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val ct = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        sp(c).edit().putString(name, Base64.encodeToString(iv + ct, Base64.NO_WRAP)).apply()
    }

    fun get(c: Context, name: String): String? {
        val raw = sp(c).getString(name, null) ?: return null
        return try {
            val all = Base64.decode(raw, Base64.NO_WRAP)
            val iv = all.copyOfRange(0, 12)
            val ct = all.copyOfRange(12, all.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    fun remove(c: Context, name: String) {
        sp(c).edit().remove(name).apply()
    }

    fun pair(c: Context, env: String): Pair<String, String>? {
        val k = get(c, "key_$env")
        val s = get(c, "secret_$env")
        return if (k != null && s != null) Pair(k, s) else null
    }
}
