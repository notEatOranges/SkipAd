package com.noteatoranges.skipad.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import com.noteatoranges.skipad.R
import com.noteatoranges.skipad.keepalive.KeepAlive
import com.noteatoranges.skipad.store.StatsStore
import com.noteatoranges.skipad.util.Ui

/**
 * 保活与后台二级页：模式状态、adb 授权、电池优化、自启动/通知跳转、保活日志与边界说明。
 */
class KeepAliveActivity : AppCompatActivity() {

    private lateinit var modeText: TextView
    private lateinit var batteryStatus: TextView
    private lateinit var batteryButton: Button
    private lateinit var notifStatus: TextView
    private lateinit var notifButton: Button
    private lateinit var kaLogText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_keepalive)
        Ui.edgeToEdge(this, findViewById(R.id.inset_top), findViewById(R.id.inset_bottom))

        modeText = findViewById(R.id.mode_text)
        batteryStatus = findViewById(R.id.battery_status_text)
        batteryButton = findViewById(R.id.battery_button)
        notifStatus = findViewById(R.id.notif_status_text)
        notifButton = findViewById(R.id.notif_button)
        kaLogText = findViewById(R.id.ka_log_text)

        findViewById<TextView>(R.id.back_button).setOnClickListener { finish() }

        batteryButton.setOnClickListener { requestIgnoreBatteryOptimization() }
        notifButton.setOnClickListener { openNotificationSettings() }
        findViewById<Button>(R.id.autostart_button).setOnClickListener { openAutostartSettings() }
        findViewById<Button>(R.id.app_info_button).setOnClickListener { openAppInfo() }
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
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
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
}
