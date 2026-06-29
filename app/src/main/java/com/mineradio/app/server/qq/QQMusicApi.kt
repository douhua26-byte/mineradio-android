package com.mineradio.app.server.qq

import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.mineradio.app.util.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType

/**
 * QQ 音乐 API 封装
 */
object QQMusicApi {
    private const val TAG = "QQMusicApi"
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"

    private val client = OkHttpClient.Builder().build()
    private val gson = Gson()

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    // ==================== 搜索 ====================

    suspend fun search(keywords: String, limit: Int = 8): Map<String, Any?> {
        Log.d(TAG, "search: $keywords, limit=$limit")

        return withContext(Dispatchers.IO) {
            try {
                val url = "https://c.y.qq.com/splcloud/fcgi-bin/smartbox_new.fcg?" +
                    "key=${java.net.URLEncoder.encode(keywords, "UTF-8")}" +
                    "&format=json&inCharset=utf-8&outCharset=utf-8"

                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", UA)
                    .header("Referer", "https://y.qq.com/")
                    .build()

                val response = client.newCall(request).execute()
                val body = response.body?.string() ?: "{}"
                val result: Map<*, *>? = gson.fromJson(body, object : TypeToken<Map<*, *>>() {}.type)

                val data = result?.get("data") as? Map<*, *>
                val song = data?.get("song") as? Map<*, *>
                val itemList = song?.get("itemlist") as? List<Map<*, *>> ?: emptyList()

                val songs = itemList.take(limit).map { item ->
                    mapOf(
                        "provider" to "qq",
                        "source" to "qq",
                        "type" to "qq",
                        "id" to (item["mid"]?.toString() ?: ""),
                        "qqId" to (item["id"]?.toString() ?: ""),
                        "name" to (item["name"]?.toString() ?: ""),
                        "artist" to (item["singer"]?.toString() ?: ""),
                        "artists" to listOf(mapOf("name" to (item["singer"]?.toString() ?: ""))),
                        "album" to (item["albumname"]?.toString() ?: ""),
                        "cover" to "https://y.qq.com/music/photo_new/T002R300x300M000${item["mid"]}.jpg",
                        "duration" to 0L
                    )
                }

                mapOf("provider" to "qq", "songs" to songs)
            } catch (e: Exception) {
                Log.e(TAG, "QQ search failed: ${e.message}")
                mapOf("provider" to "qq", "songs" to emptyList<Any>(), "error" to (e.message ?: ""))
            }
        }
    }

    // ==================== 歌曲 URL ====================

    suspend fun getSongUrl(mid: String, quality: String = "hires"): Map<String, Any?> {
        Log.d(TAG, "getSongUrl: mid=$mid, quality=$quality")

        return withContext(Dispatchers.IO) {
            try {
                val cookie = CookieManager.getQQCookie()
                val guid = generateGUID()

                val reqData = mapOf(
                    "req_0" to mapOf(
                        "module" to "CDN",
                        "method" to "CgiGetVkey",
                        "param" to mapOf(
                            "guid" to guid,
                            "songmid" to listOf(mid),
                            "songtype" to listOf(0),
                            "uin" to CookieManager.getQQCookie(),
                            "loginflag" to 1,
                            "platform" to "20"
                        )
                    )
                )

                val jsonBody = gson.toJson(reqData)
                val requestBuilder = Request.Builder()
                    .url("https://u.y.qq.com/cgi-bin/musicu.fcg")
                    .post(RequestBody.create(JSON_MEDIA_TYPE, jsonBody))
                    .header("User-Agent", UA)
                    .header("Referer", "https://y.qq.com/")

                if (cookie.isNotEmpty()) {
                    requestBuilder.header("Cookie", cookie)
                }

                val response = client.newCall(requestBuilder.build()).execute()
                val body = response.body?.string() ?: "{}"
                val result: Map<*, *>? = gson.fromJson(body, object : TypeToken<Map<*, *>>() {}.type)

                val req0 = result?.get("req_0") as? Map<*, *>
                val data = req0?.get("data") as? Map<*, *>
                val midUrls = data?.get("midurlinfo") as? List<Map<*, *>>
                val sip = data?.get("sip") as? List<*>

                val urlInfo = midUrls?.firstOrNull()
                val purl = urlInfo?.get("purl")?.toString()

                if (purl.isNullOrEmpty() || sip.isNullOrEmpty()) {
                    return@withContext mapOf(
                        "provider" to "qq",
                        "url" to "",
                        "playable" to false,
                        "error" to "QQ_URL_UNAVAILABLE"
                    )
                }

                val sipList = sip.filterIsInstance<String>()
                val prefix = if (sipList.size > 1) sipList[1] else sipList.firstOrNull() ?: ""
                val fullUrl = "$prefix$purl"

                mapOf(
                    "provider" to "qq",
                    "url" to fullUrl,
                    "trial" to false,
                    "playable" to true,
                    "level" to quality,
                    "quality" to when (quality) {
                        "hires" -> "Hi-Res FLAC"
                        "lossless" -> "无损 FLAC"
                        "exhigh" -> "320k MP3"
                        else -> "标准"
                    },
                    "requestedQuality" to quality
                )
            } catch (e: Exception) {
                Log.e(TAG, "QQ song URL failed: ${e.message}")
                mapOf("provider" to "qq", "url" to "", "playable" to false, "error" to (e.message ?: ""))
            }
        }
    }

