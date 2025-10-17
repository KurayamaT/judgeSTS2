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
import java.util.*
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration.Companion.milliseconds

class IMUService : Service(), SensorEventListener {
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var gyroscope: Sensor? = null
    private var rotationVector: Sensor? = null

    private lateinit var database: FirebaseDatabase
    private var wakeLock: PowerManager.WakeLock? = null
    private var sessionStartTime: String? = null
    private var sessionStartTimeMillis: Long = 0L

    private var isRecording = false
    private val dataBuffer = ArrayDeque<IMUDataPoint>(1000)
    private val storageBuffer = ArrayDeque<IMUDataPoint>(1000)
    private val bufferLock = ReentrantLock()

    // センサーデータ格納変数
    private var currentAx = 0f; private var currentAy = 0f; private var currentAz = 0f
    private var currentGx = 0f; private var currentGy = 0f; private var currentGz = 0f
    private var currentQw = 1f; private var currentQx = 0f; private var currentQy = 0f; private var currentQz = 0f

    private val STORAGE_WRITE_INTERVAL = 5000L
    private val FIREBASE_WRITE_INTERVAL = 5000L
    private var lastStorageWriteTime = 0L
    private var lastFirebaseWriteTime = 0L

    private lateinit var statusOverlay: StatusOverlay
    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        private const val CHANNEL_ID = "IMUServiceChannel"
        private const val NOTIFICATION_ID = 1
        private const val SENSOR_SAMPLING_PERIOD_US = 8334 // 約120Hz
        private const val MAX_REPORT_LATENCY_US = 50000
    }

    data class IMUDataPoint(
        val timestamp: Long,
        val ax: Float, val ay: Float, val az: Float,
        val gx: Float, val gy: Float, val gz: Float,
        val qw: Float, val qx: Float, val qy: Float, val qz: Float
    )

    @SuppressLint("WakelockTimeout")
    override fun onCreate() {
        super.onCreate()
        statusOverlay = StatusOverlay(applicationContext)
        database = FirebaseDatabase.getInstance()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        rotationVector = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "IMUService::WakeLock").apply {
            setReferenceCounted(false)
        }
    }

    private fun setupSensors() {
        listOfNotNull(accelerometer, gyroscope, rotationVector).forEach { sensor ->
            sensorManager.registerListener(this, sensor, SENSOR_SAMPLING_PERIOD_US, MAX_REPORT_LATENCY_US)
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
        if (isRecording) return
        isRecording = true

        val calendar = Calendar.getInstance(TimeZone.getTimeZone("Asia/Tokyo"))
        sessionStartTimeMillis = calendar.timeInMillis
        sessionStartTime = SimpleDateFormat("yyyyMMddHHmmss", Locale.getDefault()).apply {
            timeZone = TimeZone.getTimeZone("Asia/Tokyo")
        }.format(calendar.time)

        initializeStorageFile()
        startForeground(NOTIFICATION_ID, createNotification())
        wakeLock?.acquire()
        setupSensors()
        statusOverlay.show("📊 IMU計測開始")
    }

    private fun stopRecording() {
        if (!isRecording) return
        isRecording = false
        saveBufferToStorage()
        saveBufferToFirebase()

        sensorManager.unregisterListener(this)
        wakeLock?.release()

        statusOverlay.updateMessage("📊 IMU計測停止")
        mainHandler.postDelayed({ statusOverlay.hide() }, 2000)

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!isRecording) return
        val now = System.currentTimeMillis()

        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                currentAx = event.values[0]; currentAy = event.values[1]; currentAz = event.values[2]
            }
            Sensor.TYPE_GYROSCOPE -> {
                currentGx = event.values[0]; currentGy = event.values[1]; currentGz = event.values[2]
            }
            Sensor.TYPE_ROTATION_VECTOR -> {
                val quat = FloatArray(4)
                SensorManager.getQuaternionFromVector(quat, event.values)
                currentQw = quat[0]; currentQx = quat[1]; currentQy = quat[2]; currentQz = quat[3]
            }
        }

        val data = IMUDataPoint(now, currentAx, currentAy, currentAz, currentGx, currentGy, currentGz, currentQw, currentQx, currentQy, currentQz)
        bufferLock.withLock {
            dataBuffer.addLast(data)
            storageBuffer.addLast(data)
        }

        if (now - lastStorageWriteTime >= STORAGE_WRITE_INTERVAL) {
            saveBufferToStorage()
            lastStorageWriteTime = now
        }
        if (now - lastFirebaseWriteTime >= FIREBASE_WRITE_INTERVAL) {
            saveBufferToFirebase()
            lastFirebaseWriteTime = now
        }

        sendIMUData(
            currentAx, currentAy, currentAz,
            currentGx, currentGy, currentGz,
            currentQw, currentQx, currentQy, currentQz
        )
    }

    private fun initializeStorageFile() {
        val file = File(getExternalFilesDir(null), "${sessionStartTime}_imu.csv")
        if (!file.exists()) {
            file.createNewFile()
            file.writeText("Timestamp(ms),ax,ay,az,gx,gy,gz,qw,qx,qy,qz\n")
        }
    }

    private fun saveBufferToStorage() {
        var list: List<IMUDataPoint>
        bufferLock.withLock {
            if (storageBuffer.isEmpty()) return
            list = storageBuffer.toList(); storageBuffer.clear()
        }
        try {
            val file = File(getExternalFilesDir(null), "${sessionStartTime}_imu.csv")
            val lines = list.joinToString("\n") {
                "${it.timestamp},${it.ax},${it.ay},${it.az},${it.gx},${it.gy},${it.gz},${it.qw},${it.qx},${it.qy},${it.qz}"
            }
            file.appendText("$lines\n")
        } catch (e: Exception) {
            Log.e("IMUService", "File write error", e)
        }
    }

    private fun saveBufferToFirebase() {
        var list: List<IMUDataPoint>
        bufferLock.withLock {
            if (dataBuffer.isEmpty()) return
            list = dataBuffer.toList(); dataBuffer.clear()
        }

        val currentTime = System.currentTimeMillis()
        val batchData = list.joinToString("\n") {
            "${it.timestamp},${it.ax},${it.ay},${it.az},${it.gx},${it.gy},${it.gz},${it.qw},${it.qx},${it.qy},${it.qz}"
        }

        val ref = database.getReference("SmartPhone_data_IMU").child(sessionStartTime!!).child(currentTime.toString())
        ref.setValue(batchData).addOnSuccessListener {
            val elapsed = (currentTime - sessionStartTimeMillis).milliseconds
            val msg = "⏱ ${String.format("%02d:%02d:%02d", elapsed.inWholeHours, elapsed.inWholeMinutes % 60, elapsed.inWholeSeconds % 60)} 計測中"
            statusOverlay.updateMessage(msg)
        }.addOnFailureListener {
            statusOverlay.updateMessage("❌ 送信エラー")
        }
    }

    private fun sendIMUData(
        ax: Float, ay: Float, az: Float,
        gx: Float, gy: Float, gz: Float,
        qw: Float = 1f, qx: Float = 0f, qy: Float = 0f, qz: Float = 0f
    ) {
        val intent = Intent("IMU_DATA").apply {
            putExtra("AX", ax); putExtra("AY", ay); putExtra("AZ", az)
            putExtra("GX", gx); putExtra("GY", gy); putExtra("GZ", gz)
            putExtra("QW", qw); putExtra("QX", qx); putExtra("QY", qy); putExtra("QZ", qz)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }


    private fun createNotification(): Notification {
        createNotificationChannel()
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("IMUセンサー記録中")
            .setContentText("加速度・ジャイロ・姿勢を計測中")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "IMU Service", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        if (isRecording) {
            saveBufferToStorage(); saveBufferToFirebase()
        }
        sensorManager.unregisterListener(this)
        wakeLock?.release()
        statusOverlay.hide()
        super.onDestroy()
    }
}
