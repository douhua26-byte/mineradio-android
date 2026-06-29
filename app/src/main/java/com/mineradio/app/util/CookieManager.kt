package com.mineradio.app.util

/**
 * 管理网易云音乐和 QQ 音乐的登录 Cookie
 * 使用 SharedPreferences 持久化
 */
object CookieManager {

    private const val KEY_NETEASE_COOKIE = "cookie_netease"
    private const val KEY_QQ_COOKIE = "cookie_qq"

    // ========== 网易云 Cookie ==========

    fun getNeteaseCookie(): String {
        return PreferencesHelper.getString(KEY_NETEASE_COOKIE)
    }

    fun saveNeteaseCookie(cookie: String) {
        val normalized = normalizeCookie(cookie)
        PreferencesHelper.setString(KEY_NETEASE_COOKIE, normalized)
    }

    fun hasNeteaseLogin(): Boolean {
        val cookie = getNeteaseCookie()
        // 检查是否包含 MUSIC_U（网易云登录核心 token）
        return cookie.contains("MUSIC_U=")
    }

    fun clearNeteaseCookie() {
        PreferencesHelper.remove(KEY_NETEASE_COOKIE)
    }

    // ========== QQ 音乐 Cookie ==========

    fun getQQCookie(): String {
        return PreferencesHelper.getString(KEY_QQ_COOKIE)
    }

    fun saveQQCookie(cookie: String) {
        val normalized = normalizeCookie(cookie)
        PreferencesHelper.setString(KEY_QQ_COOKIE, normalized)
    }

    fun hasQQLogin(): Boolean {
        val cookie = getQQCookie()
        return cookie.contains("uin=") || cookie.contains("qqmusic_uin=")
    }

    fun hasQQPlaybackKey(): Boolean {
        val cookie = getQQCookie()
        return cookie.contains("qm_keyst=") || cookie.contains("qqmusic_key=") ||
               cookie.contains("music_key=") || cookie.contains("wxskey=")
    }

    fun clearQQCookie() {
        PreferencesHelper.remove(KEY_QQ_COOKIE)
    }

    // ========== 工具方法 ==========

    /**
     * 标准化 Cookie 字符串：只保留键值对，去除过期时间、域名等元属性
     */
    private fun normalizeCookie(input: String): String {
        if (input.isBlank()) return ""

        val attributeNames = setOf(
            "path", "domain", "expires", "max-age", "samesite", "secure", "httponly"
        )

        val pairs = mutableMapOf<String, String>()

        // 按分号分割
        input.split(";").forEach { part ->
            val trimmed = part.trim()
            val idx = trimmed.indexOf('=')
            if (idx > 0) {
                val key = trimmed.substring(0, idx).trim().lowercase()
                val value = trimmed.substring(idx + 1).trim()
                if (key.isNotEmpty() && key !in attributeNames && value.isNotEmpty()) {
                    pairs[key] = value
                }
            }
        }

        // 也支持多行输入
        if (pairs.isEmpty()) {
            input.lines().forEach { line ->
                line.split(";").forEach { part ->
                    val trimmed = part.trim()
                    val idx = trimmed.indexOf('=')
                    if (idx > 0) {
                        val key = trimmed.substring(0, idx).trim().lowercase()
                        val value = trimmed.substring(idx + 1).trim()
                        if (key.isNotEmpty() && key !in attributeNames && value.isNotEmpty()) {
                            pairs[key] = value
                        }
                    }
                }
            }
        }

        return pairs.entries.joinToString("; ") { (k, v) -> "$k=$v" }
    }
}
