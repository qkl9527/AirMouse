package dev.airmouse.tv

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
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
    private lateinit var qrImage: ImageView
    private var renderedQrUrl: String? = null

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

    private fun buildHomeScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(56), dp(36), dp(56), dp(36))
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
        instructions.addView(spacer(14))

        serverState = text("", 18f, 0xFF76E7AC.toInt(), true)
        clientState = text("", 16f, 0xFFD5DCE2.toInt(), false)
        addressState = text("", 15f, 0xFF9EABB6.toInt(), false)
        instructions.addView(serverState, matchWrap())
        instructions.addView(clientState, matchWrap())
        instructions.addView(addressState, matchWrap())

        root.addView(instructions, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.4f))
        root.addView(spacer(32, true))

        val qrPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(22), dp(22), dp(22), dp(22))
            setBackgroundColor(0xFFFFFFFF.toInt())
        }
        qrImage = ImageView(this).apply {
            contentDescription = "AirMouse 手机配对二维码"
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(0xFFFFFFFF.toInt())
        }
        qrPanel.addView(qrImage, LinearLayout.LayoutParams(dp(300), dp(300)))
        qrPanel.addView(spacer(12))
        qrPanel.addView(text("手机扫码连接", 17f, 0xFF101316.toInt(), true), wrapWrap())
        root.addView(qrPanel, LinearLayout.LayoutParams(dp(350), dp(350)))

        setContentView(ScrollView(this).apply { addView(root) })
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

        if (status.pairingUrl.isNullOrBlank()) {
            qrImage.visibility = View.INVISIBLE
            renderedQrUrl = null
        } else {
            qrImage.visibility = View.VISIBLE
            if (status.pairingUrl != renderedQrUrl) {
                createQrBitmap(status.pairingUrl)?.let(qrImage::setImageBitmap)
                renderedQrUrl = status.pairingUrl
            }
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
