package com.mineradio.app.server.netease

import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.mineradio.app.util.CookieManager
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * 网易云音乐 API 封装
 * 对应 NeteaseCloudMusicApi (Node.js) 的 Kotlin 移植
 */
object NeteaseApi {
    private const val TAG = "NeteaseApi"
    private const val BASE_URL = "https://music.163.com"
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .build()

    private val gson = Gson()

    // ==================== 搜索 ====================

    suspend fun search(keywords: String, limit: Int = 20): Map<String, Any?> {
        Log.d(TAG, "search: $keywords, limit=$limit")

        val data = mapOf(
            "s" to keywords,
            "type" to 1,        // 1=单曲
            "limit" to limit,
            "offset" to 0,
            "total" to "true"
        )
        val crypto = NeteaseCrypto.weapiEncrypt(data)
        val response = post(
            "/weapi/cloudsearch/get/web?csrf_token=",
            mapOf("params" to crypto.params, "encSecKey" to crypto.encSecKey)
        )
        val result = response?.get("result") as? Map<*, *>
        val songs = result?.get("songs") as? List<Map<*, *>> ?: emptyList()

        val mapped = songs.map { mapSongRecord(it) }
        // 兜底补齐缺失封面
        val missing = mapped.filter { it["cover"].toString().isEmpty() }
        if (missing.isNotEmpty()) {
            try {
                backfillCovers(mapped, missing)
            } catch (e: Exception) {
                Log.w(TAG, "backfill covers failed: ${e.message}")
            }
        }

        return mapOf("songs" to mapped)
    }

    // ==================== 歌曲 URL ====================

    suspend fun getSongUrl(id: String, quality: String = "hires"): Map<String, Any?> {
        Log.d(TAG, "getSongUrl: id=$id, quality=$quality")

        val levelMap = mapOf(
            "jymaster" to "jymaster",
            "hires" to "hires",
            "lossless" to "lossless",
            "exhigh" to "exhigh",
            "standard" to "standard"
        )
        val level = levelMap[quality] ?: "hires"

        val data = mapOf(
            "ids" to "[$id]",
            "level" to level,
            "encodeType" to "aac"
        )
        val crypto = NeteaseCrypto.weapiEncrypt(data)

        val response = post(
            "/weapi/song/enhance/player/url/v1?csrf_token=",
            mapOf("params" to crypto.params, "encSecKey" to crypto.encSecKey)
        )

        val dataList = response?.get("data") as? List<Map<*, *>>
        val songData = dataList?.firstOrNull()

        if (songData == null) {
            return mapOf(
                "url" to null,
                "playable" to false,
                "reason" to "未找到歌曲 URL"
            )
        }

        val url = songData["url"] as? String ?: ""
        val freeTrial = songData["freeTrialInfo"]

        if (url.isEmpty()) {
            // 尝试更低音质
            if (level != "standard") {
                val fallbackLevels = when (level) {
                    "jymaster" -> listOf("hires", "lossless", "exhigh", "standard")
                    "hires" -> listOf("lossless", "exhigh", "standard")
                    "lossless" -> listOf("exhigh", "standard")
                    "exhigh" -> listOf("standard")
                    else -> emptyList()
                }
                for (fallback in fallbackLevels) {
                    val result = getSongUrl(id, fallback)
                    if (result["url"]?.toString()?.isNotEmpty() == true) {
                        return result
                    }
                }
            }

            return mapOf(
                "url" to null,
                "playable" to false,
                "trial" to (freeTrial != null),
                "reason" to if (freeTrial != null) "仅提供试听片段" else "无可用播放地址",
                "restriction" to mapOf(
                    "provider" to "netease",
                    "category" to if (freeTrial != null) "trial_only" else "url_unavailable",
                    "message" to if (freeTrial != null) "仅提供试听片段" else "无可用播放地址"
                )
            )
        }

        val br = songData["br"] as? Number ?: 0
        val type = songData["type"] as? String ?: ""

        return mapOf(
            "url" to url,
            "trial" to (freeTrial != null),
            "playable" to true,
            "level" to level,
            "quality" to when (level) {
                "jymaster" -> "超清母带"
                "hires" -> "高清臻音"
                "lossless" -> "无损"
                "exhigh" -> "极高"
                else -> "标准"
            },
            "br" to br.toLong(),
            "type" to type,
            "requestedQuality" to quality,
            "loggedIn" to CookieManager.hasNeteaseLogin()
        )
    }

