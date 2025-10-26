package com.example.judgeSTS_ver2

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
import android.view.View
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.Legend
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.utils.ColorTemplate
import kotlin.math.*

class MainActivity : AppCompatActivity() {

    private lateinit var chart: LineChart
    private lateinit var thighAngleView: ThighAngleView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnToggleView: Button

    private var timeIndex = 0f
    private val maxVisiblePoints = 200
    private var showAngle = false  // false=加速度グラフ、true=角度表示

    // 角度平滑化用バッファ（約0.5秒分 = 60サンプル @120Hz想定）
    private val angleBuffer = ArrayDeque<Float>(60)
    private val bufferSize = 60

    // 指数移動平均用（さらに滑らかに）
    private var emaAngle: Float? = null
    private val emaAlpha = 0.15f  // 小さいほど滑らか（0.1～0.3推奨）

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        requestPermissionsIfNeeded()

        chart = findViewById(R.id.chartIMU)
        thighAngleView = findViewById(R.id.thighAngleView)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        btnToggleView = findViewById(R.id.btnToggleView)

        setupChart()
        resetChart()
        updateViewMode()

        // ==== 計測開始 ====
        btnStart.setOnClickListener {
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
            startService(intent)
        }

        // ==== 計測停止 ====
        btnStop.setOnClickListener {
            val intent = Intent(this, IMUService::class.java).apply {
                action = "STOP_RECORDING"
            }
            startService(intent)
        }

        // ==== 表示切り替え ====
        btnToggleView.setOnClickListener {
            showAngle = !showAngle
            updateViewMode()
        }

        LocalBroadcastManager.getInstance(this)
            .registerReceiver(imuReceiver, IntentFilter("IMU_DATA"))
    }

    private fun updateViewMode() {
        if (showAngle) {
            // 角度表示モード
            chart.visibility = View.GONE
            thighAngleView.visibility = View.VISIBLE
            btnToggleView.text = "加速度"
        } else {
            // 加速度グラフモード
            chart.visibility = View.VISIBLE
            thighAngleView.visibility = View.GONE
            btnToggleView.text = "角度"
        }
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
        chart.setAutoScaleMinMaxEnabled(false)
        chart.xAxis.apply {
            setDrawGridLines(false)
            granularity = 1f
            labelRotationAngle = 0f
            textSize = 10f
        }
    }

    private fun resetChart() {
        val data = LineData()
        val labels = arrayOf("X", "Y", "Z")
        val colors = intArrayOf(
            ColorTemplate.COLORFUL_COLORS[0],
            ColorTemplate.COLORFUL_COLORS[1],
            ColorTemplate.COLORFUL_COLORS[2]
        )

        for (i in 0..2) {
            val set = LineDataSet(null, "加速度 (m/s²)-${labels[i]}")
            set.lineWidth = 2f
            set.setDrawCircles(false)
            set.color = colors[i]
            data.addDataSet(set)
        }
        chart.data = data

        // Y軸スケール固定
        chart.axisLeft.axisMinimum = -20f
        chart.axisLeft.axisMaximum = 20f
        chart.axisRight.isEnabled = false
        chart.invalidate()
    }

    // === IMUデータ受信 ===
    private val imuReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val ax = intent?.getFloatExtra("AX", 0f) ?: 0f
            val ay = intent?.getFloatExtra("AY", 0f) ?: 0f
            val az = intent?.getFloatExtra("AZ", 0f) ?: 0f

            if (showAngle) {
                // 角度計算（GaitAnalyzerと同じ方式）
                val absAx = abs(ax)
                val absAy = abs(ay)
                val absAz = abs(az)
                val denom = sqrt(absAx*absAx + absAz*absAz).coerceAtLeast(1e-9f)
                val angle = abs(Math.toDegrees(atan((absAy/denom).toDouble()))).toFloat()

                // 移動平均で平滑化
                angleBuffer.addLast(angle)
                if (angleBuffer.size > bufferSize) {
                    angleBuffer.removeFirst()
                }
                val avgAngle = angleBuffer.average().toFloat()

                // 指数移動平均でさらに滑らかに
                emaAngle = if (emaAngle == null) {
                    avgAngle
                } else {
                    emaAlpha * avgAngle + (1 - emaAlpha) * emaAngle!!
                }

                thighAngleView.updateAngle(emaAngle!!)
            } else {
                // グラフ更新
                val data = chart.data ?: return
                if (data.dataSetCount == 0) return

                timeIndex += 0.2f
                updateChartData(floatArrayOf(ax, ay, az), data)

                chart.notifyDataSetChanged()
                chart.setVisibleXRangeMaximum(maxVisiblePoints.toFloat() * 5)
                chart.moveViewToX(timeIndex)
            }
        }
    }

    private fun updateChartData(values: FloatArray, data: LineData) {
        for (i in values.indices) {
            data.addEntry(Entry(timeIndex, values[i]), i)
            data.notifyDataChanged()
        }
    }

    private fun requestPermissionsIfNeeded() {
        val permissionsToRequest = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }

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