package com.mineradio.app.server.weather

import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 天气电台服务 - Open-Meteo + IP 定位
 */
object WeatherRadio {
    private const val TAG = "WeatherRadio"
    private const val OPEN_METEO_FORECAST_URL = "https://api.open-meteo.com/v1/forecast"
    private const val OPEN_METEO_GEOCODE_URL = "https://geocoding-api.open-meteo.com/v1/search"
    private const val IP_LOCATION_URL = "http://ip-api.com/json/"
    private const val UA = "Mineradio/1.0.0"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    // ==================== 天气电台 ====================

    suspend fun getWeatherRadio(
        city: String,
        lat: Double?,
        lon: Double?
    ): Map<String, Any?> {
        Log.d(TAG, "getWeatherRadio: city=$city, lat=$lat, lon=$lon")

        return withContext(Dispatchers.IO) {
            try {
                // 1. 获取位置
                val location = if (lat != null && lon != null) {
                    mapOf<String, Any>(
                        "name" to city.ifBlank { "未知" },
                        "latitude" to lat,
                        "longitude" to lon,
                        "timezone" to "Asia/Shanghai"
                    )
                } else if (city.isNotBlank()) {
                    geocode(city)
                } else {
                    getIPLocation()
                }

                // 2. 获取天气
                val locLat = location["latitude"] as? Double
                val locLon = location["longitude"] as? Double

                if (locLat == null || locLon == null) {
                    return@withContext mapOf("ok" to false, "error" to "NO_LOCATION")
                }

                val weather = getWeather(locLat, locLon, location["timezone"]?.toString() ?: "Asia/Shanghai")

                // 3. 天气心情
                val mood = determineMood(weather)
                val weatherLabel = getWeatherLabel(weather["weatherCode"] as? Number)

                mapOf(
                    "ok" to true,
                    "weather" to mapOf(
                        "provider" to "open-meteo",
                        "location" to location,
                        "label" to weatherLabel,
                        "weatherCode" to (weather["weatherCode"] ?: 0),
                        "temperature" to (weather["temperature"] ?: 20.0),
                        "apparentTemperature" to (weather["apparentTemp"] ?: 20.0),
                        "humidity" to (weather["humidity"] ?: 50),
                        "precipitation" to (weather["precipitation"] ?: 0.0),
                        "cloudCover" to (weather["cloudCover"] ?: 0),
                        "windSpeed" to (weather["windSpeed"] ?: 0.0),
                        "isDay" to (weather["isDay"] ?: 1),
                        "time" to java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm").format(java.util.Date()),
                        "mood" to mapOf(
                            "key" to mood.key,
                            "title" to mood.title,
                            "tagline" to mood.tagline,
                            "keywords" to mood.keywords
                        )
                    ),
                    "radio" to mapOf(
                        "title" to mood.title,
                        "subtitle" to mood.tagline,
                        "seedQueries" to mood.keywords,
                        "songs" to emptyList<Any>(),
                        "updatedAt" to System.currentTimeMillis()
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "Weather radio failed: ${e.message}")
                mapOf("ok" to false, "error" to (e.message ?: "UNKNOWN"))
            }
        }
    }

    // ==================== IP 定位 ====================

    suspend fun getIPLocation(): Map<String, Any?> {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(IP_LOCATION_URL)
                    .header("User-Agent", UA)
                    .build()

                val response = client.newCall(request).execute()
                val body = response.body?.string() ?: "{}"
                @Suppress("UNCHECKED_CAST")
                val result = gson.fromJson(body, Map::class.java) as? Map<String, Any?>

                mapOf(
                    "provider" to "ip-api",
                    "city" to (result?.get("city")?.toString() ?: "上海"),
                    "region" to (result?.get("regionName")?.toString() ?: ""),
                    "country" to (result?.get("country")?.toString() ?: "China"),
                    "latitude" to (result?.get("lat") as? Number)?.toDouble(),
                    "longitude" to (result?.get("lon") as? Number)?.toDouble(),
                    "timezone" to (result?.get("timezone")?.toString() ?: "Asia/Shanghai"),
                    "ip" to (result?.get("query")?.toString() ?: "")
                )
            } catch (e: Exception) {
                mapOf(
                    "provider" to "ip-api",
                    "city" to "上海",
                    "country" to "China",
                    "latitude" to 31.2304,
                    "longitude" to 121.4737,
                    "timezone" to "Asia/Shanghai"
                )
            }
        }
    }

    // ==================== 内部方法 ====================

    private suspend fun geocode(city: String): Map<String, Any?> {
        return withContext(Dispatchers.IO) {
            try {
                val url = "$OPEN_METEO_GEOCODE_URL?name=${java.net.URLEncoder.encode(city, "UTF-8")}&count=1&language=zh"
                val request = Request.Builder().url(url).header("User-Agent", UA).build()
                val response = client.newCall(request).execute()
                val body = response.body?.string() ?: "{}"
                @Suppress("UNCHECKED_CAST")
                val result = gson.fromJson(body, Map::class.java) as? Map<String, Any?>

                val results = result?.get("results") as? List<Map<*, *>>
                val first = results?.firstOrNull()

                mapOf(
                    "name" to (first?.get("name")?.toString() ?: city),
                    "country" to (first?.get("country")?.toString() ?: "China"),
                    "latitude" to (first?.get("latitude") as? Number)?.toDouble(),
                    "longitude" to (first?.get("longitude") as? Number)?.toDouble(),
                    "timezone" to (first?.get("timezone")?.toString() ?: "Asia/Shanghai")
                )
            } catch (e: Exception) {
                mapOf(
                    "name" to city,
                    "country" to "China",
                    "latitude" to 31.2304,
                    "longitude" to 121.4737,
                    "timezone" to "Asia/Shanghai"
                )
            }
        }
    }

    private suspend fun getWeather(lat: Double, lon: Double, timezone: String): Map<String, Any?> {
        return withContext(Dispatchers.IO) {
            try {
                val url = "$OPEN_METEO_FORECAST_URL?" +
                    "latitude=$lat&longitude=$lon" +
                    "&current=temperature_2m,relative_humidity_2m,apparent_temperature," +
                    "precipitation,weather_code,cloud_cover,wind_speed_10m,wind_gusts_10m,is_day" +
                    "&timezone=${java.net.URLEncoder.encode(timezone, "UTF-8")}" +
                    "&forecast_days=1"

                val request = Request.Builder().url(url).header("User-Agent", UA).build()
                val response = client.newCall(request).execute()
                val body = response.body?.string() ?: "{}"
                @Suppress("UNCHECKED_CAST")
                val result = gson.fromJson(body, Map::class.java) as? Map<String, Any?>

                val current = result?.get("current") as? Map<*, *>

                mapOf(
                    "weatherCode" to (current?.get("weather_code") as? Number ?: 0),
                    "temperature" to (current?.get("temperature_2m") as? Number ?: 20.0),
                    "apparentTemp" to (current?.get("apparent_temperature") as? Number ?: 20.0),
                    "humidity" to (current?.get("relative_humidity_2m") as? Number ?: 50),
                    "precipitation" to (current?.get("precipitation") as? Number ?: 0.0),
                    "cloudCover" to (current?.get("cloud_cover") as? Number ?: 0),
                    "windSpeed" to (current?.get("wind_speed_10m") as? Number ?: 0.0),
                    "windGusts" to (current?.get("wind_gusts_10m") as? Number ?: 0.0),
                    "isDay" to (current?.get("is_day") as? Number ?: 1)
                )
            } catch (e: Exception) {
                emptyMap()
            }
        }
    }

    private fun determineMood(weather: Map<String, Any?>): WeatherMood {
        val code = (weather["weatherCode"] as? Number)?.toInt() ?: 0
        val temp = (weather["temperature"] as? Number)?.toDouble() ?: 20.0
        val isDay = (weather["isDay"] as? Number)?.toInt() ?: 1
        val precipitation = (weather["precipitation"] as? Number)?.toDouble() ?: 0.0
        val humidity = (weather["humidity"] as? Number)?.toInt() ?: 50

        val isNight = isDay == 0
        val isRain = precipitation > 0 || code in listOf(51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82)
        val isStorm = code in listOf(95, 96, 99)
        val isSnow = code in listOf(71, 73, 75, 77, 85, 86)
        val isCloudy = code in listOf(2, 3, 45, 48)
        val isHot = temp >= 31 || humidity >= 78
        val isCold = temp <= 3

        return when {
            isStorm -> WeatherMood.Storm
            isRain -> WeatherMood.Rain
            isSnow || isCold -> WeatherMood.Snow
            isHot -> WeatherMood.Humid
            isCloudy -> WeatherMood.Cloudy
            isNight -> WeatherMood.Night
            else -> WeatherMood.Clear
        }
    }

    private fun getWeatherLabel(code: Number?): String {
        return when (code?.toInt()) {
            0 -> "晴"
            1, 2 -> "少云"
            3 -> "阴"
            45, 48 -> "雾"
            51, 53, 55 -> "毛毛雨"
            56, 57 -> "冻雨"
            61, 63, 65 -> "雨"
            66, 67 -> "冻雨"
            71, 73, 75, 77 -> "雪"
            80, 81, 82 -> "阵雨"
            85, 86 -> "阵雪"
            95, 96, 99 -> "雷雨"
            else -> "天气"
        }
    }

    // ==================== 天气心情 ====================

    sealed class WeatherMood(
        val key: String,
        val title: String,
        val tagline: String,
        val keywords: List<String>
    ) {
        object Clear : WeatherMood("clear", "晴朗电台", "让节奏亮一点，像窗边的光",
            listOf("轻快 华语", "city pop", "indie pop", "chill pop", "阳光 歌单"))
        object Storm : WeatherMood("storm", "雷雨电台", "低频更厚，适合把世界关小一点",
            listOf("暗色 R&B", "trip hop", "夜晚 电子", "氛围 摇滚", "雨夜 歌单"))
        object Rain : WeatherMood("rain", "雨天电台", "留一点潮湿的空间给旋律",
            listOf("雨天 R&B", "lofi rainy", "华语 慢歌", "dream pop", "雨夜 歌单"))
        object Snow : WeatherMood("snow", "冷空气电台", "干净、慢速、带一点冬天的颗粒感",
            listOf("冬天 民谣", "ambient piano", "日系 冬天", "indie folk", "安静 歌单"))
        object Humid : WeatherMood("humid", "闷热电台", "降低密度，留出一点呼吸",
            listOf("夏日 chill", "bossa nova", "city pop 夏天", "轻电子", "海边 歌单"))
        object Cloudy : WeatherMood("cloudy", "阴天电台", "灰白的天，适合沉一点的调子",
            listOf("氛围 华语", "post rock", "民谣 推荐", "夜晚 钢琴", "slowcore"))
        object Night : WeatherMood("night", "深夜电台", "放下白天，旋律就是黑夜里的灯",
            listOf("夜晚 R&B", "jazz hiphop", "ambient", "lofi sleep", "深夜 歌单"))
    }
}
