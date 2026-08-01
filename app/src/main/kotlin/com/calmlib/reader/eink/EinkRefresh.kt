package com.calmlib.reader.eink

import android.app.Activity
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import kotlinx.coroutines.*

object EinkRefresh {
    private var pagesSinceRefresh = 0

    fun manualRefresh(activity: Activity) {
        val decor = activity.window.decorView as? ViewGroup ?: return
        val overlay = View(activity).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        decor.addView(overlay)
        overlay.postDelayed({
            overlay.setBackgroundColor(Color.WHITE)
            overlay.postDelayed({
                decor.removeView(overlay)
            }, 50)
        }, 50)
    }

    fun onPageTurn(activity: Activity, autoRefreshInterval: Int) {
        if (autoRefreshInterval <= 0) return
        pagesSinceRefresh++
        if (pagesSinceRefresh >= autoRefreshInterval) {
            pagesSinceRefresh = 0
            manualRefresh(activity)
        }
    }

    fun resetCounter() {
        pagesSinceRefresh = 0
    }
}
