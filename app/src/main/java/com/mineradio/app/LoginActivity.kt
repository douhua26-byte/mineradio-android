package com.mineradio.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.mineradio.app.util.CookieManager as AppCookieManager

/**
 * 内嵌 WebView 登录页
 * 加载网易云/QQ音乐网页，用户扫码登录后自动抓取 Cookie
 */
class LoginActivity : Activity() {

    companion object {
        private const val TAG = "LoginActivity"
        const val EXTRA_PROVIDER = "provider"

        // 需要抓取的 Cookie 关键字段
        private val NETEASE_REQUIRED = listOf("MUSIC_U")
        private val QQ_REQUIRED = listOf("uin", "qm_keyst")
    }

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView
    private var provider: String = "netease"
    private var cookieCheckCount: Int = 0
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var cookieCheckRunnable: Runnable? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        provider = intent.getStringExtra(EXTRA_PROVIDER) ?: "netease"

        // 构建简单布局
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#050608"))
        }

        // 顶部栏
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(32, 48, 32, 16)
            setBackgroundColor(Color.parseColor("#0D0D14"))
        }

        val titleText = TextView(this).apply {
            text = if (provider == "netease") "网易云音乐登录" else "QQ 音乐登录"
            textSize = 18f
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        statusText = TextView(this).apply {
            text = "请在下方页面中扫码或输入账号登录"
            textSize = 12f
            setTextColor(Color.parseColor("#888888"))
            setPadding(0, 8, 0, 0)
        }

        val closeBtn = Button(this).apply {
            text = "✕"
            textSize = 20f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { finish() }
        }

        toolbar.addView(titleText)
        toolbar.addView(closeBtn)

        // 进度条
        progressBar = ProgressBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 8
            )
            setBackgroundColor(Color.parseColor("#1A1A2E"))
        }

        // WebView
        webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            setupWebView()
        }

        root.addView(toolbar)
        root.addView(progressBar)
        root.addView(statusText)
        root.addView(webView)

        setContentView(root)

        // 加载登录页
        val loginUrl = when (provider) {
            "netease" -> "https://music.163.com/#/login"
            "qq" -> "https://xui.ptlogin2.qq.com/cgi-bin/xlogin?appid=716027609&style=33&s_url=https%3A%2F%2Fy.qq.com"
            else -> "https://music.163.com/#/login"
        }
        webView.loadUrl(loginUrl)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun WebView.setupWebView() {
        setBackgroundColor(Color.parseColor("#050608"))

        with(settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            databaseEnabled = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        }

        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                Log.d(TAG, "Page loaded: $url")
                progressBar.visibility = View.GONE

                // 页面加载完成后，启动 Cookie 轮询
                startCookiePolling()
            }

            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                // 保持 App 内打开
                return false
            }
        }

        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progressBar.progress = newProgress
                if (newProgress < 100) {
                    progressBar.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun startCookiePolling() {
        cookieCheckRunnable?.let { handler.removeCallbacks(it) }

        cookieCheckRunnable = object : Runnable {
            override fun run() {
                cookieCheckCount++
                checkCookies()
                // 每 2 秒检查一次，最多 120 次（4 分钟）
                if (cookieCheckCount < 120) {
                    handler.postDelayed(this, 2000)
                } else {
                    statusText.text = "登录超时，请关闭重试"
                }
            }
        }

        handler.post(cookieCheckRunnable!!)
    }

    private fun checkCookies() {
        val androidCookieManager = CookieManager.getInstance()
        val url = when (provider) {
            "netease" -> "https://music.163.com"
            "qq" -> "https://y.qq.com"
            else -> return
        }

        val cookieStr = androidCookieManager.getCookie(url) ?: ""

        if (cookieStr.isEmpty()) {
            statusText.text = "等待登录... (${cookieCheckCount * 2}s)"
            return
        }

        val hasLogin = when (provider) {
            "netease" -> NETEASE_REQUIRED.all { key ->
                cookieStr.contains("$key=")
            }
            "qq" -> QQ_REQUIRED.all { key ->
                cookieStr.contains("$key=")
            }
            else -> false
        }

        if (hasLogin) {
            Log.d(TAG, "Login detected! Cookie: ${cookieStr.take(80)}...")

            // 保存 Cookie
            if (provider == "netease") {
                AppCookieManager.saveNeteaseCookie(cookieStr)
            } else {
                AppCookieManager.saveQQCookie(cookieStr)
            }

            statusText.text = "✅ 登录成功！"

            // 延迟一下让用户看到成功提示，然后关闭
            handler.postDelayed({
                setResult(Activity.RESULT_OK)
                finish()
            }, 800)
        } else {
            statusText.text = "等待登录... (${cookieCheckCount * 2}s)"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cookieCheckRunnable?.let { handler.removeCallbacks(it) }
        webView.destroy()
    }
}
