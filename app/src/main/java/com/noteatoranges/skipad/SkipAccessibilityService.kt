package com.noteatoranges.skipad

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import com.noteatoranges.skipad.core.BuiltinRules
import com.noteatoranges.skipad.core.ClickPerformer
import com.noteatoranges.skipad.core.NodeScanner
import com.noteatoranges.skipad.store.StatsStore

/**
 * 核心无障碍服务：窗口事件 → 节点树匹配"跳过" → 模拟点击。
 *
 * 性能与稳定性设计（完整论证见 docs/design.md §3）：
 * - 事件节流：全局时间窗，抵御 TYPE_WINDOW_CONTENT_CHANGED 事件风暴；
 * - 包名黑名单：系统 UI / 桌面启动器直接忽略，不做节点遍历（粗筛先行）；
 * - 会话冷却：同一窗口成功跳过后冷却期内不重复动作，防误点二级页面；
 * - 用 rootInActiveWindow 而非 event.source：后者在 CONTENT_CHANGED 里可能是局部小节点。
 */
class SkipAccessibilityService : AccessibilityService() {

    private var scanner: NodeScanner? = null
    private var clicker: ClickPerformer? = null
    private var lastHandleAt = 0L

    private var lastSkipWindow: String? = null
    private var lastSkipAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        scanner = NodeScanner(BuiltinRules.defaults())
        clicker = ClickPerformer(this)
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
        if (pkg in PACKAGE_BLACKLIST || pkg == packageName) return

        val windowKey = "$pkg/${event.className ?: root.className ?: ""}"
        if (windowKey == lastSkipWindow && now - lastSkipAt < COOLDOWN_MS) return

        val metrics = resources.displayMetrics
        val hit = scanner?.findSkipTarget(root, metrics.widthPixels, metrics.heightPixels)
            ?: return
        val detail = hit.first.text?.toString()
            ?: hit.first.contentDescription?.toString()
            ?: hit.second.id

        if (clicker?.click(hit.first) == true) {
            lastSkipWindow = windowKey
            lastSkipAt = now
            StatsStore.recordSkip(this, pkg, detail)
        }
    }

    private companion object {
        const val THROTTLE_MS = 150L
        const val COOLDOWN_MS = 4000L

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
}
