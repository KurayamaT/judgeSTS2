package com.example.judgests

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.*

class OrientationView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var roll = 0f
    private var pitch = 0f
    private var yaw = 0f

    private var isFront = true

    private val phonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        color = Color.WHITE
    }
    private val screenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val cameraPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.BLACK
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 36f
    }

    private val camera = Camera()
    private val matrix3D = Matrix()

    fun updateAngles(rollDeg: Float, pitchDeg: Float, yawDeg: Float) {
        roll = -rollDeg
        pitch = pitchDeg
        yaw = -yawDeg
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f
        val cy = height / 2f
        val size = min(width, height) * 0.6f
        val w = size * 0.55f
        val h = size

        canvas.save()
        canvas.translate(cx, cy)

        // ====== 3D回転 ======
        camera.save()
        camera.rotateX(-90f)
        camera.rotateZ(yaw)
        camera.rotateX(pitch)
        camera.rotateY(roll)
        camera.getMatrix(matrix3D)
        camera.restore()
        canvas.concat(matrix3D)

        // ====== 表裏判定（完全版） ======
        val zRad = Math.toRadians(yaw.toDouble())
        val pRad = Math.toRadians(pitch.toDouble())
        val rRad = Math.toRadians(roll.toDouble())

        // 回転行列 (Yaw→Pitch→Roll)
        val cyaw = cos(zRad)
        val syaw = sin(zRad)
        val cpitch = cos(pRad)
        val spitch = sin(pRad)
        val croll = cos(rRad)
        val sroll = sin(rRad)

        // 端末Z軸(0,0,1)のワールド空間での向き
        val zVecX = syaw * cpitch
        val zVecY = -spitch
        val zVecZ = cyaw * cpitch

        // AndroidのCameraは -Z が手前（画面側）
        isFront = zVecZ < 0

        // ====== 背面 ======
        phonePaint.color = if (isFront) Color.rgb(20, 100, 200) else Color.rgb(10, 60, 120)
        val bodyRect = RectF(-w / 2f, -h / 2f, w / 2f, h / 2f)
        canvas.drawRoundRect(bodyRect, w * 0.08f, w * 0.08f, phonePaint)
        canvas.drawRoundRect(bodyRect, w * 0.08f, w * 0.08f, framePaint)

        // ====== 表のみ：白画面＋黒丸カメラ ======
        if (isFront) {
            val screenInset = w * 0.08f
            val screenRect = RectF(
                -w / 2f + screenInset,
                -h / 2f + screenInset * 1.8f,
                w / 2f - screenInset,
                h / 2f - screenInset
            )
            canvas.drawRoundRect(screenRect, 12f, 12f, screenPaint)

            // カメラ上部
            val camRadius = w * 0.05f
            canvas.drawCircle(0f, -h / 2f + screenInset * 1.5f, camRadius, cameraPaint)
        }

        // ====== 軸描画 ======
        val axisLen = w * 0.4f
        val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 6f }

        axisPaint.color = Color.RED
        canvas.drawLine(0f, 0f, axisLen, 0f, axisPaint) // X

        axisPaint.color = Color.GREEN
        canvas.drawLine(0f, 0f, 0f, -axisLen, axisPaint) // Y

        axisPaint.color = Color.BLUE
        canvas.drawLine(0f, 0f, 0f, -axisLen * 0.6f, axisPaint) // Z

        canvas.restore()

        // ====== テキスト表示 ======
        val side = if (isFront) "Front (表: 画面上)" else "Back (裏: 背面)"
        val labels = "Yaw: ${"%.1f".format(yaw)}°, Pitch: ${"%.1f".format(pitch)}°, Roll: ${"%.1f".format(roll)}°"
        canvas.drawText(side, 24f, height - 72f, textPaint)
        canvas.drawText(labels, 24f, height - 36f, textPaint)
    }
}
