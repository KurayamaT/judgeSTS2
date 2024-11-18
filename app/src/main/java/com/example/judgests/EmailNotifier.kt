package com.example.judgests

import android.content.Context
import android.util.Log
import com.google.firebase.database.FirebaseDatabase
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MeasurementNotifier(private val context: Context) {
    private val database = FirebaseDatabase.getInstance()

    fun sendStartNotification(sessionId: String) {
        try {
            val currentTime = SimpleDateFormat(
                "yyyy/MM/dd HH:mm:ss",
                Locale.getDefault()
            ).format(Date())

            val notificationData = mapOf(
                "sessionId" to sessionId,
                "startTime" to currentTime
            )

            database.getReference("measurement_notifications")
                .child(sessionId)
                .setValue(notificationData)
                .addOnSuccessListener {
                    Log.d("Notification", "通知データをFirebaseに送信成功")
                }
                .addOnFailureListener { e ->
                    Log.e("Notification", "通知データの送信に失敗: ${e.message}", e)
                }

        } catch (e: Exception) {
            Log.e("Notification", "通知処理エラー: ${e.message}", e)
        }
    }
}