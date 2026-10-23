package com.noteatoranges.skipad.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.noteatoranges.skipad.R
import com.noteatoranges.skipad.db.RuleStore
import com.noteatoranges.skipad.db.SkipAppRule
import com.noteatoranges.skipad.db.SnapshotStore
import com.noteatoranges.skipad.util.Ui
import java.io.File
import java.util.UUID

/**
 * 快照详情：广告截图 + 完整节点列表。点选任一节点 → 生成跳过规则
 * （按控件 ID / 按文本 / 按坐标三选一），规则立即生效。
 */
class SnapshotDetailActivity : AppCompatActivity() {

    private lateinit var dir: File
    private lateinit var container: LinearLayout
    private lateinit var meta: SnapshotStore.Meta

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_snapshot_detail)
        Ui.edgeToEdge(this, findViewById(R.id.inset_top), findViewById(R.id.inset_bottom))

        dir = File(intent.getStringExtra("dir") ?: run { finish(); return })
        meta = SnapshotStore.loadMeta(dir) ?: run { finish(); return }
        container = findViewById(R.id.node_container)

        findViewById<TextView>(R.id.back_button).setOnClickListener { finish() }
        findViewById<TextView>(R.id.detail_title).text = meta.pkg

        val screen = SnapshotStore.loadScreen(dir)
        val iv = findViewById<ImageView>(R.id.screenshot)
        if (screen != null) {
            val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
            iv.setImageBitmap(BitmapFactory.decodeFile(screen.absolutePath, opts))
        } else {
            iv.visibility = View.GONE
        }

        renderNodes(SnapshotStore.loadNodes(dir))
    }

    private fun renderNodes(nodes: List<SnapshotStore.Node>) {
        container.removeAllViews()
        if (nodes.isEmpty()) {
            findViewById<TextView>(R.id.empty_text).visibility = View.VISIBLE
            return
        }
        findViewById<TextView>(R.id.empty_text).visibility = View.GONE
        // 可点击的排前面，点选目标通常就是它
        val sorted = nodes.sortedByDescending { it.clickable }
        sorted.forEach { n ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(6), dp(8), dp(6), dp(8))
                setBackgroundResource(R.drawable.bg_card)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(8) }
                setOnClickListener { askGenerateRule(n) }
            }
            row.addView(TextView(this).apply {
                text = buildString {
                    append("#${n.idx} ${n.cls.substringAfterLast('.')}")
                    if (n.clickable) append(" ⬅可点击")
                }
                textSize = 13f
                setTextColor(getColor(R.color.text_primary))
                paint.isFakeBoldText = n.clickable
            })
            row.addView(TextView(this).apply {
                text = buildString {
                    if (n.text.isNotEmpty()) append("文本「${n.text.take(24)}」 ")
                    if (n.desc.isNotEmpty()) append("描述「${n.desc.take(24)}」")
                    if (n.vid.isNotEmpty()) append("id=${n.vid.substringAfterLast('/')}")
                    if (isEmpty()) append("(无文本)")
                }
                textSize = 12f
                setTextColor(getColor(R.color.text_secondary))
            })
            row.addView(TextView(this).apply {
                text = "位置 (${n.l},${n.t}) ${n.w}×${n.h}"
                textSize = 11f
                setTextColor(getColor(R.color.text_secondary))
            })
            container.addView(row)
        }
    }

    private fun askGenerateRule(n: SnapshotStore.Node) {
        val options = mutableListOf(getString(R.string.rule_by_point))
        if (n.vid.isNotEmpty()) options.add(0, getString(R.string.rule_by_vid))
        if (n.text.isNotEmpty() || n.desc.isNotEmpty()) options.add(0, getString(R.string.rule_by_text))
        AlertDialog.Builder(this)
            .setTitle("为 ${meta.pkg} 生成规则")
            .setItems(options.toTypedArray()) { _, which ->
                val chosen = options[which]
                when (chosen) {
                    getString(R.string.rule_by_vid) -> {
                        RuleStore.add(this, rule("node", viewId = n.vid))
                        Toast.makeText(this, R.string.rule_saved, Toast.LENGTH_SHORT).show()
                    }
                    getString(R.string.rule_by_text) -> {
                        RuleStore.add(this, rule("node", textContains = n.text.ifEmpty { n.desc }))
                        Toast.makeText(this, R.string.rule_saved, Toast.LENGTH_SHORT).show()
                    }
                    else -> {
                        val dm = resources.displayMetrics
                        RuleStore.add(
                            this,
                            rule(
                                "point",
                                xRatio = (n.l + n.w / 2f) / dm.widthPixels,
                                yRatio = (n.t + n.h / 2f) / dm.heightPixels,
                            )
                        )
                        Toast.makeText(this, R.string.rule_saved, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
    }

    private fun rule(
        kind: String,
        viewId: String? = null,
        textContains: String? = null,
        xRatio: Float? = null,
        yRatio: Float? = null,
    ) = SkipAppRule(
        id = UUID.randomUUID().toString(),
        app = meta.pkg,
        appLabel = meta.pkg,
        kind = kind,
        viewId = viewId,
        textContains = textContains,
        xRatio = xRatio,
        yRatio = yRatio,
        delayMs = 900L,
        createdAt = System.currentTimeMillis(),
    )

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
}
