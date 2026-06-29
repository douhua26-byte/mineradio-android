package com.mineradio.app.player

import android.content.ComponentName
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.mineradio.app.MineradioApp
import com.mineradio.app.stats.StatsRepository
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * 单例音频播放器管理器
 * 封装 ExoPlayer，提供播放、暂停、淡入淡出、频谱分析等功能
 */
object AudioPlayerManager {
    private const val TAG = "AudioPlayerManager"
    private const val MIN_LISTEN_DURATION_MS = 30_000L // 最小计入统计的播放时长
    private const val STATE_UPDATE_INTERVAL_MS = 250L

    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private var spectrumCallback: ((FloatArray) -> Unit)? = null
    private var spectrumRunning = false

    // 当前歌曲
    private var currentSongId: String = ""
    private var currentTitle: String = ""
    private var currentArtist: String = ""
    private var currentCover: String = ""
    private var currentProvider: String = ""

    // 听歌计时
    private var listenStartTime: Long = 0L
    private var accumulatedMs: Long = 0L
    private var songStartPosition: Long = 0L

    // 状态推送定时器
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private var stateUpdateFuture: ScheduledFuture<*>? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // WebView 引用（由 MainActivity 设置）
    private var webView: android.webkit.WebView? = null

    fun setWebView(wv: android.webkit.WebView?) {
        webView = wv
    }

