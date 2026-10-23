package com.noteatoranges.skipad

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.noteatoranges.skipad.core.BuiltinRules
import com.noteatoranges.skipad.core.ClickPerformer
import com.noteatoranges.skipad.core.NodeScanner
import com.noteatoranges.skipad.db.StatsDb
import com.noteatoranges.skipad.keepalive.KeepAlive
import com.noteatoranges.skipad.store.StatsStore
import java.io.File

/**
 * 核心无障碍服务：窗口事件 → 节点树匹配"跳过" → 模拟点击。
 *
 * 点击后验证制（0.4.1，0.4.3 强化）：广告 SDK 普遍不理会 ACTION_CLICK，
 * "performAction 返回 true"不等于跳过——800ms 后核验，**在所有窗口**（含 overlay
 * 悬浮窗，rootInActiveWindow 未必是广告层）里找目标：还在就 dispatchGesture 真实
 * 手势补刀（最多 2 次），全部窗口都找不到目标才记一次跳过。
 * 诊断：命中/核验/放弃时把完整控件树落盘 filesDir/diag/（adb run-as 可拉），
 * logcat TAG=SkipAdEngine（no-match 只打 VERBOSE 防刷屏）。
 */
class SkipAccessibilityService : AccessibilityService() {

    private var scanner: NodeScanner? = null
    private var clicker: ClickPerformer? = null
    private var lastHandleAt = 0L

    private var lastSkipWindow: String? = null
    private var lastSkipAt = 0L
    private var lastFgPkg: String? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var pending: PendingSkip? = null
    private var lastFgHandleAt = 0L

    /** 每个 App 最近一次"新窗口出现"的时间：开屏广告只存在于启动后的几秒内，
     *  超出 [SPLASH_WINDOW_MS] 的窗口（浏览器长驻页面等）不再扫描，防关键字空转误触 */
    private val lastStateChangeAt = HashMap<String, Long>()

    /** 每个 App 当前前台 Activity 类名（开屏页/广告 Activity 类名多含 splash/ad，用于点按闸） */
    private val lastActivityClass = HashMap<String, String>()

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
        // 桌面动态拉黑：HOME 类应用里没有开屏广告，且桌面图标文字可能含 "skip"
        blacklist = PACKAGE_BLACKLIST + findHomePackages()
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
        val appContext = applicationContext
        Thread {
            SystemClock.sleep(UNBIND_RECOVER_DELAY_MS)
            KeepAlive.checkAndRecover(appContext)
        }.start()
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val type = event.eventType
        if (Math.random() < 0.02) {
            Log.v(TAG, "entry type=$type pkg=${event.packageName}")
        }

