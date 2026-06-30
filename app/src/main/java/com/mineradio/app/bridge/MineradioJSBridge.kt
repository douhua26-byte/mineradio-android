package com.mineradio.app.bridge

import android.util.Log
import android.webkit.JavascriptInterface
import com.google.gson.Gson
import com.mineradio.app.player.AudioPlayerManager
import com.mineradio.app.util.CookieManager
import com.mineradio.app.stats.StatsRepository
import com.mineradio.app.util.PreferencesHelper
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * JavaScript ↔ Native 桥接
 * 前端通过 window.AndroidBridge.*() 调用
 */
class MineradioJSBridge(
    private val activity: com.mineradio.app.MainActivity
) {
    companion object {
        private const val TAG = "MineradioJSBridge"
    }

    private val gson = Gson()
    private val okHttpClient = OkHttpClient()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ==================== 音频控制 ====================

    @JavascriptInterface
    fun playAudio(url: String, title: String, artist: String, cover: String) {
        Log.d(TAG, "playAudio: $title - $artist")
        activity.runOnUiThread {
            AudioPlayerManager.play(url, title, artist, cover)
        }
    }

    @JavascriptInterface
    fun pauseAudio() {
        activity.runOnUiThread {
            AudioPlayerManager.pause()
        }
    }

    @JavascriptInterface
    fun resumeAudio() {
        activity.runOnUiThread {
            AudioPlayerManager.resume()
        }
    }

    @JavascriptInterface
    fun seekTo(positionMs: Long) {
        activity.runOnUiThread {
            AudioPlayerManager.seekTo(positionMs)
        }
    }

    @JavascriptInterface
    fun setVolume(volume: Float) {
        activity.runOnUiThread {
            AudioPlayerManager.setVolume(volume)
        }
    }

    @JavascriptInterface
    fun getPlaybackState(): String {
        return gson.toJson(AudioPlayerManager.getState())
    }

    @JavascriptInterface
    fun playNext() {
        activity.runOnUiThread {
            // 通过 evaluateJavascript 通知前端切换下一首
            activity.webView?.post {
                activity.webView?.evaluateJavascript("if(typeof onNativeNext==='function')onNativeNext()", null)
            }
        }
    }

    @JavascriptInterface
    fun playPrevious() {
        activity.runOnUiThread {
            activity.webView?.post {
                activity.webView?.evaluateJavascript("if(typeof onNativePrev==='function')onNativePrev()", null)
            }
        }
    }

    // ==================== 频谱数据 ====================

    @JavascriptInterface
    fun startSpectrumAnalysis(fftSize: Int) {
        activity.runOnUiThread {
            AudioPlayerManager.startSpectrumAnalysis(fftSize) { frequencyData ->
                val json = gson.toJson(mapOf(
                    "frequencyData" to frequencyData.toList(),
                    "timestamp" to System.currentTimeMillis()
                ))
                activity.webView?.post {
                    activity.webView?.evaluateJavascript(
                        "if(typeof onSpectrumData==='function')onSpectrumData($json)", null
                    )
                }
            }
        }
    }

    @JavascriptInterface
    fun stopSpectrumAnalysis() {
        AudioPlayerManager.stopSpectrumAnalysis()
    }

    // ==================== localStorage 替代 ====================

    @JavascriptInterface
    fun getLocalStorage(key: String): String {
        return PreferencesHelper.getString("ls_$key")
    }

    @JavascriptInterface
    fun setLocalStorage(key: String, value: String) {
        PreferencesHelper.setString("ls_$key", value)
    }

    @JavascriptInterface
    fun removeLocalStorage(key: String) {
        PreferencesHelper.remove("ls_$key")
    }

    @JavascriptInterface
    fun getAllLocalStorageKeys(): String {
        val all = PreferencesHelper.getAllWithPrefix("ls_")
        val keys = all.keys.map { it.removePrefix("ls_") }
        return gson.toJson(keys)
    }

    // ==================== 听歌统计 ====================

    @JavascriptInterface
    fun recordListening(
        songId: String,
        songName: String,
        artist: String,
        provider: String,
        durationMs: Long
    ) {
        Log.d(TAG, "recordListening: $songName ${durationMs}ms")
        StatsRepository.recordListening(songId, songName, artist, provider, durationMs)
    }

    @JavascriptInterface
    fun getListeningStats(): String {
        val stats = StatsRepository.getTodayStats()
        return gson.toJson(stats)
    }

    @JavascriptInterface
    fun getListeningHistory(limit: Int): String {
        val history = StatsRepository.getRecentHistory(limit)
        return gson.toJson(history)
    }

    @JavascriptInterface
    fun getWeeklyStats(): String {
        val stats = StatsRepository.getWeeklyStats()
        return gson.toJson(stats)
    }

    // ==================== 登录 - Cookie 粘贴 ====================

    @JavascriptInterface
    fun openNeteaseLogin() {
        activity.runOnUiThread {
            activity.showCookiePasteDialog(
                provider = "netease",
                title = "网易云音乐登录",
                helpText = """电脑浏览器打开 music.163.com → F12 → Application → Cookies
找到 MUSIC_U，复制整段 Cookie 粘贴到下面：""",
                cookieHint = "MUSIC_U=xxx; __csrf=xxx; ...",
                onCookieSubmit = { cookie ->
                    submitCookieToServer("netease", cookie)
                }
            )
        }
    }

    @JavascriptInterface
    fun openQQMusicLogin() {
        activity.runOnUiThread {
            activity.showCookiePasteDialog(
                provider = "qq",
                title = "QQ 音乐登录",
                helpText = """电脑浏览器打开 y.qq.com → F12 → Application → Cookies
找到 uin 和 qm_keyst，复制整段 Cookie 粘贴到下面：""",
                cookieHint = "uin=xxx; qm_keyst=xxx; ...",
                onCookieSubmit = { cookie ->
                    submitCookieToServer("qq", cookie)
                }
            )
        }
    }

    /**
     * 将 Cookie 提交到 Ktor 本地服务器
     */
    private fun submitCookieToServer(provider: String, cookie: String) {
        scope.launch {
            try {
                val endpoint = if (provider == "netease") {
                    "/api/login/cookie"
                } else {
                    "/api/qq/login/cookie"
                }

                val json = """{"cookie":"${cookie.replace("\"", "\\\"")}"}"""
                val body = json.toRequestBody("application/json".toMediaType())

                val request = Request.Builder()
                    .url("http://127.0.0.1:3000$endpoint")
                    .post(body)
                    .build()

                val response = okHttpClient.newCall(request).execute()
                val responseBody = response.body?.string() ?: "{}"
                val result = gson.fromJson(responseBody, Map::class.java) as? Map<*, *>

                val loggedIn = result?.get("loggedIn") as? Boolean ?: false

                activity.runOnUiThread {
                    if (loggedIn) {
                        val nickname = result?.get("nickname")?.toString() ?: ""
                        val msg = if (nickname.isNotEmpty()) "登录成功：$nickname" else "登录成功！"
                        android.widget.Toast.makeText(activity, msg, android.widget.Toast.LENGTH_SHORT).show()
                        // 通知前端刷新登录状态
                        activity.webView?.evaluateJavascript(
                            "if(typeof onLoginSuccess==='function')onLoginSuccess('$provider')", null
                        )
                    } else {
                        val error = result?.get("message")?.toString()
                            ?: result?.get("error")?.toString()
                            ?: "登录失败，请检查 Cookie 是否正确"
                        android.widget.Toast.makeText(activity, error, android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Cookie submit failed: ${e.message}")
                activity.runOnUiThread {
                    android.widget.Toast.makeText(activity, "网络错误：${e.message}", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    @JavascriptInterface
    fun getNeteaseLoginStatus(): String {
        return gson.toJson(mapOf(
            "loggedIn" to CookieManager.hasNeteaseLogin(),
            "cookie" to CookieManager.getNeteaseCookie()
        ))
    }

    @JavascriptInterface
    fun getQQLoginStatus(): String {
        return gson.toJson(mapOf(
            "loggedIn" to CookieManager.hasQQLogin(),
            "hasPlaybackKey" to CookieManager.hasQQPlaybackKey(),
            "cookie" to CookieManager.getQQCookie()
        ))
    }

    // ==================== 系统信息 ====================

    @JavascriptInterface
    fun getPlatformInfo(): String {
        return gson.toJson(mapOf(
            "platform" to "android",
            "isMobile" to true,
            "version" to "1.0.0"
        ))
    }

    @JavascriptInterface
    fun keepScreenOn(keep: Boolean) {
        // Android 保持屏幕常亮
    }

    // ==================== Toast / 通知 ====================

    @JavascriptInterface
    fun showToast(message: String) {
        activity.runOnUiThread {
            android.widget.Toast.makeText(activity, message, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    // ==================== 应用控制 ====================

    @JavascriptInterface
    fun exitApp() {
        activity.finish()
    }

    @JavascriptInterface
    fun getOrientation(): String {
        val orientation = activity.resources.configuration.orientation
        return if (orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) "landscape" else "portrait"
    }
}
