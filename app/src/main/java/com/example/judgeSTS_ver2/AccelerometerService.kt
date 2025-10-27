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
import android.os.Build
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
    private var sessionStartTimeMillis: Long = 0L
    private var recordingStartTime: Long = 0L

    private var currentX: Float = 0f
    private var currentY: Float = 0f
    private var currentZ: Float = 0f

    private var isRecording = false
    private var cumulativeDataSize = 0L

    // ファイルローテーション用
    private lateinit var storageFile: File
    private var currentFileSize = 0L
    private var fileIndex = 0
    private val MAX_FILE_SIZE = 100 * 1024 * 1024L  // 100MB
    private val MIN_FREE_SPACE = 500 * 1024 * 1024L  // 500MB

    private data class AccelerometerDataPoint(
        val timestamp: Long,
        val x: Float,
        val y: Float,
        val z: Float
    )

    private val dataBuffer = ArrayDeque<AccelerometerDataPoint>(1000)
    private val storageBuffer = ArrayDeque<AccelerometerDataPoint>(1000)
    private val bufferLock = ReentrantLock()

    private val STORAGE_WRITE_INTERVAL = 5000L
    private val FIREBASE_WRITE_INTERVAL = 5000L

    private var lastWriteTime = 0L
    private var lastStorageWriteTime = 0L

    private lateinit var statusOverlay: StatusOverlay
    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        private const val CHANNEL_ID = "AccelerometerServiceChannel"
        private const val NOTIFICATION_ID = 1
        private const val WAKELOCK_TAG = "AccelerometerService::WakeLock"
        private const val SENSOR_SAMPLING_PERIOD_US = 8334
        private const val MAX_REPORT_LATENCY_US = 50000
        private const val TAG = "AccelerometerService"
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

        recordingStartTime = sessionStartTimeMillis
        lastWriteTime = sessionStartTimeMillis
        lastStorageWriteTime = sessionStartTimeMillis
        cumulativeDataSize = 0L
        fileIndex = 0

        initializeStorageFile()

        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)

        wakeLock?.apply {
            if (!isHeld) {
                acquire()
            }
        }

        setupSensor()
        statusOverlay.show("📊 ACC計測開始")
    }

    private fun stopRecording() {
        if (!isRecording) return
        isRecording = false

        saveBufferToStorage()
        saveBufferToFirebase()

        sensorManager.unregisterListener(this)

        wakeLock?.apply {
            if (isHeld) {
                release()
            }
        }

        statusOverlay.updateMessage("📊 ACC計測停止")
        mainHandler.postDelayed({
            statusOverlay.hide()
        }, 2000)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            stopForeground(STOP_FOREGROUND_DETACH)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }

        stopSelf()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER && isRecording) {
            val currentTime = System.currentTimeMillis()

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

            currentX = event.values[0]
            currentY = event.values[1]
            currentZ = event.values[2]

            sampleCount++

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
            val baseDir = getExternalFilesDir(null)
            val fileName = "${sessionStartTime}_accelerometer_part${fileIndex}.csv"
            storageFile = File(baseDir, fileName)
            currentFileSize = 0L

            if (!storageFile.exists()) {
                storageFile.createNewFile()
                val header = "Timestamp(ms),X,Y,Z\n"
                storageFile.writeText(header)
                currentFileSize = header.length.toLong()
            }

            Log.d(TAG, "Initialized storage file: ${storageFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing file", e)
            statusOverlay.updateMessage("❌ ファイル初期化エラー")
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
            // ストレージ容量チェック
            if (getFreeStorageSpace() < MIN_FREE_SPACE) {
                Log.e(TAG, "Low storage space, stopping recording")
                statusOverlay.updateMessage("❌ ストレージ容量不足")
                stopRecording()
                return
            }

            val csvLines = dataToWrite.joinToString("\n") { data ->
                "${data.timestamp},${data.x},${data.y},${data.z}"
            }
            val csvData = "$csvLines\n"
            val dataSize = csvData.length.toLong()

            // ファイルサイズチェック（100MBでローテーション）
            if (currentFileSize + dataSize > MAX_FILE_SIZE) {
                Log.d(TAG, "File size limit reached, rotating to new file")
                fileIndex++
                initializeStorageFile()
            }

            // 効率的な書き込み
            FileOutputStream(storageFile, true).use { fos ->
                fos.write(csvData.toByteArray())
            }

            currentFileSize += dataSize
            cumulativeDataSize += dataSize

            Log.d(TAG, "Wrote ${dataToWrite.size} samples, file size: ${currentFileSize / 1024}KB")

        } catch (e: Exception) {
            Log.e(TAG, "Error writing to file", e)
            statusOverlay.updateMessage("❌ ファイル書き込みエラー")
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
                val freeSpace = getFreeStorageSpace() / (1024 * 1024)

                val message = """
                📊 ACC計測中
                ⏱ 経過時間: $formattedElapsedTime
                💾 累計データ: ${dataSizeMB}MB
                📊 sampling: ${String.format("%.1f", actualSamplingRate)}Hz
                💾 空き: ${freeSpace}MB
                📄 File#${fileIndex}
                """.trimIndent()

                statusOverlay.updateMessage(message)
                Log.d(TAG, "Saved ${dataToSend.size} samples")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Error saving data", e)
            }
    }

    private fun getFreeStorageSpace(): Long {
        return try {
            val path = getExternalFilesDir(null)?.path ?: return 0L
            val stat = StatFs(path)
            stat.availableBlocksLong * stat.blockSizeLong
        } catch (e: Exception) {
            Log.e(TAG, "Error getting storage space", e)
            0L
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

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

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

        mainHandler.removeCallbacksAndMessages(null)
        statusOverlay.hide()
    }
}