package com.example.vigil.data

import android.graphics.Bitmap
import android.graphics.RectF
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class DetectionLog(
    val id: Long = System.currentTimeMillis(),
    val label: String,
    val classId: Int,
    val confidence: Float,
    val boundingBox: RectF,
    val timestamp: Long = System.currentTimeMillis(),
    val imagePath: String? = null,
    val thumbnailPath: String? = null,
    val isPerson: Boolean = false,
    val isVehicle: Boolean = false
) {
    val timestampFormatted: String
        get() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date(timestamp))
    
    val timeOnly: String
        get() = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
}

data class DetectionSession(
    val id: String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date()),
    val startTime: Long = System.currentTimeMillis(),
    var detectionCount: Int = 0,
    var personCount: Int = 0,
    var vehicleCount: Int = 0
)