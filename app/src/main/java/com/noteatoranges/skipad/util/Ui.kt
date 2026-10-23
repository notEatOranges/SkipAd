package com.noteatoranges.skipad.util

import android.app.Activity
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/**
 * 沉浸式：状态栏/导航栏透明（在主题里设），系统栏+挖孔内边距落到两个专用垫片 View 上。
 * 垫片高度即 inset 高度，避免 padding 叠加问题。
 */
object Ui {

    fun edgeToEdge(activity: Activity, topSpacer: View, bottomSpacer: View) {
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        ViewCompat.setOnApplyWindowInsetsListener(topSpacer) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            topSpacer.layoutParams.height = bars.top
            bottomSpacer.layoutParams.height = bars.bottom
            topSpacer.requestLayout()
            bottomSpacer.requestLayout()
            WindowInsetsCompat.CONSUMED
        }
    }
}