    // ==================== 歌词 ====================

    suspend fun getLyric(id: String): Map<String, Any?> {
        Log.d(TAG, "getLyric: id=$id")

        val data = mapOf(
            "id" to id,
            "lv" to -1,
            "tv" to -1,
            "rv" to -1
        )
        val crypto = NeteaseCrypto.weapiEncrypt(data)

        // 先尝试 lyric_new
        val response = post(
            "/weapi/song/lyric?csrf_token=",
            mapOf("params" to crypto.params, "encSecKey" to crypto.encSecKey)
        )

        val lrc = response?.get("lrc") as? Map<*, *>
        val tlyric = response?.get("tlyric") as? Map<*, *>

        return mapOf(
            "lyric" to (lrc?.get("lyric") as? String ?: ""),
            "tlyric" to (tlyric?.get("lyric") as? String ?: ""),
            "yrc" to "",
            "source" to "lyric_new"
        )
    }

    // ==================== 登录相关 ====================

    fun getLoginStatus(): Map<String, Any?> {
        val loggedIn = CookieManager.hasNeteaseLogin()
        val cookie = CookieManager.getNeteaseCookie()

        if (!loggedIn) {
            return mapOf(
                "loggedIn" to false,
                "hasCookie" to false
            )
        }

        // 尝试获取用户信息
        return try {
            runBlocking {
                val data = mapOf<String, Any>("uid" to "0")
                val crypto = NeteaseCrypto.weapiEncrypt(data)
                val response = post(
                    "/weapi/login/status",
                    mapOf("params" to crypto.params, "encSecKey" to crypto.encSecKey)
                )

                val profile = response?.get("profile") as? Map<*, *>
                val account = response?.get("account") as? Map<*, *>

                mapOf(
                    "loggedIn" to true,
                    "userId" to (profile?.get("userId")?.toString() ?: ""),
                    "nickname" to (profile?.get("nickname")?.toString() ?: ""),
                    "avatar" to (profile?.get("avatarUrl")?.toString() ?: ""),
                    "vipType" to (profile?.get("vipType") as? Number)?.toInt(),
                    "vipLevel" to when (profile?.get("vipType") as? Number) {
                        11 -> "svip"
                        10 -> "vip"
                        else -> "free"
                    },
                    "isVip" to (((profile?.get("vipType") as? Number)?.toInt() ?: 0) >= 10),
                    "isSvip" to (((profile?.get("vipType") as? Number)?.toInt() ?: 0) == 11),
                    "hasCookie" to true
                )
            }
        } catch (e: Exception) {
            mapOf(
                "loggedIn" to true,
                "hasCookie" to true,
                "error" to (e.message ?: "unknown")
            )
        }
    }

    suspend fun getQrKey(): Map<String, Any?> {
        val data = mapOf<String, Any>("type" to 1)
        val crypto = NeteaseCrypto.weapiEncrypt(data)
        val response = post(
            "/weapi/login/qrcode/unikey?csrf_token=",
            mapOf("params" to crypto.params, "encSecKey" to crypto.encSecKey)
        )
        return mapOf("key" to (response?.get("unikey")?.toString() ?: ""))
    }

    suspend fun createQrCode(key: String): Map<String, Any?> {
        val data = mapOf(
            "key" to key,
            "qrimg" to true
        )
        val crypto = NeteaseCrypto.weapiEncrypt(data)
        val response = post(
            "/weapi/login/qrcode/create?csrf_token=",
            mapOf("params" to crypto.params, "encSecKey" to crypto.encSecKey)
        )
        return mapOf(
            "img" to (response?.get("qrimg")?.toString() ?: ""),
            "url" to (response?.get("qrurl")?.toString() ?: "")
        )
    }

