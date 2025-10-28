package com.example.judgeSTS_ver2

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
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.StatFs
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.firebase.database.FirebaseDatabase
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import android.os.HandlerThread
import kotlin.math.min

class IMUService : Service(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private lateinit var database: FirebaseDatabase
    private var wakeLock: PowerManager.WakeLock? = null
    private var sessionStartTime: String? = null
    private var sessionStartTimeMillis: Long = 0L
    private var isRecording = false

    // サマリー保存用
    private var totalSamplesCount = 0L

    private lateinit var storageFile: File
    private var currentFileSize = 0L
    private var fileIndex = 0
    private val MAX_FILE_SIZE = 100 * 1024 * 1024L  // 100MB
    private val MIN_FREE_SPACE = 500 * 1024 * 1024L  // 500MB

    private lateinit var locationManager: LocationManager

    // GPS速度のみ
    private var currentSpeed = 0.0f
    private var gpsJustUpdated = false

    private val dataBuffer = ArrayDeque<IMUDataPoint>(3000)  // ★ 拡大: 1000→3000
    private val bufferLock = ReentrantLock()

    // 加速度のみ
    private var currentAx = 0f
    private var currentAy = 0f
    private var currentAz = 0f

    private var isFirebaseSending = false
    private val STORAGE_WRITE_INTERVAL = 5000L
    private val FIREBASE_WRITE_INTERVAL = 5000L  // ★ 5秒（元に戻す）
    private var lastStorageWriteTime = 0L
    private var lastFirebaseWriteTime = 0L

    private lateinit var statusOverlay: StatusOverlay
    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        private const val CHANNEL_ID = "IMUServiceChannel"
        private const val NOTIFICATION_ID = 1
        private const val SENSOR_SAMPLING_PERIOD_US = 8334  // 120Hz
        private const val MAX_REPORT_LATENCY_US = 50000
        private const val TAG = "IMUService"
    }

    // シンプル版：timestamp, ax, ay, az, speedのみ
    data class IMUDataPoint(
        val timestamp: Long,
        val ax: Float,
        val ay: Float,
        val az: Float,
        val speed: Float = 0.0f
    )

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            currentSpeed = location.speed
            gpsJustUpdated = true
        }
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {
            currentSpeed = 0.0f
        }
    }

    private lateinit var gaitAnalyzer: GaitAnalyzer
    private lateinit var analysisThread: HandlerThread
    private lateinit var analysisHandler: Handler

    private val analysisTask = object : Runnable {
        override fun run() {
            Log.d(TAG, "=== analysisTask START ===")

            try {
                Log.d(TAG, "Calling gaitAnalyzer.compute()...")
                gaitAnalyzer.compute()
                Log.d(TAG, "gaitAnalyzer.compute() completed")

                val totalSitToStand = gaitAnalyzer.totalSitToStandCount
                val totalSteps = gaitAnalyzer.totalStepCount

                val elapsed = System.currentTimeMillis() - sessionStartTimeMillis
                val h = elapsed / 3600000; val m = (elapsed % 3600000) / 60000; val s = (elapsed % 60000) / 1000

                val freeSpace = getFreeStorageSpace()
                val freeSpaceMB = freeSpace / (1024 * 1024)

                statusOverlay.updateMessage(
                    """
                    ⏱ ${String.format("%02d:%02d:%02d", h, m, s)}
                    🪑 起立: $totalSitToStand 回
                    🏃‍♂️ 歩行: $totalSteps 歩
                    🚀 速度: ${"%.2f".format(currentSpeed)} m/s
                    💾 空き: ${freeSpaceMB}MB
                    📄 File#${fileIndex}
                    """.trimIndent()
                )

                val intent = Intent("ANALYSIS_UPDATE").apply {
                    putExtra("SIT2STAND", totalSitToStand)
                    putExtra("STEPS", totalSteps)
                }
                LocalBroadcastManager.getInstance(this@IMUService).sendBroadcast(intent)

            } catch (e: Exception) {
                Log.e(TAG, "Analysis error", e)
            }

            Log.d(TAG, "Scheduling next analysis in 15 seconds")
            analysisHandler.postDelayed(this, 15000L)
        }
    }

    @SuppressLint("WakelockTimeout", "MissingPermission")
    override fun onCreate() {
        super.onCreate()

        statusOverlay = StatusOverlay(applicationContext)
        database = FirebaseDatabase.getInstance()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 1000L, 0f, locationListener
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "GPS permission missing", e)
        }

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "IMUService::WakeLock")

        gaitAnalyzer = GaitAnalyzer(fs = 120)

        analysisThread = HandlerThread("AnalysisThread", Thread.NORM_PRIORITY)
        analysisThread.start()
        analysisHandler = Handler(analysisThread.looper)
    }

    private fun setupSensors() {
        accelerometer?.let { sensor ->
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

        if (getFreeStorageSpace() < MIN_FREE_SPACE) {
            Log.e(TAG, "Insufficient storage space")
            statusOverlay.show("❌ ストレージ容量不足（500MB以上必要）")
            mainHandler.postDelayed({ statusOverlay.hide() }, 3000)
            return
        }

        isRecording = true
        wakeLock?.acquire()

        val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
        sessionStartTime = sdf.format(Date())
        sessionStartTimeMillis = System.currentTimeMillis()
        fileIndex = 0
        totalSamplesCount = 0L

        initializeStorageFile()
        setupSensors()

        startForeground(NOTIFICATION_ID, createNotification())
        statusOverlay.show("📊 計測開始")

        analysisHandler.post(analysisTask)
    }

    private fun stopRecording() {
        if (!isRecording) return
        isRecording = false

        sensorManager.unregisterListener(this)
        wakeLock?.release()

        saveBufferToStorage()
        saveBufferToFirebase()
        saveSummary()

        if (::statusOverlay.isInitialized) {
            statusOverlay.hide()
        }

        statusOverlay.updateMessage("📊 計測停止")
        mainHandler.postDelayed({ statusOverlay.hide() }, 2000)

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()

        analysisHandler.removeCallbacksAndMessages(null)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!isRecording) return
        val now = System.currentTimeMillis()

        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                currentAx = event.values[0]
                currentAy = event.values[1]
                currentAz = event.values[2]
                gaitAnalyzer.append(now, currentAx, currentAy, currentAz)
            }
        }

        val intent = Intent("IMU_DATA").apply {
            putExtra("AX", currentAx)
            putExtra("AY", currentAy)
            putExtra("AZ", currentAz)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)

        // GPS更新時のみspeedを記録
        val speedToRecord = if (gpsJustUpdated) {
            gpsJustUpdated = false
            currentSpeed
        } else {
            0.0f
        }

        val data = IMUDataPoint(now, currentAx, currentAy, currentAz, speedToRecord)
        bufferLock.withLock {
            dataBuffer.addLast(data)
        }

        val shouldSaveStorage = now - lastStorageWriteTime >= STORAGE_WRITE_INTERVAL
        val shouldSendFirebase = now - lastFirebaseWriteTime >= FIREBASE_WRITE_INTERVAL

        if (shouldSaveStorage) {
            saveBufferToStorage()
            lastStorageWriteTime = now
        }

        if (shouldSendFirebase) {
            saveBufferToFirebase()
            lastFirebaseWriteTime = now
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    @SuppressLint("SimpleDateFormat")
    private fun initializeStorageFile() {
        try {
            val baseDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            val folder = File(baseDir, "STS_MeasurementData")

            if (!folder.exists()) {
                folder.mkdirs()
            }

            val fileName = "${sessionStartTime}_imu_part${fileIndex}.csv"
            storageFile = File(folder, fileName)
            currentFileSize = 0L

            if (!storageFile.exists()) {
                storageFile.createNewFile()
                val header = "Timestamp(ms),ax,ay,az,speed\n"
                storageFile.writeText(header)
                currentFileSize = header.length.toLong()
            }

            Log.d(TAG, "Initialized storage file: ${storageFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing storage file", e)
            statusOverlay.updateMessage("❌ ファイル初期化エラー")
        }
    }

    private fun saveBufferToStorage() {
        if (!::storageFile.isInitialized) return

        var dataToWrite: List<IMUDataPoint>
        bufferLock.withLock {
            if (dataBuffer.isEmpty()) return

            // ★ コピーのみ、削除しない（Firebase送信後にクリアされる）
            dataToWrite = dataBuffer.toList()

            Log.d(TAG, "Processing ${dataToWrite.size} samples (${dataBuffer.size} copied for storage)")
        }

        try {
            if (getFreeStorageSpace() < MIN_FREE_SPACE) {
                Log.e(TAG, "Low storage space, stopping recording")
                statusOverlay.updateMessage("❌ ストレージ容量不足")
                stopRecording()
                return
            }

            if (currentFileSize > MAX_FILE_SIZE) {
                Log.d(TAG, "File size limit reached, rotating to new file")
                fileIndex++
                initializeStorageFile()
            }

            // timestamp, ax, ay, az, speedのみ
            FileOutputStream(storageFile, true).bufferedWriter().use { writer ->
                for (data in dataToWrite) {
                    writer.write("${data.timestamp},${data.ax},${data.ay},${data.az},${data.speed}\n")
                }
            }

            currentFileSize += (dataToWrite.size * 60L)
            totalSamplesCount += dataToWrite.size

            Log.d(TAG, "Wrote ${dataToWrite.size} samples, file size: ${currentFileSize / 1024}KB")

        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "OutOfMemoryError! Emergency stop", e)
            statusOverlay.updateMessage("❌ メモリ不足：計測停止")
            stopRecording()

        } catch (e: Exception) {
            Log.e(TAG, "Error writing to storage", e)
            statusOverlay.updateMessage("❌ ファイル書き込みエラー")
        }
    }

    private fun saveBufferToFirebase() {
        if (isFirebaseSending) return
        isFirebaseSending = true

        var dataToSend: List<IMUDataPoint>
        bufferLock.withLock {
            if (dataBuffer.isEmpty()) {
                isFirebaseSending = false
                return
            }
            dataToSend = dataBuffer.toList()
            dataBuffer.clear()
        }

        try {
            val timeKey = System.currentTimeMillis().toString()
            // timestamp, ax, ay, az, speed (GPS更新時のみ値あり)
            val csvChunk = dataToSend.joinToString("\n") {
                val speedStr = if (it.speed != 0.0f) {
                    "%.2f".format(it.speed)
                } else {
                    ""
                }
                "${it.timestamp},${"%.3f".format(it.ax)},${"%.3f".format(it.ay)},${"%.3f".format(it.az)},$speedStr"
            }

            val ref = database.getReference("SmartPhone_data_IMU_Speed")
                .child(sessionStartTime!!)
                .child(timeKey)

            ref.setValue(csvChunk)
                .addOnSuccessListener {
                    Log.d(TAG, "Firebase send OK: ${dataToSend.size} pts (with GPS speed)")
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Firebase send error", e)
                }
                .addOnCompleteListener {
                    isFirebaseSending = false
                }

        } catch (e: Exception) {
            Log.e(TAG, "Error in Firebase send", e)
            isFirebaseSending = false
        }
    }

    private fun saveSummary() {
        try {
            val baseDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            val folder = File(baseDir, "STS_MeasurementData")
            val summaryFile = File(folder, "${sessionStartTime}_summary.txt")

            val elapsed = System.currentTimeMillis() - sessionStartTimeMillis
            val h = elapsed / 3600000
            val m = (elapsed % 3600000) / 60000
            val s = (elapsed % 60000) / 1000

            val summary = """
                計測サマリー
                ================
                開始時刻: $sessionStartTime
                計測時間: ${h}時間${m}分${s}秒
                総サンプル数: $totalSamplesCount
                ファイル数: ${fileIndex + 1}
                起立回数: ${gaitAnalyzer.totalSitToStandCount}
                歩数: ${gaitAnalyzer.totalStepCount}
            """.trimIndent()

            summaryFile.writeText(summary)
            Log.d(TAG, "Summary saved: ${summaryFile.absolutePath}")

        } catch (e: Exception) {
            Log.e(TAG, "Error saving summary", e)
        }
    }

    private fun getFreeStorageSpace(): Long {
        val path = Environment.getExternalStorageDirectory()
        val stat = StatFs(path.path)
        return stat.availableBlocksLong * stat.blockSizeLong
    }

    private fun createNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "IMU計測サービス",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("IMU+GPS計測中")
            .setContentText("加速度とGPS速度を記録しています")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (::statusOverlay.isInitialized) {
            statusOverlay.hide()
        }
        analysisThread.quitSafely()
        super.onDestroy()
    }
}