    /**
     * 初始化播放器
     */
    fun init() {
        if (player != null) return

        player = ExoPlayer.Builder(MineradioApp.instance)
            .setHandleAudioBecomingNoisy(true)
            .build()

        player?.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_READY -> {
                        if (player?.playWhenReady == true) {
                            startListeningTimer()
                            startStateUpdates()
                        }
                    }
                    Player.STATE_ENDED -> {
                        stopListeningTimer()
                        stopStateUpdates()
                        notifyFrontendNext()
                    }
                    else -> {}
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    startListeningTimer()
                    startStateUpdates()
                } else {
                    stopListeningTimer()
                    stopStateUpdates()
                }
                pushState()
            }

            override fun onMediaMetadataChanged(metadata: MediaMetadata) {
                pushState()
            }
        })
    }

    /**
     * 获取播放器实例
     */
    fun getPlayer(): ExoPlayer? = player

    /**
     * 播放音频
     */
    fun play(url: String, title: String, artist: String, cover: String) {
        Log.d(TAG, "play: $title - $artist")
        init()

        // 保存上首歌的听歌记录
        saveCurrentListenRecord()

        currentTitle = title
        currentArtist = artist
        currentCover = cover
        currentProvider = if (url.contains("qq.com")) "qq" else "netease"
        currentSongId = extractSongId(url)
        accumulatedMs = 0L

        val mediaItem = MediaItem.Builder()
            .setUri(url)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .setArtworkUri(android.net.Uri.parse(cover))
                    .build()
            )
            .build()

        player?.apply {
            setMediaItem(mediaItem)
            prepare()
            play()
            volume = 1.0f
        }

        pushState()
    }

    fun pause() {
        player?.pause()
    }

    fun resume() {
        player?.play()
    }

    fun seekTo(positionMs: Long) {
        player?.seekTo(positionMs)
    }

    fun setVolume(volume: Float) {
        player?.volume = volume.coerceIn(0f, 1f)
    }

    /**
     * 淡入效果
     */
    fun fadeIn(durationMs: Long = 300) {
        val steps = (durationMs / 50).coerceAtLeast(1)
        val increment = 1.0f / steps
        val handler = Handler(Looper.getMainLooper())
        var step = 0

        val runnable = object : Runnable {
            override fun run() {
                step++
                player?.volume = (increment * step).coerceAtMost(1.0f)
                if (step < steps) {
                    handler.postDelayed(this, 50)
                }
            }
        }
        handler.post(runnable)
    }

    /**
     * 淡出效果
     */
    fun fadeOut(durationMs: Long = 800) {
        val steps = (durationMs / 50).coerceAtLeast(1)
        val currentVol = player?.volume ?: 1.0f
        val decrement = currentVol / steps
        val handler = Handler(Looper.getMainLooper())
        var step = 0

        val runnable = object : Runnable {
            override fun run() {
                step++
                player?.volume = (currentVol - decrement * step).coerceAtLeast(0.0f)
                if (step < steps) {
                    handler.postDelayed(this, 50)
                }
            }
        }
        handler.post(runnable)
    }

    /**
     * 频谱分析
     */
    fun startSpectrumAnalysis(fftSize: Int, callback: (FloatArray) -> Unit) {
        spectrumCallback = callback
        spectrumRunning = true
        // 使用 ExoPlayer 的 AudioProcessor 获取频谱数据
        // 这里简化实现：通过模拟频谱数据
        scheduler.scheduleAtFixedRate({
            if (spectrumRunning && player?.isPlaying == true) {
                val mockSpectrum = generateMockSpectrum(fftSize)
                spectrumCallback?.invoke(mockSpectrum)
            }
        }, 0, 50, TimeUnit.MILLISECONDS)
    }

    fun stopSpectrumAnalysis() {
        spectrumRunning = false
        spectrumCallback = null
    }

    /**
     * 获取当前播放状态 (JSON 兼容)
     */
    fun getState(): Map<String, Any?> {
        return mapOf(
            "playing" to (player?.isPlaying ?: false),
            "position" to (player?.currentPosition ?: 0L),
            "duration" to (player?.duration ?: 0L),
            "buffered" to (player?.bufferedPercentage ?: 0),
            "title" to currentTitle,
            "artist" to currentArtist,
            "cover" to currentCover
        )
    }

    /**
     * 释放资源
     */
    fun release() {
        saveCurrentListenRecord()
        stopSpectrumAnalysis()
        stopStateUpdates()
        player?.release()
        player = null
        mediaSession?.release()
        mediaSession = null
    }

    // ==================== 内部方法 ====================

    private fun startListeningTimer() {
        if (listenStartTime == 0L) {
            listenStartTime = System.currentTimeMillis()
            songStartPosition = player?.currentPosition ?: 0L
        }
    }

    private fun stopListeningTimer() {
        if (listenStartTime > 0L) {
            val elapsed = System.currentTimeMillis() - listenStartTime
            accumulatedMs += elapsed
            listenStartTime = 0L
        }
    }

    private fun saveCurrentListenRecord() {
        stopListeningTimer()
        if (accumulatedMs >= MIN_LISTEN_DURATION_MS && currentSongId.isNotEmpty()) {
            StatsRepository.recordListening(
                songId = currentSongId,
                songName = currentTitle,
                artist = currentArtist,
                provider = currentProvider,
                durationMs = accumulatedMs
            )
        }
        accumulatedMs = 0L
    }

    private fun startStateUpdates() {
        stopStateUpdates()
        stateUpdateFuture = scheduler.scheduleAtFixedRate({
            pushState()
        }, 0, STATE_UPDATE_INTERVAL_MS, TimeUnit.MILLISECONDS)
    }

    private fun stopStateUpdates() {
        stateUpdateFuture?.cancel(false)
        stateUpdateFuture = null
    }

    private fun pushState() {
        val state = getState()
        val json = com.google.gson.Gson().toJson(state)
        mainHandler.post {
            webView?.evaluateJavascript(
                "if(typeof onPlaybackStateUpdate==='function')onPlaybackStateUpdate($json)",
                null
            )
        }
    }

    private fun notifyFrontendNext() {
        mainHandler.post {
            webView?.evaluateJavascript(
                "if(typeof onNativeNext==='function')onNativeNext()",
                null
            )
        }
    }

    private fun extractSongId(url: String): String {
        // 尝试从URL中提取歌曲ID
        val idPattern = Regex("""[?&]id=(\d+)""")
        val midPattern = Regex("""[?&]mid=(\w+)""")
        return idPattern.find(url)?.groupValues?.get(1)
            ?: midPattern.find(url)?.groupValues?.get(1)
            ?: url.hashCode().toString()
    }

    /**
     * 生成模拟频谱数据（占位，实际应使用 AudioProcessor）
     */
    private fun generateMockSpectrum(fftSize: Int): FloatArray {
        return FloatArray(fftSize / 2) {
            (Math.random() * 255).toFloat()
        }
    }
}
