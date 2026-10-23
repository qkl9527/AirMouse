package dev.airmouse.tv

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** 只读阅读页：桥接只能读取公开状态及报告页面健康情况，不能操作电视或读取 API Key。 */
class AgentActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var debugPanel: LinearLayout
    private lateinit var debugText: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val debugRefresh = object : Runnable {
        override fun run() {
            renderDiagnostics()
            handler.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AgentDiagnostics.initialize(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        web = WebView(this)
        web.setBackgroundColor(Color.rgb(249, 248, 246))
        web.settings.apply {
            javaScriptEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            textZoom = 100
            allowFileAccess = false
            allowContentAccess = false
            blockNetworkLoads = true
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false
        }
        // 原生层接收语法错误：即使页面脚本根本没开始执行，Debug 面板仍有错误可看。
        web.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR || message.messageLevel() == ConsoleMessage.MessageLevel.WARNING)
                    AgentDiagnostics.event("webview", "console_${message.messageLevel().name.lowercase()}",
                        "line=${message.lineNumber()} ${message.message()}", message.messageLevel() == ConsoleMessage.MessageLevel.ERROR)
                return true
            }
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                val version = if (Build.VERSION.SDK_INT >= 26) WebView.getCurrentWebViewPackage()?.versionName
                    else Regex("Chrome/([\\d.]+)").find(web.settings.userAgentString)?.groupValues?.get(1)
                AgentDiagnostics.pageStarted(web.settings.userAgentString, version ?: "unknown")
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                AgentDiagnostics.event("webview", "resource_error", "code=${error.errorCode} ${error.description}", true)
            }
        }
        web.addJavascriptInterface(object {
            @JavascriptInterface fun state(revision: Long): String {
                AgentDiagnostics.polled()
                return AgentSession.snapshotSince(revision)
            }
            @JavascriptInterface fun ready() { AgentDiagnostics.pageReady() }
            @JavascriptInterface fun rendered(revision: Long, width: Int, height: Int) { AgentDiagnostics.rendered(revision, width, height) }
            @JavascriptInterface fun error(message: String) { AgentDiagnostics.event("tv-script", "page_error", message, true) }
        }, "AirMouseAgent")
        val root = FrameLayout(this)
        root.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        debugPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(0xEF101820.toInt())
        }
        debugText = TextView(this).apply { setTextColor(Color.WHITE); textSize = 12f; maxLines = 9 }
        debugPanel.addView(debugText)
        val buttons = LinearLayout(this)
        buttons.addView(Button(this).apply { text = "重新加载页面"; setOnClickListener { web.reload() } })
        buttons.addView(Button(this).apply { text = "关闭 Debug"; setOnClickListener { toggleDebug(false) } })
        debugPanel.addView(buttons)
        root.addView(debugPanel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        setContentView(root)
        web.loadUrl("file:///android_asset/agent-tv.html")
    }

    private fun toggleDebug(enabled: Boolean) {
        AgentDiagnostics.setEnabled(enabled)
        AgentSession.refreshConnectionState()
        renderDiagnostics()
        if (enabled) (debugPanel.getChildAt(1) as ViewGroup).getChildAt(0).requestFocus() else web.requestFocus()
    }

    private fun renderDiagnostics() {
        debugPanel.visibility = if (AgentDiagnostics.enabled) View.VISIBLE else View.GONE
        if (!AgentDiagnostics.enabled) return
        val info = AgentDiagnostics.snapshot()
        val page = info.getJSONObject("tvPage")
        val polled = info.optLong("lastBridgePollAt")
        val poll = if (polled == 0L) "未收到" else "${((System.currentTimeMillis() - polled).coerceAtLeast(0) / 1000)} 秒前"
        debugText.text = "DEBUG · ${info.optString("device")} · Android ${info.optString("android")}\n" +
            "WebView ${page.optString("webView", "unknown")} · 页面 ${page.optString("phase")} · 已连接手机 ${info.optInt("connectedPhones")}\n" +
            "桥接轮询 $poll · viewport ${page.optString("viewport", "未上报")} · revision ${page.optLong("revision", -1)}\n" +
            "最近错误：${info.optString("lastError").ifBlank { "无" }}\n手机可在 Agent 设置中刷新、复制诊断报告；遥控器菜单键可切换 Debug。"
    }

    override fun onResume() {
        super.onResume(); web.onResume(); AgentSession.setVisible(true)
        handler.post(debugRefresh)
    }
    override fun onPause() {
        handler.removeCallbacks(debugRefresh)
        AgentSession.setVisible(false); web.onPause(); super.onPause()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU && event.repeatCount == 0) { toggleDebug(!AgentDiagnostics.enabled); return true }
        if (!debugPanel.hasFocus() && (keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == KeyEvent.KEYCODE_DPAD_UP)) {
            web.evaluateJavascript("if(window.agentScrollBy)window.agentScrollBy(${if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) 240 else -240})", null)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    override fun onDestroy() {
        handler.removeCallbacks(debugRefresh)
        web.removeJavascriptInterface("AirMouseAgent"); web.destroy(); super.onDestroy()
    }
}
