package com.example.vigil.data

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
    val videoPath: String? = null,
    val isPerson: Boolean = false,
    val isVehicle: Boolean = false,
    val speedMph: Int = 0,
    val direction: String = "",
    val personId: String = "",
    val plateText: String = ""
) {
    val timestampFormatted: String
        get() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date(timestamp))
    
    val timeOnly: String
        get() = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
}
