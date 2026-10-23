package dev.airmouse.tv

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque

/**
 * 真机诊断只保存运行状态，不采集聊天、图片、Key、配对 URL 或请求正文。
 * 开关保存在电视，最近 80 条事件只驻留内存；关闭后清空事件。
 * 最后一条页面错误单独保留，方便脚本失败之后再打开 Debug 定位。
 */
object AgentDiagnostics {
    private val lock = Any()
    private var context: Context? = null
    @Volatile var enabled = false; private set
    private val events = ArrayDeque<JSONObject>()
    private var page = JSONObject().put("phase", "not_opened")
    private var lastError = ""
    private var pollAt = 0L
    private var renderAt = 0L

    fun initialize(value: Context) = synchronized(lock) {
        if (context == null) {
            context = value.applicationContext
            enabled = value.getSharedPreferences("agent-debug", Context.MODE_PRIVATE).getBoolean("enabled", false)
        }
    }

    fun setEnabled(value: Boolean): String? = synchronized(lock) {
        val app = context ?: return@synchronized "电视服务尚未初始化。"
        if (!app.getSharedPreferences("agent-debug", Context.MODE_PRIVATE).edit().putBoolean("enabled", value).commit())
            return@synchronized "电视无法保存调试开关。"
        enabled = value
        events.clear()
        null
    }

    /** 仅接受错误摘要；去除地址中的查询串和常见凭据格式，不能传完整状态或任意 JSON。 */
    private fun safe(raw: String): String = raw.take(1200)
        .replace(Regex("https?://[^\\s\"'<>]+", RegexOption.IGNORE_CASE), "[address]")
        .replace(Regex("(?i)(token|api[-_ ]?key|authorization)\\s*[:=]\\s*[^\\s,;]+"), "$1=[redacted]")
        .replace(Regex("(?i)\\b(sk-|bearer )[A-Za-z0-9_./+\\-=]+"), "[redacted]")
        .replace(Regex("[\\r\\n]+"), " ").take(240)

    fun event(area: String, code: String, detail: String = "", error: Boolean = false) = synchronized(lock) {
        val clean = safe(detail)
        if (error) {
            lastError = "$area / $code: $clean"
            if (area == "tv-script" || area == "webview") page.put("phase", "error")
        }
        if (enabled) {
            if (events.size >= 80) events.removeFirst()
            events.addLast(JSONObject().put("at", System.currentTimeMillis()).put("area", area)
                .put("code", code).put("detail", clean))
        }
    }

    fun pageStarted(userAgent: String, webViewVersion: String) = synchronized(lock) {
        page = JSONObject().put("phase", "loading").put("webView", webViewVersion)
            .put("userAgent", safe(userAgent))
        pollAt = 0L; renderAt = 0L; lastError = ""
        event("tv", "page_loading")
    }

    fun pageReady() = synchronized(lock) { page.put("phase", "ready"); event("tv", "script_ready") }
    fun polled() = synchronized(lock) { pollAt = System.currentTimeMillis() }
    fun rendered(revision: Long, width: Int, height: Int) = synchronized(lock) {
        renderAt = System.currentTimeMillis()
        page.put("revision", revision).put("viewport", "${width.coerceIn(0, 16384)} × ${height.coerceIn(0, 16384)}")
    }

    fun summary(): JSONObject = synchronized(lock) {
        JSONObject().put("enabled", enabled).put("phase", page.optString("phase"))
            .put("lastError", lastError).put("pollAt", pollAt).put("renderAt", renderAt)
    }

    fun snapshot(): JSONObject = synchronized(lock) {
        val status = TvStatusStore.snapshot()
        val app = context
        @Suppress("DEPRECATION")
        val version = runCatching { app?.packageManager?.getPackageInfo(app.packageName, 0)?.versionName }.getOrNull()
        JSONObject().put("enabled", enabled).put("at", System.currentTimeMillis())
            .put("appVersion", version ?: "unknown").put("android", Build.VERSION.RELEASE)
            .put("sdk", Build.VERSION.SDK_INT).put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("serviceAvailable", status.pairingUrl != null).put("connectedPhones", ControlServer.connectedPhoneCount())
            .put("inputMethodSelected", status.inputMethodSelected).put("tvPage", JSONObject(page.toString()))
            .put("lastBridgePollAt", pollAt).put("lastRenderAt", renderAt).put("lastError", lastError)
            .put("events", JSONArray(events.map { JSONObject(it.toString()) }))
    }
}
