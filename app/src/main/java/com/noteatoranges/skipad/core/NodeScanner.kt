package com.noteatoranges.skipad.core

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale

/**
 * 在节点树中定位"跳过"目标：BFS 遍历 → 规则匹配 → 安全校验 → 返回可点击节点。
 *
 * 稳定性设计：
 * - 遍历有节点数与时间双上限，防御病态层级树拖垮服务主线程；
 * - 命中节点多为不可点击的 TextView，沿父链向上找第一个 isClickable 祖先再返回
 *   （"跳过按钮位置会变"由此天然解决：按特征找节点，坐标跟随节点走）。
 *
 * 注：AccessibilityNodeInfo.recycle() 在 API 33+ 已废弃（系统接管回收）；
 * 低版本上不主动回收只造成轻微内存压力，换来规避 recycle 误用型崩溃，M1 有意不回收。
 */
class NodeScanner(rules: List<SkipRule>) {

    private data class Compiled(
        val rule: SkipRule,
        val regexes: List<Regex>,
        val keywords: List<String>,
    )

    private val compiled: List<Compiled> = rules.filter { it.enabled }.map { r ->
        Compiled(
            rule = r,
            regexes = if (r.regex) r.patterns.map { Regex(it) } else emptyList(),
            keywords = if (!r.regex) r.patterns.map { it.lowercase(Locale.ROOT) } else emptyList(),
        )
    }

    /** 最近一次扫描的诊断信息（节点数/采样文本/匹配与拒绝原因），供真机排查 */
    var lastDebug: String? = null
        private set

    /** 返回 (可点击目标节点, 命中的规则)；找不到返回 null */
    fun findSkipTarget(
        root: AccessibilityNodeInfo,
        screenW: Int,
        screenH: Int,
    ): Pair<AccessibilityNodeInfo, SkipRule>? {
        if (screenW <= 0 || screenH <= 0) return null
        val screenArea = screenW.toFloat() * screenH
        val deadline = System.currentTimeMillis() + TRAVERSE_TIMEOUT_MS

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        val samples = LinkedHashSet<String>()
        var reject: String? = null

        while (queue.isNotEmpty()) {
            if (visited++ > MAX_NODES || System.currentTimeMillis() > deadline) {
                lastDebug = "ABORT nodes=$visited texts=${samples.joinToString("|").take(180)}"
                return null
            }
            val node = queue.removeFirst()

            val text = nodeText(node)
            if (text != null && samples.size < 20) samples.add(text.take(16))
            val matched = firstMatch(node)
            if (matched != null && text != null) {
                if (text.length > matched.maxTextLength) {
                    reject = "「${text.take(12)}」文本超长(${text.length})"
                } else {
                    val clickable = clickableAncestor(node)
                    when {
                        clickable == null ->
                            reject = "「${text.take(12)}」无可点击祖先(6级内)"
                        !clickable.isVisibleToUser ->
                            reject = "「${text.take(12)}」祖先不可见"
                        else -> {
                            val rect = Rect()
                            clickable.getBoundsInScreen(rect)
                            val area = rect.width().toFloat() * rect.height()
                            val pct = (area / screenArea * 100).toInt()
                            if (area in 1f..screenArea * matched.maxAreaRatio) {
                                lastDebug = "HIT「${text.take(16)}」面积${pct}% 规则=${matched.id}"
                                return clickable to matched
                            }
                            reject = "「${text.take(12)}」祖先面积${pct}%超限"
                        }
                    }
                }
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let(queue::add)
            }
        }
        lastDebug = "no-match nodes=$visited texts=[${samples.joinToString("|").take(180)}]" +
            (reject?.let { " reject=$it" } ?: "")
        return null
    }

    private fun firstMatch(node: AccessibilityNodeInfo): SkipRule? {
        val text = nodeText(node) ?: return null
        val lower = text.lowercase(Locale.ROOT)
        for (c in compiled) {
            if (c.regexes.any { it.containsMatchIn(text) }) return c.rule
            if (c.keywords.any { lower.contains(it) }) return c.rule
        }
        return null
    }

    /** text 优先，无 text 用 contentDescription（图片型"跳过"按钮常只有 desc） */
    private fun nodeText(node: AccessibilityNodeInfo): String? {
        val t = node.text?.toString()
        if (!t.isNullOrBlank()) return t
        val d = node.contentDescription?.toString()
        if (!d.isNullOrBlank()) return d
        return null
    }

    private fun clickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = node
        var hops = 0
        while (cur != null && hops < ANCESTOR_HOPS) {
            if (cur.isClickable) return cur
            cur = cur.parent
            hops++
        }
        return null
    }

    private companion object {
        const val MAX_NODES = 800
        const val TRAVERSE_TIMEOUT_MS = 400L
        const val ANCESTOR_HOPS = 6
    }
}
