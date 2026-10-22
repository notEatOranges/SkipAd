package com.noteatoranges.skipad.store

import android.content.Context
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
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val total = prefs.getInt(KEY_TOTAL, 0) + 1
        val old = JSONArray(prefs.getString(KEY_LOG, "[]"))
        val fresh = JSONArray()
        fresh.put("[$total] ${timeFmt.format(Date())} $pkg 「$detail」")
        for (i in 0 until old.length()) {
            if (fresh.length() >= LOG_LIMIT) break
            fresh.put(old.optString(i))
        }
        prefs.edit()
            .putInt(KEY_TOTAL, total)
            .putString(KEY_LOG, fresh.toString())
            .apply()
    }

    fun total(context: Context): Int =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_TOTAL, 0)

    fun recentLog(context: Context): List<String> {
        val arr = JSONArray(
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_LOG, "[]")
        )
        return List(arr.length()) { arr.optString(it) }
    }

    fun clear(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().clear().apply()
    }
}
