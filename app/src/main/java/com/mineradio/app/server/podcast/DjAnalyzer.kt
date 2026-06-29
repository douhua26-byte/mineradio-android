package com.mineradio.app.server.podcast

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import kotlin.math.*

/**
 * 播客/DJ 音频节拍分析器
 * 移植自 dj-analyzer.js 的核心算法
 *
 * 使用 Android MediaCodec 解码 MP3 为 PCM，
 * 然后应用 Biquad 滤波 + 能量检测 + Tempo 估计
 *
 * 当前为骨架实现，完整版需要更精细的 DSP 调参
 */
object DjAnalyzer {
    private const val TAG = "DjAnalyzer"

    // 分析参数
    private const val HOP_SEC = 0.010         // 10ms 帧跳跃
    private const val MIN_BEAT_GAP_MS = 180.0 // 最小节拍间隔
    private const val MAX_STEP_SEC = 0.86     // 最大 Tempo 间隔 (~70 BPM)
    private const val MIN_STEP_SEC = 0.32     // 最小 Tempo 间隔 (~187 BPM)

    // 滤波参数
    private const val HP_CUTOFF = 32.0        // 高通截止频率 (Hz)
    private const val LP_CUTOFF = 178.0       // 低通截止频率 (Hz)

    /**
     * 分析完整音频流的节拍
     */
    suspend fun analyzeStream(
        audioUrl: String,
        durationSec: Double,
        userAgent: String = "Mineradio/1.0.0"
    ): Map<String, Any?> {
        Log.d(TAG, "analyzeStream: duration=${durationSec}s")

        return withContext(Dispatchers.IO) {
            try {
                // 下载音频文件
                val audioData = downloadAudio(audioUrl, userAgent)
                if (audioData.isEmpty()) {
                    return@withContext mapOf("ok" to false, "error" to "DOWNLOAD_FAILED")
                }

                // 解码 MP3 → PCM
                val pcmSamples = decodeMp3ToPcm(audioData)
                if (pcmSamples.isEmpty()) {
                    return@withContext mapOf("ok" to false, "error" to "DECODE_FAILED")
                }

                // DSP 节拍检测
                val beatMap = detectBeats(pcmSamples, durationSec)

                mapOf("ok" to true, "map" to beatMap)
            } catch (e: Exception) {
                Log.e(TAG, "Beat analysis failed: ${e.message}")
                mapOf("ok" to false, "error" to (e.message ?: "UNKNOWN"))
            }
        }
    }

    /**
     * 分析音频开头部分的节拍（快速模式）
     */
    suspend fun analyzeIntro(
        audioUrl: String,
        durationSec: Double,
        introSec: Double = 180.0,
        userAgent: String = "Mineradio/1.0.0"
    ): Map<String, Any?> {
        Log.d(TAG, "analyzeIntro: duration=${durationSec}s, intro=${introSec}s")

        val result = analyzeStream(audioUrl, minOf(durationSec, introSec), userAgent)
        val map = (result["map"] as? Map<*, *>)?.toMutableMap() ?: mutableMapOf()
        (map as MutableMap<String, Any>)["partial"] = true
        (map as MutableMap<String, Any>)["partialUntilSec"] = introSec

        val ok = result["ok"]
        return mapOf("ok" to ok, "map" to map)
    }

    // ==================== 内部方法 ====================

    private suspend fun downloadAudio(url: String, userAgent: String): ByteArray {
        return withContext(Dispatchers.IO) {
            try {
                val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                connection.setRequestProperty("User-Agent", userAgent)
                connection.connectTimeout = 15000
                connection.readTimeout = 30000
                connection.connect()

                connection.inputStream.use { it.readBytes() }
            } catch (e: Exception) {
                Log.e(TAG, "Download failed: ${e.message}")
                ByteArray(0)
            }
        }
    }

