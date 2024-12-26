package com.example.judgests

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.firebase.database.FirebaseDatabase
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds
import java.util.ArrayDeque
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class AccelerometerService : Service(), SensorEventListener {
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private lateinit var database: FirebaseDatabase
    private var wakeLock: PowerManager.WakeLock? = null
    private var sessionStartTime: String? = null
    private var sessionStartTimeMillis: Long = 0L  // セッション開始時のシステム時間
    private var recordingStartTime: Long = 0L

    // 現在の加速度値を保持するプロパティ
    private var currentX: Float = 0f
    private var currentY: Float = 0f
    private var currentZ: Float = 0f

    private var isRecording = false
    private var cumulativeDataSize = 0L

    // データポイントを表現するデータクラス
    private data class AccelerometerDataPoint(
        val timestamp: Long,
        val x: Float,
        val y: Float,
        val z: Float
    )

    // バッファリング用のデータ構造
    private val dataBuffer = ArrayDeque<AccelerometerDataPoint>(1000)
    private val storageBuffer = ArrayDeque<AccelerometerDataPoint>(1000)
    private val bufferLock = ReentrantLock()

    // 時間間隔の定数
    private val STORAGE_WRITE_INTERVAL = 5000L
    private val FIREBASE_WRITE_INTERVAL = 5000L

    // タイムスタンプ管理
    private var lastWriteTime = 0L
    private var lastStorageWriteTime = 0L
    private var lastSensorTimestamp: Long = 0
    private var initNanoTime: Long = 0
    private var initSystemTime: Long = 0

    private lateinit var statusOverlay: StatusOverlay
    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        private const val CHANNEL_ID = "AccelerometerServiceChannel"
        private const val NOTIFICATION_ID = 1
        private const val WAKELOCK_TAG = "AccelerometerService::WakeLock"
        private const val SENSOR_SAMPLING_PERIOD_US = 8334
        private const val MAX_REPORT_LATENCY_US = 50000
    }

    private var lastSampleTime = 0L
    private var sampleCount = 0
    private var actualSamplingRate = 0.0

    @SuppressLint("WakelockTimeout")
    override fun onCreate() {
        super.onCreate()

        statusOverlay = StatusOverlay(applicationContext)
        database = FirebaseDatabase.getInstance()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        // WakeLockの初期化
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            WAKELOCK_TAG
        ).apply {
            setReferenceCounted(false)
        }
    }

    private fun setupSensor() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            sensorManager.registerListener(
                this,
                accelerometer,
                SENSOR_SAMPLING_PERIOD_US,
                MAX_REPORT_LATENCY_US
            )
        } else {
            sensorManager.registerListener(
                this,
                accelerometer,
                SensorManager.SENSOR_DELAY_FASTEST
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "START_RECORDING" -> startRecording()
            "STOP_RECORDING" -> stopRecording()
        }
        return Service.START_REDELIVER_INTENT
    }

    private fun startRecording() {
        if (!isRecording) {
            isRecording = true

            // セッション開始時の正確な時間を記録（JSTで取得）
            val calendar = Calendar.getInstance(TimeZone.getTimeZone("Asia/Tokyo"))
            sessionStartTimeMillis = calendar.timeInMillis
            sessionStartTime = SimpleDateFormat("yyyyMMddHHmmss", Locale.getDefault()).apply {
                timeZone = TimeZone.getTimeZone("Asia/Tokyo")
            }.format(calendar.time)

            // 初期化
            initNanoTime = System.nanoTime()
            initSystemTime = sessionStartTimeMillis
            lastSensorTimestamp = 0
            recordingStartTime = sessionStartTimeMillis
            lastWriteTime = sessionStartTimeMillis
            lastStorageWriteTime = sessionStartTimeMillis
            cumulativeDataSize = 0L

            // ファイルのヘッダーを作成
            initializeStorageFile()

            // フォアグラウンドサービス開始
            val notification = createNotification()
            startForeground(NOTIFICATION_ID, notification)

            // メール通知を送信
            sessionStartTime?.let { sessionId ->
                MeasurementNotifier(applicationContext).sendStartNotification(sessionId)
            }

            // WakeLock取得
            wakeLock?.apply {
                if (!isHeld) {
                    acquire()
                }
            }

            // センサー登録
            setupSensor()

            // 初期メッセージを表示
            statusOverlay.show("📊 ACC計測開始")
        }
    }

    private fun stopRecording() {
        if (isRecording) {
            isRecording = false

            // 残りのデータを保存
            saveBufferToStorage()
            saveBufferToFirebase()

            // センサー登録解除
            sensorManager.unregisterListener(this)

            // WakeLock解放
            wakeLock?.apply {
                if (isHeld) {
                    release()
                }
            }

            // 停止メッセージを表示
            statusOverlay.updateMessage("📊 ACC計測停止")

            // 2秒後にオーバーレイを非表示
            mainHandler.postDelayed({
                statusOverlay.hide()
            }, 2000)

            // フォアグラウンドサービス停止
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                stopForeground(STOP_FOREGROUND_DETACH)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }

            stopSelf()
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER && isRecording) {
            val nanoTime = event.timestamp
            val elapsedNanos = nanoTime - initNanoTime

            // セッション開始時からの経過時間を計算して現在時刻を求める
            val currentTime = sessionStartTimeMillis + (elapsedNanos / 1_000_000)

            sampleCount++
            lastSensorTimestamp = nanoTime

            val dataPoint = AccelerometerDataPoint(
                timestamp = currentTime,
                x = event.values[0],
                y = event.values[1],
                z = event.values[2]
            )

            bufferLock.withLock {
                dataBuffer.addLast(dataPoint)
                storageBuffer.addLast(dataPoint)
            }

            // 現在値の更新
            currentX = event.values[0]
            currentY = event.values[1]
            currentZ = event.values[2]

            // 書き込みチェック
            if (currentTime - lastStorageWriteTime >= STORAGE_WRITE_INTERVAL) {
                saveBufferToStorage()
                lastStorageWriteTime = currentTime
            }

            if (currentTime - lastWriteTime >= FIREBASE_WRITE_INTERVAL) {
                saveBufferToFirebase()
                lastWriteTime = currentTime
            }

            sendAccelerometerData(currentX, currentY, currentZ)
        }
    }

    private fun initializeStorageFile() {
        try {
            val file = File(getExternalFilesDir(null), "${sessionStartTime}_accelerometer.csv")
            if (!file.exists()) {
                file.createNewFile()
                file.writeText("Timestamp(ms),X,Y,Z\n")
            }
        } catch (e: Exception) {
            Log.e("Storage", "Error initializing file", e)
        }
    }

    private fun saveBufferToStorage() {
        var dataToWrite: List<AccelerometerDataPoint>

        bufferLock.withLock {
            if (storageBuffer.isEmpty()) return
            dataToWrite = storageBuffer.toList()
            storageBuffer.clear()
        }

        try {
            val file = File(getExternalFilesDir(null), "${sessionStartTime}_accelerometer.csv")
            val csvLines = dataToWrite.joinToString("\n") { data ->
                "${data.timestamp},${data.x},${data.y},${data.z}"
            }
            file.appendText("$csvLines\n")

            cumulativeDataSize += csvLines.length
        } catch (e: Exception) {
            Log.e("Storage", "Error writing to file", e)
        }
    }

    @SuppressLint("DefaultLocale")
    private fun saveBufferToFirebase() {
        var dataToSend: List<AccelerometerDataPoint>

        bufferLock.withLock {
            if (dataBuffer.isEmpty()) return
            dataToSend = dataBuffer.toList()
            dataBuffer.clear()
        }

        val currentTime = System.currentTimeMillis()
        val batchData = dataToSend.joinToString("\n") { data ->
            "${data.timestamp},${data.x},${data.y},${data.z}"
        }

        val reference = database.getReference("SmartPhone_data")
            .child(sessionStartTime!!)
            .child(currentTime.toString())

        actualSamplingRate = (sampleCount / 5.0)
        sampleCount = 0

        reference.setValue(batchData)
            .addOnSuccessListener {
                val elapsedTime = currentTime - recordingStartTime
                val formattedElapsedTime = formatElapsedTime(elapsedTime)
                val dataSizeMB = String.format("%.2f", cumulativeDataSize / (1024.0 * 1024.0))
                val message = """
                📊 ACC計測中
                ⏱ 経過時間: $formattedElapsedTime
                💾 累計データ: ${dataSizeMB}MB
                📊 sampling: ${String.format("%.1f", actualSamplingRate)}Hz
                """.trimIndent()

                statusOverlay.updateMessage(message)
                Log.d("Firebase", "Saved ${dataToSend.size} samples at: $currentTime")
            }
            .addOnFailureListener { e ->
                Log.e("Firebase", "Error saving data", e)
                statusOverlay.updateMessage("❌ データ送信エラー")
            }
    }

    private fun formatElapsedTime(elapsedMillis: Long): String {
        val duration = elapsedMillis.milliseconds
        val hours = duration.inWholeHours
        val minutes = duration.inWholeMinutes % 60
        val seconds = duration.inWholeSeconds % 60
        return String.format("%02d:%02d:%02d", hours, minutes, seconds)
    }

    private fun createNotification(): Notification {
        createNotificationChannel()

        val notificationIntent = packageManager
            .getLaunchIntentForPackage(packageName)
            ?.apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            ?: Intent().apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("加速度センサー記録中")
            .setContentText("データを記録しています")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Accelerometer Service"
            val descriptionText = "Records accelerometer data"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun sendAccelerometerData(x: Float, y: Float, z: Float) {
        val intent = Intent("ACCELEROMETER_DATA").apply {
            putExtra("X", x)
            putExtra("Y", y)
            putExtra("Z", z)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // 精度変更時の処理が必要な場合はここに実装
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        if (isRecording) {
            saveBufferToStorage()
            saveBufferToFirebase()
        }

        sensorManager.unregisterListener(this)
        wakeLock?.apply {
            if (isHeld) {
                release()
            }
        }

        // メインハンドラーの後続処理をキャンセル
        mainHandler.removeCallbacksAndMessages(null)

        // オーバーレイを非表示
        statusOverlay.hide()
    }
}