    suspend fun checkQrStatus(key: String): Map<String, Any?> {
        val data = mapOf(
            "key" to key,
            "type" to 1
        )
        val crypto = NeteaseCrypto.weapiEncrypt(data)
        val response = post(
            "/weapi/login/qrcode/client/login?csrf_token=",
            mapOf("params" to crypto.params, "encSecKey" to crypto.encSecKey)
        )

        val code = (response?.get("code") as? Number)?.toInt() ?: -1
        val result = mutableMapOf<String, Any>("code" to code)

        when (code) {
            800 -> result["message"] = "二维码已过期"
            801 -> result["message"] = "等待扫码"
            802 -> {
                result["message"] = "已扫码，请在手机上确认"
                result["nickname"] = response?.get("nickname")?.toString() ?: ""
                result["avatar"] = response?.get("avatarUrl")?.toString() ?: ""
            }
            803 -> {
                result["message"] = "授权成功"
                result["loggedIn"] = true
                // 保存 cookie
                val cookie = response?.get("cookie")?.toString() ?: ""
                if (cookie.isNotEmpty()) {
                    CookieManager.saveNeteaseCookie(cookie)
                }
                result["hasCookie"] = true
            }
        }

        return result
    }

    fun loginWithCookie(cookie: String): Map<String, Any?> {
        if (cookie.isBlank()) {
            return mapOf("loggedIn" to false, "error" to "COOKIE_EMPTY")
        }

        if (!cookie.contains("MUSIC_U=")) {
            return mapOf(
                "loggedIn" to false,
                "error" to "MUSIC_U_MISSING",
                "message" to "Cookie 中缺少 MUSIC_U，请确认已完整复制登录后的 Cookie"
            )
        }

        CookieManager.saveNeteaseCookie(cookie)
        return getLoginStatus().toMutableMap().apply {
            put("saved", true)
        }
    }

    fun logout(): Map<String, Any?> {
        CookieManager.clearNeteaseCookie()
        return mapOf("ok" to true, "loggedIn" to false)
    }

    // ==================== 首页推荐 ====================

    suspend fun getDiscoverHome(): Map<String, Any?> {
        val loggedIn = CookieManager.hasNeteaseLogin()

        if (!loggedIn) {
            return mapOf(
                "loggedIn" to false,
                "user" to null,
                "dailySongs" to emptyList<Any>(),
                "playlists" to emptyList<Any>(),
                "podcasts" to emptyList<Any>(),
                "updatedAt" to System.currentTimeMillis()
            )
        }

        // 并行请求
        val personalized = async { getPersonalized() }
        val dailySongs = async { getRecommendSongs() }

        return mapOf(
            "loggedIn" to true,
            "user" to mapOf(
                "userId" to "",
                "nickname" to "",
                "avatar" to ""
            ),
            "dailySongs" to dailySongs.await(),
            "playlists" to personalized.await(),
            "podcasts" to emptyList<Any>(),
            "updatedAt" to System.currentTimeMillis()
        )
    }

    private suspend fun getPersonalized(limit: Int = 8): List<Map<String, Any?>> {
        val data = mapOf<String, Any>(
            "limit" to limit,
            "total" to "true",
            "n" to 1000
        )
        val crypto = NeteaseCrypto.weapiEncrypt(data)
        val response = post(
            "/weapi/personalized/playlist?csrf_token=",
            mapOf("params" to crypto.params, "encSecKey" to crypto.encSecKey)
        )
        val result = response?.get("result") as? List<Map<*, *>> ?: emptyList()
        return result.map { pl ->
            mapOf(
                "provider" to "netease",
                "source" to "netease",
                "type" to "playlist",
                "id" to (pl["id"]?.toString() ?: ""),
                "name" to (pl["name"]?.toString() ?: ""),
                "cover" to (pl["picUrl"]?.toString() ?: ""),
                "trackCount" to (pl["trackCount"] as? Number)?.toInt(),
                "playCount" to (pl["playCount"] as? Number)?.toInt(),
                "creator" to ((pl["creator"] as? Map<*, *>)?.get("nickname")?.toString() ?: ""),
                "tag" to "推荐歌单"
            )
        }
    }

    private suspend fun getRecommendSongs(limit: Int = 12): List<Map<String, Any?>> {
        val data = mapOf<String, Any>(
            "limit" to limit,
            "total" to "true"
        )
        val crypto = NeteaseCrypto.weapiEncrypt(data)
        val response = post(
            "/weapi/v1/discovery/recommend/songs?csrf_token=",
            mapOf("params" to crypto.params, "encSecKey" to crypto.encSecKey)
        )
        val recommend = response?.get("recommend") as? List<Map<*, *>> ?: emptyList()
        return recommend.map { mapSongRecord(it) }
    }

