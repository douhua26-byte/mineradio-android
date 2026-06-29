package com.mineradio.app.bridge

import android.util.Log
import android.webkit.JavascriptInterface
import com.google.gson.Gson
import com.mineradio.app.player.AudioPlayerManager
import com.mineradio.app.util.CookieManager
import com.mineradio.app.stats.StatsRepository
import com.mineradio.app.util.PreferencesHelper

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

    // ==================== 登录相关 ====================

    @JavascriptInterface
    fun openNeteaseLogin() {
        activity.runOnUiThread {
            activity.openLoginWebView("netease")
        }
    }

    @JavascriptInterface
    fun openQQMusicLogin() {
        activity.runOnUiThread {
            activity.openLoginWebView("qq")
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
