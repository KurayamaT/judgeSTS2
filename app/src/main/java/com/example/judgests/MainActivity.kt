package com.example.judgests

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.Legend
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.utils.ColorTemplate
import java.lang.Math.copySign
import kotlin.math.*

class MainActivity : AppCompatActivity() {

    private lateinit var chart: LineChart
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnAccel: Button
    private lateinit var btnGyro: Button
    private lateinit var btnOrient: Button

    private var currentMode = "ACCEL" // ACCEL / GYRO / ORIENT
    private var timeIndex = 0f
    private val maxVisiblePoints = 200
    private lateinit var orientationView: OrientationView


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        orientationView = findViewById(R.id.orientationView)


        requestPermissionsIfNeeded()   // ✅ これを追加

        chart = findViewById(R.id.chartIMU)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        btnAccel = findViewById(R.id.btnAccel)
        btnGyro = findViewById(R.id.btnGyro)
        btnOrient = findViewById(R.id.btnOrient)

        setupChart()
        resetChart("加速度 (m/s²)", intArrayOf(
            ColorTemplate.COLORFUL_COLORS[0],
            ColorTemplate.COLORFUL_COLORS[1],
            ColorTemplate.COLORFUL_COLORS[2]
        ))

        // ==== 計測開始 ====
        btnStart.setOnClickListener {
            // オーバーレイ許可がなければ設定画面へ誘導して終了（戻ってきたらもう一度押してもらう）
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
                return@setOnClickListener
            }

            val intent = Intent(this, IMUService::class.java).apply {
                action = "START_RECORDING"
            }
            startService(intent) // Foreground化はサービス内で実施
        }

// ==== 計測停止 ====
        btnStop.setOnClickListener {
            val intent = Intent(this, IMUService::class.java).apply {
                action = "STOP_RECORDING"
            }
            startService(intent)
        }

