package com.example.judgests

import android.app.*
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.google.firebase.database.FirebaseDatabase
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class AccelerometerService : Service(), SensorEventListener {
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private lateinit var database: FirebaseDatabase
    private var wakeLock: PowerManager.WakeLock? = null
    private var sessionStartTime: String? = null
    private var recordingStartTime: Long = 0L

    //  data size in each sending to firebase.
    private val MAX_BUFFER_SIZE = 1000  // 例: 1000サンプルごとに送信


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

    override fun onCreate() {
        super.onCreate()
        database = FirebaseDatabase.getInstance()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createNotificationChannel()
        }

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            WAKELOCK_TAG
        )
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

            sensorManager.registerListener(
                this,
                accelerometer,
                10000  // 既存の設定を維持
            )

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

        // 最新の加速度値の大きさを計算
        val accelerationMagnitude = String.format("%.2f",
            Math.sqrt((currentX * currentX + currentY * currentY + currentZ * currentZ).toDouble()))

        reference.setValue(dataToSend)
            .addOnSuccessListener {
                showToast("ACC計測中\n$formattedElapsedTime ${dataSizeKB}KB\n加速度: ${accelerationMagnitude}G")
                Log.d("Firebase", "Saved batch data at: $currentTime")
                dataBuffer.clear()
            }
            .addOnFailureListener { e ->
                Log.e("Firebase", "Error saving data", e)
                showToast("データ送信エラー")
            }
    }

    private fun showToast(message: String) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
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
}