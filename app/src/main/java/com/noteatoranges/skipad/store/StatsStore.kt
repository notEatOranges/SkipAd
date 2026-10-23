package com.noteatoranges.skipad.store

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 跳过统计与最近日志。SharedPreferences 存储即可满足"同 App 进程读写"，
 * M1 刻意不引入 Room/DataStore，保持零重型依赖。
 */
object StatsStore {

    private const val PREFS = "skipad_stats"
    private const val KEY_TOTAL = "total"
    private const val KEY_LOG = "log"
    private const val LOG_LIMIT = 50

    private val timeFmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA)

    fun recordSkip(context: Context, pkg: String, detail: String) {
        val prefs = prefs(context)
        val total = prefs.getInt(KEY_TOTAL, 0) + 1
        pushLog(prefs, "[$total] ${timeFmt.format(Date())} $pkg 「$detail」")
        prefs.edit().putInt(KEY_TOTAL, total).apply()
    }

    /** 保活等非跳过事件也进同一条日志环，UI 统一展示 */
    fun logEvent(context: Context, message: String) {
        pushLog(prefs(context), "${timeFmt.format(Date())} $message")
    }

    fun total(context: Context): Int = prefs(context).getInt(KEY_TOTAL, 0)

    fun recentLog(context: Context): List<String> {
        val arr = JSONArray(prefs(context).getString(KEY_LOG, "[]"))
        return List(arr.length()) { arr.optString(it) }
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 新行插头部，超 [LOG_LIMIT] 截断 */
    private fun pushLog(prefs: SharedPreferences, line: String) {
        val old = JSONArray(prefs.getString(KEY_LOG, "[]"))
        val fresh = JSONArray()
        fresh.put(line)
        for (i in 0 until old.length()) {
            if (fresh.length() >= LOG_LIMIT) break
            fresh.put(old.optString(i))
        }
        prefs.edit().putString(KEY_LOG, fresh.toString()).apply()
    }
}
