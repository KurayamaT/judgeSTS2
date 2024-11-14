package com.example.judgests

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.provider.Settings  // 追加
import android.util.Log          // 追加
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.database.FirebaseDatabase
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

// データクラスを追加
data class AccelerometerData(
    val timestamp: Long = 0,
    val x: Float = 0f,
    val y: Float = 0f,
    val z: Float = 0f,
    val deviceId: String = ""
)

class MainActivity : AppCompatActivity(), SensorEventListener {
    // ... 既存のコード ...
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private lateinit var xValue: TextView
    private lateinit var yValue: TextView
    private lateinit var zValue: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button

    private var isRecording = false
    private lateinit var database: FirebaseDatabase
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize Firebase
        database = FirebaseDatabase.getInstance()

        // Initialize UI elements
        xValue = findViewById(R.id.xValue)
        yValue = findViewById(R.id.yValue)
        zValue = findViewById(R.id.zValue)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)

        // Initialize sensor
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        startButton.setOnClickListener {
            startRecording()
        }

        stopButton.setOnClickListener {
            stopRecording()
        }
    }

    private fun startRecording() {
        isRecording = true
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL)
        startButton.isEnabled = false
        stopButton.isEnabled = true
    }

    private fun stopRecording() {
        isRecording = false
        sensorManager.unregisterListener(this)
        startButton.isEnabled = true
        stopButton.isEnabled = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER && isRecording) {
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]

            // Update UI
            xValue.text = "X: $x"
            yValue.text = "Y: $y"
            zValue.text = "Z: $z"

            // Get current timestamp
            val timestamp = System.currentTimeMillis()
            val dateString = dateFormat.format(Date(timestamp))

            // Save to internal storage
            saveToInternalStorage(timestamp, x, y, z)

            // Save to Firebase
            saveToFirebase(timestamp, x, y, z)
        }
    }

    private fun saveToInternalStorage(timestamp: Long, x: Float, y: Float, z: Float) {
        val file = File(getExternalFilesDir(null), "accelerometer_data.csv")
        val data = "$timestamp,$x,$y,$z\n"

        try {
            if (!file.exists()) {
                file.createNewFile()
                file.writeText("Timestamp,X,Y,Z\n")
            }
            file.appendText(data)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun saveToFirebase(timestamp: Long, x: Float, y: Float, z: Float) {
        // WHS3_data配下にデータを保存
        val reference = database.getReference("WHS3_data/accelerometer_judgests")

        val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        val data = AccelerometerData(timestamp, x, y, z, deviceId)

        reference.child(deviceId)
            .child(timestamp.toString())
            .setValue(data)
            .addOnSuccessListener {
                Log.d("Firebase", "Data saved successfully")
            }
            .addOnFailureListener { e ->
                Log.e("Firebase", "Error saving data", e)
            }
    }

    
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not used in this example
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }




}