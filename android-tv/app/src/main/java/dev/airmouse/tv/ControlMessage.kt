package dev.airmouse.tv

import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

/** 经过校验后交给电视输入服务执行的操作，不把网络 JSON 直接传到系统输入 API。 */
sealed interface RemoteAction {
    data class Move(val dx: Float, val dy: Float) : RemoteAction
    data class Click(val count: Int) : RemoteAction
    data object DragStart : RemoteAction
    data object DragEnd : RemoteAction
    data class Scroll(val dx: Float, val dy: Float) : RemoteAction
    data class Key(val name: String, val requestId: String? = null) : RemoteAction
    data class Text(val value: String, val mode: TextInputMode = TextInputMode.REPLACE, val requestId: String? = null, val submit: Boolean = false) : RemoteAction
    data class EnableAirMouse(val clientId: String) : RemoteAction
    data class ListApps(val requestId: String) : RemoteAction
    data class LaunchApp(val packageName: String, val requestId: String) : RemoteAction
    data class SceneText(val packageName: String, val value: String, val requestId: String) : RemoteAction
    data class JoinParty(val clientId: String, val proof: String) : RemoteAction
    data class StartQuiz(val clientId: String, val question: String) : RemoteAction
    data class Buzz(val clientId: String) : RemoteAction
    data class StartVote(val clientId: String, val options: List<String>) : RemoteAction
    data class Vote(val clientId: String, val optionIndex: Int) : RemoteAction
    data class EndParty(val clientId: String) : RemoteAction
    data class CursorStyle(val style: String) : RemoteAction
}

enum class TextInputMode { REPLACE, INSERT }

/**
 * 手机上的控制页和 TV App 共用的 JSON 协议入口。
 *
 * 网络输入先经过长度、数值范围和操作名称校验；无效命令被丢弃，避免无界
 * 大文本、NaN 坐标或未知快捷键进入服务层。
 */
object ControlMessage {
    private const val MAX_TEXT_CHARS = 4096
    private const val MAX_DELTA = 2000.0

    private val allowedKeys = setOf(
        "back", "home", "up", "down", "left", "right", "ok", "enter", "backspace", "space",
        "volume_up", "volume_down", "mute", "play_pause",
        "cursor_left", "cursor_right",
    )

    fun decode(payload: String): RemoteAction? {
        if (payload.length > 16_384) return null

        return runCatching {
            val json = JSONObject(payload)
            when (json.optString("type")) {
                "move" -> RemoteAction.Move(
                    dx = boundedNumber(json, "dx") ?: return null,
                    dy = boundedNumber(json, "dy") ?: return null,
                )

                "click" -> {
                    val count = json.optInt("count", 1)
                    if (count !in 1..2) null else RemoteAction.Click(count)
                }

                "drag_start" -> RemoteAction.DragStart
                "drag_end" -> RemoteAction.DragEnd

                "scroll" -> RemoteAction.Scroll(
                    dx = boundedNumber(json, "dx") ?: return null,
                    dy = boundedNumber(json, "dy") ?: return null,
                )

                "key" -> {
                    val name = json.optString("name").takeIf { it in allowedKeys } ?: return null
                    val requestId = if (json.has("requestId")) validClientId(json.optString("requestId")) ?: return null else null
                    RemoteAction.Key(name, requestId)
                }

                "text" -> {
                    val value = json.optString("value")
                    // 旧控制页未携带模式时仍采用默认覆盖；未知模式拒绝执行，避免误清空文字。
                    val mode = when (json.optString("mode", "replace")) {
                        "replace" -> TextInputMode.REPLACE
                        "insert" -> TextInputMode.INSERT
                        else -> return null
                    }
                    val requestId = if (json.has("requestId")) validClientId(json.optString("requestId")) ?: return null else null
                    if (value.length > MAX_TEXT_CHARS) null else RemoteAction.Text(value, mode, requestId, json.optBoolean("submit", false))
                }

                "enable_air_mouse" -> {
                    val clientId = validClientId(json.optString("clientId")) ?: return null
                    RemoteAction.EnableAirMouse(clientId)
                }

                "list_apps" -> json.optString("requestId")
                    .takeIf { it.length in 1..64 && it.all { character -> character.isLetterOrDigit() || character in "-_" } }
                    ?.let(RemoteAction::ListApps)

                "launch_app" -> {
                    val packageName = json.optString("packageName")
                    val requestId = json.optString("requestId")
                    if (packageName.length !in 3..255 || !packageName.matches(Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")) ||
                        requestId.length !in 1..64 || !requestId.all { it.isLetterOrDigit() || it in "-_" }
                    ) null else RemoteAction.LaunchApp(packageName, requestId)
                }

                "scene_text" -> {
                    val packageName = json.optString("packageName")
                    val requestId = json.optString("requestId")
                    val value = json.optString("value")
                    if (packageName.length !in 3..255 || !packageName.matches(Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")) ||
                        requestId.length !in 1..96 || !requestId.all { it.isLetterOrDigit() || it in "-_" } ||
                        value.length !in 1..MAX_TEXT_CHARS
                    ) null else RemoteAction.SceneText(packageName, value, requestId)
                }

                "join" -> {
                    val clientId = validClientId(json.optString("clientId")) ?: return null
                    val proof = json.optString("proof")
                    if (proof.isNotEmpty() && (proof.length !in 16..128 || !proof.all { it.isLetterOrDigit() || it in "-_" })) null
                    else RemoteAction.JoinParty(clientId, proof)
                }

                "start_quiz" -> {
                    val clientId = validClientId(json.optString("clientId")) ?: return null
                    val question = json.optString("question").trim()
                    if (question.length !in 1..100) null else RemoteAction.StartQuiz(clientId, question)
                }

                "buzz" -> validClientId(json.optString("clientId"))?.let(RemoteAction::Buzz)

                "start_vote" -> {
                    val clientId = validClientId(json.optString("clientId")) ?: return null
                    val jsonOptions = json.optJSONArray("options") ?: return null
                    if (jsonOptions.length() !in 2..6) return null
                    val options = (0 until jsonOptions.length()).map { jsonOptions.optString(it).trim() }
                    if (options.any { it.isEmpty() || it.length > 48 }) null
                    else RemoteAction.StartVote(clientId, options)
                }

                "vote" -> {
                    val clientId = validClientId(json.optString("clientId")) ?: return null
                    val index = json.optInt("option", -1)
                    if (index !in 0..5) null else RemoteAction.Vote(clientId, index)
                }

                "party_end" -> validClientId(json.optString("clientId"))?.let(RemoteAction::EndParty)

                "cursor_style" -> json.optString("style")
                    .takeIf { it in setOf("arrow", "mint", "blue", "amber") }
                    ?.let(RemoteAction::CursorStyle)

                else -> null
            }
        }.getOrNull()
    }

    private fun boundedNumber(json: JSONObject, name: String): Float? {
        if (!json.has(name) || json.isNull(name)) return null
        val value = json.optDouble(name, Double.NaN)
        if (!value.isFinite()) return null
        return min(MAX_DELTA, max(-MAX_DELTA, value)).toFloat()
    }

    private fun validClientId(value: String): String? = value.takeIf {
        it.length in 1..96 && it.all { character -> character.isLetterOrDigit() || character in "-_" }
    }
}
