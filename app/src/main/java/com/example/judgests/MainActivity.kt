package com.example.judgests

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.Description
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.components.YAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet

class MainActivity : AppCompatActivity() {

    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var chart: LineChart
    private lateinit var accelerometerDataReceiver: BroadcastReceiver

    // 最大表示データ数を300に減らしつつ、間引きを増やして実質的な表示時間は維持
    private val MAX_ENTRIES = 5000  // 500から300に減らす
    private var xEntries = ArrayList<Entry>(MAX_ENTRIES)  // 初期容量を指定
    private var yEntries = ArrayList<Entry>(MAX_ENTRIES)  // パフォーマンス改善
    private var zEntries = ArrayList<Entry>(MAX_ENTRIES)  // GCの削減
    private var timeCounter = 1000f

    // データの間引きを増やす
    private var updateCounter = 0
    private val UPDATE_INTERVAL = 10  // 5から10に変更（表示更新頻度を半分に）

    // Toast用のカスタムクラスで再利用（メモリ効率化）
    private val toastHelper by lazy {
        object {
            private var currentToast: Toast? = null

            fun showToast(context: Context, message: String) {
                currentToast?.cancel()
                currentToast = Toast.makeText(context, message, Toast.LENGTH_SHORT)
                currentToast?.show()
            }
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.all { it.value }) {
            startRecordingService()
        } else {
            toastHelper.showToast(this, "必要な権限が許可されていません")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize UI elements
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        chart = findViewById(R.id.accelerometerChart)

        setupChart()

        startButton.setOnClickListener {
            startRecording()
        }

        stopButton.setOnClickListener {
            stopRecording()
        }

        stopButton.isEnabled = false

        accelerometerDataReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                intent?.let {
                    val x = it.getFloatExtra("X", 0f)
                    val y = it.getFloatExtra("Y", 0f)
                    val z = it.getFloatExtra("Z", 0f)
                    // updateAccelerometerData(x, y, z) を削除

                    // グラフは間引いて更新
                    updateCounter++
                    if (updateCounter >= UPDATE_INTERVAL) {
                        updateChart(x, y, z)
                        updateCounter = 0
                    }
                }
            }
        }
    }

    private fun setupChart() {
        // チャートの基本設定
        chart.apply {
            setTouchEnabled(true)
            isDragEnabled = true
            setScaleEnabled(true)
            setPinchZoom(true)
            setDrawGridBackground(false)

            // 初期表示時から最大幅で表示するための設定
            setVisibleXRangeMaximum(MAX_ENTRIES.toFloat())
            setVisibleXRangeMinimum(MAX_ENTRIES.toFloat())  // これを追加

            // X軸の初期表示範囲を設定
            moveViewToX(0f)  // これを追加

            // 説明テキストを非表示
            description = Description().apply {
                text = ""
            }

            // X軸の設定
            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM
                textColor = Color.BLACK
                setDrawGridLines(true)
                setAvoidFirstLastClipping(true)

                // 目盛りの間隔を1000単位に設定
                granularity = 1000f
                // 最小値を1000に設定
                axisMinimum = 1000f
            }

            // 初期表示範囲の設定
            setVisibleXRangeMaximum(MAX_ENTRIES.toFloat())
            setVisibleXRangeMinimum(MAX_ENTRIES.toFloat())
            moveViewToX(1000f)  // 初期位置を1000からスタート


            // 左Y軸の設定
            axisLeft.apply {
                textColor = Color.BLACK
                setDrawGridLines(true)
                axisMaximum = 15f
                axisMinimum = -15f
            }

            // 右Y軸を非表示
            axisRight.isEnabled = false

            // 初期データセットの作成
            val dataSets = ArrayList<ILineDataSet>()

            // X軸データセット（赤）
            val xDataSet = LineDataSet(ArrayList(), "X-axis").apply {
                color = Color.RED
                setDrawCircles(false)
                setDrawValues(false)
                lineWidth = 2f
            }

            // Y軸データセット（緑）
            val yDataSet = LineDataSet(ArrayList(), "Y-axis").apply {
                color = Color.GREEN
                setDrawCircles(false)
                setDrawValues(false)
                lineWidth = 2f
            }

            // Z軸データセット（青）
            val zDataSet = LineDataSet(ArrayList(), "Z-axis").apply {
                color = Color.BLUE
                setDrawCircles(false)
                setDrawValues(false)
                lineWidth = 2f
            }

            dataSets.add(xDataSet)
            dataSets.add(yDataSet)
            dataSets.add(zDataSet)

            // データの設定
            data = LineData(dataSets)
        }
    }

    private fun updateChart(x: Float, y: Float, z: Float) {
        timeCounter += UPDATE_INTERVAL.toFloat()  // 時間軸も間引きに合わせて調整

        // エントリーの追加
        xEntries.add(Entry(timeCounter, x))
        yEntries.add(Entry(timeCounter, y))
        zEntries.add(Entry(timeCounter, z))

        // 最大数を超えた場合、古いデータを削除
        if (xEntries.size > MAX_ENTRIES) {
            xEntries.removeAt(0)
            yEntries.removeAt(0)
            zEntries.removeAt(0)
        }

        // データセットの更新
        val dataSets = ArrayList<ILineDataSet>()

        dataSets.add(LineDataSet(xEntries, "X-axis").apply {
            color = Color.RED
            setDrawCircles(false)
            setDrawValues(false)
            lineWidth = 2f
            mode = LineDataSet.Mode.CUBIC_BEZIER  // 線を滑らかに
        })

        dataSets.add(LineDataSet(yEntries, "Y-axis").apply {
            color = Color.GREEN
            setDrawCircles(false)
            setDrawValues(false)
            lineWidth = 2f
            mode = LineDataSet.Mode.CUBIC_BEZIER  // 線を滑らかに
        })

        dataSets.add(LineDataSet(zEntries, "Z-axis").apply {
            color = Color.BLUE
            setDrawCircles(false)
            setDrawValues(false)
            lineWidth = 2f
            mode = LineDataSet.Mode.CUBIC_BEZIER  // 線を滑らかに
        })

        chart.data = LineData(dataSets)

        // 表示範囲の更新（常にMAX_ENTRIESの幅を維持）
        chart.setVisibleXRangeMaximum(MAX_ENTRIES.toFloat())
        chart.setVisibleXRangeMinimum(MAX_ENTRIES.toFloat())

        // 最新のデータが見えるようにスクロール（範囲を維持したまま）
        if (timeCounter > MAX_ENTRIES) {
            chart.moveViewToX(timeCounter - MAX_ENTRIES)
        }

        // チャートの再描画
        chart.invalidate()
    }
    override fun onResume() {
        super.onResume()
        LocalBroadcastManager.getInstance(this).registerReceiver(
            accelerometerDataReceiver,
            IntentFilter("ACCELEROMETER_DATA")
        )
    }

    override fun onPause() {
        super.onPause()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(accelerometerDataReceiver)
    }

    private fun startRecording() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permissions = mutableListOf(
                Manifest.permission.POST_NOTIFICATIONS,
                Manifest.permission.FOREGROUND_SERVICE
            ).apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    add(Manifest.permission.FOREGROUND_SERVICE_DATA_SYNC)
                }
            }.toTypedArray()

            // 必要な権限が付与されているかチェック
            val permissionsToRequest = permissions.filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }.toTypedArray()

            if (permissionsToRequest.isEmpty()) {
                startRecordingService()
            } else {
                requestPermissionLauncher.launch(permissionsToRequest)
            }
        } else {
            startRecordingService()
        }
    }

    private fun startRecordingService() {
        val serviceIntent = Intent(this, AccelerometerService::class.java).apply {
            action = "START_RECORDING"
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        startButton.isEnabled = false
        stopButton.isEnabled = true
    }

    private fun stopRecording() {
        val serviceIntent = Intent(this, AccelerometerService::class.java).apply {
            action = "STOP_RECORDING"
        }
        stopService(serviceIntent)
        startButton.isEnabled = true
        stopButton.isEnabled = false
    }
}