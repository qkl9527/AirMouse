package dev.airmouse.tv

import org.json.JSONArray
import org.json.JSONObject

/**
 * 电视本机维护一个临时同乐房间，所有连接通过同一配对令牌加入。
 * 抢答胜者、主持人和每位参与者的投票都在此集中串行更新，防止并发消息重复计分。
 */
object PartyRoom {
    data class Transition(val state: JSONObject? = null, val error: String? = null)

    private val lock = Any()
    private var hostId: String? = null
    private var mode: String? = null
    private var question: String = ""
    private var sessionId: String = ""
    private var options: List<String> = emptyList()
    private val votesByClient = linkedMapOf<String, Int>()
    private val playerLabels = linkedMapOf<String, String>()
    private var winnerId: String? = null
    private var countdownUntil: Long = 0L

    fun snapshot(): JSONObject = synchronized(lock) { currentState() }

    /** 同一 clientId 断线重连后复用昵称，避免重复占用玩家席位。 */
    fun join(clientId: String): JSONObject = synchronized(lock) {
        playerLabels.getOrPut(clientId) { "玩家 ${playerLabels.size + 1}" }
        currentState()
    }

    fun startQuiz(clientId: String, prompt: String): Transition = synchronized(lock) {
        val cleanPrompt = prompt.trim().take(100)
        if (cleanPrompt.isEmpty()) return@synchronized Transition(error = "先写一个抢答问题。")
        if (mode != null) return@synchronized Transition(error = "电视上已有进行中的活动。")
        hostId = clientId
        mode = "quiz"
        sessionId = java.util.UUID.randomUUID().toString()
        question = cleanPrompt
        options = emptyList()
        votesByClient.clear()
        winnerId = null
        countdownUntil = System.currentTimeMillis() + 3_000
        Transition(state = currentState())
    }

    fun buzz(clientId: String): Transition = synchronized(lock) {
        if (mode != "quiz") return@synchronized Transition(error = "当前没有进行中的抢答。")
        if (System.currentTimeMillis() < countdownUntil) return@synchronized Transition(error = "倒数结束再抢答。")
        if (winnerId != null) return@synchronized Transition(error = "这一题已经有人抢到了。")
        winnerId = clientId
        Transition(state = currentState())
    }

    fun startVote(clientId: String, candidates: List<String>): Transition = synchronized(lock) {
        val cleanCandidates = candidates.map { it.trim().take(48) }.filter(String::isNotEmpty).distinct()
        if (cleanCandidates.size !in 2..6) return@synchronized Transition(error = "请提供 2 到 6 个不同的片名。")
        if (mode != null) return@synchronized Transition(error = "电视上已有进行中的活动。")
        hostId = clientId
        mode = "vote"
        sessionId = java.util.UUID.randomUUID().toString()
        question = "今晚看哪部？"
        options = cleanCandidates
        votesByClient.clear()
        winnerId = null
        countdownUntil = 0L
        Transition(state = currentState())
    }

    fun vote(clientId: String, optionIndex: Int): Transition = synchronized(lock) {
        if (mode != "vote") return@synchronized Transition(error = "当前没有进行中的投票。")
        if (optionIndex !in options.indices) return@synchronized Transition(error = "这个片名已不在本轮候选中。")
        if (votesByClient.containsKey(clientId)) return@synchronized Transition(error = "你已经投过票了。")
        votesByClient[clientId] = optionIndex
        Transition(state = currentState())
    }

    /** 主持人离线时移交给仍在线的玩家；所有人都离线则结束活动，避免房间卡死。 */
    fun onDisconnect(clientId: String, connectedClientIds: List<String>): Transition = synchronized(lock) {
        if (mode == null || hostId != clientId) return@synchronized Transition()
        if (clientId in connectedClientIds) return@synchronized Transition()
        hostId = connectedClientIds.firstOrNull { it != clientId }
        if (hostId == null) clear()
        Transition(state = currentState())
    }

    fun end(clientId: String): Transition = synchronized(lock) {
        if (hostId != clientId) return@synchronized Transition(error = "只有发起活动的人可以结束本轮。")
        clear()
        Transition(state = currentState())
    }

    fun reset() = synchronized(lock) { clear() }

    private fun currentState(): JSONObject {
        val counts = IntArray(options.size)
        votesByClient.values.forEach { index -> if (index in counts.indices) counts[index]++ }
        val optionJson = JSONArray()
        options.forEachIndexed { index, label ->
            optionJson.put(JSONObject().put("id", index).put("label", label).put("count", counts[index]))
        }
        val votesJson = JSONObject()
        votesByClient.forEach { (clientId, index) -> votesJson.put(clientId, index) }
        return JSONObject()
            .put("sessionId", sessionId)
            .put("type", "party_state")
            .put("active", mode != null)
            .put("mode", mode ?: JSONObject.NULL)
            .put("hostId", hostId ?: JSONObject.NULL)
            .put("question", question)
            .put("options", optionJson)
            .put("votesByClient", votesJson)
            .put("winner", winnerId != null)
            .put("winnerId", winnerId ?: JSONObject.NULL)
            .put("winnerLabel", winnerId?.let(playerLabels::get) ?: JSONObject.NULL)
            .put("playerCount", playerLabels.size)
            .put("countdownUntil", countdownUntil)
    }

    private fun clear() {
        hostId = null
        mode = null
        question = ""
        options = emptyList()
        votesByClient.clear()
        playerLabels.clear()
        winnerId = null
        countdownUntil = 0L
    }
}
