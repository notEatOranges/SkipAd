package com.noteatoranges.skipad.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.noteatoranges.skipad.R
import com.noteatoranges.skipad.keepalive.KeepAlive
import com.noteatoranges.skipad.store.StatsStore

/**
 * 主界面：服务开关状态 + 保活状态 + 跳过统计 + 最近日志。
 * 数据流：服务/保活任务写 StatsStore（SharedPreferences），UI 在 onResume 时读取刷新。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var keepaliveStatusText: TextView
    private lateinit var statsText: TextView
    private lateinit var logText: TextView
    private lateinit var actionButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.status_text)
        keepaliveStatusText = findViewById(R.id.keepalive_status_text)
        statsText = findViewById(R.id.stats_text)
        logText = findViewById(R.id.log_text)
        actionButton = findViewById(R.id.action_button)

        actionButton.setOnClickListener {
            // App 无法程序化替用户开无障碍（未授权静默时），跳系统设置页（design.md §6）
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.clear_button).setOnClickListener {
            StatsStore.clear(this)
            refresh()
        }

        // 保活：UI 一打开就确保周期 Job 已注册（强停后重新打开 App 也由此恢复保活）
        KeepAlive.scheduleJob(this)

        findViewById<Button>(R.id.copy_cmd_button).setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("adb", KeepAlive.ADB_GRANT_CMD))
            Toast.makeText(this, R.string.keepalive_copied, Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.check_now_button).setOnClickListener { btn ->
            btn.isEnabled = false
            val appContext = applicationContext
            Thread {
                val result = KeepAlive.checkAndRecover(appContext)
                runOnUiThread {
                    btn.isEnabled = true
                    Toast.makeText(this, result, Toast.LENGTH_SHORT).show()
                    refresh()
                }
            }.start()
        }

        requestNotificationPermissionIfNeeded()
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

        val silent = KeepAlive.hasWriteSecureSettings(this)
        keepaliveStatusText.setText(if (silent) R.string.keepalive_mode_silent else R.string.keepalive_mode_notify)

        statsText.text = getString(R.string.stats_format, StatsStore.total(this))

        val log = StatsStore.recentLog(this)
        logText.text = if (log.isEmpty()) getString(R.string.log_empty) else log.joinToString("\n")
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
    }
}