    private fun decodeMp3ToPcm(mp3Data: ByteArray): FloatArray {
        // 使用 Android MediaCodec 解码 MP3
        // 这是简化实现，完整版需要处理 MediaCodec 的异步缓冲区
        try {
            val extractor = MediaExtractor()
            // 将 MP3 数据写入临时文件并设置数据源
            // 实际实现中需要使用 MemoryFile 或临时文件
            // 此处为骨架代码
            return FloatArray(0)
        } catch (e: Exception) {
            Log.e(TAG, "MP3 decode failed: ${e.message}")
            return FloatArray(0)
        }
    }

    /**
     * 核心节拍检测算法
     * 移植自 dj-analyzer.js 的 DSP 管道
     */
    private fun detectBeats(samples: FloatArray, durationSec: Double): Map<String, Any?> {
        if (samples.isEmpty()) return emptyBeatMap(durationSec)

        val sampleRate = 44100.0
        val hopSamples = (HOP_SEC * sampleRate).toInt()

        // 1. 降采样
        val step = 4 // 44100 Hz → step 4
        val downsampled = mutableListOf<Float>()
        var i = 0
        while (i < samples.size) {
            downsampled.add(samples[i])
            i += step
        }

        val dsSampleRate = sampleRate / step
        val dsSamples = downsampled.toFloatArray()

        // 2. Biquad 滤波（高通 + 低通级联 → 带通）
        val filtered = biquadCascade(dsSamples, dsSampleRate)

        // 3. 能量帧提取
        val frameHop = (HOP_SEC * dsSampleRate).toInt()
        val energies = extractEnergyFrames(filtered, frameHop)
        val lowBand = extractLowBandEnergy(filtered, frameHop, dsSampleRate)
        val hitBand = extractHitBandEnergy(filtered, frameHop, dsSampleRate)

        // 4. 起始点检测
        val onsets = detectOnsets(energies, lowBand, hitBand)

        // 5. 自适应阈值
        val peaks = adaptiveThreshold(onsets, energies)

        // 6. Tempo 估计
        val tempos = estimateTempo(peaks, dsSampleRate / frameHop)

        // 7. 生成节拍网格
        val beatGrid = generateBeatGrid(
            peaks, tempos, durationSec, dsSampleRate / frameHop
        )

        return beatGrid
    }

    /**
     * Biquad 级联滤波（高通 32Hz + 低通 178Hz ≈ 带通）
     */
    private fun biquadCascade(samples: FloatArray, sampleRate: Double): FloatArray {
        val highpassed = biquadHighpass(samples, sampleRate, HP_CUTOFF, 0.72)
        return biquadLowpass(highpassed, sampleRate, LP_CUTOFF, 0.82)
    }

