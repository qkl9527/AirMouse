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
    const val DEFAULT_TEXT_SIZE = 18
    data class Value(val key: String = "", val contextTokens: Int = 32768, val maxTokens: Int = 2048,
        val effort: String = "off", val textSize: Int = DEFAULT_TEXT_SIZE,
        val providerId: String = "agnes", val providerName: String = "Agnes AI",
        val baseUrl: String = "https://apihub.agnes-ai.com/v1", val protocol: String = "chat",
        val family: String = "agnes", val modelId: String = "agnes-3.0-flash", val auth: String = "key",
        val toolsEnabled: Boolean = true, val visionEnabled: Boolean = true,
        val maxTokenField: String = "auto", val extraJson: String = "{}")
    private const val KEY_ALIAS = "airmouse-agent-api-key"
    private fun secret(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    /** 多提供商配置整体加密，Key 不以明文单独落盘，也不进入公开状态。 */
    fun seal(value: String): JSONObject {
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE,secret()) }
        return JSONObject().put("iv",Base64.encodeToString(cipher.iv,Base64.NO_WRAP))
            .put("data",Base64.encodeToString(cipher.doFinal(value.toByteArray(Charsets.UTF_8)),Base64.NO_WRAP))
    }
    fun unseal(value: JSONObject): String {
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE,secret(),
            GCMParameterSpec(128,Base64.decode(value.getString("iv"),Base64.NO_WRAP))) }
        return cipher.doFinal(Base64.decode(value.getString("data"),Base64.NO_WRAP)).toString(Charsets.UTF_8)
    }
    fun saveTextSize(context: Context, value: Int) {
        check(context.getSharedPreferences("agent-model",Context.MODE_PRIVATE).edit().putInt("textSize",value).commit())
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
            prefs.getInt("textSize",DEFAULT_TEXT_SIZE).coerceIn(12,48))
    }
    fun publicJson(value: Value)=JSONObject().put("contextTokens",value.contextTokens).put("maxTokens",value.maxTokens)
        .put("effort",value.effort).put("textSize",value.textSize).put("providerId",value.providerId)
        .put("providerName",value.providerName).put("modelId",value.modelId).put("protocol",value.protocol)
        .put("toolsEnabled",value.toolsEnabled).put("visionEnabled",value.visionEnabled)
}
