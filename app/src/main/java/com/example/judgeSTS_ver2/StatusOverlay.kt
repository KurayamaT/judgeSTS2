package com.example.judgeSTS_ver2

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import android.graphics.Color
import android.view.ViewGroup
import android.widget.LinearLayout
import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.util.Log

class StatusOverlay(private val context: Context) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: LinearLayout? = null
    private var textView: TextView? = null
    private var isShowing = false

    init {
        createOverlayView()
    }

    // === レイアウト構築 ===
    private fun createOverlayView() {
        overlayView = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(android.R.drawable.dialog_holo_dark_frame)
            setPadding(24, 12, 24, 12)
        }

        textView = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            maxLines = 4
            ellipsize = TextUtils.TruncateAt.END
            setAutoSizeTextTypeUniformWithConfiguration(
                8, 14, 1, TypedValue.COMPLEX_UNIT_SP
            )
        }

        overlayView?.addView(
            textView, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
    }

    // === 表示 ===
    fun show(message: String) {
        if (isShowing) {
            updateMessage(message)
            return
        }

        val params = WindowManager.LayoutParams().apply {
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 1600

            flags = (WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)

            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
            }

            format = PixelFormat.TRANSLUCENT
        }

        try {
            windowManager.addView(overlayView, params)
            textView?.text = message
            isShowing = true
        } catch (e: SecurityException) {
            Log.e("StatusOverlay", "Overlay permission missing: ${e.message}")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // === 非表示 ===
    fun hide() {
        if (isShowing && overlayView != null) {
            try {
                windowManager.removeView(overlayView)
                isShowing = false
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // === メッセージ更新 ===
    fun updateMessage(message: String) {
        textView?.post { textView?.text = message }
    }
}
