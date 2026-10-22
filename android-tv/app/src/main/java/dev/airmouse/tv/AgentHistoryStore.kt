package dev.airmouse.tv

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** 普通会话存于 TV 私有目录；一次性会话绝不传给此存储层。调用方在会话锁内串行读写。 */
class AgentHistoryStore(context: Context) {
    private val directory = File(context.filesDir, "agent-sessions").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("agent-history", Context.MODE_PRIVATE)
    private val catalog = linkedMapOf<String, JSONObject>()

    init {
        // 每个会话独立原子文件，某份记录损坏不影响其他会话；不静默覆盖损坏文件。
        directory.listFiles()?.map { it.name.removeSuffix(".bak") }?.filter { it.endsWith(".json") }?.distinct()?.forEach { name ->
            runCatching { read(name.removeSuffix(".json")) }.getOrNull()?.let { catalog[it.id] = it.metadata() }
        }
    }

    data class Session(val id: String = UUID.randomUUID().toString(), val temporary: Boolean = false,
        var title: String = "新会话", var updatedAt: Long = System.currentTimeMillis(),
        var messages: JSONArray = JSONArray(), var summary: String = "", var summaryCount: Int = 0) {
        fun metadata() = JSONObject().put("id", id).put("title", title).put("updatedAt", updatedAt)
            .put("temporary", temporary).put("messageCount", messages.length())
        fun json() = metadata().put("messages", messages).put("summary", summary).put("summaryCount", summaryCount)
    }

    fun list(): JSONArray = JSONArray(catalog.values.sortedByDescending { it.optLong("updatedAt") })
    fun count() = catalog.size
    fun restore(): Session? = prefs.getString("active", null)?.let { runCatching { read(it) }.getOrNull() }
    fun read(id: String): Session {
        require(id.matches(Regex("[a-fA-F0-9-]{36}")))
        val file=File(directory, "$id.json")
        require(maxOf(file.length(),File(directory,"$id.json.bak").length()) <= 32_000_000)
        val raw = AtomicFile(file).readFully()
        val data = JSONObject(raw.toString(Charsets.UTF_8))
        val messages = data.getJSONArray("messages")
        return Session(id, false, data.optString("title", "历史会话"), data.optLong("updatedAt"), messages,
            data.optString("summary"), data.optInt("summaryCount").coerceIn(0, messages.length()))
    }
    fun save(session: Session) {
        require(!session.temporary)
        val file = AtomicFile(File(directory, "${session.id}.json"))
        val output = file.startWrite()
        try { output.write(session.json().toString().toByteArray(Charsets.UTF_8)); file.finishWrite(output) }
        catch (failure: Exception) { file.failWrite(output); throw failure }
        catalog[session.id] = session.metadata()
        check(prefs.edit().putString("active", session.id).commit())
    }
    fun delete(id: String) {
        require(id.matches(Regex("[a-fA-F0-9-]{36}")))
        AtomicFile(File(directory, "$id.json")).delete()
        check(!File(directory, "$id.json").exists())
        catalog.remove(id)
        if(prefs.getString("active",null)==id) prefs.edit().remove("active").apply()
    }
}
