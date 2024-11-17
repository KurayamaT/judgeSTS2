package com.example.judgests

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.os.Handler
import android.os.Looper
import android.graphics.drawable.GradientDrawable
import android.view.WindowManager.LayoutParams.*

class StatusOverlay(private val context: Context) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: LinearLayout? = null
    private val handler = Handler(Looper.getMainLooper())

    fun show(message: String) {
        hide() // 既存の表示があれば消す

        // メインコンテナの作成
        overlayView = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL

            background = GradientDrawable().apply {
                cornerRadius = 16f
                setColor(Color.argb(230, 33, 33, 33))
            }

            setPadding(40, 30, 40, 30)
        }

        // メッセージの各行を処理
        message.trimIndent().split("\n").forEach { line ->
            TextView(context).apply {
                text = line
                setTextColor(Color.WHITE)
                textSize = when {
                    line.startsWith("📊") -> 18f
                    else -> 16f
                }
                setPadding(0, 8, 0, 8)

                overlayView?.addView(this)
            }
        }

        // WindowManagerのパラメータ設定
        val params = WindowManager.LayoutParams().apply {
            width = WRAP_CONTENT
            height = WRAP_CONTENT
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = 400  // 画面下端からの距離

            flags = FLAG_NOT_FOCUSABLE or FLAG_NOT_TOUCH_MODAL

            // Android 8.0以降はTYPE_APPLICATION_OVERLAYを使用
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                TYPE_SYSTEM_ALERT  // 8.0未満ではTYPE_SYSTEM_ALERTを使用
            }

            format = PixelFormat.TRANSLUCENT
        }

        try {
            overlayView?.let { windowManager.addView(it, params) }

            // 3秒後に非表示
            handler.postDelayed({ hide() }, 3000)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun hide() {
        try {
            overlayView?.let {
                windowManager.removeView(it)
                overlayView = null
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}