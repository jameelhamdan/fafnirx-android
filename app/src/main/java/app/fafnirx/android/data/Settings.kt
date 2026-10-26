package app.fafnirx.android.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** What the last contact with the master said. */
object LastResult {
    const val NONE = 0
    const val OK = 1
    const val SERVER_DOWN = 2
    const val AUTH = 3
    const val IN_USE = 4
}

class Settings(context: Context) {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences("fafnirx", Context.MODE_PRIVATE)

    var server: String
        get() = prefs.getString("server", "") ?: ""
        set(v) = prefs.edit().putString("server", v.trim()).apply()

    /** The device token, wrapped with a key that lives in the Android Keystore. */
    var token: String
        get() = tokenCache ?: (prefs.getString("token", null)?.let { unwrap(it) } ?: "").also { tokenCache = it }
        set(v) {
            prefs.edit().putString("token", if (v.isBlank()) null else wrap(v.trim())).apply()
            tokenCache = v.trim()
        }

    var wifiOnly: Boolean
        get() = prefs.getBoolean("wifi_only", true)
        set(v) = prefs.edit().putBoolean("wifi_only", v).apply()

    var chargingOnly: Boolean
        get() = prefs.getBoolean("charging_only", false)
        set(v) = prefs.edit().putBoolean("charging_only", v).apply()

    /** The master switch: when off, nothing is synced (no background runs, Sync now is refused, a running sync stops). */
    var syncEnabled: Boolean
        get() = prefs.getBoolean("sync_enabled", true)
        set(v) = prefs.edit().putBoolean("sync_enabled", v).apply()

    /** How many files are uploaded at the same time (1 to 3). */
    var parallel: Int
        get() = prefs.getInt("parallel", 2).coerceIn(1, 3)
        set(v) = prefs.edit().putInt("parallel", v.coerceIn(1, 3)).apply()

    /** Which install this is; sent with every call so the master can tell two installs of one device apart. */
    val instanceId: String
        get() {
            prefs.getString("instance", null)?.let { return it }
            val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val id = bytes.joinToString("") { "%02x".format(it) }
            prefs.edit().putString("instance", id).apply()
            return id
        }

    var masterId: String
        get() = prefs.getString("master_id", "") ?: ""
        set(v) = prefs.edit().putString("master_id", v).apply()

    var lastResult: Int
        get() = prefs.getInt("last_result", LastResult.NONE)
        set(v) = prefs.edit().putInt("last_result", v).apply()

    /** Unix ms of the last time the master answered. */
    var lastContact: Long
        get() = prefs.getLong("last_contact", 0)
        set(v) = prefs.edit().putLong("last_contact", v).apply()

    /** Unix ms when a sync last finished. */
    var lastSyncEnd: Long
        get() = prefs.getLong("last_sync_end", 0)
        set(v) = prefs.edit().putLong("last_sync_end", v).apply()

    val configured get() = server.isNotBlank() && token.isNotBlank()

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return gen.generateKey()
    }

    private fun wrap(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.encodeToString(cipher.iv + cipher.doFinal(plain.toByteArray()), Base64.NO_WRAP)
    }

    private fun unwrap(wrapped: String): String = try {
        val raw = Base64.decode(wrapped, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            .apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12)) }
        String(cipher.doFinal(raw, 12, raw.size - 12))
    } catch (e: Exception) {
        ""
    }

    private companion object {
        const val KEY_ALIAS = "fafnirx-token"

        /** Decrypting needs the Keystore, which is slow; the screens ask for the token often. */
        @Volatile
        var tokenCache: String? = null
    }
}
