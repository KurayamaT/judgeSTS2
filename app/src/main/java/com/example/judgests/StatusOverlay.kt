package com.example.judgests

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

class StatusOverlay(private val context: Context) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: LinearLayout? = null
    private var textView: TextView? = null
    private var isShowing = false

    init {
        createOverlayView()
    }

    private fun createOverlayView() {
        // LinearLayoutの作成
        overlayView = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(android.R.drawable.dialog_holo_dark_frame)
            setPadding(24, 12, 24, 12)
        }

        // TextViewの作成
        textView = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            maxLines = 4
            ellipsize = TextUtils.TruncateAt.END

            // テキストサイズを画面の大きさに応じて調整
            setAutoSizeTextTypeUniformWithConfiguration(
                8, // 最小テキストサイズ
                14, // 最大テキストサイズ
                1, // サイズステップ
                TypedValue.COMPLEX_UNIT_SP
            )
        }

        // TextViewをLinearLayoutに追加
        overlayView?.addView(textView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
    }

    fun show(message: String) {
        if (!isShowing) {
            // オーバーレイパラメータの設定
            val params = WindowManager.LayoutParams().apply {
                width = WindowManager.LayoutParams.WRAP_CONTENT
                height = WindowManager.LayoutParams.WRAP_CONTENT
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = 1600  // 上端からの距離

                // Android バージョンに応じたフラグの設定
                flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

                // オーバーレイウィンドウのタイプ設定
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
                isShowing = true
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // メッセージの更新
        textView?.post {
            textView?.text = message
        }
    }

    fun hide() {
        if (isShowing) {
            try {
                windowManager.removeView(overlayView)
                isShowing = false
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun updateMessage(message: String) {
        textView?.post {
            textView?.text = message
        }
    }
}
