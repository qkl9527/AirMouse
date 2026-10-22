package dev.airmouse.tv

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** TV 是唯一配置源。普通偏好持久化，API Key 仅以 Keystore 保护的 AES-GCM 密文保存。 */
object AgentConfiguration {
    data class Value(val key: String = "", val contextTokens: Int = 32768, val maxTokens: Int = 2048,
        val effort: String = "off", val textSize: Int = 28)
    private const val KEY_ALIAS = "airmouse-agent-api-key"
    private fun secret(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun load(context: Context): Value {
        val prefs = context.getSharedPreferences("agent-model", Context.MODE_PRIVATE)
        val encrypted = prefs.getString("key", null)
        val key = if (encrypted == null) "" else runCatching {
            val iv = Base64.decode(prefs.getString("iv", ""), Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, secret(), GCMParameterSpec(128, iv)) }
            cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)).toString(Charsets.UTF_8)
        }.getOrDefault("")
        val contextTokens=prefs.getInt("contextTokens",32768).coerceIn(4096,512000)
        return Value(key, contextTokens, prefs.getInt("maxTokens",2048).coerceIn(128,minOf(65536,contextTokens-1024)),
            prefs.getString("effort","off").orEmpty().takeIf { it in setOf("off","low","medium","high") } ?: "off",
            prefs.getInt("textSize",28).coerceIn(12,48))
    }
    fun save(context: Context, value: Value) {
        val prefs=context.getSharedPreferences("agent-model",Context.MODE_PRIVATE)
        val edit=prefs.edit().putInt("contextTokens",value.contextTokens).putInt("maxTokens",value.maxTokens)
            .putString("effort",value.effort).putInt("textSize",value.textSize)
        if(value.key.isEmpty()) edit.remove("key").remove("iv")
        else {
            val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE,secret()) }
            edit.putString("key",Base64.encodeToString(cipher.doFinal(value.key.toByteArray(Charsets.UTF_8)),Base64.NO_WRAP))
                .putString("iv",Base64.encodeToString(cipher.iv,Base64.NO_WRAP))
        }
        check(edit.commit()) { "电视无法保存模型配置。" }
    }
    fun publicJson(value: Value)=JSONObject().put("contextTokens",value.contextTokens).put("maxTokens",value.maxTokens)
        .put("effort",value.effort).put("textSize",value.textSize)
}
