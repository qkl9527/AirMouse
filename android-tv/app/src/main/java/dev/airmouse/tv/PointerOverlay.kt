package dev.airmouse.tv

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import kotlin.math.max

/** 只负责显示光标；触屏事件仍由 AccessibilityService 统一派发。 */
internal class PointerOverlay(
    context: Context,
    private val windowManager: WindowManager,
) {
    private val cursorView = CursorView(context)
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
    }

    fun update(x: Float, y: Float, bounds: Rect) {
        cursorView.position(x, y, bounds)
        show()
    }

    private class CursorView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f * resources.displayMetrics.density
            color = 0xFF76E7AC.toInt()
            setShadowLayer(5f, 0f, 1f, 0xCC000000.toInt())
        }
        private var pointerX = 0f
        private var pointerY = 0f
        private var radius = 12f * resources.displayMetrics.density

        init {
            // 覆盖层铺满屏幕便于和物理屏幕坐标对应，但 canvas 仅在光标附近重绘。
            setLayerType(LAYER_TYPE_SOFTWARE, null)
            isClickable = false
            isFocusable = false
        }

        fun position(x: Float, y: Float, bounds: Rect) {
            val old = Rect(
                (pointerX - radius - 8).toInt(), (pointerY - radius - 8).toInt(),
                (pointerX + radius + 8).toInt(), (pointerY + radius + 8).toInt(),
            )
            pointerX = x
            pointerY = y
            radius = max(10f, resources.displayMetrics.density * 12f)
            val dirty = Rect(
                (x - radius - 8).toInt(), (y - radius - 8).toInt(),
                (x + radius + 8).toInt(), (y + radius + 8).toInt(),
            )
            old.union(dirty)
            old.intersect(bounds)
            invalidate(old)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            // 圆环、中心点和十字线在浅色或视频背景上都容易辨认。
            canvas.drawCircle(pointerX, pointerY, radius, paint)
            canvas.drawLine(pointerX - radius * 1.6f, pointerY, pointerX + radius * 1.6f, pointerY, paint)
            canvas.drawLine(pointerX, pointerY - radius * 1.6f, pointerX, pointerY + radius * 1.6f, paint)
            paint.style = Paint.Style.FILL
            canvas.drawCircle(pointerX, pointerY, 2.5f * resources.displayMetrics.density, paint)
            paint.style = Paint.Style.STROKE
        }
    }
}
