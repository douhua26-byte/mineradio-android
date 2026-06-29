package com.mineradio.app.server.proxy

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * 音频和封面代理
 * 绕过 CORS 限制，让 WebView 可以加载外部资源
 */
object AudioCoverProxy {
    private const val TAG = "Proxy"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /**
     * 代理音频流
     * @param url 原始音频 URL
     * @param rangeHeader Range 请求头（支持断点续传/seek）
     * @return 代理结果
     */
    suspend fun proxyAudio(
        url: String,
        rangeHeader: String? = null
    ): AudioProxyResult {
        Log.d(TAG, "proxyAudio: $url")

        return withContext(Dispatchers.IO) {
            try {
                val requestBuilder = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mineradio/1.0.0")

                // 自动添加 Referer
                when {
                    url.contains("163.com") || url.contains("music.126.net") ->
                        requestBuilder.header("Referer", "https://music.163.com/")
                    url.contains("qq.com") ->
                        requestBuilder.header("Referer", "https://y.qq.com/")
                    url.contains("qlogo.cn") ->
                        requestBuilder.header("Referer", "https://y.qq.com/")
                }

                if (!rangeHeader.isNullOrBlank()) {
                    requestBuilder.header("Range", rangeHeader)
                }

                val response = client.newCall(requestBuilder.build()).execute()

                // 读取完整响应体
                val bodyBytes = response.body?.bytes() ?: ByteArray(0)

                // 检测内容类型
                val contentType = response.header("Content-Type") ?: detectContentType(url)

                AudioProxyResult(
                    data = bodyBytes,
                    contentType = contentType,
                    contentLength = bodyBytes.size.toLong(),
                    statusCode = response.code,
                    isRange = rangeHeader != null,
                    responseHeaders = response.headers.toMultimap()
                        .mapValues { it.value.joinToString(", ") }
                )
            } catch (e: Exception) {
                Log.e(TAG, "Proxy audio failed: ${e.message}")
                AudioProxyResult(
                    data = ByteArray(0),
                    contentType = "audio/mpeg",
                    contentLength = 0,
                    statusCode = 502,
                    error = e.message
                )
            }
        }
    }

    /**
     * 代理封面图片
     */
    suspend fun proxyCover(url: String): CoverProxyResult {
        Log.d(TAG, "proxyCover: $url")

        return withContext(Dispatchers.IO) {
            try {
                val requestBuilder = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .header("Referer", "https://music.163.com/")

                val response = client.newCall(requestBuilder.build()).execute()
                val bodyBytes = response.body?.bytes() ?: ByteArray(0)

                CoverProxyResult(
                    data = bodyBytes,
                    contentType = response.header("Content-Type") ?: "image/jpeg",
                    contentLength = bodyBytes.size.toLong(),
                    statusCode = response.code
                )
            } catch (e: Exception) {
                Log.e(TAG, "Proxy cover failed: ${e.message}")
                CoverProxyResult(
                    data = ByteArray(0),
                    contentType = "image/jpeg",
                    contentLength = 0,
                    statusCode = 502,
                    error = e.message
                )
            }
        }
    }

    /**
     * 下载文件到字节数组
     */
    suspend fun downloadBytes(url: String): ByteArray {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(url).build()
                val response = client.newCall(request).execute()
                response.body?.bytes() ?: ByteArray(0)
            } catch (e: Exception) {
                ByteArray(0)
            }
        }
    }

    /**
     * 根据 URL 检测内容类型
     */
    private fun detectContentType(url: String): String {
        return when {
            url.endsWith(".mp3") -> "audio/mpeg"
            url.endsWith(".flac") -> "audio/flac"
            url.endsWith(".m4a") -> "audio/mp4"
            url.endsWith(".ogg") -> "audio/ogg"
            url.endsWith(".wav") -> "audio/wav"
            url.endsWith(".aac") -> "audio/aac"
            url.endsWith(".jpg") || url.endsWith(".jpeg") -> "image/jpeg"
            url.endsWith(".png") -> "image/png"
            url.endsWith(".webp") -> "image/webp"
            url.endsWith(".gif") -> "image/gif"
            else -> "audio/mpeg"  // 默认
        }
    }

    data class AudioProxyResult(
        val data: ByteArray,
        val contentType: String,
        val contentLength: Long,
        val statusCode: Int,
        val isRange: Boolean = false,
        val responseHeaders: Map<String, String> = emptyMap(),
        val error: String? = null
    )

    data class CoverProxyResult(
        val data: ByteArray,
        val contentType: String,
        val contentLength: Long,
        val statusCode: Int,
        val error: String? = null
    )
}
