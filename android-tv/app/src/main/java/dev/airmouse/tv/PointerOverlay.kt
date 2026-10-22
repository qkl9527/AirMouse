package dev.airmouse.tv

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.text.TextPaint
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import kotlin.math.max
import org.json.JSONObject

/** 只负责显示光标；触屏事件仍由 AccessibilityService 统一派发。 */
internal class PointerOverlay(
    private val context: Context,
    private val windowManager: WindowManager,
) {
    private val cursorView = CursorView(context)
    private var agentApprovalView: View? = null
    private val layoutParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }

    fun show() {
        if (cursorView.parent == null) runCatching { windowManager.addView(cursorView, layoutParams) }
    }

    fun hide() {
        if (cursorView.parent != null) runCatching { windowManager.removeView(cursorView) }
        updateAgentApproval(null)
    }

    /** 即使已离开 Agent 页面，仍在电视上提示待确认动作；浮层不抢焦点、不执行批准。 */
    fun updateAgentApproval(state: JSONObject?) {
        agentApprovalView?.let { view -> runCatching { windowManager.removeView(view) } }
        agentApprovalView = null
        if (state == null) return
        val density = context.resources.displayMetrics.density
        val steps = state.optJSONArray("steps") ?: org.json.JSONArray()
        val preview = (0 until steps.length()).joinToString("\n") { "${it + 1}. ${steps.optString(it)}" }.take(600)
        val card = android.widget.TextView(context).apply {
            text = "AirMouse Agent 请求操作\n${state.optString("title")}\n\n$preview\n\n请在发起对话的手机上选择「允许」或「拒绝」"
            textSize = 18f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding((24*density).toInt(),(18*density).toInt(),(24*density).toInt(),(18*density).toInt())
            maxLines = 12
            ellipsize = android.text.TextUtils.TruncateAt.END
            background = android.graphics.drawable.GradientDrawable().apply { setColor(0xF0181B18.toInt()); cornerRadius=18*density }
        }
        val params = WindowManager.LayoutParams(
            minOf((680*density).toInt(), context.resources.displayMetrics.widthPixels-(32*density).toInt()),
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT).apply { gravity=Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y=(24*density).toInt() }
        runCatching { windowManager.addView(card,params); agentApprovalView=card }
    }

    fun update(x: Float, y: Float, bounds: Rect) {
        cursorView.position(x, y, bounds)
        show()
    }

    /** 空闲计时器只隐藏指针图形，保留覆盖层以便同乐提示继续显示。 */
    fun hidePointer() {
        cursorView.pointerVisible(false)
    }

    fun showPointer() {
        cursorView.pointerVisible(true)
        show()
    }

    /** 同乐期间在电视画面上显示问题和投票进度，inactive 状态会自动隐藏提示。 */
    fun updatePartyState(state: JSONObject) {
        cursorView.partyState(state)
        show()
    }

    /** 远程只允许选择预置外观，避免来自局域网的任意颜色/绘制参数进入覆盖层。 */
    fun updateCursorStyle(style: String) {
        cursorView.cursorStyle(style)
        show()
    }

    private class CursorView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f * resources.displayMetrics.density
            color = 0xFF76E7AC.toInt()
            setShadowLayer(5f, 0f, 1f, 0xCC000000.toInt())
        }
        private val bannerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE6191F23.toInt() }
        private val bannerTitle = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFEEF5F2.toInt()
            textSize = 18f * resources.displayMetrics.density
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        private val bannerDetail = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF9AA9A4.toInt()
            textSize = 13f * resources.displayMetrics.density
        }
        private val arrowFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = 0xFFFFFFFF.toInt()
        }
        private val arrowOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.8f * resources.displayMetrics.density
            color = 0xCC101316.toInt()
        }
        private var pointerX = 0f
        private var pointerY = 0f
        private var radius = 12f * resources.displayMetrics.density
        private var partyState: JSONObject? = null
        private var cursorStyle = "arrow"
        private var isPointerVisible = true

        init {
            // 覆盖层铺满屏幕便于和物理屏幕坐标对应，但 canvas 仅在光标附近重绘。
            setLayerType(LAYER_TYPE_SOFTWARE, null)
            isClickable = false
            isFocusable = false
        }

        fun position(x: Float, y: Float, bounds: Rect) {
            val padding = if (cursorStyle == "arrow") resources.displayMetrics.density * 56f else radius + 8f
            val old = Rect(
                (pointerX - padding).toInt(), (pointerY - padding).toInt(),
                (pointerX + padding).toInt(), (pointerY + padding).toInt(),
            )
            pointerX = x
            pointerY = y
            isPointerVisible = true
            radius = max(10f, resources.displayMetrics.density * 12f)
            val dirty = Rect(
                (x - padding).toInt(), (y - padding).toInt(),
                (x + padding).toInt(), (y + padding).toInt(),
            )
            old.union(dirty)
            old.intersect(bounds)
            invalidate(old)
        }

        fun pointerVisible(visible: Boolean) {
            if (isPointerVisible == visible) return
            isPointerVisible = visible
            invalidate()
        }

        fun partyState(state: JSONObject) {
            partyState = state
            invalidate()
        }

        fun cursorStyle(style: String) {
            cursorStyle = style.takeIf { it in setOf("arrow", "mint", "blue", "amber") } ?: "arrow"
            paint.color = when (cursorStyle) {
                "blue" -> 0xFF70B9FF.toInt()
                "amber" -> 0xFFFFBF69.toInt()
                else -> 0xFF76E7AC.toInt()
            }
            paint.setShadowLayer(5f, 0f, 1f, 0xCC000000.toInt())
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (isPointerVisible) when (cursorStyle) {
                "arrow" -> drawMouseArrow(canvas)
                "blue" -> {
                    // 蓝色皮肤使用旋转方框，让用户不只靠颜色也能区分光标样式。
                    canvas.save()
                    canvas.rotate(45f, pointerX, pointerY)
                    canvas.drawRoundRect(
                        RectF(pointerX - radius * .78f, pointerY - radius * .78f, pointerX + radius * .78f, pointerY + radius * .78f),
                        3f * resources.displayMetrics.density,
                        3f * resources.displayMetrics.density,
                        paint,
                    )
                    canvas.restore()
                }
                "amber" -> {
                    // 橙色皮肤用四个分离的角标，视频明暗背景下更容易找准中心。
                    val arm = radius * .58f
                    val gap = radius * .82f
                    canvas.drawLine(pointerX - gap - arm, pointerY - gap, pointerX - gap, pointerY - gap, paint)
                    canvas.drawLine(pointerX - gap, pointerY - gap - arm, pointerX - gap, pointerY - gap, paint)
                    canvas.drawLine(pointerX + gap, pointerY - gap, pointerX + gap + arm, pointerY - gap, paint)
                    canvas.drawLine(pointerX + gap, pointerY - gap - arm, pointerX + gap, pointerY - gap, paint)
                    canvas.drawLine(pointerX - gap - arm, pointerY + gap, pointerX - gap, pointerY + gap, paint)
                    canvas.drawLine(pointerX - gap, pointerY + gap, pointerX - gap, pointerY + gap + arm, paint)
                    canvas.drawLine(pointerX + gap, pointerY + gap, pointerX + gap + arm, pointerY + gap, paint)
                    canvas.drawLine(pointerX + gap, pointerY + gap, pointerX + gap, pointerY + gap + arm, paint)
                }
                else -> {
                    canvas.drawCircle(pointerX, pointerY, radius, paint)
                    canvas.drawLine(pointerX - radius * 1.6f, pointerY, pointerX + radius * 1.6f, pointerY, paint)
                    canvas.drawLine(pointerX, pointerY - radius * 1.6f, pointerX, pointerY + radius * 1.6f, paint)
                }
            }
            if (isPointerVisible && cursorStyle != "arrow") {
                paint.style = Paint.Style.FILL
                canvas.drawCircle(pointerX, pointerY, 2.5f * resources.displayMetrics.density, paint)
                paint.style = Paint.Style.STROKE
            }
            drawPartyBanner(canvas)
        }

        private fun drawMouseArrow(canvas: Canvas) {
            // 箭头按约 24×32dp 绘制，与原来的 24dp 指针圆环视觉尺寸接近。
            val unit = resources.displayMetrics.density
            val arrow = Path().apply {
                moveTo(pointerX, pointerY)
                lineTo(pointerX, pointerY + 29f * unit)
                lineTo(pointerX + 7.5f * unit, pointerY + 22f * unit)
                lineTo(pointerX + 14f * unit, pointerY + 32f * unit)
                lineTo(pointerX + 19f * unit, pointerY + 29f * unit)
                lineTo(pointerX + 13f * unit, pointerY + 19f * unit)
                lineTo(pointerX + 24f * unit, pointerY + 18f * unit)
                close()
            }
            canvas.drawPath(arrow, arrowFillPaint)
            canvas.drawPath(arrow, arrowOutlinePaint)
        }

        private fun drawPartyBanner(canvas: Canvas) {
            val state = partyState?.takeIf { it.optBoolean("active") } ?: return
            val density = resources.displayMetrics.density
            val inset = 24f * density
            val left = inset
            val right = width - inset
            val top = 20f * density
            val bottom = top + 74f * density
            canvas.drawRoundRect(left, top, right, bottom, 15f * density, 15f * density, bannerPaint)
            val question = state.optString("question", "一起玩")
            canvas.drawText(ellipsize(question, bannerTitle, right - left - 28f * density), left + 14f * density, top + 29f * density, bannerTitle)
            val detail = when (state.optString("mode")) {
                "quiz" -> if (state.optBoolean("winner")) "${state.optString("winnerLabel", "有玩家")} 抢到了" else "抢答中 · ${state.optInt("playerCount", 1)} 位玩家"
                "vote" -> {
                    val options = state.optJSONArray("options")
                    (0 until (options?.length() ?: 0)).joinToString("  ·  ") { index ->
                        val option = options?.optJSONObject(index)
                        "${option?.optString("label")} ${option?.optInt("count", 0) ?: 0}票"
                    }.ifBlank { "等待大家投票…" }
                }
                else -> "活动进行中"
            }
            canvas.drawText(ellipsize(detail, bannerDetail, right - left - 28f * density), left + 14f * density, top + 54f * density, bannerDetail)
        }

        private fun ellipsize(text: String, paint: TextPaint, maxWidth: Float): String {
            if (paint.measureText(text) <= maxWidth) return text
            var result = text
            while (result.isNotEmpty() && paint.measureText("$result…") > maxWidth) result = result.dropLast(1)
            return "$result…"
        }
    }
}
