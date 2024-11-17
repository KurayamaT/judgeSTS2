package com.example.judgests

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
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
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.firebase.database.FirebaseDatabase
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class AccelerometerService : Service(), SensorEventListener {
    private lateinit var sensorManager: SensorManager
    private lateinit var sensor: Sensor
    private var accelerometer: Sensor? = null
    private lateinit var database: FirebaseDatabase
    private var wakeLock: PowerManager.WakeLock? = null
    private var sessionStartTime: String? = null
    private var recordingStartTime: Long = 0L

    // 現在の加速度値を保持するプロパティを追加
    private var currentX: Float = 0f
    private var currentY: Float = 0f
    private var currentZ: Float = 0f

    private var isRecording = false
    private val dataBuffer = StringBuilder(3000)
    private var lastWriteTime = 0L
    private var cumulativeDataSize = 0L

    // ストレージ関連
    private val storageBuffer = ArrayList<String>(1000)
    private var lastStorageWriteTime = 0L

    private val STORAGE_WRITE_INTERVAL = 1000L
    private val FIREBASE_WRITE_INTERVAL = 10000L  // Firebaseへの送信を10秒間隔に変更

    private var lastSensorTimestamp: Long = 0
    private val sensorTimestamps = ArrayList<Long>(1000) // デバッグ用
    private var initNanoTime: Long = 0
    private var initSystemTime: Long = 0


    companion object {
        private const val CHANNEL_ID = "AccelerometerServiceChannel"
        private const val NOTIFICATION_ID = 1
        private const val WAKELOCK_TAG = "AccelerometerService::WakeLock"
    }


    private val SENSOR_SAMPLING_PERIOD_US = 10000  // 10ms間隔 (100Hz)
    private val maxReportLatencyUs = 50000

    override fun onCreate() {
        super.onCreate()
        database = FirebaseDatabase.getInstance()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        // システム時間とセンサーのタイムスタンプの初期同期
        initNanoTime = System.nanoTime()
        initSystemTime = System.currentTimeMillis()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // サンプリングレートとバッチサイズを明示的に設定
            val samplingPeriodUs = 10000 // 100Hz = 10ms
            val maxReportLatencyUs = samplingPeriodUs / 2 // バッチ遅延を半分に設定

            sensorManager.registerListener(
                this,
                accelerometer,
                samplingPeriodUs,
                maxReportLatencyUs
            )

            // FIFOサイズの確認（デバッグ用）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val fifoSize = accelerometer?.fifoMaxEventCount ?: 0
                Log.d("Sensor", "FIFO size: $fifoSize events")
            }
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
        return START_STICKY
    }

    private fun startRecording() {
        if (!isRecording) {
            isRecording = true

            initNanoTime = System.nanoTime()
            initSystemTime = System.currentTimeMillis()
            lastSensorTimestamp = 0
            sensorTimestamps.clear()

            recordingStartTime = System.currentTimeMillis()
            sessionStartTime = SimpleDateFormat("yyyyMMddHHmmss", Locale.getDefault())
                .format(Date())
            lastWriteTime = System.currentTimeMillis()
            lastStorageWriteTime = System.currentTimeMillis()  // 追加
            cumulativeDataSize = 0L



            // ファイルのヘッダーを作成
            initializeStorageFile()

            val notification = createNotification()
            startForeground(NOTIFICATION_ID, notification)

            wakeLock?.apply {
                if (!isHeld) {
                    acquire()
                }
            }

            // 記録開始時にもセンサーを再登録
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                sensorManager.registerListener(
                    this,
                    accelerometer,
                    SENSOR_SAMPLING_PERIOD_US,
                    maxReportLatencyUs
                )
            } else {
                sensorManager.registerListener(
                    this,
                    accelerometer,
                    SensorManager.SENSOR_DELAY_GAME
                )
            }


            Log.d("Recording", "Started new session at: $sessionStartTime")
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

    private fun stopRecording() {
        if (isRecording) {
            isRecording = false
            sensorManager.unregisterListener(this)

            wakeLock?.apply {
                if (isHeld) {
                    release()
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                stopForeground(STOP_FOREGROUND_DETACH)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            stopSelf()
        }
    }


    private var lastTimestamp: Long? = null

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER && isRecording) {
            val nanoTime = event.timestamp

            // 正確なシステム時間に変換
            val elapsedNanos = nanoTime - initNanoTime
            val currentTime = initSystemTime + (elapsedNanos / 1_000_000)

            // サンプリング間隔の監視（デバッグ用）
            if (lastSensorTimestamp != 0L) {
                val interval = nanoTime - lastSensorTimestamp
                sensorTimestamps.add(interval)

                // 100サンプルごとに統計情報を出力
                if (sensorTimestamps.size >= 100) {
                    val avgInterval = sensorTimestamps.average() / 1_000_000 // ms単位
                    val minInterval = sensorTimestamps.min() / 1_000_000
                    val maxInterval = sensorTimestamps.max() / 1_000_000
                    Log.d("Sampling", "Avg: ${avgInterval}ms, Min: ${minInterval}ms, Max: ${maxInterval}ms")
                    sensorTimestamps.clear()
                }
            }
            lastSensorTimestamp = nanoTime

            currentX = event.values[0]
            currentY = event.values[1]
            currentZ = event.values[2]

            val dataLine = "$currentTime,$currentX,$currentY,$currentZ\n"

            // バッファリング処理
            dataBuffer.append(dataLine)
            storageBuffer.add(dataLine)

            cumulativeDataSize += dataLine.length

            // ストレージへの書き込みを1秒ごとに実行
            val currentSystemTime = System.currentTimeMillis()
            if (currentSystemTime - lastStorageWriteTime >= STORAGE_WRITE_INTERVAL) {
                saveBufferToStorage()
                lastStorageWriteTime = currentSystemTime
            }

            // Firebaseへの送信
            if (currentSystemTime - lastWriteTime >= FIREBASE_WRITE_INTERVAL) {
                saveBufferToFirebase()
                lastWriteTime = currentSystemTime
            }

            // display on Main screen.
            sendAccelerometerData(currentX, currentY, currentZ)
        }
    }





    private fun saveBufferToStorage() {
        if (storageBuffer.isEmpty()) return

        try {
            val file = File(getExternalFilesDir(null), "${sessionStartTime}_accelerometer.csv")
            file.appendText(storageBuffer.joinToString(""))
            storageBuffer.clear()
        } catch (e: Exception) {
            Log.e("Storage", "Error writing to file", e)
        }
    }




    private fun formatElapsedTime(elapsedMillis: Long): String {
        val hours = TimeUnit.MILLISECONDS.toHours(elapsedMillis)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(elapsedMillis) % 60
        val seconds = TimeUnit.MILLISECONDS.toSeconds(elapsedMillis) % 60
        return String.format("%02d:%02d:%02d", hours, minutes, seconds)
    }

    @SuppressLint("DefaultLocale")
    private fun saveBufferToFirebase() {
        if (dataBuffer.isEmpty() || sessionStartTime == null) return

        val currentTime = System.currentTimeMillis()
        val reference = database.getReference("SmartPhone_data")
            .child(sessionStartTime!!)
            .child(currentTime.toString())

        val dataToSend = dataBuffer.toString()
        val elapsedTime = currentTime - recordingStartTime
        val formattedElapsedTime = formatElapsedTime(elapsedTime)
        val dataSizeKB = String.format("%.2f", cumulativeDataSize / 1024.0)

        reference.setValue(dataToSend)
            .addOnSuccessListener {
                val message = """
                📊 ACC計測中
                ⏱ 経過時間: $formattedElapsedTime
                💾 データ: ${dataSizeKB}KB
            """.trimIndent()

                showToast(message)
                Log.d("Firebase", "Saved batch data at: $currentTime")
                dataBuffer.clear()
            }
            .addOnFailureListener { e ->
                Log.e("Firebase", "Error saving data", e)
                showToast("❌ データ送信エラー")
            }
    }

    private fun showToast(message: String) {
        Handler(Looper.getMainLooper()).post {
            // デフォルトのToastを作成
            val toast = Toast.makeText(applicationContext, message, Toast.LENGTH_LONG)

            // カスタムビューを設定する方法を変更
            val layout = LinearLayout(applicationContext).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(40, 25, 40, 25)

                // 背景設定
                background = GradientDrawable().apply {
                    cornerRadius = 25f
                    setColor(Color.argb(230, 33, 33, 33))
                }
            }

            val textView = TextView(applicationContext).apply {
                text = message
                textSize = 16f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setSingleLine(false)
                setLineSpacing(0f, 1.2f)
            }

            layout.addView(textView)

            // Reflectionを使用して非推奨の警告を避けて設定（将来的には非推奨になる可能性を認識）
            try {
                val field = Toast::class.java.getDeclaredField("mNextView")
                field.isAccessible = true
                field.set(toast, layout)
            } catch (e: Exception) {
                Log.e("Toast", "Error setting custom view", e)
            }

            toast.show()
        }
    }


    private fun saveToInternalStorage(timestamp: Long, x: Float, y: Float, z: Float) {
        val file = File(getExternalFilesDir(null), "${sessionStartTime}_accelerometer.csv")
        val data = "$timestamp,$x,$y,$z\n"

        try {
            if (!file.exists()) {
                file.createNewFile()
                file.writeText("Timestamp(ms),X,Y,Z\n")
            }
            file.appendText(data)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Accelerometer Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            channel.description = "加速度センサーの記録を行っています"
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
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
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not used
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        // 残っているデータを保存
        if (dataBuffer.isNotEmpty()) {
            saveBufferToFirebase()
        }
        // ストレージバッファの残りを保存
        if (storageBuffer.isNotEmpty()) {
            saveBufferToStorage()
        }
        sensorManager.unregisterListener(this)
        wakeLock?.apply {
            if (isHeld) {
                release()
            }
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

    private fun checkDeviceStatus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            val isPowerSaveMode = powerManager.isPowerSaveMode
            Log.d("Device", "Power save mode: $isPowerSaveMode")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                val cpuFreq = File("/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq")
                if (cpuFreq.exists()) {
                    Log.d("CPU", "Current frequency: ${cpuFreq.readText().trim()}")
                }
            } catch (e: Exception) {
                Log.e("CPU", "Error reading CPU frequency", e)
            }
        }
    }
}