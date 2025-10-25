package com.example.judgeSTS_ver2

data class AccelerometerData(
    val timestamp: Long = 0,
    val x: Float = 0f,
    val y: Float = 0f,
    val z: Float = 0f,
    val deviceId: String = ""
)