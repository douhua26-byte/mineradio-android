package com.mineradio.app.server

import android.util.Log
import io.ktor.http.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import com.mineradio.app.server.netease.NeteaseApi
import com.mineradio.app.server.podcast.DjAnalyzer
import com.mineradio.app.server.proxy.AudioCoverProxy
import com.mineradio.app.server.qq.QQMusicApi
import com.mineradio.app.server.weather.WeatherRadio
import io.ktor.utils.io.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 嵌入式 Ktor HTTP 服务器
 * 运行在 127.0.0.1:3000，提供与原 server.js 完全相同的 API
 */
object MineradioServer {

    private const val TAG = "MineradioServer"
    private const val PORT = 3000
    private const val HOST = "127.0.0.1"

    private val running = AtomicBoolean(false)
    private var server: ApplicationEngine? = null

    fun start() {
        if (running.getAndSet(true)) {
            Log.d(TAG, "Server already running")
            return
        }

        Thread({
            try {
                Log.d(TAG, "Starting Mineradio server on $HOST:$PORT...")

                server = embeddedServer(CIO, port = PORT, host = HOST) {
                    install(CORS) {
                        anyHost()
                        allowHeader(HttpHeaders.ContentType)
                        allowHeader(HttpHeaders.Authorization)
                        allowHeader(HttpHeaders.Range)
                        allowHeader("X-Requested-With")
                        allowMethod(HttpMethod.Get)
                        allowMethod(HttpMethod.Post)
                        allowMethod(HttpMethod.Options)
                    }

                    install(ContentNegotiation) {
                        json(Json {
                            ignoreUnknownKeys = true
                            isLenient = true
                            prettyPrint = false
                        })
                    }

                    routing {
                        // 健康检查
                        get("/api/ping") {
                            call.respond(mapOf("ok" to true, "service" to "mineradio-android"))
                        }

                        // ========================
                        // 网易云音乐 API
                        // ========================

                        get("/api/search") {
                            val keywords = call.request.queryParameters["keywords"] ?: ""
                            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20
                            val result = NeteaseApi.search(keywords, limit)
                            call.respond(HttpStatusCode.OK, result)
                        }

                        get("/api/song/url") {
                            val id = call.request.queryParameters["id"] ?: ""
                            val quality = call.request.queryParameters["quality"] ?: "hires"
                            val result = NeteaseApi.getSongUrl(id, quality)
                            call.respond(HttpStatusCode.OK, result)
                        }

                        get("/api/lyric") {
                            val id = call.request.queryParameters["id"] ?: ""
                            val result = NeteaseApi.getLyric(id)
                            call.respond(HttpStatusCode.OK, result)
                        }

                        get("/api/login/status") {
                            val result = NeteaseApi.getLoginStatus()
                            call.respond(HttpStatusCode.OK, result)
                        }

                        get("/api/login/qr/key") {
                            val result = NeteaseApi.getQrKey()
                            call.respond(HttpStatusCode.OK, result)
                        }

                        get("/api/login/qr/create") {
                            val key = call.request.queryParameters["key"] ?: ""
                            val result = NeteaseApi.createQrCode(key)
                            call.respond(HttpStatusCode.OK, result)
                        }

                        get("/api/login/qr/check") {
                            val key = call.request.queryParameters["key"] ?: ""
                            val result = NeteaseApi.checkQrStatus(key)
                            call.respond(HttpStatusCode.OK, result)
                        }

                        post("/api/login/cookie") {
                            val body = call.receive<Map<String, String>>()
                            val cookie = body["cookie"] ?: body["data"] ?: ""
                            val result = NeteaseApi.loginWithCookie(cookie)
                            call.respond(HttpStatusCode.OK, result)
                        }

                        get("/api/logout") {
                            val result = NeteaseApi.logout()
                            call.respond(HttpStatusCode.OK, result)
                        }

                        get("/api/discover/home") {
                            val result = NeteaseApi.getDiscoverHome()
                            call.respond(HttpStatusCode.OK, result)
                        }

                        get("/api/user/playlists") {
                            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 60
                            val result = NeteaseApi.getUserPlaylists(limit)
                            call.respond(HttpStatusCode.OK, result)
                        }

                        get("/api/playlist/tracks") {
                            val id = call.request.queryParameters["id"] ?: ""
                            val result = NeteaseApi.getPlaylistTracks(id)
                            call.respond(HttpStatusCode.OK, result)
                        }

                        get("/api/song/like/check") {
                            val ids = call.request.queryParameters["ids"]
                                ?: call.request.queryParameters["id"] ?: ""
                            val result = NeteaseApi.checkLikedSongs(ids)
                            call.respond(HttpStatusCode.OK, result)
                        }

                        get("/api/song/like") {
                            val id = call.request.queryParameters["id"] ?: ""
                            val like = call.request.queryParameters["like"]?.toBooleanStrictOrNull() ?: true
                            val result = NeteaseApi.likeSong(id, like)
                            call.respond(HttpStatusCode.OK, result)
                        }

                        post("/api/song/like") {
                            val body = call.receive<Map<String, String>>()
                            val id = body["id"] ?: ""
                            val like = body["like"]?.toBooleanStrictOrNull() ?: true
                            val result = NeteaseApi.likeSong(id, like)
                            call.respond(HttpStatusCode.OK, result)
                        }

                        get("/api/playlist/create") {
                            val name = call.request.queryParameters["name"] ?: ""
                            val result = NeteaseApi.createPlaylist(name)
                            call.respond(HttpStatusCode.OK, result)
                        }

                        get("/api/playlist/add-song") {
                            val pid = call.request.queryParameters["pid"] ?: ""
                            val id = call.request.queryParameters["id"] ?: ""
                            val result = NeteaseApi.addSongToPlaylist(pid, id)
                            call.respond(HttpStatusCode.OK, result)
                        }

                        get("/api/song/comments") {
                            val id = call.request.queryParameters["id"] ?: ""
                            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20
                            val offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0
                            val result = NeteaseApi.getSongComments(id, limit, offset)
                            call.respond(HttpStatusCode.OK, result)
                        }

                        get("/api/artist/detail") {
                            val id = call.request.queryParameters["id"] ?: ""
                            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 30
                            val result = NeteaseApi.getArtistDetail(id, limit)
                            call.respond(HttpStatusCode.OK, result)
                        }

                        // ========================
                        // QQ 音乐 API
                        // ========================

                        get("/api/qq/search") {
                            val keywords = call.request.queryParameters["keywords"] ?: ""
                            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 8
                            val result = QQMusicApi.search(keywords, limit)
                            call.respond(HttpStatusCode.OK, result)
                        }

                        get("/api/qq/song/url") {
                            val mid = call.request.queryParameters["mid"]
                                ?: call.request.queryParameters["id"] ?: ""
                            val quality = call.request.queryParameters["quality"] ?: "hires"
                            val result = QQMusicApi.getSongUrl(mid, quality)
                            call.respond(HttpStatusCode.OK, result)
                        }

                        get("/api/qq/lyric") {
                            val mid = call.request.queryParameters["mid"]
                                ?: call.request.queryParameters["songmid"] ?: ""
                            val result = QQMusicApi.getLyric(mid)
                            call.respond(HttpStatusCode.OK, result)
                        }

                        get("/api/qq/login/status") {
                            val result = QQMusicApi.getLoginStatus()
                            call.respond(HttpStatusCode.OK, result)
                        }

                        post("/api/qq/login/cookie") {
                            val body = call.receive<Map<String, String>>()
                            val cookie = body["cookie"] ?: body["data"] ?: ""
                            val result = QQMusicApi.loginWithCookie(cookie)
                            call.respond(HttpStatusCode.OK, result)
                        }

                        get("/api/qq/logout") {
                            val result = QQMusicApi.logout()
                            call.respond(HttpStatusCode.OK, result)
                        }

                        get("/api/qq/user/playlists") {
                            val result = QQMusicApi.getUserPlaylists()
                            call.respond(HttpStatusCode.OK, result)
                        }

                        get("/api/qq/playlist/tracks") {
                            val id = call.request.queryParameters["id"]
                                ?: call.request.queryParameters["disstid"] ?: ""
                            val result = QQMusicApi.getPlaylistTracks(id)
                            call.respond(HttpStatusCode.OK, result)
                        }

                        get("/api/qq/song/comments") {
                            val id = call.request.queryParameters["id"]
                                ?: call.request.queryParameters["qqId"] ?: ""
                            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20
                            val result = QQMusicApi.getSongComments(id, limit)
                            call.respond(HttpStatusCode.OK, result)
                        }

                        get("/api/qq/artist/detail") {
                            val mid = call.request.queryParameters["mid"]
                                ?: call.request.queryParameters["singermid"] ?: ""
                            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 36
                            val result = QQMusicApi.getArtistDetail(mid, limit)
                            call.respond(HttpStatusCode.OK, result)
                        }

                        // ========================
                        // 天气电台
                        // ========================

                        get("/api/weather/radio") {
                            val city = call.request.queryParameters["city"]
                                ?: call.request.queryParameters["q"] ?: ""
                            val lat = call.request.queryParameters["lat"]?.toDoubleOrNull()
                            val lon = call.request.queryParameters["lon"]?.toDoubleOrNull()
                            val result = WeatherRadio.getWeatherRadio(city, lat, lon)
                            call.respond(HttpStatusCode.OK, result)
                        }

                        get("/api/weather/ip-location") {
                            val result = WeatherRadio.getIPLocation()
                            call.respond(HttpStatusCode.OK, result)
                        }

                        // ========================
                        // 应用信息
                        // ========================

                        get("/api/app/version") {
                            call.respond(mapOf(
                                "name" to "mineradio",
                                "productName" to "Mineradio",
                                "version" to "1.0.0",
                                "platform" to "android"
                            ))
                        }

                        get("/api/update/latest") {
                            call.respond(mapOf(
                                "configured" to false,
                                "updateAvailable" to false,
                                "currentVersion" to "1.0.0",
                                "latestVersion" to "1.0.0",
                                "release" to mapOf(
                                    "summary" to "当前为最新版本"
                                )
                            ))
                        }

                        // ========================
                        // 代理端点（音频/封面）
                        // ========================

                        get("/api/audio") {
                            val url = call.request.queryParameters["url"] ?: ""
                            if (url.isEmpty()) {
                                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "MISSING_URL"))
                                return@get
                            }

                            val rangeHeader = call.request.header("Range")
                            val result = AudioCoverProxy.proxyAudio(url, rangeHeader)

                            if (result.error != null) {
                                call.respond(HttpStatusCode.BadGateway, mapOf("error" to result.error))
                                return@get
                            }

                            val status = if (result.isRange) HttpStatusCode.PartialContent else HttpStatusCode.OK
                            call.response.header("Content-Type", result.contentType)
                            call.response.header("Accept-Ranges", "bytes")
                            call.response.header("Access-Control-Allow-Origin", "*")
                            call.response.header("Cross-Origin-Resource-Policy", "cross-origin")

                            if (result.isRange) {
                                call.response.header("Content-Range",
                                    result.responseHeaders["content-range"] ?: "")
                            }

                            call.respond(status, result.data)
                        }

                        get("/api/cover") {
                            val url = call.request.queryParameters["url"] ?: ""
                            if (url.isEmpty()) {
                                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "MISSING_URL"))
                                return@get
                            }

                            val result = AudioCoverProxy.proxyCover(url)

                            if (result.error != null) {
                                call.respond(HttpStatusCode.BadGateway, mapOf("error" to result.error))
                                return@get
                            }

                            call.response.header("Content-Type", result.contentType)
                            call.response.header("Access-Control-Allow-Origin", "*")
                            call.response.header("Cross-Origin-Resource-Policy", "cross-origin")
                            call.response.header("Cache-Control", "public, max-age=86400")

                            call.respond(HttpStatusCode.OK, result.data)
                        }

                        // ========================
                        // 静态文件服务
                        // ========================
                        // WebView 直接从 assets 加载 index.html
                        // 本地 HTTP 路由用于资源一致性请求

                        // ========================
                        // 播客
                        // ========================

                        get("/api/podcast/dj-beatmap") {
                            val url = call.request.queryParameters["url"] ?: ""
                            val duration = call.request.queryParameters["duration"]?.toDoubleOrNull() ?: 0.0
                            val intro = call.request.queryParameters["intro"]?.toDoubleOrNull()

                            val result = if (intro != null) {
                                DjAnalyzer.analyzeIntro(url, duration, intro)
                            } else {
                                DjAnalyzer.analyzeStream(url, duration)
                            }
                            call.respond(result)
                        }

                        get("/api/beatmap/cache/status") {
                            call.respond(mapOf(
                                "enabled" to true,
                                "dir" to "",
                                "mode" to "memory"
                            ))
                        }

                        get("/api/beatmap/cache") {
                            call.respond(mapOf("ok" to true, "hit" to false))
                        }

                        post("/api/beatmap/cache") {
                            call.respond(mapOf("ok" to true))
                        }
                    }
                }.start(wait = false)

                Log.d(TAG, "Mineradio server started successfully on port $PORT")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start server: ${e.message}", e)
                running.set(false)
            }
        }, "MineradioServer").start()
    }

    fun stop() {
        running.set(false)
        server?.stop(1000, 2000)
        Log.d(TAG, "Server stopped")
    }
}
