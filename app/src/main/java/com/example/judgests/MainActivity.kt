package com.example.judgests

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager

class MainActivity : AppCompatActivity() {
    private lateinit var xValue: TextView
    private lateinit var yValue: TextView
    private lateinit var zValue: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var accelerometerDataReceiver: BroadcastReceiver

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.all { it.value }) {
            startRecordingService()
        } else {
            Toast.makeText(this, "必要な権限が許可されていません", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize UI elements
        xValue = findViewById(R.id.xValue)
        yValue = findViewById(R.id.yValue)
        zValue = findViewById(R.id.zValue)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)

        startButton.setOnClickListener {
            startRecording()
        }

        stopButton.setOnClickListener {
            stopRecording()
        }

        // 初期状態ではstopButtonを無効化
        stopButton.isEnabled = false

        accelerometerDataReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                intent?.let {
                    val x = it.getFloatExtra("X", 0f)
                    val y = it.getFloatExtra("Y", 0f)
                    val z = it.getFloatExtra("Z", 0f)
                    updateAccelerometerData(x, y, z)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        LocalBroadcastManager.getInstance(this).registerReceiver(accelerometerDataReceiver, IntentFilter("ACCELEROMETER_DATA"))
    }

    override fun onPause() {
        super.onPause()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(accelerometerDataReceiver)
    }

    private fun updateAccelerometerData(x: Float, y: Float, z: Float) {
        xValue.text = String.format("X: %.2f", x)
        yValue.text = String.format("Y: %.2f", y)
        zValue.text = String.format("Z: %.2f", z)
    }

    private fun startRecording() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permissions = mutableListOf(
                Manifest.permission.POST_NOTIFICATIONS,
                Manifest.permission.FOREGROUND_SERVICE
            ).apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    add(Manifest.permission.FOREGROUND_SERVICE_DATA_SYNC)
                }
            }.toTypedArray()

            // 必要な権限が付与されているかチェック
            val permissionsToRequest = permissions.filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }.toTypedArray()

            if (permissionsToRequest.isEmpty()) {
                startRecordingService()
            } else {
                requestPermissionLauncher.launch(permissionsToRequest)
            }
        } else {
            startRecordingService()
        }
    }

    private fun startRecordingService() {
        val serviceIntent = Intent(this, AccelerometerService::class.java).apply {
            action = "START_RECORDING"
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        startButton.isEnabled = false
        stopButton.isEnabled = true
    }

    private fun stopRecording() {
        val serviceIntent = Intent(this, AccelerometerService::class.java).apply {
            action = "STOP_RECORDING"
        }
        stopService(serviceIntent)
        startButton.isEnabled = true
        stopButton.isEnabled = false
    }
}