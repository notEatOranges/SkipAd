import re

p = 'app/src/main/java/com/noteatoranges/skipad/ui/MainActivity.kt'
s = open(p, encoding='utf-8').read()

# 1) 清理 imports
for imp in [
    'import android.content.ClipData\n',
    'import android.content.ClipboardManager\n',
    'import android.content.ComponentName\n',
    'import android.net.Uri\n',
    'import android.os.PowerManager\n',
    'import androidx.appcompat.app.AlertDialog\n',
    'import androidx.core.app.NotificationManagerCompat\n',
    'import com.noteatoranges.skipad.store.StatsStore\n',
]:
    s = s.replace(imp, '')

# 2) 删除已迁出视图的字段
s = s.replace('''    private lateinit var rankOthers: TextView
    private lateinit var memText: TextView
    private lateinit var modeText: TextView
    private lateinit var batteryStatus: TextView
    private lateinit var batteryButton: Button
    private lateinit var notifStatus: TextView
    private lateinit var notifButton: Button
    private lateinit var kaLogText: TextView
    private lateinit var tabBar: GlassBarView''',
'''    private lateinit var rankOthers: TextView
    private lateinit var tabBar: GlassBarView''')

# 3) 删除 findViewById 绑定
s = s.replace('''        rankOthers = findViewById(R.id.rank_others_text)
        memText = findViewById(R.id.mem_text)
        modeText = findViewById(R.id.mode_text)
        batteryStatus = findViewById(R.id.battery_status_text)
        batteryButton = findViewById(R.id.battery_button)
        notifStatus = findViewById(R.id.notif_status_text)
        notifButton = findViewById(R.id.notif_button)
        kaLogText = findViewById(R.id.ka_log_text)
        tabBar = findViewById(R.id.tab_bar)''',
'''        rankOthers = findViewById(R.id.rank_others_text)
        tabBar = findViewById(R.id.tab_bar)''')

# 4) 删除设置页处理器块
s = s.replace('''        // —— 设置 ——
        batteryButton.setOnClickListener { requestIgnoreBatteryOptimization() }
        notifButton.setOnClickListener { openNotificationSettings() }
        findViewById<Button>(R.id.autostart_button).setOnClickListener { openAutostartSettings() }
        findViewById<Button>(R.id.app_info_button).setOnClickListener { openAppInfo() }
        findViewById<Button>(R.id.copy_cmd_button).setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("adb", KeepAlive.ADB_GRANT_CMD))
            Toast.makeText(this, R.string.keepalive_copied, Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.clear_cache_button).setOnClickListener {
            AppLabels.clearCache()
            Toast.makeText(this, R.string.clear_mem_cache_done, Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.clear_stats_button).setOnClickListener { confirmClearStats() }

''', '')

# 5) refresh() 删迁出部分
s = s.replace('''        actionButton.setText(if (enabled) R.string.action_open_settings else R.string.action_enable)

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
        kaLogText.text = if (log.isEmpty()) getString(R.string.ka_log_empty) else log.joinToString("\\n")

        loadStatsAsync()''',
'''        actionButton.setText(if (enabled) R.string.action_open_settings else R.string.action_enable)
        loadStatsAsync()''')

# 6) renderStats 删 memText
s = s.replace('''        statsSummary.text = getString(R.string.stats_summary_format, total, distinct)
        memText.text = getString(
            R.string.mem_format,
            String.format(Locale.CHINA, "%.1f", pssKb / 1024f),
            dbKb.toString()
        )

''', '''        statsSummary.text = getString(R.string.stats_summary_format, total, distinct)

''')

# 7) 删除迁出的函数
start = s.index('    private fun confirmClearStats()')
end = s.index('    /** 提醒模式依赖通知权限')
s = s[:start] + s[end:]

open(p, 'w', encoding='utf-8', newline='\n').write(s)
print('slimmed; stale refs:', 'memText' in s, 'modeText' in s, 'batteryButton' in s, 'kaLogText' in s, 'confirmClearStats' in s)