        // —— 坐标点按调度（必须最先做）：冷启动切换瞬间 rootInActiveWindow 往往还是
        //    旧窗口/桌面，root 包名判定会误入黑名单分支；这里用 event.packageName（准）。
        //    此路径只写 map，零开销，**不做时间节流**——桌面→App 切换常在 5ms 内连发，
        //    一旦节流就会吞掉切换事件（0.5.1 实测被 300ms 阀吞掉导致坐标点按永不触发）——
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val ep = event.packageName?.toString()
            if (ep != null && ep != packageName) {
                if (lastStateChangeAt.size > 50) lastStateChangeAt.clear()
                val freshSwitch = lastFgPkg != ep && ep !in blacklist
                lastStateChangeAt[ep] = SystemClock.elapsedRealtime()
                event.className?.toString()?.let { lastActivityClass[ep] = it }
                if (ep in CORNER_TAP_PKGS && freshSwitch) scheduleCornerTaps(ep)
                lastFgPkg = ep
            }
        }

        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) return

        val now = SystemClock.elapsedRealtime()
        if (now - lastHandleAt < THROTTLE_MS) return
        lastHandleAt = now

        val root = rootInActiveWindow ?: return
        val pkg = root.packageName?.toString() ?: event.packageName?.toString() ?: return
        if (pkg in blacklist || pkg == packageName) return

        // 启动窗口闸：只在"新窗口出现后 SPLASH_WINDOW_MS 内"扫描（开屏广告只在这段时间存在）
        val stateAt = lastStateChangeAt[pkg] ?: 0L
        if (now - stateAt > SPLASH_WINDOW_MS) return

        val windowKey = "$pkg/${event.className ?: root.className ?: ""}"
        if (windowKey == lastSkipWindow && now - lastSkipAt < COOLDOWN_MS) return

        val hit = scanner?.findSkipTarget(root, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        if (hit == null) {
            // 逐窗扫描刷屏严重，no-match 降为 VERBOSE
            Log.v(TAG, "scan $pkg: ${scanner?.lastDebug}")
            return
        }
        val detail = hit.first.text?.toString()
            ?: hit.first.contentDescription?.toString()
            ?: hit.second.id
        Log.d(TAG, "match $pkg: ${scanner?.lastDebug}")
        dumpTree(root, pkg, "match")

        if (clicker?.click(hit.first) == true) {
            lastSkipWindow = windowKey
            lastSkipAt = now
            pending = PendingSkip(windowKey, pkg, detail, 0)
            val token = pending
            mainHandler.postDelayed({ if (pending === token) verifySkip() }, VERIFY_DELAY_MS)
        }
    }

    /** 核验：所有窗口（含 overlay）都找不到目标才算跳过成功，否则手势补刀 */
    private fun verifySkip() {
        val p = pending ?: return
        val found = findTargetInAnyWindow()
        if (found == null) {
            StatsDb.get(this).insert(p.pkg, p.detail)
            Log.d(TAG, "verified ${p.pkg} 「${p.detail}」 (attempts=${p.attempts})")
            pending = null
            return
        }
        if (p.attempts >= MAX_RETRY) {
            Log.d(TAG, "giveup ${p.pkg} 「${p.detail}」广告仍在(${found.second}), 已补刀${p.attempts}次不记账")
            dumpTree(rootInActiveWindow, p.pkg, "giveup")
            pending = null
            return
        }
        p.attempts++
        val rect = Rect()
        found.first.getBoundsInScreen(rect)
        if (!rect.isEmpty) {
            val ok = clicker?.clickPoint(rect.exactCenterX(), rect.exactCenterY()) ?: false
            Log.d(TAG, "retry#${p.attempts} ${p.pkg} gesture ok=$ok at (${rect.exactCenterX().toInt()},${rect.exactCenterY().toInt()}) win=${found.second}")
        }
        mainHandler.postDelayed({ if (pending === p) verifySkip() }, VERIFY_DELAY_MS)
    }

    /** 在所有可交互窗口里找跳过目标（rootInActiveWindow 未必是广告 overlay 层） */
    private fun findTargetInAnyWindow(): Triple<AccessibilityNodeInfo, String, String>? {
        val w = resources.displayMetrics.widthPixels
        val h = resources.displayMetrics.heightPixels
        for (window in windows) {
            val root = window.root ?: continue
            val wp = root.packageName?.toString() ?: continue
            if (wp == packageName || wp in blacklist) continue
            val hit = scanner?.findSkipTarget(root, w, h)
            if (hit != null) return Triple(hit.first, wp, "win#${window.id} ${window.type}")
        }
        return null
    }

    /** 完整控件树落盘（诊断广告页真实层级：文本/viewId/可点击/坐标） */
    private fun dumpTree(root: AccessibilityNodeInfo?, pkg: String, tag: String) {
        root ?: return
        try {
            val dir = File(filesDir, "diag").apply { mkdirs() }
            // 只留最近 20 份
            dir.listFiles()?.sortedBy { it.name }?.dropLast(20)?.forEach { it.delete() }
            val sb = StringBuilder()
            sb.append("== $tag $pkg ${System.currentTimeMillis()} ==\n")
            for ((i, win) in windows.withIndex()) {
                sb.append("win#$i id=${win.id} type=${win.type} pkg=${win.root?.packageName} active=${win.isActive} focused=${win.isFocused}\n")
            }
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            var n = 0
            while (queue.isNotEmpty() && n < 300) {
                val node = queue.removeFirst()
                n++
                val r = Rect()
                node.getBoundsInScreen(r)
                sb.append("[$n] ${node.className} txt=${node.text?.toString()?.take(30)} desc=${node.contentDescription?.toString()?.take(30)} " +
                    "id=${node.viewIdResourceName} click=${node.isClickable} bounds=$r\n")
                for (i in 0 until node.childCount) node.getChild(i)?.let(queue::add)
            }
            File(dir, "${tag}_${System.currentTimeMillis()}.txt").writeText(sb.toString())
        } catch (e: Exception) {
            Log.w(TAG, "dumpTree failed", e)
        }
    }

    /** 冷启动切入后按延迟依次真点右上角（跳过按钮标准位）；期间离开该 App 自动作废。
     *  每次点按前验"广告在场"：当前窗口类名含 splash/ad（开屏页或广告 SDK Activity）才真点，
     *  普通页面（无广告的冷启动）零误触。 */
    private fun scheduleCornerTaps(pkg: String) {
        Log.d(TAG, "corner scheduled $pkg")
        CORNER_TAP_DELAYS.forEachIndexed { i, delay ->
            mainHandler.postDelayed({
                val root = rootInActiveWindow ?: return@postDelayed
                if (root.packageName?.toString() != pkg) return@postDelayed
                // 广告在场闸：看前台 Activity 类名（window.root.className 是根视图类名，恒为
                // FrameLayout 之类，永远不含 splash/ad——0.5.3 实测闸门全拦的坑）
                val actCls = lastActivityClass[pkg] ?: ""
                if (!actCls.contains("ad", true) && !actCls.contains("splash", true) &&
                    !actCls.contains("launch", true)
                ) {
                    Log.d(TAG, "corner#${i + 1} $pkg skip: 非广告/开屏窗口(act=$actCls)")
                    return@postDelayed
                }
                val ok = clicker?.clickPoint(
                    resources.displayMetrics.widthPixels * CORNER_X_RATIO,
                    resources.displayMetrics.heightPixels * CORNER_Y_RATIO,
                ) ?: false
                Log.d(TAG, "corner#${i + 1} $pkg ok=$ok act=$actCls")
            }, delay)
        }
    }

    private companion object {
        const val TAG = "SkipAdEngine"
        const val THROTTLE_MS = 150L
        const val COOLDOWN_MS = 4000L
        const val UNBIND_RECOVER_DELAY_MS = 1500L
        const val VERIFY_DELAY_MS = 800L
        const val MAX_RETRY = 2
        const val SPLASH_WINDOW_MS = 8000L
        const val FG_THROTTLE_MS = 300L
        const val CORNER_X_RATIO = 0.93f
        const val CORNER_Y_RATIO = 0.065f

        /** 跳过按钮无可读文本的广告 App：启用右上角坐标点按（切 ApP 冷启动后 0.9/1.9/2.9s 各点一下） */
        val CORNER_TAP_PKGS = setOf(
            "com.coolapk.market",
            "com.netease.mail",
            "com.ehai",
        )
        val CORNER_TAP_DELAYS = longArrayOf(900L, 1900L, 2900L)

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
