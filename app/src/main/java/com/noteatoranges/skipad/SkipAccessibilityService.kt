package com.noteatoranges.skipad

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.noteatoranges.skipad.core.BuiltinRules
import com.noteatoranges.skipad.core.ClickPerformer
import com.noteatoranges.skipad.core.NodeScanner
import com.noteatoranges.skipad.db.StatsDb
import com.noteatoranges.skipad.keepalive.KeepAlive
import com.noteatoranges.skipad.store.StatsStore

/**
 * 核心无障碍服务：窗口事件 → 节点树匹配"跳过" → 模拟点击。
 *
 * 性能与稳定性设计（完整论证见 docs/design.md §3）：
 * - 事件节流 + 包名黑名单（静态系统包 + 动态 HOME 桌面）+ 会话冷却；
 * - **点击后验证制（0.4.1）**：国产广告 SDK 普遍不理会 ACTION_CLICK（只认真实触摸），
 *   "performAction 返回 true"不等于广告被跳过——800ms 后重扫窗口，目标还在就改用
 *   dispatchGesture 真实手势补刀（最多 2 次），**广告真消失才记一次跳过**；
 * - 用 rootInActiveWindow 而非 event.source；全链路诊断日志打 TAG=SkipAdEngine。
 */
class SkipAccessibilityService : AccessibilityService() {

    private var scanner: NodeScanner? = null
    private var clicker: ClickPerformer? = null
    private var lastHandleAt = 0L

    private var lastSkipWindow: String? = null
    private var lastSkipAt = 0L

    private val mainHandler = Handler(Looper.getMainLooper())
    private var pending: PendingSkip? = null

    private data class PendingSkip(
        val windowKey: String,
        val pkg: String,
        val detail: String,
        var attempts: Int,
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        scanner = NodeScanner(BuiltinRules.defaults())
        clicker = ClickPerformer(this)
        // 桌面动态拉黑：HOME 类应用（含厂商桌面）里没有开屏广告，且桌面图标文字
        // 可能含 "skip"（比如本 App 自己的图标），不拉黑会把图标当跳过按钮点掉
        blacklist = PACKAGE_BLACKLIST + findHomePackages()
        // 连上系统的同时把保活 Job 确认到位，并留痕到日志环
        KeepAlive.scheduleJob(this)
        StatsStore.logEvent(this, getString(R.string.ka_log_connected))
    }

    private fun findHomePackages(): Set<String> = try {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        packageManager.queryIntentActivities(home, 0)
            .mapTo(mutableSetOf()) { it.activityInfo.packageName }
    } catch (e: Exception) {
        emptySet()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        // 被解绑（设置里被关 / 被 ROM 杀 / 覆盖安装）后的黄金几秒进程还活着：
        // 延迟一点再自检，静默模式当场拉起，提醒模式立刻发通知。
        // 冷却阀在 KeepAlive 里防震荡；本方法也会被两段式写入的中间态触发，届时 checkAndRecover 会识别"还在运行"直接返回。
        val appContext = applicationContext
        Thread {
            SystemClock.sleep(UNBIND_RECOVER_DELAY_MS)
            KeepAlive.checkAndRecover(appContext)
        }.start()
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {
        // 无语音反馈场景，无需处理
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val type = event.eventType
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) return

        val now = SystemClock.elapsedRealtime()
        if (now - lastHandleAt < THROTTLE_MS) return
        lastHandleAt = now

        val root = rootInActiveWindow ?: return
        val pkg = root.packageName?.toString() ?: event.packageName?.toString() ?: return
        if (pkg in blacklist || pkg == packageName) return

        val windowKey = "$pkg/${event.className ?: root.className ?: ""}"
        if (windowKey == lastSkipWindow && now - lastSkipAt < COOLDOWN_MS) return

        val hit = scanner?.findSkipTarget(root, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        if (hit == null) {
            Log.d(TAG, "scan $pkg: ${scanner?.lastDebug}")
            return
        }
        val detail = hit.first.text?.toString()
            ?: hit.first.contentDescription?.toString()
            ?: hit.second.id
        Log.d(TAG, "match $pkg: ${scanner?.lastDebug}")

        if (clicker?.click(hit.first) == true) {
            lastSkipWindow = windowKey
            lastSkipAt = now
            pending = PendingSkip(windowKey, pkg, detail, 0)
            val token = pending
            mainHandler.postDelayed({ if (pending === token) verifySkip() }, VERIFY_DELAY_MS)
        }
    }

    /** 点击后核验：目标还在就手势补刀，真消失才记账 */
    private fun verifySkip() {
        val p = pending ?: return
        val root = rootInActiveWindow
        val hit = root?.let { r ->
            scanner?.findSkipTarget(r, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        }
        val curPkg = root?.packageName?.toString()
        // 消失判定：窗口没了 / 扫不到目标 / 换了 App（重扫 className 与事件 className 不可比，勿比）
        val gone = root == null || hit == null || curPkg != p.pkg
        if (gone) {
            StatsDb.get(this).insert(p.pkg, p.detail)
            Log.d(TAG, "verified ${p.pkg} 「${p.detail}」 (attempts=${p.attempts})")
            pending = null
            return
        }
        if (p.attempts >= MAX_RETRY) {
            Log.d(TAG, "giveup ${p.pkg} 「${p.detail}」广告仍在, 已补刀${p.attempts}次不记账")
            pending = null
            return
        }
        p.attempts++
        val rect = Rect()
        hit!!.first.getBoundsInScreen(rect)
        if (!rect.isEmpty) {
            val ok = clicker?.clickPoint(rect.exactCenterX(), rect.exactCenterY()) ?: false
            Log.d(TAG, "retry#${p.attempts} ${p.pkg} gesture ok=$ok at (${rect.exactCenterX().toInt()},${rect.exactCenterY().toInt()})")
        }
        mainHandler.postDelayed({ if (pending === p) verifySkip() }, VERIFY_DELAY_MS)
    }

    private companion object {
        const val TAG = "SkipAdEngine"
        const val THROTTLE_MS = 150L
        const val COOLDOWN_MS = 4000L
        const val UNBIND_RECOVER_DELAY_MS = 1500L
        const val VERIFY_DELAY_MS = 800L
        const val MAX_RETRY = 2

        /** 系统包与噪声大户：直接跳过节点遍历（李跳跳同款"先粗筛再遍历"思路） */
        val PACKAGE_BLACKLIST = setOf(
            "com.android.systemui",
            "com.android.launcher3",
            "com.miui.home",
            "com.huawei.android.launcher",
            "com.sec.android.app.launcher",
            "com.google.android.apps.nexuslauncher",
        )
    }

    @Volatile
    private var blacklist: Set<String> = PACKAGE_BLACKLIST
}
