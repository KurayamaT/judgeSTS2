package com.example.judgeSTS_ver2

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.*

class ThighAngleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var currentAngle = 0f  // 0°=座位、90°=立位

    private val paintThigh = Paint().apply {
        color = Color.parseColor("#2196F3")  // 青色
        strokeWidth = 30f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
    }

    private val paintJoint = Paint().apply {
        color = Color.parseColor("#FF9800")  // オレンジ色（関節）
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val paintGround = Paint().apply {
        color = Color.GRAY
        strokeWidth = 4f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val paintText = Paint().apply {
        color = Color.BLACK
        textSize = 60f
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }

    private val paintGrid = Paint().apply {
        color = Color.LTGRAY
        strokeWidth = 2f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    fun updateAngle(angle: Float) {
        currentAngle = angle.coerceIn(0f, 90f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val centerX = width / 2f
        val centerY = height / 2f
        val thighLength = min(width, height) * 0.35f

        // 背景グリッド（角度目盛り）
        drawAngleGrid(canvas, centerX, centerY, thighLength)

        // 地面（水平線）
        canvas.drawLine(
            centerX - thighLength * 1.5f,
            centerY,
            centerX + thighLength * 1.5f,
            centerY,
            paintGround
        )

        // 大腿部の棒（角度に応じて回転）
        val angleRad = Math.toRadians(currentAngle.toDouble())
        // 90度回転：sinとcosを入れ替え
        val endX = centerX - (thighLength * cos(angleRad)).toFloat()
        val endY = centerY - (thighLength * sin(angleRad)).toFloat()

        // 大腿部の棒を描画
        canvas.drawLine(centerX, centerY, endX, endY, paintThigh)

        // 関節（股関節）
        canvas.drawCircle(centerX, centerY, 20f, paintJoint)

        // 膝関節
        canvas.drawCircle(endX, endY, 15f, paintJoint)

        // 角度テキスト表示
        canvas.drawText(
            "${currentAngle.toInt()}°",
            centerX,
            centerY + thighLength * 1.3f,
            paintText
        )

        // 状態テキスト
        val statusText = when {
            currentAngle < 30 -> "座位"
            currentAngle > 65 -> "立位"
            else -> "中間"
        }
        paintText.textSize = 40f
        canvas.drawText(
            statusText,
            centerX,
            centerY + thighLength * 1.5f,
            paintText
        )
        paintText.textSize = 60f
    }

    private fun drawAngleGrid(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        // 0°, 30°, 45°, 60°, 90° の目盛り線
        val angles = floatArrayOf(0f, 30f, 45f, 60f, 90f)

        for (angle in angles) {
            val rad = Math.toRadians(angle.toDouble())
            // 90度回転：sinとcosを入れ替え
            val x = cx - (radius * 1.2f * cos(rad)).toFloat()
            val y = cy - (radius * 1.2f * sin(rad)).toFloat()

            canvas.drawLine(cx, cy, x, y, paintGrid)
        }
    }
}