    private fun biquadHighpass(samples: FloatArray, fs: Double, cutoff: Double, q: Double): FloatArray {
        val w0 = 2.0 * PI * cutoff / fs
        val alpha = sin(w0) / (2.0 * q)
        val cosW0 = cos(w0)

        val b0 = (1.0 + cosW0) / 2.0
        val b1 = -(1.0 + cosW0)
        val b2 = b0
        val a0 = 1.0 + alpha
        val a1 = -2.0 * cosW0
        val a2 = 1.0 - alpha

        return applyBiquad(samples, b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
    }

    private fun biquadLowpass(samples: FloatArray, fs: Double, cutoff: Double, q: Double): FloatArray {
        val w0 = 2.0 * PI * cutoff / fs
        val alpha = sin(w0) / (2.0 * q)
        val cosW0 = cos(w0)

        val b0 = (1.0 - cosW0) / 2.0
        val b1 = 1.0 - cosW0
        val b2 = b0
        val a0 = 1.0 + alpha
        val a1 = -2.0 * cosW0
        val a2 = 1.0 - alpha

        return applyBiquad(samples, b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
    }

    private fun applyBiquad(
        samples: FloatArray,
        b0: Double, b1: Double, b2: Double,
        a1: Double, a2: Double
    ): FloatArray {
        val result = FloatArray(samples.size)
        var x1 = 0.0; var x2 = 0.0
        var y1 = 0.0; var y2 = 0.0

        for (i in samples.indices) {
            val x0 = samples[i].toDouble()
            val y0 = b0 * x0 + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            result[i] = y0.toFloat()
            x2 = x1; x1 = x0
            y2 = y1; y1 = y0
        }
        return result
    }

    private fun extractEnergyFrames(samples: FloatArray, hop: Int): FloatArray {
        val frameCount = (samples.size / hop)
        val energies = FloatArray(frameCount)
        for (i in 0 until frameCount) {
            var sum = 0f
            val start = i * hop
            val end = minOf(start + hop, samples.size)
            for (j in start until end) {
                sum += samples[j] * samples[j]
            }
            energies[i] = sqrt(sum / (end - start))
        }
        return energies
    }

    private fun extractLowBandEnergy(samples: FloatArray, hop: Int, fs: Double): FloatArray {
        // 简化：低频段 (20-80Hz) 的能量
        return extractEnergyFrames(samples, hop)
    }

    private fun extractHitBandEnergy(samples: FloatArray, hop: Int, fs: Double): FloatArray {
        // 简化：中频段 (80-250Hz) 的能量
        return extractEnergyFrames(samples, hop)
    }

    private fun detectOnsets(
        energies: FloatArray,
        lowBand: FloatArray,
        hitBand: FloatArray
    ): FloatArray {
        val onsets = FloatArray(energies.size)
        for (i in 1 until energies.size) {
            val diff = energies[i] - energies[i - 1]
            val lowDiff = if (i < lowBand.size) lowBand[i] - lowBand.getOrElse(i - 1) { 0f } else 0f
            val hitDiff = if (i < hitBand.size) hitBand[i] - hitBand.getOrElse(i - 1) { 0f } else 0f
            // 加权组合
            onsets[i] = maxOf(0f, diff * 0.5f + lowDiff * 0.3f + hitDiff * 0.2f)
        }
        return onsets
    }

    private fun adaptiveThreshold(onsets: FloatArray, energies: FloatArray): List<BeatPeak> {
        val peaks = mutableListOf<BeatPeak>()
        val windowSize = minOf(43, energies.size / 3).coerceAtLeast(8) // ~430ms

        for (i in windowSize until energies.size - 1) {
            val window = energies.copyOfRange(i - windowSize, minOf(i + windowSize, energies.size))
            val mean = window.average()
            val std = windowStdDev(window, mean.toFloat())

            val threshold = mean + std * 1.8
            val onsetVal = onsets.getOrElse(i) { 0f }

            if (onsetVal > threshold) {
                // 检查最小间隔
                val minFrames = (MIN_BEAT_GAP_MS / 1000.0 / HOP_SEC).toInt()
                val lastPeak = peaks.lastOrNull()
                if (lastPeak == null || i - lastPeak.frameIndex >= minFrames) {
                    peaks.add(BeatPeak(
                        frameIndex = i,
                        time = i * HOP_SEC,
                        strength = onsetVal / threshold.toFloat(),
                        energy = energies[i]
                    ))
                }
            }
        }
        return peaks
    }

    private fun windowStdDev(window: FloatArray, mean: Float): Double {
        if (window.isEmpty()) return 0.0
        val variance = window.map { (it - mean).toDouble().pow(2) }.average()
        return sqrt(variance)
    }

    private fun estimateTempo(peaks: List<BeatPeak>, framesPerSec: Double): List<TempoEstimate> {
        if (peaks.size < 2) return listOf(TempoEstimate(0.5, 120.0, 0.0))

        // 计算峰-峰间隔直方图
        val gaps = mutableMapOf<Double, Int>()
        for (i in 1 until peaks.size) {
            val gap = peaks[i].time - peaks[i - 1].time
            if (gap in MIN_STEP_SEC..MAX_STEP_SEC) {
                val roundedGap = (gap * 100).roundToInt() / 100.0
                gaps[roundedGap] = (gaps[roundedGap] ?: 0) + 1
            }
        }

        // 找到主导间隔
        return gaps.entries
            .sortedByDescending { it.value }
            .take(3)
            .map { (gap, count) ->
                val bpm = 60.0 / gap
                val confidence = count.toDouble() / peaks.size
                TempoEstimate(gap, bpm, confidence)
            }
    }

    private fun generateBeatGrid(
        peaks: List<BeatPeak>,
        tempos: List<TempoEstimate>,
        durationSec: Double,
        framesPerSec: Double
    ): Map<String, Any?> {
        if (tempos.isEmpty()) return emptyBeatMap(durationSec)

        val mainTempo = tempos.first()
        val gridStep = mainTempo.stepSec
        val bpm = mainTempo.bpm

        // 用相位对齐确定网格偏移
        val phaseOffset = if (peaks.size >= 3) {
            val firstTimes = peaks.take(10).map { it.time }
            firstTimes.firstOrNull() ?: 0.0
        } else {
            0.0
        }

        // 生成完整节拍网格
        val beats = mutableListOf<Map<String, Any>>()
        val kicks = mutableListOf<Double>()
        var time = phaseOffset
        var index = 0

        while (time < durationSec) {
            // 找到最近的检测峰值获取能量
            val nearestPeak = peaks.minByOrNull { abs(it.time - time) }
            val energy = nearestPeak?.energy ?: 0f
            val strength = nearestPeak?.strength ?: 0f
            val dist = nearestPeak?.let { abs(it.time - time) } ?: gridStep
            val confidence = maxOf(0.0, 1.0 - dist / gridStep)

            val isDownbeat = index % 4 == 0
            val isCamera = isDownbeat || (strength > 1.5f)

            beats.add(mapOf(
                "time" to time,
                "strength" to strength,
                "confidence" to confidence,
                "impact" to if (isDownbeat) 0.8 else 0.4,
                "primary" to isDownbeat,
                "camera" to isCamera,
                "pulse" to true,
                "low" to energy * 0.6f,
                "body" to energy * 0.3f,
                "snap" to energy * 0.1f,
                "mass" to (energy * 0.7 + 0.3).toDouble(),
                "sharpness" to (0.08 + (1.0 - energy) * 0.12),
                "combo" to when {
                    isDownbeat -> "downbeat"
                    index % 2 == 0 -> "push"
                    else -> "accent"
                },
                "step" to gridStep,
                "index" to index,
                "server" to true,
                "grid" to true
            ))

            kicks.add(time)
            time += gridStep
            index++
        }

        return mapOf(
            "kicks" to kicks,
            "beats" to beats,
            "pulseBeats" to beats.filter { it["pulse"] == true },
            "cameraBeats" to beats.filter { it["camera"] == true },
            "gridStep" to gridStep,
            "tempoSource" to "podcast-dj-server-grid",
            "duration" to durationSec,
            "visualBeatCount" to beats.size,
            "analyzedAt" to System.currentTimeMillis(),
            "debug" to mapOf(
                "candidates" to peaks.size,
                "hopSec" to HOP_SEC,
                "bpm" to bpm,
                "gridStep" to gridStep,
                "phaseOffset" to phaseOffset
            )
        )
    }

    private fun emptyBeatMap(durationSec: Double): Map<String, Any?> {
        return mapOf(
            "kicks" to emptyList<Double>(),
            "beats" to emptyList<Map<String, Any?>>(),
            "pulseBeats" to emptyList<Map<String, Any?>>(),
            "cameraBeats" to emptyList<Map<String, Any?>>(),
            "gridStep" to 0.5,
            "duration" to durationSec,
            "visualBeatCount" to 0,
            "analyzedAt" to System.currentTimeMillis()
        )
    }

    // ==================== 数据类 ====================

    data class BeatPeak(
        val frameIndex: Int,
        val time: Double,
        val strength: Float,
        val energy: Float
    )

    data class TempoEstimate(
        val stepSec: Double,
        val bpm: Double,
        val confidence: Double
    )
}
