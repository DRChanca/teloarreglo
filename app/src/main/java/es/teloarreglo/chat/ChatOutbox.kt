package es.teloarreglo.chat

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@Serializable
data class PendingText(val requestId: String, val body: String)

/** One uncertain send per account/conversation, encrypted and committed before HTTP. */
class ChatOutbox(context: Context) {
    private val preferences = context.getSharedPreferences("chat_outbox", Context.MODE_PRIVATE)
    private val alias = "${context.packageName}.chat_outbox"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    suspend fun load(accountId: String, conversationId: String): PendingText? = withContext(Dispatchers.IO) {
        val encoded = preferences.getString("$accountId/$conversationId", null) ?: return@withContext null
        val parts = encoded.split(":")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
        Json.decodeFromString<PendingText>(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)).toString(Charsets.UTF_8))
    }

    suspend fun save(accountId: String, conversationId: String, pending: PendingText) = withContext(Dispatchers.IO) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(Json.encodeToString(pending).toByteArray(Charsets.UTF_8))
        check(preferences.edit().putString("$accountId/$conversationId",
            Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
                Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit())
    }

    suspend fun clear(accountId: String, conversationId: String) = withContext(Dispatchers.IO) {
        check(preferences.edit().remove("$accountId/$conversationId").commit())
    }
}
