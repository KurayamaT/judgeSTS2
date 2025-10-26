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
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.firebase.database.FirebaseDatabase
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import android.os.HandlerThread

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

    private lateinit var locationManager: LocationManager
    private var currentLat = 0.0
    private var currentLon = 0.0
    private var currentAlt = 0.0
    private var currentAcc = 0.0

    private val dataBuffer = ArrayDeque<IMUDataPoint>(1000)
    private val storageBuffer = ArrayDeque<IMUDataPoint>(1000)
    private val bufferLock = ReentrantLock()

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
        private const val SENSOR_SAMPLING_PERIOD_US = 8334
        private const val MAX_REPORT_LATENCY_US = 50000
    }

    data class IMUDataPoint(
        val timestamp: Long,
        val ax: Float, val ay: Float, val az: Float,
        val gx: Float, val gy: Float, val gz: Float,
        val qw: Float, val qx: Float, val qy: Float, val qz: Float,
        val lat: Double = 0.0,
        val lon: Double = 0.0,
        val alt: Double = 0.0,
        val acc: Double = 0.0
    )

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            currentLat = location.latitude
            currentLon = location.longitude
            currentAlt = location.altitude
            currentAcc = location.accuracy.toDouble()
        }
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    private lateinit var gaitAnalyzer: GaitAnalyzer
    private lateinit var analysisThread: HandlerThread
    private lateinit var analysisHandler: Handler

    private val analysisTask = object : Runnable {
        override fun run() {
            try {
                gaitAnalyzer.compute()

                val totalSitToStand = gaitAnalyzer.totalSitToStandCount
                val totalSteps = gaitAnalyzer.totalStepCount
                val elapsed = System.currentTimeMillis() - sessionStartTimeMillis
                val sec = elapsed / 1000
                val h = sec / 3600
                val m = (sec % 3600) / 60
                val s = sec % 60

                statusOverlay.updateMessage(
                    """
                    ⏱ ${String.format("%02d:%02d:%02d", h, m, s)}
                    🪑 起立: $totalSitToStand 回
                    🏃‍♂️ 歩行: $totalSteps 歩
                    📍 Lat: ${"%.5f".format(currentLat)}
                    📍 Lon: ${"%.5f".format(currentLon)}
                    """.trimIndent()
                )

                val intent = Intent("ANALYSIS_UPDATE").apply {
                    putExtra("SIT2STAND", totalSitToStand)
                    putExtra("STEPS", totalSteps)
                }
                LocalBroadcastManager.getInstance(this@IMUService).sendBroadcast(intent)

            } catch (e: Exception) {
                e.printStackTrace()
            }
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
            Log.e("IMUService", "GPS permission missing", e)
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
        statusOverlay.show("📊 IMU+GPS計測開始")

        analysisHandler.postDelayed(analysisTask, 15000L)
    }

    private fun stopRecording() {
        if (!isRecording) return
        isRecording = false
        saveBufferToStorage()
        saveBufferToFirebase()
        sensorManager.unregisterListener(this)
        locationManager.removeUpdates(locationListener)
        wakeLock?.release()
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

        val data = IMUDataPoint(
            now, currentAx, currentAy, currentAz,
            currentGx, currentGy, currentGz,
            currentQw, currentQx, currentQy, currentQz
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
        val baseDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        val folder = File(baseDir, "STS計測データ")

        if (!folder.exists()) {
            folder.mkdirs()
        }

        val fileName = "${sessionStartTime}_imu.csv"
        storageFile = File(folder, fileName)
        if (!storageFile.exists()) {
            storageFile.createNewFile()
            storageFile.writeText("Timestamp(ms),ax,ay,az,gx,gy,gz,qw,qx,qy,qz,lat,lon,alt,acc\n")
        }
    }

    private fun saveBufferToStorage() {
        if (!::storageFile.isInitialized) return

        val builder = StringBuilder()
        while (dataBuffer.isNotEmpty()) {
            val data = dataBuffer.poll()
            builder.append("${data.timestamp},${data.ax},${data.ay},${data.az},")
            builder.append("${data.gx},${data.gy},${data.gz},${data.qw},${data.qx},${data.qy},${data.qz},")
            builder.append("${currentLat},${currentLon},${currentAlt},${currentAcc}\n")
        }
        storageFile.appendText(builder.toString())
    }

    // =======================================================
    // Firebase逐次送信（OOM防止・送信済みデータ削除版）
    // =======================================================
    private fun saveBufferToFirebase() {
        Log.d("IMUService", "saveBufferToFirebase() called")

        val chunkSize = 400
        var list: List<IMUDataPoint>

        bufferLock.withLock {
            if (storageBuffer.isEmpty()) {
                Log.w("IMUService", "storageBuffer empty -> skip")
                return
            }

            // 先頭から最大400個取得
            val actualChunkSize = minOf(chunkSize, storageBuffer.size)
            list = storageBuffer.take(actualChunkSize)
        }

        val timeKey = System.currentTimeMillis().toString()
        val csvChunk = list.joinToString("\n") {
            "${it.timestamp},${it.ax},${it.ay},${it.az}," +
                    "${it.gx},${it.gy},${it.gz},${it.qw},${it.qx},${it.qy},${it.qz}," +
                    "$currentLat,$currentLon,$currentAlt,$currentAcc"
        }

        val ref = database.getReference("SmartPhone_data_IMU_GPS")
            .child(sessionStartTime!!)
            .child(timeKey)

        ref.setValue(csvChunk)
            .addOnSuccessListener {
                // 送信成功したら先頭から削除してメモリ解放
                bufferLock.withLock {
                    repeat(list.size) {
                        if (storageBuffer.isNotEmpty()) {
                            storageBuffer.removeFirst()
                        }
                    }
                }
                Log.d("IMUService", "Firebase send OK: ${list.size} pts (buffer size: ${storageBuffer.size})")
            }
            .addOnFailureListener {
                Log.e("IMUService", "Firebase send failed: ${it.message}")
                // 失敗時は削除しないので次回リトライされる
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
        wakeLock?.release()
        statusOverlay.hide()

        analysisHandler.removeCallbacksAndMessages(null)
        if (this::analysisThread.isInitialized) {
            analysisThread.quitSafely()
        }
        super.onDestroy()
    }
}