package com.example.vigil

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.example.vigil.data.DetectionLog
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DetectionStorage(private val context: Context) {
    private val settings = SettingsManager(context)
    
    companion object {
        private const val TAG = "DetectionStorage"
        private const val LOGS_FILE = "detection_logs.json"
        private const val MAX_LOGS = 1000
        
        // Higher quality settings
        private const val IMAGE_QUALITY = 95 // JPEG quality (0-100)
        private const val THUMBNAIL_QUALITY = 80
        private const val THUMBNAIL_SIZE = 300 // Larger thumbnails
    }
    
    private val gson = Gson()
    private val logsDir: File by lazy {
        File(context.filesDir, "detections").apply {
            if (!exists()) mkdirs()
        }
    }
    
    private val imagesDir: File by lazy {
        File(context.filesDir, "detection_images").apply {
            if (!exists()) mkdirs()
        }
    }
    
    private val thumbnailsDir: File by lazy {
        File(context.filesDir, "detection_thumbnails").apply {
            if (!exists()) mkdirs()
        }
    }
    
    private val logsFile: File by lazy {
        File(context.filesDir, LOGS_FILE)
    }
    
    private var cachedLogs: List<DetectionLog> = emptyList()
    
    init {
        loadLogs()
    }
    
    private fun loadLogs() {
        try {
            if (logsFile.exists()) {
                val json = logsFile.readText()
                val type = object : TypeToken<List<DetectionLog>>() {}.type
                cachedLogs = gson.fromJson(json, type) ?: emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load logs", e)
            cachedLogs = emptyList()
        }
    }
    
    suspend fun saveDetection(
        detection: Detection,
        originalBitmap: Bitmap? = null,
        zoomedBitmap: Bitmap? = null
    ): DetectionLog? = withContext(Dispatchers.IO) {
        try {
            val timestamp = System.currentTimeMillis()
            val isPerson = detection.classId == 0
            val isVehicle = detection.classId in setOf(1, 2, 3, 5, 7)
            
            var imagePath: String? = null
            var thumbnailPath: String? = null
            
            // Use zoomed bitmap if available, otherwise original
            val imageToSave = zoomedBitmap ?: originalBitmap
            
            if (imageToSave != null && !imageToSave.isRecycled && imageToSave.width > 10 && imageToSave.height > 10) {
                try {
                    // Save HIGH QUALITY main image (no downscaling)
                    imagePath = saveHighQualityImage(imageToSave, "detection_${timestamp}")
                    
                    // Save better quality thumbnail
                    val thumbnail = createHighQualityThumbnail(imageToSave, THUMBNAIL_SIZE, THUMBNAIL_SIZE)
                    if (thumbnail != null && !thumbnail.isRecycled) {
                        thumbnailPath = saveImage(thumbnail, "thumb_${timestamp}", THUMBNAIL_QUALITY)
                        thumbnail.recycle()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to save images", e)
                }
            }
            
            val log = DetectionLog(
                id = timestamp,
                label = detection.label,
                classId = detection.classId,
                confidence = detection.confidence,
                boundingBox = detection.bounds,
                timestamp = timestamp,
                imagePath = imagePath,
                thumbnailPath = thumbnailPath,
                isPerson = isPerson,
                isVehicle = isVehicle
            )
            
            cachedLogs = (listOf(log) + cachedLogs).take(MAX_LOGS)
            saveLogs()
            
            Log.d(TAG, "Saved detection: ${log.label} at ${log.timestampFormatted}")
            Log.d(TAG, "Image path: $imagePath")
            log
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save detection", e)
            null
        }
    }
    
    // New: Save high quality image without compression artifacts
    private fun saveHighQualityImage(bitmap: Bitmap, name: String): String? {
        return try {
            if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
                return null
            }
            
            val file = File(imagesDir, "$name.png")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            Log.d(TAG, "Saved lossless PNG image: ${file.absolutePath}")
            file.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save high quality image", e)
            null
        }
    }
    
    // New: Better quality thumbnail
    private fun createHighQualityThumbnail(bitmap: Bitmap, maxWidth: Int, maxHeight: Int): Bitmap? {
        return try {
            if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
                return null
            }
            
            // Calculate scale to fit within max dimensions while maintaining aspect ratio
            val width = bitmap.width
            val height = bitmap.height
            
            // Use larger thumbnails for better quality
            val scale = minOf(
                maxWidth.toFloat() / width,
                maxHeight.toFloat() / height,
                1f // Don't upscale
            )
            
            if (scale >= 1f) {
                // If image is smaller than thumbnail size, return a copy
                bitmap.copy(Bitmap.Config.ARGB_8888, false)
            } else {
                val newWidth = (width * scale).toInt()
                val newHeight = (height * scale).toInt()
                
                // Use high quality filter
                Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create thumbnail", e)
            null
        }
    }
    
    private fun saveImage(bitmap: Bitmap, name: String, quality: Int = 90): String? {
        return try {
            if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
                return null
            }
            
            val file = File(thumbnailsDir, "$name.jpg")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            }
            file.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save image", e)
            null
        }
    }
    
    private fun saveLogs() {
        try {
            val json = gson.toJson(cachedLogs)
            logsFile.writeText(json)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save logs", e)
        }
    }
    
    fun getLogs(): List<DetectionLog> = cachedLogs
    
    fun getPersonLogs(): List<DetectionLog> = cachedLogs.filter { it.isPerson }
    
    fun getVehicleLogs(): List<DetectionLog> = cachedLogs.filter { it.isVehicle }
    
    fun getRecentLogs(count: Int = 50): List<DetectionLog> = cachedLogs.take(count)
    
    fun clearLogs() {
        cachedLogs = emptyList()
        saveLogs()
        imagesDir.listFiles()?.forEach { it.delete() }
        thumbnailsDir.listFiles()?.forEach { it.delete() }
    }
    
    fun getLogsForTimeRange(startTime: Long, endTime: Long): List<DetectionLog> {
        return cachedLogs.filter { it.timestamp in startTime..endTime }
    }
    
    fun getDetectionStats(): String {
        val total = cachedLogs.size
        val persons = cachedLogs.count { it.isPerson }
        val vehicles = cachedLogs.count { it.isVehicle }
        val lastHour = cachedLogs.count { 
            System.currentTimeMillis() - it.timestamp < 3600000 
        }
        
        return "Total: $total | People: $persons | Vehicles: $vehicles | Last Hour: $lastHour"
    }

    fun exportToGallery(log: DetectionLog): Boolean {
        val path = log.imagePath ?: return false
        return saveToGallery(context, path)
    }

    fun saveToGallery(context: Context, imagePath: String): Boolean {
        return try {
            val isPng = imagePath.endsWith(".png", ignoreCase = true)
            val extension = if (isPng) "png" else "jpg"
            val mimeType = if (isPng) "image/png" else "image/jpeg"
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "Vigil_${System.currentTimeMillis()}.$extension")
                    put(MediaStore.Images.Media.MIME_TYPE, mimeType)
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Vigil")
                }
                val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                uri?.let {
                    context.contentResolver.openOutputStream(it)?.use { output ->
                        File(imagePath).inputStream().use { input ->
                            input.copyTo(output)
                        }
                    }
                    true
                } ?: false
            } else {
                @Suppress("DEPRECATION")
                val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                val vigilDir = File(picturesDir, "Vigil")
                if (!vigilDir.exists()) vigilDir.mkdirs()
                
                val destFile = File(vigilDir, "Vigil_${System.currentTimeMillis()}.$extension")
                File(imagePath).copyTo(destFile, overwrite = true)
                
                // Scan media
                MediaScannerConnection.scanFile(context, arrayOf(destFile.absolutePath), null, null)
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save to gallery", e)
            false
        }
    }
}
