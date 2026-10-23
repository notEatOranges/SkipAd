package com.noteatoranges.skipad.keepalive

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.noteatoranges.skipad.R
import com.noteatoranges.skipad.SkipAccessibilityService
import com.noteatoranges.skipad.store.StatsStore

/**
 * 无障碍保活中枢（分级设计，详见 docs/design.md §3.1）：
 *
 * - 静默复活（需 adb 授权 WRITE_SECURE_SETTINGS；该权限系统安装发不了、必须电脑手动给，
 *   只用于把本 App 自己的无障碍服务写回系统设置，不涉及任何用户数据）：
 *   服务被杀/被关/开机/升级后，直接写回设置让系统重新绑定；
 * - 提醒模式（默认，零额外授权）：掉线时发本地通知引导去设置页开启。
 *
 * 触发源全部收敛到 [checkAndRecover]：开机/升级广播、15 分钟周期 Job、UI「立即自检」。
 * 已知边界：用户在系统设置里主动关闭后，周期任务也会把它重新开启（授权静默即视为接受）；
 * 被一键强停后 App 处于 stopped 态，一切调度失效——打开一次 App 即恢复保活。
 */
object KeepAlive {

    private const val JOB_ID = 1001
    private const val CHANNEL_ID = "keepalive"
    private const val NOTIFY_ID = 1001

    /**
     * 复活冷却：系统重绑有过渡期，isRunningNow 会滞后返回 false；
     * 没有冷却时"写回→重绑→任务又跑→又写回"会震荡成每秒几十次的活循环（0.2.0 真机首测实锤）。
     */
    private const val RESURRECT_COOLDOWN_MS = 30_000L

    @Volatile
    private var lastResurrectAt = 0L

    /** 电脑上一条命令即可授予静默复活能力（UI 里一键复制） */
    const val ADB_GRANT_CMD: String =
        "adb shell pm grant com.noteatoranges.skipad android.permission.WRITE_SECURE_SETTINGS"

    fun serviceComponent(context: Context): ComponentName =
        ComponentName(context, SkipAccessibilityService::class.java)

    /** 配置态：系统设置里已登记为开启 */
    fun isEnabledInSettings(context: Context): Boolean {
        val cur = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val me = serviceComponent(context).flattenToString()
        return cur.split(':').any { it.equals(me, ignoreCase = true) }
    }

    /** 运行态：系统当前真实绑定着本服务（比配置态可信，ROM 拦自启时配置在但没绑定） */
    fun isRunningNow(context: Context): Boolean {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            ?: return false
        val me = serviceComponent(context).flattenToString()
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.id.equals(me, ignoreCase = true) }
    }

    fun hasWriteSecureSettings(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.WRITE_SECURE_SETTINGS
        ) == PackageManager.PERMISSION_GRANTED

    /**
     * 统一自检入口：活着 → 不动；挂了 → 静默复活或发提醒。
     * @return 结果描述，供 UI Toast 与保活日志使用
     */
    fun checkAndRecover(context: Context): String {
        if (isRunningNow(context)) return context.getString(R.string.ka_result_running)
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastResurrectAt < RESURRECT_COOLDOWN_MS) {
            return context.getString(R.string.ka_result_cooldown)
        }
        if (hasWriteSecureSettings(context) && tryReEnable(context)) {
            lastResurrectAt = now
            StatsStore.logEvent(context, context.getString(R.string.ka_log_revived))
            return context.getString(R.string.ka_result_revived)
        }
        StatsStore.logEvent(context, context.getString(R.string.ka_log_detected_off))
        notifyToReEnable(context)
        return context.getString(R.string.ka_result_notified)
    }

    /**
     * 把本服务写回系统设置（需 WRITE_SECURE_SETTINGS），保留其他 App 已开启的无障碍服务。
     * 配置里没有本服务 → 单次追加写入（干净拉起）；
     * 配置里已有但系统没绑定（开机被 ROM 拦）→ 两段式写入制造一次变更，系统才重新解析绑定。
     */
    fun tryReEnable(context: Context): Boolean = try {
        val cr = context.contentResolver
        val me = serviceComponent(context).flattenToString()
        val cur = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        val others = cur.split(':').filter { it.isNotBlank() && !it.equals(me, ignoreCase = true) }
        val mine = cur.split(':').any { it.equals(me, ignoreCase = true) }
        if (mine) {
            Settings.Secure.putString(
                cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, others.joinToString(":")
            )
        }
        Settings.Secure.putString(
            cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, (others + me).joinToString(":")
        )
        Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        true
    } catch (e: Exception) {
        false
    }

    /** 15 分钟周期自检；persisted 跨重启（开机广播只是让它更早跑第一次的保险）。幂等：已注册不再重排 */
    fun scheduleJob(context: Context) {
        val js = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        if (js.getPendingJob(JOB_ID) != null) return
        val info = JobInfo.Builder(JOB_ID, ComponentName(context, KeepAliveJobService::class.java))
            .setPeriodic(JobInfo.getMinPeriodMillis())
            .setPersisted(true)
            .build()
        js.schedule(info)
    }

    private fun notifyToReEnable(context: Context) {
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nmSys =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nmSys.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.ka_channel_name),
                    NotificationManager.IMPORTANCE_HIGH,
                )
            )
        }
        val pi = PendingIntent.getActivity(
            context, 0,
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_keepalive)
            .setContentTitle(context.getString(R.string.ka_notify_title))
            .setContentText(context.getString(R.string.ka_notify_text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        try {
            nm.notify(NOTIFY_ID, n)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS 运行时未授：提醒模式只能就此放弃，不打扰
        }
    }
}
