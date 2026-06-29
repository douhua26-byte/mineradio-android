package com.mineradio.app.stats

/**
 * 单次听歌记录（Room Entity 占位 - 当前使用内存存储）
 */
data class ListenRecord(
    val id: Long = System.currentTimeMillis(),
    val songId: String,
    val songName: String,
    val artist: String,
    val provider: String,          // "netease" 或 "qq"
    val durationListenMs: Long,    // 实际收听时长（毫秒）
    val startTime: Long,           // 开始收听时间戳
    val endTime: Long              // 结束收听时间戳
)

/**
 * 每日统计
 */
data class DailyStats(
    val date: String,              // "2026-06-29"
    val totalListenMs: Long,       // 总收听时长
    val songCount: Int,            // 收听的歌曲数
    val morningMs: Long,           // 上午 6-12点
    val afternoonMs: Long,         // 下午 12-18点
    val eveningMs: Long,           // 晚上 18-24点
    val nightMs: Long              // 深夜 0-6点
)

/**
 * 周统计
 */
data class WeeklyStats(
    val weekStart: String,
    val weekEnd: String,
    val totalListenMs: Long,
    val songCount: Int,
    val dailyBreakdown: List<DailyStats>,
    val topArtists: List<ArtistCount>,
    val streakDays: Int            // 连续听歌天数
)

data class ArtistCount(
    val artist: String,
    val count: Int,
    val totalMs: Long
)

/**
 * 前端统计面板显示的数据
 */
data class StatsDisplay(
    val todayMs: Long,
    val todayLabel: String,
    val weekMs: Long,
    val weekLabel: String,
    val monthMs: Long,
    val monthLabel: String,
    val streakDays: Int,
    val recentDays: List<DayBar>,         // 最近7天柱状图数据
    val periodBreakdown: PeriodBreakdown, // 时段分布
    val topArtists: List<ArtistCount>
)

data class DayBar(
    val day: String,       // "一","二","三"...
    val date: String,
    val minutes: Int
)

data class PeriodBreakdown(
    val morning: Int,   // 百分比
    val afternoon: Int,
    val evening: Int,
    val night: Int
)
