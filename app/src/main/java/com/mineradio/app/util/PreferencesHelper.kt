package com.mineradio.app.util

import android.content.Context
import android.content.SharedPreferences
import com.mineradio.app.MineradioApp

/**
 * 统一管理 SharedPreferences，替代前端 localStorage
 */
object PreferencesHelper {

    private val prefs: SharedPreferences by lazy {
        MineradioApp.instance.getSharedPreferences("mineradio_prefs", Context.MODE_PRIVATE)
    }

    fun getString(key: String, defaultValue: String = ""): String {
        return prefs.getString(key, defaultValue) ?: defaultValue
    }

    fun setString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    fun getBoolean(key: String, defaultValue: Boolean = false): Boolean {
        return prefs.getBoolean(key, defaultValue)
    }

    fun setBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }

    fun getInt(key: String, defaultValue: Int = 0): Int {
        return prefs.getInt(key, defaultValue)
    }

    fun setInt(key: String, value: Int) {
        prefs.edit().putInt(key, value).apply()
    }

    fun getLong(key: String, defaultValue: Long = 0L): Long {
        return prefs.getLong(key, defaultValue)
    }

    fun setLong(key: String, value: Long) {
        prefs.edit().putLong(key, value).apply()
    }

    fun getFloat(key: String, defaultValue: Float = 0f): Float {
        return prefs.getFloat(key, defaultValue)
    }

    fun setFloat(key: String, value: Float) {
        prefs.edit().putFloat(key, value).apply()
    }

    fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    /**
     * 获取所有以某个前缀开头的键值对（模拟 localStorage 的部分行为）
     */
    fun getAllWithPrefix(prefix: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        prefs.all.forEach { (key, value) ->
            if (key.startsWith(prefix) && value is String) {
                result[key] = value
            }
        }
        return result
    }
}
