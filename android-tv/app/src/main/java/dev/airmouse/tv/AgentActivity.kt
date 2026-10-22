package dev.airmouse.tv

import android.app.Activity
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

/** 电视只读阅读页：WebView 仅加载内置资源，桥接接口不能发起模型请求或读取 API Key。 */
class AgentActivity : Activity() {
    private lateinit var web: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        web = WebView(this)
        web.setBackgroundColor(android.graphics.Color.rgb(10, 10, 10))
        web.settings.apply {
            javaScriptEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            blockNetworkLoads = true
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
        }
        web.addJavascriptInterface(object {
            // 轮询携带 revision；页面未变化时不重传图片和历史，节省电视内存/桥接开销。
            @JavascriptInterface fun state(revision: Long): String {
                return AgentSession.snapshotSince(revision)
            }
        }, "AirMouseAgent")
        setContentView(web)
        web.loadUrl("file:///android_asset/agent-tv.html")
    }

    override fun onResume() { super.onResume(); web.onResume(); AgentSession.setVisible(true) }
    override fun onPause() { AgentSession.setVisible(false); web.onPause(); super.onPause() }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // 真遥控器上下键翻阅长回答；返回交给 Android，回到进入前的电视页面。
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            web.evaluateJavascript("window.agentScrollBy(${if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) 240 else -240})", null)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        web.removeJavascriptInterface("AirMouseAgent")
        web.destroy()
        super.onDestroy()
    }
}
