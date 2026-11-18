package com.mkcam.core

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Pairing credentials, encrypted with a key held in the Android Keystore. */
class Prefs(context: Context, file: String) {
    private val sp: SharedPreferences = run {
        val key = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context, file, key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    var serverUrl: String
        get() = sp.getString("server", "") ?: ""
        set(v) = sp.edit().putString("server", v.trim()).apply()

    var pairId: String?
        get() = sp.getString("pairId", null)
        set(v) = sp.edit().putString("pairId", v).apply()

    var secret: String?
        get() = sp.getString("secret", null)
        set(v) = sp.edit().putString("secret", v).apply()

    fun clearPairing() {
        sp.edit().remove("pairId").remove("secret").apply()
    }
}