// ==== グラフ切り替え ====
        btnAccel.setOnClickListener {
            currentMode = "ACCEL"
            resetChart("加速度 (m/s²)", intArrayOf(
                ColorTemplate.COLORFUL_COLORS[0],
                ColorTemplate.COLORFUL_COLORS[1],
                ColorTemplate.COLORFUL_COLORS[2]
            ))
        }
        btnGyro.setOnClickListener {
            currentMode = "GYRO"
            resetChart("角速度 (°/s)", intArrayOf(
                ColorTemplate.COLORFUL_COLORS[3],
                ColorTemplate.COLORFUL_COLORS[4],
                ColorTemplate.COLORFUL_COLORS[0]
            ))
        }
        btnOrient.setOnClickListener {
            currentMode = "ORIENT"
            resetChart("姿勢角 (°)", intArrayOf(
                ColorTemplate.COLORFUL_COLORS[1],
                ColorTemplate.COLORFUL_COLORS[2],
                ColorTemplate.COLORFUL_COLORS[3]
            ))
        }


        LocalBroadcastManager.getInstance(this)
            .registerReceiver(imuReceiver, IntentFilter("IMU_DATA"))
    }

    // === チャート初期設定 ===
    private fun setupChart() {
        chart.data = LineData()
        chart.legend.isEnabled = true
        chart.legend.horizontalAlignment = Legend.LegendHorizontalAlignment.CENTER
        chart.description.isEnabled = false
        chart.axisLeft.setDrawGridLines(true)
        chart.axisRight.isEnabled = false
        chart.xAxis.setDrawGridLines(false)
        chart.setAutoScaleMinMaxEnabled(false) // ← これを必ず追加
        chart.xAxis.apply {
            setDrawGridLines(false)
            granularity = 1f
            labelRotationAngle = 0f
            textSize = 10f
        }
        chart.setAutoScaleMinMaxEnabled(false)


    }

    private fun resetChart(label: String, colors: IntArray) {
        val data = LineData()
        val labels = arrayOf("X", "Y", "Z")
        for (i in 0..2) {
            val set = LineDataSet(null, "${label}-${labels[i]}")
            set.lineWidth = 2f
            set.setDrawCircles(false)
            set.color = colors[i % colors.size]
            data.addDataSet(set)
        }
        chart.data = data

        // ✅ Y軸スケール固定（モードごとに範囲設定）
        when (currentMode) {
            "ACCEL" -> {
                chart.axisLeft.axisMinimum = -20f
                chart.axisLeft.axisMaximum = 20f
            }
            "GYRO" -> {
                chart.axisLeft.axisMinimum = -500f
                chart.axisLeft.axisMaximum = 500f
            }
            "ORIENT" -> {
                chart.axisLeft.axisMinimum = -180f
                chart.axisLeft.axisMaximum = 180f
            }
        }

        chart.axisRight.isEnabled = false
        chart.invalidate()
    }


    // === IMUデータ受信 ===
    private val imuReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val ax = intent?.getFloatExtra("AX", 0f) ?: 0f
            val ay = intent?.getFloatExtra("AY", 0f) ?: 0f
            val az = intent?.getFloatExtra("AZ", 0f) ?: 0f
            val gx = intent?.getFloatExtra("GX", 0f) ?: 0f
            val gy = intent?.getFloatExtra("GY", 0f) ?: 0f
            val gz = intent?.getFloatExtra("GZ", 0f) ?: 0f
            val qw = intent?.getFloatExtra("QW", 1f) ?: 1f
            val qx = intent?.getFloatExtra("QX", 0f) ?: 0f
            val qy = intent?.getFloatExtra("QY", 0f) ?: 0f
            val qz = intent?.getFloatExtra("QZ", 0f) ?: 0f

            // === Android標準の安定化関数でクォータニオン→姿勢角 ===
            val rotationMatrix = FloatArray(9)
            val orientationAngles = FloatArray(3)

            // クォータニオン→回転行列に変換
            android.hardware.SensorManager.getRotationMatrixFromVector(
                rotationMatrix,
                floatArrayOf(qx, qy, qz, qw)
            )

            // オイラー角（ラジアン）取得
            android.hardware.SensorManager.getOrientation(rotationMatrix, orientationAngles)

            // 各軸を度数に変換
            var yaw = Math.toDegrees(orientationAngles[0].toDouble())   // 方位（Z軸）
            var pitch = Math.toDegrees(orientationAngles[1].toDouble()) // 前後傾き（Y軸）
            var roll = Math.toDegrees(orientationAngles[2].toDouble())  // 左右傾き（X軸）

            // === 補正 ===
            // Androidは左手座標系なので、自然な動きにするため符号を調整
            pitch = -pitch       // 前後方向を反転（スマホを自分に向けて起こす→正）
            roll = -roll         // 左右を反転（右に傾ける→正）
            yaw = -yaw           // 水平回転を右手系に合わせる

            // === グラフ更新 ===
            val data = chart.data ?: return
            if (data.dataSetCount == 0) return

            timeIndex += 0.2f // サンプリング間隔

            when (currentMode) {
                "ACCEL" -> updateChartData(floatArrayOf(ax, ay, az), data)
                "GYRO" -> updateChartData(floatArrayOf(gx, gy, gz), data)
                "ORIENT" -> updateChartData(
                    floatArrayOf(roll.toFloat(), pitch.toFloat(), yaw.toFloat()),
                    data
                )
            }

            chart.notifyDataSetChanged()
            chart.setVisibleXRangeMaximum(maxVisiblePoints.toFloat() * 5)
            chart.moveViewToX(timeIndex)

            // === 3D描画ビューを更新 ===
            orientationView.updateAngles(
                roll.toFloat(),
                pitch.toFloat(),
                yaw.toFloat()
            )
        }
    }


    private fun updateChartData(values: FloatArray, data: LineData) {
        for (i in values.indices) {
            val set = data.getDataSetByIndex(i)
            data.addEntry(Entry(timeIndex, values[i]), i)
            data.notifyDataChanged()
        }
    }

    // === 実行時権限リクエスト ===
    private fun requestPermissionsIfNeeded() {
        val permissionsToRequest = mutableListOf<String>()

        // ストレージ書き込み（データ保存）
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

        // 通知（Foreground Service用）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        // 権限リクエスト実行
        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsToRequest.toTypedArray(), 101)
        }


        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            }
        }

    }



    override fun onDestroy() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(imuReceiver)
        super.onDestroy()
    }
}
