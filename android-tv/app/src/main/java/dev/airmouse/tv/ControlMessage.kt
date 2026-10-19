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
    data class Key(val name: String) : RemoteAction
    data class Text(val value: String) : RemoteAction
}

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

                "key" -> json.optString("name")
                    .takeIf { it in allowedKeys }
                    ?.let(RemoteAction::Key)

                "text" -> json.optString("value")
                    .takeIf { it.length <= MAX_TEXT_CHARS }
                    ?.let(RemoteAction::Text)

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
}
