package com.noteatoranges.skipad.core

/**
 * 跳过规则模型。
 *
 * M1 只内置通用默认规则；M2 起支持 JSON 导入导出与 per-App 规则
 * （viewId / 相对坐标 / 包名黑白名单），格式设计见 docs/design.md §5。
 *
 * 防误触双约束是本模型的底线（任何匹配都必须同时过这两关）：
 * - [maxTextLength]：节点文本超长视为广告正文里提到"跳过"，不是按钮；
 * - [maxAreaRatio]：可点击祖先面积占屏比例过大视为广告大图上印的假"跳过"。
 */
data class SkipRule(
    val id: String,
    /** regex=false 时为关键字（忽略大小写包含匹配）；regex=true 时为正则 */
    val patterns: List<String>,
    val regex: Boolean = false,
    val maxTextLength: Int = 24,
    val maxAreaRatio: Float = 0.12f,
    val enabled: Boolean = true,
)

/** M1 内置默认规则：多语言关键字 + 倒计时正则 */
object BuiltinRules {

    fun defaults(): List<SkipRule> = listOf(
        SkipRule(
            id = "splash-common-text",
            // zh-CN/zh-TW/ja/ko/en 常见"跳过"文案；contains 匹配天然覆盖"跳过 3""Skip 5s"等倒计时变体
            patterns = listOf("跳过", "跳過", "スキップ", "건너뛰기", "skip"),
        ),
        SkipRule(
            id = "splash-countdown-regex",
            regex = true,
            // 兜底补充 contains 覆盖不到的形态："3s Skip"倒排、"5 秒后跳过"等
            patterns = listOf(
                "(?i)skip[\\s\\d]*(s|sec|秒)?",
                "\\d+\\s*(s|S|秒)\\s*(之|後|后)?\\s*跳过",
            ),
        ),
    )
}
