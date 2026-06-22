package com.example.vigil

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import com.example.vigil.data.DetectionLog
import com.example.vigil.data.VigilDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class DetectionStorage(private val context: Context) {
    private val database = VigilDatabase.getDatabase(context)
    private val dao = database.detectionLogDao()
    private val imagesDir = File(context.filesDir, "detection_images").apply { if (!exists()) mkdirs() }

    suspend fun saveDetection(
        detection: Detection, 
        originalBitmap: Bitmap? = null, 
        zoomedBitmap: Bitmap? = null, 
        speedMph: Int = 0, 
        direction: String = "", 
        personId: String = "", 
        plateText: String = "", 
        videoPath: String? = null
    ): DetectionLog? = withContext(Dispatchers.IO) {
        try {
            val timestamp = System.currentTimeMillis()
            val img = zoomedBitmap ?: originalBitmap
            var path: String? = null
            
            if (img != null && !img.isRecycled) {
                val file = File(imagesDir, "det_$timestamp.jpg")
                FileOutputStream(file).use { img.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                path = file.absolutePath
            }
          
            val log = DetectionLog(
                id = timestamp,
                label = detection.label,
                classId = detection.classId,
                confidence = detection.confidence,
                boxLeft = detection.bounds.left,
                boxTop = detection.bounds.top,
                boxRight = detection.bounds.right,
                boxBottom = detection.bounds.bottom,
                timestamp = timestamp,
                imagePath = path,
                thumbnailPath = null,
                videoPath = videoPath,
                isPerson = detection.classId == 0,
                isVehicle = detection.classId in setOf(1,2,3,5,7),
                speedMph = speedMph,
                direction = direction,
                personId = personId,
                plateText = plateText
            )
            
            dao.insertLog(log)
            log
        } catch (e: Exception) { 
            null 
        }
    }

    suspend fun getRecentLogs(count: Int = 50): List<DetectionLog> = withContext(Dispatchers.IO) {
        dao.getRecentLogs(count)
    }

    suspend fun clearLogs() = withContext(Dispatchers.IO) {
        dao.clearAllLogs()
        imagesDir.listFiles()?.forEach { it.delete() }
    }

    suspend fun getDetectionStats(): String = withContext(Dispatchers.IO) {
        "Total: ${dao.getTotalCount()} | P: ${dao.getPersonCount()} | V: ${dao.getVehicleCount()}"
    }

    suspend fun exportToGallery(log: DetectionLog): Boolean = withContext(Dispatchers.IO) {
        try {
            val file = File(log.imagePath ?: "")
            if (!file.exists()) return@withContext false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val cv = ContentValues().apply { 
                    put(MediaStore.Images.Media.DISPLAY_NAME, "Vigil_${log.timestamp}.jpg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Vigil")
                    put(MediaStore.Images.Media.IS_PENDING, 1) 
                }
                resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)?.let { uri ->
                    resolver.openOutputStream(uri).use { file.inputStream().copyTo(it!!) }
                    cv.clear()
                    cv.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(uri, cv, null, null)
                    true
                } ?: false
            } else false
        } catch (e: Exception) { 
            false
        }
    }
}
