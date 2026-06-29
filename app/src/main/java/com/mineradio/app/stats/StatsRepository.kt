package com.mineradio.app.stats

import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.mineradio.app.util.PreferencesHelper

/**
 * 听歌统计数据仓库
 * 先用 SharedPreferences 存储，后续可迁移到 Room
 */
object StatsRepository {
    private const val TAG = "StatsRepository"
    private const val KEY_RECORDS = "stats_records"
    private const val MAX_RECORDS = 5000

    private val gson = Gson()

    /**
     * 记录一次听歌
     */
    fun recordListening(
        songId: String,
        songName: String,
        artist: String,
        provider: String,
        durationMs: Long
    ) {
        if (durationMs < 30_000) return // 少于30秒不计入

        val now = System.currentTimeMillis()
        val record = ListenRecord(
            id = now,
            songId = songId,
            songName = songName,
            artist = artist,
            provider = provider,
            durationListenMs = durationMs,
            startTime = now - durationMs,
            endTime = now
        )

        val records = loadRecords().toMutableList()
        records.add(record)

        // 限制记录数量（保留最新的）
        val trimmed = if (records.size > MAX_RECORDS) {
            records.sortedByDescending { it.id }.take(MAX_RECORDS)
        } else {
            records
        }

        saveRecords(trimmed)
        Log.d(TAG, "Recorded: $songName ${durationMs / 1000}s (total: ${trimmed.size})")
    }

    /**
     * 获取今日统计
     */
    fun getTodayStats(): StatsDisplay {
        val records = loadRecords()
        val todayStart = getDayStart(System.currentTimeMillis())
        val todayRecords = records.filter { it.startTime >= todayStart }

        val todayMs = todayRecords.sumOf { it.durationListenMs }
        val weekRecords = records.filter { it.startTime >= getWeekStart() }
        val weekMs = weekRecords.sumOf { it.durationListenMs }
        val monthMs = records.filter { it.startTime >= getMonthStart() }.sumOf { it.durationListenMs }

        // 时段分布
        val breakdown = calculatePeriodBreakdown(todayRecords)

        // 连续天数
        val streak = calculateStreak(records)

        // 最近7天
        val recentDays = calculateRecentDays(records)

        // 最常听艺术家
        val topArtists = calculateTopArtists(weekRecords, 5)

        return StatsDisplay(
            todayMs = todayMs,
            todayLabel = formatDuration(todayMs),
            weekMs = weekMs,
            weekLabel = formatDuration(weekMs),
            monthMs = monthMs,
            monthLabel = formatDuration(monthMs),
            streakDays = streak,
            recentDays = recentDays,
            periodBreakdown = breakdown,
            topArtists = topArtists
        )
    }

    /**
     * 获取最近 N 条听歌记录
     */
    fun getRecentHistory(limit: Int): List<ListenRecord> {
        return loadRecords()
            .sortedByDescending { it.id }
            .take(limit)
    }

    /**
     * 获取周统计
     */
    fun getWeeklyStats(): WeeklyStats {
        val records = loadRecords()
        val weekStart = getWeekStart()
        val weekRecords = records.filter { it.startTime >= weekStart }
        val dailyBreakdown = mutableListOf<DailyStats>()

        for (i in 0..6) {
            val dayStart = weekStart + i * 24 * 3600_000L
            val dayEnd = dayStart + 24 * 3600_000L
            val dayRecords = weekRecords.filter { it.startTime in dayStart until dayEnd }
            if (dayRecords.isNotEmpty()) {
                dailyBreakdown.add(DailyStats(
                    date = formatDate(dayStart),
                    totalListenMs = dayRecords.sumOf { it.durationListenMs },
                    songCount = dayRecords.size,
                    morningMs = dayRecords.filter { getHourOfDay(it.startTime) in 6..11 }.sumOf { it.durationListenMs },
                    afternoonMs = dayRecords.filter { getHourOfDay(it.startTime) in 12..17 }.sumOf { it.durationListenMs },
                    eveningMs = dayRecords.filter { getHourOfDay(it.startTime) in 18..23 }.sumOf { it.durationListenMs },
                    nightMs = dayRecords.filter { getHourOfDay(it.startTime) in 0..5 }.sumOf { it.durationListenMs }
                ))
            }
        }

        return WeeklyStats(
            weekStart = formatDate(weekStart),
            weekEnd = formatDate(weekStart + 6 * 24 * 3600_000L),
            totalListenMs = weekRecords.sumOf { it.durationListenMs },
            songCount = weekRecords.size,
            dailyBreakdown = dailyBreakdown,
            topArtists = calculateTopArtists(weekRecords, 10),
            streakDays = calculateStreak(records)
        )
    }

    // ==================== 内部方法 ====================

