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

        // SensorManagerの初期化
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager

        // センサーを取得
        sensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)!!


        // Android 8.0 (API 26) 以降では、より詳細なセンサー設定が可能
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            sensorManager.registerListener(
                this,
                sensor,
                SENSOR_SAMPLING_PERIOD_US, // サンプリング周期を設定
                maxReportLatencyUs         // レイテンシー設定で負荷軽減
            )
        } else {
            // 古いバージョンでは近似値を設定
            sensorManager.registerListener(
                this,
                accelerometer,
                SensorManager.SENSOR_DELAY_GAME  // 約20ms (50Hz) - FASTEST(0ms)より適切
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
            val currentTime = System.currentTimeMillis()
            currentX = event.values[0]
            currentY = event.values[1]
            currentZ = event.values[2]

            // データラインを作成
            val dataLine = "$currentTime,$currentX,$currentY,$currentZ\n"

            // Firebaseバッファに追加
            dataBuffer.append(dataLine)

            // ストレージバッファに追加
            storageBuffer.add(dataLine)

            cumulativeDataSize += dataLine.length

            // ストレージへの書き込みを1秒ごとに実行
            if (currentTime - lastStorageWriteTime >= STORAGE_WRITE_INTERVAL) {
                saveBufferToStorage()
                lastStorageWriteTime = currentTime
            }

            // Firebaseへの送信（30秒間隔）
            if (currentTime - lastWriteTime >= FIREBASE_WRITE_INTERVAL) {
                saveBufferToFirebase()
                lastWriteTime = currentTime
            }

            // display on Main screen.
            sendAccelerometerData(currentX, currentY, currentZ)

            event?.let {
                val timestampInMs = event.timestamp / 1_000_000  // ナノ秒からミリ秒に変換
                lastTimestamp?.let { last ->
                    val interval = timestampInMs - last
                    Log.d("SensorSampling", "Interval: ${interval}ms")
                }
                lastTimestamp = timestampInMs
            }
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
            Toast(applicationContext).apply {
                duration = Toast.LENGTH_LONG
                // 位置を下側に調整（yOffsetを正の値に設定）
                setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, 150)  // 150dpを下から空ける

                // カスタムレイアウトの作成
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
                view = layout
            }.show()
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
}