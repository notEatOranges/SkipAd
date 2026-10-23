package com.noteatoranges.skipad.ui

import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.noteatoranges.skipad.R
import com.noteatoranges.skipad.db.StatsDb
import com.noteatoranges.skipad.keepalive.KeepAlive
import com.noteatoranges.skipad.store.StatsStore
import com.noteatoranges.skipad.util.AppLabels
import com.noteatoranges.skipad.util.GlassBarView
import com.noteatoranges.skipad.util.Ui
import java.util.Locale

/**
 * 单 Activity 三 Tab（悬浮底栏）：首页（状态/自检/排行榜）、设置（保活/系统权限/维护）、关于。
 * 最近记录保留二级页（列表长，从首页排行榜卡进入）。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var pages: List<View>
    private lateinit var tabs: List<TextView>

    private lateinit var statusText: TextView
    private lateinit var actionButton: Button
    private lateinit var statsSummary: TextView
    private lateinit var rankContainer: LinearLayout
    private lateinit var rankEmpty: TextView
    private lateinit var rankOthers: TextView
    private lateinit var memText: TextView
    private lateinit var modeText: TextView
    private lateinit var batteryStatus: TextView
    private lateinit var batteryButton: Button
    private lateinit var notifStatus: TextView
    private lateinit var notifButton: Button
    private lateinit var kaLogText: TextView
    private lateinit var tabBar: GlassBarView

    private var bottomInsetPx = 0
    private var currentTab = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.status_text)
        actionButton = findViewById(R.id.action_button)
        statsSummary = findViewById(R.id.stats_summary_text)
        rankContainer = findViewById(R.id.rank_container)
        rankEmpty = findViewById(R.id.rank_empty_text)
        rankOthers = findViewById(R.id.rank_others_text)
        memText = findViewById(R.id.mem_text)
        modeText = findViewById(R.id.mode_text)
        batteryStatus = findViewById(R.id.battery_status_text)
        batteryButton = findViewById(R.id.battery_button)
        notifStatus = findViewById(R.id.notif_status_text)
        notifButton = findViewById(R.id.notif_button)
        kaLogText = findViewById(R.id.ka_log_text)
        tabBar = findViewById(R.id.tab_bar)
        // 玻璃：把内容层（本栏的兄弟节点）指定为模糊目标
        tabBar.setup(findViewById(R.id.blur_content))

        pages = listOf(findViewById(R.id.page_home), findViewById(R.id.page_settings), findViewById(R.id.page_about))
        tabs = listOf(findViewById(R.id.tab_home), findViewById(R.id.tab_settings), findViewById(R.id.tab_about))
        tabs.forEachIndexed { i, tab -> tab.setOnClickListener { selectTab(i) } }

        // 沉浸式 + 底部安全区：内容滚到手势条底下，玻璃 Tab 栏浮在其上
        Ui.edgeToEdge(this, findViewById(R.id.inset_top)) { bottom ->
            bottomInsetPx = bottom
            tabBar.layoutParams = (tabBar.layoutParams as FrameLayout.LayoutParams).apply {
                bottomMargin = bottom + dp(14)
                width = glassWidth()
            }
            tabBar.requestLayout()
            pages.forEach { p ->
                (p as ViewGroup).getChildAt(0).setPadding(
                    dp(20), 0, dp(20), dp(96) + bottom
                )
            }
        }

        // —— 首页 ——
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

        // —— 设置 ——
        batteryButton.setOnClickListener { requestIgnoreBatteryOptimization() }
        notifButton.setOnClickListener { openNotificationSettings() }
        findViewById<Button>(R.id.autostart_button).setOnClickListener { openAutostartSettings() }
        findViewById<Button>(R.id.app_info_button).setOnClickListener { openAppInfo() }
        findViewById<Button>(R.id.copy_cmd_button).setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("adb", KeepAlive.ADB_GRANT_CMD))
            Toast.makeText(this, R.string.keepalive_copied, Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.settings_check_button).setOnClickListener { btn ->
            btn.isEnabled = false
            runCheck { btn.isEnabled = true }
        }
        findViewById<Button>(R.id.clear_cache_button).setOnClickListener {
            AppLabels.clearCache()
            Toast.makeText(this, R.string.clear_mem_cache_done, Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.clear_stats_button).setOnClickListener { confirmClearStats() }

        // —— 关于 ——
        findViewById<TextView>(R.id.about_version_text).text = try {
            getString(R.string.about_version_format, packageManager.getPackageInfo(packageName, 0).versionName)
        } catch (e: Exception) {
            ""
        }

        requestNotificationPermissionIfNeeded()
        // 事件表上限治理：开页时裁一次
        Thread { StatsDb.get(this).prune(1000) }.start()
        selectTab(0)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun glassWidth(): Int =
        minOf(dp(300), resources.displayMetrics.widthPixels - dp(40))

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun selectTab(index: Int) {
        currentTab = index
        pages.forEachIndexed { i, p -> p.visibility = if (i == index) View.VISIBLE else View.GONE }
        tabs.forEachIndexed { i, t ->
            t.setBackgroundResource(if (i == index) R.drawable.bg_tab_on else android.R.color.transparent)
            t.setTextColor(getColor(if (i == index) R.color.primary else R.color.text_secondary))
            t.isActivated = i == index
        }
    }

    private fun refresh() {
        val enabled = KeepAlive.isEnabledInSettings(this)
        statusText.setText(if (enabled) R.string.status_on else R.string.status_off)
        statusText.setTextColor(getColor(if (enabled) R.color.status_on else R.color.status_off))
        actionButton.setText(if (enabled) R.string.action_open_settings else R.string.action_enable)

        modeText.setText(
            if (KeepAlive.hasWriteSecureSettings(this)) R.string.keepalive_mode_silent
            else R.string.keepalive_mode_notify
        )
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val ignoring = pm.isIgnoringBatteryOptimizations(packageName)
        batteryStatus.setText(if (ignoring) R.string.battery_status_on else R.string.battery_status_off)
        batteryStatus.setTextColor(getColor(if (ignoring) R.color.status_on else R.color.status_off))
        batteryButton.visibility = if (ignoring) Button.GONE else Button.VISIBLE
        val notifOn = NotificationManagerCompat.from(this).areNotificationsEnabled()
        notifStatus.setText(if (notifOn) R.string.notif_status_on else R.string.notif_status_off)
        notifStatus.setTextColor(getColor(if (notifOn) R.color.status_on else R.color.status_off))
        notifButton.visibility = if (notifOn) Button.GONE else Button.VISIBLE
        val log = StatsStore.recentLog(this)
        kaLogText.text = if (log.isEmpty()) getString(R.string.ka_log_empty) else log.joinToString("\n")

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

    /** 弹系统级"忽略电池优化"确认框（REQUEST_IGNORE_BATTERY_OPTIMIZATIONS 已声明） */
    private fun requestIgnoreBatteryOptimization() {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e2: Exception) {
                Toast.makeText(this, e2.message ?: "failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openNotificationSettings() {
        try {
            startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            )
        } catch (e: Exception) {
            openAppInfo()
        }
    }

    /** 自启动管理：荣耀管家动作 → 已知组件逐个兜底 → 应用信息页 */
    private fun openAutostartSettings() {
        val candidates = listOf(
            Intent("hihonor.intent.action.HSM_STARTUPAPP_MANAGER")
                .setPackage("com.hihonor.systemmanager"),
            Intent("hihonor.intent.action.HSM_STARTUPAPP_MANAGER"),
            Intent().setComponent(
                ComponentName(
                    "com.hihonor.systemmanager",
                    "com.hihonor.systemmanager.appcontrol.activity.StartupAppControlActivity"
                )
            ),
            Intent().setComponent(
                ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                )
            ),
        )
        for (i in candidates) {
            try {
                startActivity(i)
                return
            } catch (e: Exception) {
                // 试下一个
            }
        }
        openAppInfo()
    }

    private fun openAppInfo() {
        try {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            Toast.makeText(this, e.message ?: "failed", Toast.LENGTH_SHORT).show()
        }
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
