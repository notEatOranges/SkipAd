package com.noteatoranges.skipad.ui

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.noteatoranges.skipad.SkipAccessibilityService
import com.noteatoranges.skipad.R
import com.noteatoranges.skipad.store.StatsStore

/**
 * M1 主界面：服务开关状态 + 跳过统计 + 最近日志。
 * 数据流：服务写 StatsStore（SharedPreferences），UI 在 onResume 时读取刷新。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var statsText: TextView
    private lateinit var logText: TextView
    private lateinit var actionButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.status_text)
        statsText = findViewById(R.id.stats_text)
        logText = findViewById(R.id.log_text)
        actionButton = findViewById(R.id.action_button)

        actionButton.setOnClickListener {
            // App 无法程序化替用户开无障碍，只能跳系统设置页（design.md §6）
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.clear_button).setOnClickListener {
            StatsStore.clear(this)
            refresh()
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val enabled = isServiceEnabled()
        statusText.setText(if (enabled) R.string.status_on else R.string.status_off)
        statusText.setTextColor(getColor(if (enabled) R.color.status_on else R.color.status_off))
        actionButton.setText(if (enabled) R.string.action_open_settings else R.string.action_enable)

        statsText.text = getString(R.string.stats_format, StatsStore.total(this))

        val log = StatsStore.recentLog(this)
        logText.text = if (log.isEmpty()) getString(R.string.log_empty) else log.joinToString("\n")
    }

    private fun isServiceEnabled(): Boolean {
        val setting = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val me = ComponentName(this, SkipAccessibilityService::class.java).flattenToString()
        return setting.split(':').any { it.equals(me, ignoreCase = true) }
    }
}
