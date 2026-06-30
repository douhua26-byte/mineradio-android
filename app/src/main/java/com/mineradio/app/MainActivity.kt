package com.mineradio.app

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import android.app.Activity
import androidx.core.view.WindowCompat
import com.mineradio.app.bridge.MineradioJSBridge
import com.mineradio.app.player.AudioPlayerManager

class MainActivity : Activity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val LOCAL_SERVER_URL = "http://127.0.0.1:3000"

        private const val ANDROID_ADAPTATION_JS = """
(function() {
    if (!window.desktopWindow) {
        window.desktopWindow = {
            isDesktop: false,
            minimize: function() { AndroidBridge.exitApp(); },
            close: function() { AndroidBridge.exitApp(); },
            toggleFullscreen: function() {},
            getState: function() { return { isFullScreen: false }; },
            setDesktopLyricsEnabled: function() {},
            updateDesktopLyrics: function() {},
            setWallpaperMode: function() {},
            updateWallpaperMode: function() {},
            onGlobalHotkey: function() {},
            onStateChange: function() {},
            configureGlobalHotkeys: function() { return { ok: true, results: [] }; },
            openNeteaseMusicLogin: function() { AndroidBridge.openNeteaseLogin(); },
            openQQMusicLogin: function() { AndroidBridge.openQQMusicLogin(); },
            exportJsonFile: function() {},
            importJsonFile: function() {},
            openUpdateInstaller: function() {},
            restartApp: function() {}
        };
    }
    try {
        var _origSetItem = localStorage.setItem;
        var _origGetItem = localStorage.getItem;
        localStorage.setItem = function(key, value) {
            try { _origSetItem.call(localStorage, key, value); } catch(e) {}
            try { AndroidBridge.setLocalStorage(key, value); } catch(e) {}
        };
        localStorage.getItem = function(key) {
            try { var val = AndroidBridge.getLocalStorage(key); if (val) return val; } catch(e) {}
            return _origGetItem.call(localStorage, key);
        };
    } catch(e) {}
    document.body.dataset.orientation = window.innerWidth > window.innerHeight ? 'landscape' : 'portrait';
    window.onPlaybackStateUpdate = function(state) {};
    window.onNativeNext = function() {};
    window.onNativePrev = function() {};
    window.onSpectrumData = function(data) {};
    window.MINERADIO_PLATFORM = 'android';
    window.MINERADIO_IS_MOBILE = true;
    console.log('[Mineradio Android] Ready');
})();
"""
    }

    var webView: WebView? = null
        private set

    private lateinit var jsBridge: MineradioJSBridge
    private val handler = Handler(Looper.getMainLooper())

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            setupImmersiveMode()

            webView = WebView(this).apply { setupWebView() }
            setContentView(webView)

            jsBridge = MineradioJSBridge(this)
            webView?.addJavascriptInterface(jsBridge, "AndroidBridge")

            AudioPlayerManager.setWebView(webView)

            // 先加载本地页面，不依赖 Ktor 服务器
            webView?.loadUrl("file:///android_asset/index.html")

            // 后台启动 Ktor 服务器
            startServerWithRetry()
        } catch (e: Exception) {
            Log.e(TAG, "FATAL onCreate: ${e.message}", e)
            Toast.makeText(this, "启动失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun startServerWithRetry() {
        Thread({
            try {
                com.mineradio.app.server.MineradioServer.start()
                Log.d(TAG, "Ktor server started")
            } catch (e: Exception) {
                Log.w(TAG, "Server start failed, retrying...: ${e.message}")
                try { Thread.sleep(2000) } catch (_: Exception) {}
                try {
                    com.mineradio.app.server.MineradioServer.start()
                } catch (e2: Exception) {
                    Log.e(TAG, "Server start failed permanently: ${e2.message}")
                    handler.post {
                        Toast.makeText(this, "API 服务启动失败，搜索和播放功能不可用", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }, "ServerThread").start()
    }

    private fun setupImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER
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
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(false)
            allowUniversalAccessFromFileURLs = true
        }

        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                view.evaluateJavascript(ANDROID_ADAPTATION_JS, null)
            }
        }

        webChromeClient = object : WebChromeClient() {}

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT &&
            0 != applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
    }

    // 登录回调：LoginActivity 成功抓取 Cookie 后通知前端
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == MineradioJSBridge.REQUEST_LOGIN && resultCode == Activity.RESULT_OK) {
            webView?.postDelayed({
                webView?.evaluateJavascript(
                    "if(typeof onLoginSuccess==='function')onLoginSuccess()", null
                )
            }, 500)
        }
    }

    override fun onResume() {
        super.onResume()
        webView?.onResume()
    }

    override fun onPause() {
        super.onPause()
        webView?.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        webView?.removeJavascriptInterface("AndroidBridge")
        webView?.destroy()
        AudioPlayerManager.release()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val o = if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) "landscape" else "portrait"
        webView?.evaluateJavascript("if(typeof onOrientationChange==='function')onOrientationChange('$o')", null)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) { moveTaskToBack(true); return true }
        return super.onKeyDown(keyCode, event)
    }
}