    // ==================== 用户歌单 ====================

    suspend fun getUserPlaylists(limit: Int = 60): Map<String, Any?> {
        if (!CookieManager.hasNeteaseLogin()) {
            return mapOf("loggedIn" to false, "playlists" to emptyList<Any>())
        }

        val data = mapOf<String, Any>(
            "limit" to limit,
            "offset" to 0,
            "total" to "true"
        )
        val crypto = NeteaseCrypto.weapiEncrypt(data)
        val response = post(
            "/weapi/user/playlist?csrf_token=",
            mapOf("params" to crypto.params, "encSecKey" to crypto.encSecKey)
        )

        val playlist = response?.get("playlist") as? List<Map<*, *>> ?: emptyList()
        val mapped = playlist.map { pl ->
            mapOf(
                "id" to (pl["id"]?.toString() ?: ""),
                "name" to (pl["name"]?.toString() ?: ""),
                "cover" to (pl["coverImgUrl"]?.toString() ?: ""),
                "trackCount" to (pl["trackCount"] as? Number)?.toInt(),
                "playCount" to (pl["playCount"] as? Number)?.toInt(),
                "creator" to ((pl["creator"] as? Map<*, *>)?.get("nickname")?.toString() ?: ""),
                "subscribed" to (pl["subscribed"] as? Boolean ?: false),
                "specialType" to (pl["specialType"] as? Number)?.toInt()
            )
        }

        return mapOf(
            "loggedIn" to true,
            "userId" to "",
            "playlists" to mapped
        )
    }

    // ==================== 歌单详情 ====================

    suspend fun getPlaylistTracks(id: String): Map<String, Any?> {
        val data = mapOf(
            "id" to id,
            "n" to 100000,
            "s" to 8
        )
        val crypto = NeteaseCrypto.weapiEncrypt(data)
        val response = post(
            "/weapi/v6/playlist/detail?csrf_token=",
            mapOf("params" to crypto.params, "encSecKey" to crypto.encSecKey)
        )

        val playlist = response?.get("playlist") as? Map<*, *>
        val tracks = playlist?.get("tracks") as? List<Map<*, *>> ?: emptyList()

        return mapOf(
            "playlist" to mapOf(
                "id" to (playlist?.get("id")?.toString() ?: id),
                "name" to (playlist?.get("name")?.toString() ?: ""),
                "cover" to (playlist?.get("coverImgUrl")?.toString() ?: ""),
                "trackCount" to (playlist?.get("trackCount") as? Number)?.toInt()
            ),
            "tracks" to tracks.map { mapSongRecord(it) }
        )
    }

    // ==================== 收藏 ====================

    suspend fun checkLikedSongs(ids: String): Map<String, Any?> {
        val idList = ids.split(",").map { it.trim() }.filter { it.isNotEmpty() }

        val data = mapOf<String, Any>("trackIds" to idList)
        val crypto = NeteaseCrypto.weapiEncrypt(data)
        val response = post(
            "/weapi/song/like/list?csrf_token=",
            mapOf("params" to crypto.params, "encSecKey" to crypto.encSecKey)
        )

        val checkMap = response?.get("checkPoint") as? Map<*, *>
        val idMap = response?.get("idToLikeMode") as? Map<*, *>

        val liked = mutableMapOf<String, Boolean>()
        idList.forEach { id ->
            liked[id] = (checkMap?.get(id) as? Boolean) ?: ((idMap?.get(id) as? Number)?.toInt() == 1)
        }

        return mapOf(
            "loggedIn" to CookieManager.hasNeteaseLogin(),
            "ids" to idList,
            "liked" to liked
        )
    }

    suspend fun likeSong(id: String, like: Boolean): Map<String, Any?> {
        val data = mapOf(
            "trackId" to id,
            "like" to like,
            "time" to "25"
        )
        val crypto = NeteaseCrypto.weapiEncrypt(data)
        val response = post(
            "/weapi/radio/like?csrf_token=",
            mapOf("params" to crypto.params, "encSecKey" to crypto.encSecKey)
        )

        return mapOf(
            "loggedIn" to CookieManager.hasNeteaseLogin(),
            "id" to id,
            "liked" to like,
            "code" to (response?.get("code") as? Number)?.toInt()
        )
    }

