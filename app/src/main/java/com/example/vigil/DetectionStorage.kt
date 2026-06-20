package com.example.vigil

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
    companion object {
        private const val TAG = "DetectionStorage"
        private const val LOGS_FILE = "detection_logs.json"
        private const val MAX_LOGS = 1000
        private const val THUMBNAIL_SIZE = 300
    }

    private val settings = SettingsManager(context)

    private val gson = Gson()
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
        zoomedBitmap: Bitmap? = null,
        speedMph: Int = 0,
        direction: String = "",
        personId: String = "",
        plateText: String = ""
    ): DetectionLog? = withContext(Dispatchers.IO) {
        try {
            val timestamp = System.currentTimeMillis()
            val isPerson = detection.classId == 0
            val isVehicle = detection.classId in setOf(1, 2, 3, 5, 7)
            
            var imagePath: String? = null
            var thumbnailPath: String? = null
            
            val imageToSave = zoomedBitmap ?: originalBitmap
            
            if (imageToSave != null && !imageToSave.isRecycled && imageToSave.width > 10 && imageToSave.height > 10) {
                try {
                    imagePath = saveImage(imageToSave, "detection_${timestamp}")
                    
                    val thumbnail = createThumbnail(imageToSave, THUMBNAIL_SIZE, THUMBNAIL_SIZE)
                    if (thumbnail != null && !thumbnail.isRecycled) {
                        thumbnailPath = saveImage(thumbnail, "thumb_${timestamp}")
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
                isVehicle = isVehicle,
                speedMph = speedMph,
                direction = direction,
                personId = personId,
                plateText = plateText
            )
            
            cachedLogs = (listOf(log) + cachedLogs).take(MAX_LOGS)
            saveLogs()
            
            Log.d(TAG, "Saved detection: ${log.label} at ${log.timestampFormatted}")
            log
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save detection", e)
            null
        }
    }

    private fun saveImage(bitmap: Bitmap, name: String): String? {
        return try {
            if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
                return null
            }
            val file = File(imagesDir, "$name.jpg")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, settings.imageQuality, out)
            }
            file.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save image", e)
            null
        }
    }

    private fun createThumbnail(bitmap: Bitmap, maxWidth: Int, maxHeight: Int): Bitmap? {
        return try {
            if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
                return null
            }
            val scale = minOf(
                maxWidth.toFloat() / bitmap.width,
                maxHeight.toFloat() / bitmap.height,
                1f
            )
            if (scale >= 1f) {
                bitmap.copy(Bitmap.Config.ARGB_8888, false)
            } else {
                val newWidth = (bitmap.width * scale).toInt()
                val newHeight = (bitmap.height * scale).toInt()
                Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create thumbnail", e)
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

    fun getDetectionStats(): String {
        val total = cachedLogs.size
        val persons = cachedLogs.count { it.isPerson }
        val vehicles = cachedLogs.count { it.isVehicle }
        val lastHour = cachedLogs.count { 
            System.currentTimeMillis() - it.timestamp < 3600000 
        }
        return "Total: $total | People: $persons | Vehicles: $vehicles | Last Hour: $lastHour"
    }

    suspend fun exportToGallery(log: DetectionLog): Boolean = withContext(Dispatchers.IO) {
        try {
            val imagePath = log.imagePath ?: log.thumbnailPath ?: return@withContext false
            val file = File(imagePath)
            if (!file.exists()) return@withContext false
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val contentValues = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "Vigil_${System.currentTimeMillis()}.jpg")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Vigil")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                if (uri != null) {
                    resolver.openOutputStream(uri).use { output ->
                        file.inputStream().use { input ->
                            input.copyTo(output!!)
                        }
                    }
                    contentValues.clear()
                    contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(uri, contentValues, null, null)
                    true
                } else false
            } else {
                @Suppress("DEPRECATION")
                val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                val vigilDir = File(picturesDir, "Vigil")
                if (!vigilDir.exists()) vigilDir.mkdirs()
                val destFile = File(vigilDir, "Vigil_${System.currentTimeMillis()}.jpg")
                file.copyTo(destFile, overwrite = true)
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Export failed", e)
            false
        }
    }
}