    private fun loadRecords(): List<ListenRecord> {
        val json = PreferencesHelper.getString(KEY_RECORDS)
        if (json.isBlank()) return emptyList()
        return try {
            val type = object : TypeToken<List<ListenRecord>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load records: ${e.message}")
            emptyList()
        }
    }

    private fun saveRecords(records: List<ListenRecord>) {
        val json = gson.toJson(records)
        PreferencesHelper.setString(KEY_RECORDS, json)
    }

    private fun getDayStart(timestamp: Long): Long {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = timestamp
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun getWeekStart(): Long {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        // 周一为周起始
        val dayOfWeek = cal.get(java.util.Calendar.DAY_OF_WEEK)
        val diff = if (dayOfWeek == java.util.Calendar.SUNDAY) 6 else dayOfWeek - 2
        cal.add(java.util.Calendar.DAY_OF_MONTH, -diff)
        return cal.timeInMillis
    }

    private fun getMonthStart(): Long {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.DAY_OF_MONTH, 1)
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun getHourOfDay(timestamp: Long): Int {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = timestamp
        return cal.get(java.util.Calendar.HOUR_OF_DAY)
    }

    private fun formatDate(timestamp: Long): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
        return sdf.format(java.util.Date(timestamp))
    }

    private fun formatDuration(ms: Long): String {
        val totalMinutes = ms / 60_000
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return if (hours > 0) "${hours}小时${minutes}分钟" else "${minutes}分钟"
    }

    private fun calculatePeriodBreakdown(records: List<ListenRecord>): PeriodBreakdown {
        if (records.isEmpty()) return PeriodBreakdown(25, 25, 25, 25)

        var morning = 0L
        var afternoon = 0L
        var evening = 0L
        var night = 0L

        records.forEach { record ->
            when (getHourOfDay(record.startTime)) {
                in 6..11 -> morning += record.durationListenMs
                in 12..17 -> afternoon += record.durationListenMs
                in 18..23 -> evening += record.durationListenMs
                else -> night += record.durationListenMs
            }
        }

        val total = (morning + afternoon + evening + night).coerceAtLeast(1)
        return PeriodBreakdown(
            morning = ((morning * 100) / total).toInt(),
            afternoon = ((afternoon * 100) / total).toInt(),
            evening = ((evening * 100) / total).toInt(),
            night = ((night * 100) / total).toInt()
        )
    }

    private fun calculateStreak(records: List<ListenRecord>): Int {
        if (records.isEmpty()) return 0

        var streak = 1
        val todayStart = getDayStart(System.currentTimeMillis())
        var checkDay = todayStart - 24 * 3600_000L

        while (true) {
            val dayRecords = records.filter {
                it.startTime >= checkDay && it.startTime < checkDay + 24 * 3600_000L
            }
            if (dayRecords.isNotEmpty()) {
                streak++
                checkDay -= 24 * 3600_000L
            } else {
                break
            }
        }

        // 检查今天是否已经听过
        val todayRecords = records.filter { it.startTime >= todayStart }
        if (todayRecords.isEmpty() && streak == 1) {
            // 今天和昨天都没听 → 连续天数归零
            val yesterdayStart = todayStart - 24 * 3600_000L
            val yesterdayRecords = records.filter {
                it.startTime >= yesterdayStart && it.startTime < todayStart
            }
            if (yesterdayRecords.isEmpty()) return 0
        }

        return streak
    }

    private fun calculateRecentDays(records: List<ListenRecord>): List<DayBar> {
        val dayNames = arrayOf("日", "一", "二", "三", "四", "五", "六")
        val result = mutableListOf<DayBar>()

        for (i in 6 downTo 0) {
            val dayStart = getDayStart(System.currentTimeMillis()) - i * 24 * 3600_000L
            val dayRecords = records.filter {
                it.startTime >= dayStart && it.startTime < dayStart + 24 * 3600_000L
            }
            val totalMs = dayRecords.sumOf { it.durationListenMs }
            val cal = java.util.Calendar.getInstance()
            cal.timeInMillis = dayStart
            val dayOfWeek = cal.get(java.util.Calendar.DAY_OF_WEEK) - 1

            result.add(DayBar(
                day = dayNames[dayOfWeek],
                date = formatDate(dayStart).takeLast(5), // MM-DD
                minutes = (totalMs / 60_000).toInt()
            ))
        }

        return result
    }

    private fun calculateTopArtists(records: List<ListenRecord>, limit: Int): List<ArtistCount> {
        val artistMap = mutableMapOf<String, Pair<Int, Long>>()
        records.forEach { record ->
            val (count, totalMs) = artistMap.getOrDefault(record.artist, Pair(0, 0L))
            artistMap[record.artist] = Pair(count + 1, totalMs + record.durationListenMs)
        }
        return artistMap.entries
            .sortedByDescending { it.value.first }
            .take(limit)
            .map { (artist, pair) ->
                ArtistCount(artist = artist, count = pair.first, totalMs = pair.second)
            }
    }
}