    // ==================== 歌词 ====================

    suspend fun getLyric(mid: String): Map<String, Any?> {
        Log.d(TAG, "getLyric: mid=$mid")

        return withContext(Dispatchers.IO) {
            try {
                val url = "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?" +
                    "songmid=${mid}&format=json&nobase64=1&g_tk=5381"

                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", UA)
                    .header("Referer", "https://y.qq.com/")
                    .build()

                val response = client.newCall(request).execute()
                val body = response.body?.string() ?: "{}"

                val jsonStr = body
                    .removePrefix("MusicJsonCallback(")
                    .removeSuffix(")")

                val result: Map<*, *>? = try {
                    gson.fromJson(jsonStr, object : TypeToken<Map<*, *>>() {}.type)
                } catch (e: Exception) {
                    gson.fromJson(body, object : TypeToken<Map<*, *>>() {}.type)
                }

                mapOf(
                    "provider" to "qq",
                    "id" to "",
                    "mid" to mid,
                    "lyric" to (result?.get("lyric")?.toString()?.replace("\\n", "\n") ?: ""),
                    "tlyric" to (result?.get("trans")?.toString()?.replace("\\n", "\n") ?: ""),
                    "source" to "qq-fcg"
                )
            } catch (e: Exception) {
                mapOf(
                    "provider" to "qq",
                    "id" to "",
                    "mid" to mid,
                    "lyric" to "",
                    "tlyric" to ""
                )
            }
        }
    }

    // ==================== 登录 ====================

    fun getLoginStatus(): Map<String, Any?> {
        val loggedIn = CookieManager.hasQQLogin()
        val hasPlaybackKey = CookieManager.hasQQPlaybackKey()

        return mapOf(
            "provider" to "qq",
            "loggedIn" to loggedIn,
            "hasCookie" to loggedIn,
            "playbackKeyReady" to hasPlaybackKey
        )
    }

    fun loginWithCookie(cookie: String): Map<String, Any?> {
        if (cookie.isBlank()) {
            return mapOf("provider" to "qq", "loggedIn" to false, "error" to "COOKIE_EMPTY")
        }

        CookieManager.saveQQCookie(cookie)
        return mapOf(
            "provider" to "qq",
            "loggedIn" to true,
            "saved" to true,
            "hasPlaybackKey" to CookieManager.hasQQPlaybackKey()
        )
    }

    fun logout(): Map<String, Any?> {
        CookieManager.clearQQCookie()
        return mapOf("provider" to "qq", "ok" to true, "loggedIn" to false)
    }

    // ==================== 用户歌单 ====================

    suspend fun getUserPlaylists(): Map<String, Any?> {
        if (!CookieManager.hasQQLogin()) {
            return mapOf("loggedIn" to false, "playlists" to emptyList<Any>())
        }
        return mapOf("loggedIn" to true, "provider" to "qq", "playlists" to emptyList<Any>())
    }

    suspend fun getPlaylistTracks(id: String): Map<String, Any?> {
        return mapOf(
            "loggedIn" to true,
            "provider" to "qq",
            "playlist" to mapOf("id" to id),
            "tracks" to emptyList<Any>()
        )
    }

    suspend fun getSongComments(id: String, limit: Int = 20): Map<String, Any?> {
        return mapOf(
            "provider" to "qq",
            "id" to id,
            "total" to 0,
            "comments" to emptyList<Any>()
        )
    }

    suspend fun getArtistDetail(mid: String, limit: Int = 36): Map<String, Any?> {
        return mapOf(
            "provider" to "qq",
            "artist" to mapOf("mid" to mid),
            "songs" to emptyList<Any>()
        )
    }

    // ==================== 工具 ====================

    private fun generateGUID(): String {
        val chars = "0123456789abcdef"
        return (1..32).map { chars.random() }.joinToString("")
    }
}
