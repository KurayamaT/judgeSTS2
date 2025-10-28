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
    private var gyroscope: Sensor? = null
    private var rotationVector: Sensor? = null
    private lateinit var database: FirebaseDatabase
    private var wakeLock: PowerManager.WakeLock? = null
    private var sessionStartTime: String? = null
    private var sessionStartTimeMillis: Long = 0L
    private var isRecording = false

    private lateinit var storageFile: File
    private var currentFileSize = 0L
    private var fileIndex = 0
    private val MAX_FILE_SIZE = 100 * 1024 * 1024L  // 100MB
    private val MIN_FREE_SPACE = 500 * 1024 * 1024L  // 500MB

    private lateinit var locationManager: LocationManager
    private var currentLat = 0.0
    private var currentLon = 0.0
    private var currentAlt = 0.0
    private var currentAcc = 0.0

    // GPS速度関連
    private var currentSpeed = 0.0f
    private var gpsJustUpdated = false

    private val dataBuffer = ArrayDeque<IMUDataPoint>(1000)
    private val storageBuffer = ArrayDeque<IMUDataPoint>(1000)
    private val bufferLock = ReentrantLock()

    private var currentAx = 0f; private var currentAy = 0f; private var currentAz = 0f
    private var currentGx = 0f; private var currentGy = 0f; private var currentGz = 0f
    private var currentQw = 1f; private var currentQx = 0f; private var currentQy = 0f; private var currentQz = 0f

    private var isFirebaseSending = false
    private val STORAGE_WRITE_INTERVAL = 5000L
    private val FIREBASE_WRITE_INTERVAL = 20000L  // povo2.0実測40-80kbps対応
    private var lastStorageWriteTime = 0L
    private var lastFirebaseWriteTime = 0L

    private lateinit var statusOverlay: StatusOverlay
    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        private const val CHANNEL_ID = "IMUServiceChannel"
        private const val NOTIFICATION_ID = 1
        private const val SENSOR_SAMPLING_PERIOD_US = 8334
        private const val MAX_REPORT_LATENCY_US = 50000
        private const val TAG = "IMUService"
    }

    data class IMUDataPoint(
        val timestamp: Long,
        val ax: Float, val ay: Float, val az: Float,
        val gx: Float, val gy: Float, val gz: Float,
        val qw: Float, val qx: Float, val qy: Float, val qz: Float,
        val lat: Double = 0.0,
        val lon: Double = 0.0,
        val alt: Double = 0.0,
        val acc: Double = 0.0,
        val speed: Float = 0.0f  // GPS速度(m/s)
    )

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            currentLat = location.latitude
            currentLon = location.longitude
            currentAlt = location.altitude
            currentAcc = location.accuracy.toDouble()

            // GPS速度を取得
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
                Log.d(TAG, "SitToStand: $totalSitToStand, Steps: $totalSteps")

                val elapsed = System.currentTimeMillis() - sessionStartTimeMillis
                val sec = elapsed / 1000
                val h = sec / 3600
                val m = (sec % 3600) / 60
                val s = sec % 60

                // ストレージ容量チェック
                val freeSpace = getFreeStorageSpace()
                val freeSpaceMB = freeSpace / (1024 * 1024)

                statusOverlay.updateMessage(
                    """
                    ⏱ ${String.format("%02d:%02d:%02d", h, m, s)}
                    🪑 起立: $totalSitToStand 回
                    🏃‍♂️ 歩行: $totalSteps 歩
                    📍 Lat: ${"%.5f".format(currentLat)}
                    📍 Lon: ${"%.5f".format(currentLon)}
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
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        rotationVector = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

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

        // ストレージ容量チェック
        if (getFreeStorageSpace() < MIN_FREE_SPACE) {
            Log.e(TAG, "Insufficient storage space")
            statusOverlay.show("❌ ストレージ容量不足（500MB以上必要）")
            mainHandler.postDelayed({ statusOverlay.hide() }, 3000)
            return
        }

        isRecording = true

        val calendar = Calendar.getInstance(TimeZone.getTimeZone("Asia/Tokyo"))
        sessionStartTimeMillis = calendar.timeInMillis
        sessionStartTime = SimpleDateFormat("yyyyMMddHHmmss", Locale.getDefault()).apply {
            timeZone = TimeZone.getTimeZone("Asia/Tokyo")
        }.format(calendar.time)

        fileIndex = 0
        initializeStorageFile()
        startForeground(NOTIFICATION_ID, createNotification())
        wakeLock?.acquire()
        setupSensors()
        statusOverlay.show("📊 IMU+GPS計測開始")

        analysisHandler.postDelayed(analysisTask, 3000L)
        Log.d(TAG, "First analysis scheduled in 3 seconds")

    }

    private fun stopRecording() {
        if (!isRecording) return
        isRecording = false
        saveBufferToStorage()
        saveBufferToFirebase()
        sensorManager.unregisterListener(this)
        locationManager.removeUpdates(locationListener)

        // WakeLock安全解放
        wakeLock?.apply {
            if (isHeld) {
                release()
            }
        }

        statusOverlay.updateMessage("📊 IMU+GPS計測停止")
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
                currentAx = event.values[0]; currentAy = event.values[1]; currentAz = event.values[2]
                gaitAnalyzer.append(now, currentAx, currentAy, currentAz)
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

        val data = IMUDataPoint(
            now, currentAx, currentAy, currentAz,
            currentGx, currentGy, currentGz,
            currentQw, currentQx, currentQy, currentQz,
            currentLat, currentLon, currentAlt, currentAcc,
            speedToRecord
        )
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
    }

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
                val header = "Timestamp(ms),ax,ay,az,gx,gy,gz,qw,qx,qy,qz,lat,lon,alt,acc\n"
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

            // ★ バッファサイズ制限：最大500サンプルずつ処理
            val maxBatchSize = 500
            val batchSize = min(dataBuffer.size, maxBatchSize)

            dataToWrite = dataBuffer.take(batchSize)
            repeat(batchSize) { dataBuffer.removeFirst() }

            Log.d(TAG, "Processing ${dataToWrite.size} samples (${dataBuffer.size} remaining in buffer)")
        }

        try {
            // ストレージ容量チェック
            if (getFreeStorageSpace() < MIN_FREE_SPACE) {
                Log.e(TAG, "Low storage space, stopping recording")
                statusOverlay.updateMessage("❌ ストレージ容量不足")
                stopRecording()
                return
            }

            // ファイルサイズチェック（100MBでローテーション）
            if (currentFileSize > MAX_FILE_SIZE) {
                Log.d(TAG, "File size limit reached, rotating to new file")
                fileIndex++
                initializeStorageFile()
            }

            // ★ 直接ファイルに書き込み（StringBuilder不使用）
            FileOutputStream(storageFile, true).bufferedWriter().use { writer ->
                for (data in dataToWrite) {
                    writer.write("${data.timestamp},${data.ax},${data.ay},${data.az},")
                    writer.write("${data.gx},${data.gy},${data.gz},${data.qw},${data.qx},${data.qy},${data.qz},")
                    writer.write("${data.lat},${data.lon},${data.alt},${data.acc},")
                    writer.write("${data.speed}\n")
                }
            }

            // ファイルサイズ更新（概算）
            currentFileSize += (dataToWrite.size * 375L)  // 約375バイト/サンプル

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
        // 128kbps対応: 送信完了待ち
        if (isFirebaseSending) {
            Log.d(TAG, "Firebase still sending, skip this cycle")
            return
        }

        var list: List<IMUDataPoint>
        var beforeSize = 0

        bufferLock.withLock {
            beforeSize = storageBuffer.size

            if (storageBuffer.isEmpty()) {
                Log.w(TAG, "storageBuffer empty -> skip")
                return
            }

            // バッファオーバーフロー対策（最大6000サンプルまで保持）
            var deletedCount = 0
            while (storageBuffer.size > 18000) {
                storageBuffer.removeFirst()
                deletedCount++
            }
            if (deletedCount > 0) {
                Log.w(TAG, "Buffer overflow: deleted $deletedCount old samples")
            }

            // ★ 最大1000サンプルずつ送信（128kbps対応）
            val maxBatchSize = 800
            val batchSize = min(storageBuffer.size, maxBatchSize)

            list = storageBuffer.take(batchSize)
            repeat(batchSize) { storageBuffer.removeFirst() }

            Log.d(TAG, "Sending ${list.size} pts (buffer: $beforeSize -> ${storageBuffer.size})")
        }

        if (list.isEmpty()) {
            Log.w(TAG, "No data to send")
            return
        }

        isFirebaseSending = true  // ★ 送信開始フラグ

        try {
            val timeKey = System.currentTimeMillis().toString()
            // timestamp, 加速度3軸, GPS速度（更新時のみ値あり）
            val csvChunk = list.joinToString("\n") { data ->
                val speedStr = if (data.speed != 0.0f) {
                    String.format("%.2f", data.speed)
                } else {
                    ""
                }
                String.format("%d,%.3f,%.3f,%.3f,%s",
                    data.timestamp, data.ax, data.ay, data.az, speedStr)
            }

            val ref = database.getReference("SmartPhone_data_IMU")
                .child(sessionStartTime!!)
                .child(timeKey)

            ref.setValue(csvChunk)
                .addOnSuccessListener {
                    isFirebaseSending = false  // ★ 送信完了
                    Log.d(TAG, "Firebase send OK: ${list.size} pts (128kbps)")
                }
                .addOnFailureListener {
                    isFirebaseSending = false  // ★ 失敗時もフラグ解除
                    Log.e(TAG, "Firebase send failed: ${it.message}")
                }

        } catch (e: OutOfMemoryError) {
            isFirebaseSending = false  // ★ 例外時もフラグ解除
            Log.e(TAG, "OutOfMemoryError in Firebase send!", e)
            // バッファをクリアして緊急回避
            bufferLock.withLock {
                storageBuffer.clear()
            }
        } catch (e: Exception) {
            isFirebaseSending = false  // ★ 例外時もフラグ解除
            Log.e(TAG, "Firebase send error", e)
        }
    }

    private fun getFreeStorageSpace(): Long {
        return try {
            val path = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            val stat = StatFs(path.path)
            stat.availableBlocksLong * stat.blockSizeLong
        } catch (e: Exception) {
            Log.e(TAG, "Error getting storage space", e)
            0L
        }
    }

    private fun createNotification(): Notification {
        createNotificationChannel()
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("IMU+GPSセンサー記録中")
            .setContentText("加速度・角速度・姿勢・位置情報を計測中")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "IMU+GPS Service", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (isRecording) {
            saveBufferToStorage()
            saveBufferToFirebase()
        }
        sensorManager.unregisterListener(this)
        locationManager.removeUpdates(locationListener)

        // WakeLock安全解放
        wakeLock?.apply {
            if (isHeld) {
                release()
            }
        }

        statusOverlay.hide()

        analysisHandler.removeCallbacksAndMessages(null)
        if (this::analysisThread.isInitialized) {
            analysisThread.quitSafely()
        }
        super.onDestroy()
    }
}