    // ==================== 歌单创建/添加 ====================

    suspend fun createPlaylist(name: String): Map<String, Any?> {
        val data = mapOf(
            "name" to name,
            "privacy" to "0"
        )
        val crypto = NeteaseCrypto.weapiEncrypt(data)
        val response = post(
            "/weapi/playlist/create?csrf_token=",
            mapOf("params" to crypto.params, "encSecKey" to crypto.encSecKey)
        )

        return mapOf(
            "loggedIn" to CookieManager.hasNeteaseLogin(),
            "playlist" to response,
            "body" to response
        )
    }

    suspend fun addSongToPlaylist(pid: String, ids: String): Map<String, Any?> {
        val idList = ids.split(",").map { it.trim() }
        val data = mapOf(
            "op" to "add",
            "pid" to pid,
            "trackIds" to "[${idList.joinToString(",")}]"
        )
        val crypto = NeteaseCrypto.weapiEncrypt(data)
        val response = post(
            "/weapi/playlist/manipulate/tracks?csrf_token=",
            mapOf("params" to crypto.params, "encSecKey" to crypto.encSecKey)
        )

        return mapOf(
            "loggedIn" to CookieManager.hasNeteaseLogin(),
            "pid" to pid,
            "id" to ids,
            "success" to ((response?.get("code") as? Number)?.toInt() == 200),
            "code" to (response?.get("code") as? Number)?.toInt()
        )
    }

    // ==================== 评论 ====================

    suspend fun getSongComments(id: String, limit: Int = 20, offset: Int = 0): Map<String, Any?> {
        val data = mapOf(
            "rid" to id,
            "limit" to limit,
            "offset" to offset,
            "beforeTime" to "0"
        )
        val crypto = NeteaseCrypto.weapiEncrypt(data)
        val response = post(
            "/weapi/v1/resource/comments/R_SO_4_$id?csrf_token=",
            mapOf("params" to crypto.params, "encSecKey" to crypto.encSecKey)
        )

        val comments = (response?.get("comments") as? List<Map<*, *>>)?.map { comment ->
            val user = comment["user"] as? Map<*, *>
            mapOf(
                "id" to (comment["commentId"]?.toString() ?: ""),
                "content" to (comment["content"]?.toString() ?: ""),
                "likedCount" to (comment["likedCount"] as? Number)?.toInt(),
                "time" to (comment["time"] as? Number)?.toLong(),
                "user" to mapOf(
                    "id" to (user?.get("userId")?.toString() ?: ""),
                    "nickname" to (user?.get("nickname")?.toString() ?: ""),
                    "avatar" to (user?.get("avatarUrl")?.toString() ?: "")
                )
            )
        } ?: emptyList()

        return mapOf(
            "id" to id,
            "total" to (response?.get("total") as? Number)?.toInt(),
            "comments" to comments,
            "hot" to (offset == 0)
        )
    }

    // ==================== 艺术家 ====================

    suspend fun getArtistDetail(id: String, limit: Int = 30): Map<String, Any?> {
        val data = mapOf("id" to id)
        val crypto = NeteaseCrypto.weapiEncrypt(data)
        val response = post(
            "/weapi/artist/head/info/get?csrf_token=",
            mapOf("params" to crypto.params, "encSecKey" to crypto.encSecKey)
        )

        val artist = response?.get("data") as? Map<*, *> ?: emptyMap<String, Any>()
        val artistData = artist["artist"] as? Map<*, *>

        return mapOf(
            "id" to id,
            "artist" to mapOf(
                "id" to (artistData?.get("id")?.toString() ?: id),
                "name" to (artistData?.get("name")?.toString() ?: ""),
                "avatar" to (artistData?.get("picUrl")?.toString() ?: ""),
                "brief" to (artistData?.get("briefDesc")?.toString() ?: ""),
                "musicSize" to (artistData?.get("musicSize") as? Number)?.toInt(),
                "albumSize" to (artistData?.get("albumSize") as? Number)?.toInt()
            ),
            "songs" to emptyList<Any>() // 热歌列表需要单独接口
        )
    }

