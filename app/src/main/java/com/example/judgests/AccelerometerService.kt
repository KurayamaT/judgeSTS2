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
import androidx.core.app.NotificationCompat
import com.google.firebase.database.FirebaseDatabase
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class AccelerometerService : Service(), SensorEventListener {
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private lateinit var database: FirebaseDatabase
    private var wakeLock: PowerManager.WakeLock? = null
    private var sessionStartTime: String? = null

    private var isRecording = false
    private val dataBuffer = StringBuilder(3000)  // 余裕を持ったサイズ
    private var lastWriteTime = 0L

    companion object {
        private const val CHANNEL_ID = "AccelerometerServiceChannel"
        private const val NOTIFICATION_ID = 1
        private const val WAKELOCK_TAG = "AccelerometerService::WakeLock"
        private const val WRITE_INTERVAL_MS = 5000 // 5秒間隔で送信
    }

    override fun onCreate() {
        super.onCreate()
        database = FirebaseDatabase.getInstance()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createNotificationChannel()
        }

        // WakeLockの初期化
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
            sessionStartTime = SimpleDateFormat("yyyyMMddHHmmss", Locale.getDefault())
                .format(Date())
            lastWriteTime = System.currentTimeMillis()

            val notification = createNotification()
            startForeground(NOTIFICATION_ID, notification)

            wakeLock?.apply {
                if (!isHeld) {
                    acquire()
                }
            }

            // 200Hzでセンサーを登録
            sensorManager.registerListener(
                this,
                accelerometer,
                5000  // 200Hz (5000マイクロ秒)
            )

            Log.d("Recording", "Started new session at: $sessionStartTime")
        }
    }


    private fun stopRecording() {
        if (isRecording) {
            isRecording = false
            sensorManager.unregisterListener(this)

            // WakeLockを解放
            wakeLock?.apply {
                if (isHeld) {
                    release()
                }
            }

            // APIレベルに応じて適切なメソッドを使用
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Android 13 (API 33)以上
                stopForeground(STOP_FOREGROUND_DETACH)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                // Android 7.0 (API 24)以上
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                // それ以前のバージョン
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            stopSelf()
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER && isRecording) {
            val currentTime = System.currentTimeMillis()
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]

            // 内部ストレージにも保存
            saveToInternalStorage(currentTime, x, y, z)

            // データをバッファに追加
            dataBuffer.append(currentTime)
                .append(",")
                .append(x)
                .append(",")
                .append(y)
                .append(",")
                .append(z)
                .append("\n")

            // 5秒経過したらデータを送信
            if (currentTime - lastWriteTime >= WRITE_INTERVAL_MS) {
                saveBufferToFirebase()
                lastWriteTime = currentTime
            }
        }
    }




    private fun saveBufferToFirebase() {
        if (dataBuffer.isEmpty() || sessionStartTime == null) return

        val reference = database.getReference("SmartPhone_data")
            .child(sessionStartTime!!)
            .child(lastWriteTime.toString())

        reference.setValue(dataBuffer.toString())
            .addOnSuccessListener {
                Log.d("Firebase", "Saved 5-second batch data at: $lastWriteTime")
                dataBuffer.clear()
            }
            .addOnFailureListener { e ->
                Log.e("Firebase", "Error saving data", e)
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
        // 最後のバッファデータを保存
        if (dataBuffer.isNotEmpty()) {
            saveBufferToFirebase()
        }
        sensorManager.unregisterListener(this)
        wakeLock?.apply {
            if (isHeld) {
                release()
            }
        }
    }
}