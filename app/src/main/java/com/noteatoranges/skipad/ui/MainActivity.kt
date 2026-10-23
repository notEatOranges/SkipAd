package com.noteatoranges.skipad.ui

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.noteatoranges.skipad.R
import com.noteatoranges.skipad.db.StatsDb
import com.noteatoranges.skipad.keepalive.KeepAlive
import com.noteatoranges.skipad.store.StatsStore
import com.noteatoranges.skipad.util.AppLabels
import com.noteatoranges.skipad.util.Ui
import java.util.Locale

/**
 * 首页：服务状态 + 立即自检 + 广告排行榜（分应用图标/计数条）+ 应用维护（内存/缓存）。
 * 最近记录与保活设置收进二级页（RecentActivity / KeepAliveActivity）。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var actionButton: Button
    private lateinit var statsSummary: TextView
    private lateinit var rankContainer: LinearLayout
    private lateinit var rankEmpty: TextView
    private lateinit var rankOthers: TextView
    private lateinit var memText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        Ui.edgeToEdge(this, findViewById(R.id.inset_top), findViewById(R.id.inset_bottom))

        statusText = findViewById(R.id.status_text)
        actionButton = findViewById(R.id.action_button)
        statsSummary = findViewById(R.id.stats_summary_text)
        rankContainer = findViewById(R.id.rank_container)
        rankEmpty = findViewById(R.id.rank_empty_text)
        rankOthers = findViewById(R.id.rank_others_text)
        memText = findViewById(R.id.mem_text)

        actionButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.check_now_button).setOnClickListener { btn ->
            btn.isEnabled = false
            runCheck { btn.isEnabled = true }
        }
        findViewById<TextView>(R.id.recent_entry).setOnClickListener {
            startActivity(Intent(this, RecentActivity::class.java))
        }
        findViewById<TextView>(R.id.keepalive_entry).setOnClickListener {
            startActivity(Intent(this, KeepAliveActivity::class.java))
        }
        findViewById<Button>(R.id.clear_cache_button).setOnClickListener {
            AppLabels.clearCache()
            Toast.makeText(this, R.string.clear_mem_cache_done, Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.clear_stats_button).setOnClickListener {
            confirmClearStats()
        }

        requestNotificationPermissionIfNeeded()
        // 事件表上限治理：开页时裁一次
        Thread { StatsDb.get(this).prune(1000) }.start()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val enabled = KeepAlive.isEnabledInSettings(this)
        statusText.setText(if (enabled) R.string.status_on else R.string.status_off)
        statusText.setTextColor(getColor(if (enabled) R.color.status_on else R.color.status_off))
        actionButton.setText(if (enabled) R.string.action_open_settings else R.string.action_enable)
        loadStatsAsync()
    }

    private fun runCheck(onDone: () -> Unit) {
        val appContext = applicationContext
        Thread {
            val result = KeepAlive.checkAndRecover(appContext)
            runOnUiThread {
                onDone()
                Toast.makeText(this, result, Toast.LENGTH_SHORT).show()
                refresh()
            }
        }.start()
    }

    private fun loadStatsAsync() {
        val appContext = applicationContext
        Thread {
            val db = StatsDb.get(appContext)
            val total = db.total()
            val distinct = db.distinctApps()
            val counts = db.countsByPackage()
            val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val pssKb = am.getProcessMemoryInfo(intArrayOf(Process.myPid()))
                .firstOrNull()?.totalPss ?: 0
            val dbKb = StatsDb.sizeKb(appContext)
            runOnUiThread {
                renderStats(total, distinct, counts, pssKb, dbKb)
            }
        }.start()
    }

    private fun renderStats(total: Int, distinct: Int, counts: List<StatsDb.PkgCount>, pssKb: Int, dbKb: Long) {
        statsSummary.text = getString(R.string.stats_summary_format, total, distinct)
        memText.text = getString(
            R.string.mem_format,
            String.format(Locale.CHINA, "%.1f", pssKb / 1024f),
            dbKb.toString()
        )

        rankContainer.removeAllViews()
        rankOthers.visibility = View.GONE
        if (counts.isEmpty()) {
            rankEmpty.visibility = View.VISIBLE
            return
        }
        rankEmpty.visibility = View.GONE

        val max = counts.first().count.coerceAtLeast(1)
        val shown = counts.take(SHOW_TOP)
        shown.forEachIndexed { idx, pc ->
            val row = layoutInflater.inflate(R.layout.item_rank, rankContainer, false)
            row.findViewById<TextView>(R.id.rank_num).text = (idx + 1).toString()
            val info = AppLabels.get(this, pc.pkg)
            row.findViewById<TextView>(R.id.app_name).text = info.label
            row.findViewById<ImageView>(R.id.app_icon).setImageDrawable(info.icon)
            row.findViewById<TextView>(R.id.count_text).text =
                getString(R.string.times_format, pc.count)
            val bar = row.findViewById<View>(R.id.bar)
            bar.layoutParams = (bar.layoutParams as LinearLayout.LayoutParams).apply {
                weight = pc.count.toFloat() / max
            }
            rankContainer.addView(row)
        }
        val rest = counts.drop(SHOW_TOP)
        if (rest.isNotEmpty()) {
            rankOthers.visibility = View.VISIBLE
            rankOthers.text = getString(
                R.string.rank_others_format, rest.size, rest.sumOf { it.count }
            )
        }
    }

    private fun confirmClearStats() {
        AlertDialog.Builder(this)
            .setTitle(R.string.clear_stats_title)
            .setMessage(R.string.clear_stats_msg)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_confirm_clear) { _, _ ->
                StatsDb.get(this).clear()
                Toast.makeText(this, R.string.clear_stats_done, Toast.LENGTH_SHORT).show()
                refresh()
            }
            .show()
    }

    /** 提醒模式依赖通知权限：Android 13+ 首次打开时请求一次 */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        ) return
        requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
    }

    private companion object {
        const val REQ_NOTIF = 1001
        const val SHOW_TOP = 10
    }
}