    // ==================== 内部工具方法 ====================

    /**
     * 映射歌曲记录为统一格式
     */
    private fun mapSongRecord(s: Map<*, *>): Map<String, Any?> {
        val artists = (s["ar"] as? List<Map<*, *>>)
            ?: (s["artists"] as? List<Map<*, *>>)
            ?: emptyList()
        val album = s["al"] as? Map<*, *> ?: (s["album"] as? Map<*, *>)

        return mapOf(
            "provider" to "netease",
            "source" to "netease",
            "type" to "song",
            "id" to (s["id"]?.toString() ?: ""),
            "name" to (s["name"]?.toString() ?: ""),
            "artist" to artists.joinToString(" / ") { it["name"]?.toString() ?: "" },
            "artists" to artists.map { mapOf("id" to (it["id"]?.toString() ?: ""), "name" to (it["name"]?.toString() ?: "")) },
            "artistId" to (artists.firstOrNull()?.get("id")?.toString() ?: ""),
            "album" to (album?.get("name")?.toString() ?: ""),
            "cover" to (album?.get("picUrl")?.toString()
                ?: album?.get("coverUrl")?.toString() ?: ""),
            "duration" to ((s["dt"] as? Number)?.toLong()
                ?: (s["duration"] as? Number)?.toLong() ?: 0L),
            "fee" to (s["fee"] as? Number)?.toInt()
        )
    }

    /**
     * 补齐缺失的封面图
     */
    private suspend fun backfillCovers(songs: List<Map<String, Any?>>, missing: List<Map<String, Any?>>) {
        val ids = missing.map { it["id"]?.toString() ?: "" }.filter { it.isNotEmpty() }.joinToString(",")
        if (ids.isEmpty()) return

        val data = mapOf(
            "c" to "[$ids]",
            "ids" to "[$ids]"
        )
        val crypto = NeteaseCrypto.weapiEncrypt(data)
        val response = post(
            "/weapi/v3/song/detail",
            mapOf("params" to crypto.params, "encSecKey" to crypto.encSecKey)
        )
        val detailSongs = response?.get("songs") as? List<Map<*, *>> ?: return

        val coverMap = mutableMapOf<String, String>()
        detailSongs.forEach { s ->
            val id = (s["id"]?.toString() ?: "")
            val album = s["al"] as? Map<*, *>
            val cover = album?.get("picUrl")?.toString() ?: ""
            if (id.isNotEmpty() && cover.isNotEmpty()) {
                coverMap[id] = cover
            }
        }

        // 更新 mapped 中的 cover
        songs.filter { it["cover"].toString().isEmpty() }.forEach { song ->
            val id = song["id"]?.toString() ?: ""
            coverMap[id]?.let { song.toMutableMap()["cover"] = it }
        }
    }

    /**
     * 发送 POST 请求到网易云音乐 API
     */
    private suspend fun post(path: String, formData: Map<String, String>): Map<*, *>? {
        return withContext(Dispatchers.IO) {
            try {
                val cookie = CookieManager.getNeteaseCookie()
                val url = "$BASE_URL$path"

                val formBody = FormBody.Builder()
                formData.forEach { (key, value) -> formBody.add(key, value) }
                val body = formBody.build()

                val request = Request.Builder()
                    .url(url)
                    .post(body)
                    .header("User-Agent", UA)
                    .header("Referer", "https://music.163.com/")
                    .header("Origin", "https://music.163.com")
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .let {
                        if (cookie.isNotEmpty()) {
                            it.header("Cookie", cookie)
                        } else it
                    }
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: "{}"

                try {
                    gson.fromJson<Map<*, *>>(
                        responseBody,
                        object : TypeToken<Map<*, *>>() {}.type
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "JSON parse error for $path: ${e.message}")
                    mapOf("code" to response.code, "body" to responseBody)
                }
            } catch (e: IOException) {
                Log.e(TAG, "Request failed for $path: ${e.message}")
                null
            }
        }
    }

    // 协程辅助
    private fun <T> async(block: suspend () -> T): kotlinx.coroutines.Deferred<T> {
        return CoroutineScope(Dispatchers.IO).async { block() }
    }
}
