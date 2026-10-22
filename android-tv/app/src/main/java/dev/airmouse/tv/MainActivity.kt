package dev.airmouse.tv

import android.app.Activity
import android.content.res.Configuration
import android.content.ComponentName
import android.content.Intent
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/** 电视端配对与授权首页，全部控件都可通过 D-pad 聚焦。 */
class MainActivity : Activity() {
    private lateinit var accessState: TextView
    private lateinit var imeState: TextView
    private lateinit var serverState: TextView
    private lateinit var clientState: TextView
    private lateinit var addressState: TextView
    private lateinit var tlsFingerprintState: TextView
    private lateinit var qrImage: ImageView
    private var renderedQrUrl: String? = null
    // 横竖屏切换会创建新的 ImageView；缓存位图可直接挂回新视图，避免 URL 缓存误判成已显示。
    private var renderedQrBitmap: android.graphics.Bitmap? = null
    private var qrBitmapBoundTo: ImageView? = null

    private val statusListener: (TvStatus) -> Unit = { status ->
        runOnUiThread { render(status) }
    }

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        buildHomeScreen()
    }

    override fun onResume() {
        super.onResume()
        refreshSetupStatus()
        TvStatusStore.subscribe(statusListener)
    }

    override fun onPause() {
        TvStatusStore.unsubscribe(statusListener)
        super.onPause()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Manifest 保留 Activity 以维持电视服务状态；尺寸变化时重建首页以切换横竖屏布局。
        buildHomeScreen()
        render(TvStatusStore.snapshot())
    }

    private fun buildHomeScreen() {
        val widthDp = resources.configuration.screenWidthDp.takeIf { it > 0 }
            ?: (resources.displayMetrics.widthPixels / resources.displayMetrics.density).toInt()
        val heightDp = resources.configuration.screenHeightDp.takeIf { it > 0 }
            ?: (resources.displayMetrics.heightPixels / resources.displayMetrics.density).toInt()
        val wideLayout = widthDp >= 720 || (widthDp >= 540 && widthDp > heightDp)
        val compactHeight = heightDp < 480
        val root = LinearLayout(this).apply {
            orientation = if (wideLayout) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = if (wideLayout) Gravity.CENTER else Gravity.TOP or Gravity.CENTER_HORIZONTAL
            setPadding(dp(if (wideLayout) 40 else 22), dp(if (compactHeight) 16 else 26), dp(if (wideLayout) 40 else 22), dp(24))
            setBackgroundColor(0xFF101316.toInt())
        }

        val instructions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = text(getString(R.string.setup_title), 30f, 0xFFFFFFFF.toInt(), true)
        instructions.addView(title, matchWrap())
        instructions.addView(spacer(18))
        instructions.addView(text(getString(R.string.setup_intro), 18f, 0xFFD5DCE2.toInt(), false), matchWrap())
        instructions.addView(spacer(22))

        accessState = text("", 16f, 0xFF9EABB6.toInt(), false)
        imeState = text("", 16f, 0xFF9EABB6.toInt(), false)
        instructions.addView(accessState, matchWrap())
        instructions.addView(imeState, matchWrap())
        instructions.addView(spacer(10))

        instructions.addView(actionButton(getString(R.string.enable_accessibility)) {
            openSystemSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        }, matchWrap())
        instructions.addView(actionButton(getString(R.string.enable_input_method)) {
            openSystemSettings(Settings.ACTION_INPUT_METHOD_SETTINGS)
        }, matchWrap())
        instructions.addView(actionButton("打开客厅 Agent · Demo") {
            startActivity(Intent(this, AgentActivity::class.java))
        }, matchWrap())
        instructions.addView(spacer(14))

        serverState = text("", 18f, 0xFF76E7AC.toInt(), true)
        clientState = text("", 16f, 0xFFD5DCE2.toInt(), false)
        addressState = text("", 15f, 0xFF9EABB6.toInt(), false)
        tlsFingerprintState = text("", 13f, 0xFF9EABB6.toInt(), false)
        instructions.addView(serverState, matchWrap())
        instructions.addView(clientState, matchWrap())
        instructions.addView(addressState, matchWrap())
        instructions.addView(tlsFingerprintState, matchWrap())

        if (wideLayout) {
            root.addView(instructions, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.4f))
        } else {
            root.addView(instructions, matchWrap())
        }
        root.addView(spacer(if (wideLayout) 28 else 18, wideLayout))

        val qrPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(22), dp(22), dp(22), dp(22))
            setBackgroundColor(0xFFFFFFFF.toInt())
        }
        val qrSizeDp = when {
            widthDp >= 900 -> 280
            widthDp >= 600 -> 230
            // 窄屏要同时给根容器留白、二维码卡片内边距和边缘缓冲，避免竖屏手机上横向溢出。
            else -> (widthDp - 96).coerceIn(128, 220)
        }
        qrImage = ImageView(this).apply {
            contentDescription = "AirMouse 手机配对二维码"
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(0xFFFFFFFF.toInt())
            setImageBitmap(renderedQrBitmap)
        }
        qrPanel.addView(qrImage, LinearLayout.LayoutParams(dp(qrSizeDp), dp(qrSizeDp)))
        qrPanel.addView(spacer(12))
        qrPanel.addView(text("手机扫码连接", 17f, 0xFF101316.toInt(), true), wrapWrap())
        root.addView(
            qrPanel,
            if (wideLayout) LinearLayout.LayoutParams(dp(qrSizeDp + 44), dp(qrSizeDp + 86))
            else LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            },
        )

        setContentView(ScrollView(this).apply {
            isFillViewport = wideLayout
            addView(
                root,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    if (wideLayout) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        })

        // 首次进入时服务可能正在启动；等新视图完成挂载后再用最新状态补画一次二维码。
        val createdQrImage = qrImage
        createdQrImage.post {
            if (qrImage === createdQrImage && !isFinishing) render(TvStatusStore.snapshot())
        }
    }

    private fun refreshSetupStatus() {
        val accessibilityEnabled = isAccessibilityEnabled()
        val imeStatus = getInputMethodStatus()
        TvStatusStore.update { old ->
            old.copy(
                accessibilityEnabled = accessibilityEnabled,
                inputMethodEnabled = imeStatus.first,
                inputMethodSelected = imeStatus.second,
                message = when {
                    !accessibilityEnabled -> getString(R.string.server_waiting)
                    old.pairingUrl == null -> getString(R.string.no_network)
                    else -> null
                },
            )
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val expected = ComponentName(packageName, "dev.airmouse.tv.RemoteAccessibilityService").flattenToString()
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        return enabledServices.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    private fun getInputMethodStatus(): Pair<Boolean, Boolean> {
        val expected = ComponentName(packageName, "dev.airmouse.tv.RemoteInputMethodService").flattenToString()
        val inputMethodManager = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        val enabled = inputMethodManager.enabledInputMethodList.any { it.id == expected }
        val selected = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) == expected
        return enabled to selected
    }

    private fun render(status: TvStatus) {
        accessState.text = getString(
            if (status.accessibilityEnabled) R.string.service_enabled else R.string.service_disabled,
        )
        imeState.text = when {
            status.inputMethodSelected -> getString(R.string.input_method_selected)
            status.inputMethodEnabled -> getString(R.string.input_method_enabled)
            else -> getString(R.string.input_method_disabled)
        }
        serverState.text = status.message ?: getString(R.string.server_ready)
        clientState.text = getString(R.string.connected_count, status.clientCount)
        addressState.text = status.pairingUrl?.let { getString(R.string.pairing_url, it) }.orEmpty()
        tlsFingerprintState.text = status.secureFingerprint?.let { "空中飞鼠证书指纹（请与手机页面核对）\n$it" }.orEmpty()
        tlsFingerprintState.visibility = if (status.secureFingerprint.isNullOrBlank()) View.GONE else View.VISIBLE

        if (status.pairingUrl.isNullOrBlank()) {
            qrImage.setImageDrawable(null)
            qrImage.visibility = View.INVISIBLE
            renderedQrUrl = null
            renderedQrBitmap = null
            qrBitmapBoundTo = null
        } else {
            if (status.pairingUrl != renderedQrUrl || renderedQrBitmap == null) {
                val bitmap = createQrBitmap(status.pairingUrl)
                if (bitmap == null) {
                    // 生成失败时不要把 URL 记成已渲染；下次状态刷新时仍可重试。
                    qrImage.setImageDrawable(null)
                    qrImage.visibility = View.INVISIBLE
                    renderedQrUrl = null
                    renderedQrBitmap = null
                    qrBitmapBoundTo = null
                    return
                }
                renderedQrBitmap = bitmap
                renderedQrUrl = status.pairingUrl
            }
            // 每个新建的 ImageView 都单独记录绑定状态；不依赖 drawable 是否为 null 来判断是否画过。
            if (qrBitmapBoundTo !== qrImage || qrImage.drawable == null) {
                qrImage.setImageBitmap(renderedQrBitmap)
                qrBitmapBoundTo = qrImage
            }
            qrImage.visibility = View.VISIBLE
        }
    }

    private fun createQrBitmap(value: String): android.graphics.Bitmap? = try {
        val matrix = QRCodeWriter().encode(
            value,
            BarcodeFormat.QR_CODE,
            600,
            600,
            mapOf(EncodeHintType.MARGIN to 2, EncodeHintType.CHARACTER_SET to "UTF-8"),
        )
        android.graphics.Bitmap.createBitmap(600, 600, android.graphics.Bitmap.Config.RGB_565).apply {
            for (x in 0 until 600) {
                for (y in 0 until 600) {
                    setPixel(x, y, if (matrix[x, y]) 0xFF101316.toInt() else 0xFFFFFFFF.toInt())
                }
            }
        }
    } catch (_: Exception) {
        null
    }

    private fun openSystemSettings(action: String) {
        runCatching { startActivity(Intent(action)) }.onFailure {
            serverState.text = "无法打开系统设置，请从电视设置中手动进入辅助功能或输入法。"
        }
    }

    private fun text(value: String, sizeSp: Float, color: Int, bold: Boolean) = TextView(this).apply {
        text = value
        textSize = sizeSp
        setTextColor(color)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        setLineSpacing(dp(4).toFloat(), 1f)
    }

    private fun actionButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        isFocusable = true
        isFocusableInTouchMode = true
        setOnClickListener { action() }
        setPadding(dp(18), dp(8), dp(18), dp(8))
    }

    private fun spacer(size: Int, horizontal: Boolean = false): View = View(this).apply {
        layoutParams = if (horizontal) {
            LinearLayout.LayoutParams(dp(size), 1)
        } else {
            LinearLayout.LayoutParams(1, dp(size))
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun wrapWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
}
