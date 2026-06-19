package com.example.vigil

import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log

class PlateDetector {
    companion object {
        private const val TAG = "PlateDetector"
        private const val MIN_PLATE_WIDTH = 50
        private const val MIN_PLATE_HEIGHT = 20
        private const val ASPECT_RATIO_MIN = 1.5f
        private const val ASPECT_RATIO_MAX = 4.0f
    }
    
    fun detectPlates(bitmap: Bitmap, detections: List<Detection>): List<PlateDetection> {
        val plates = mutableListOf<PlateDetection>()
        
        detections.filter { it.classId in setOf(2, 3, 5, 7) }.forEach { vehicle ->
            val plateCandidates = findPlateCandidates(bitmap, vehicle.bounds)
            plates.addAll(plateCandidates)
        }
        
        return plates
    }
    
    private fun findPlateCandidates(bitmap: Bitmap, vehicleBounds: RectF): List<PlateDetection> {
        val candidates = mutableListOf<PlateDetection>()
        
        val plateBounds = RectF(
            vehicleBounds.left + vehicleBounds.width() * 0.2f,
            vehicleBounds.bottom - vehicleBounds.height() * 0.3f,
            vehicleBounds.right - vehicleBounds.width() * 0.2f,
            vehicleBounds.bottom - vehicleBounds.height() * 0.1f
        )
        
        if (plateBounds.width() * bitmap.width > MIN_PLATE_WIDTH &&
            plateBounds.height() * bitmap.height > MIN_PLATE_HEIGHT) {
            
            val aspectRatio = (plateBounds.width() * bitmap.width) / (plateBounds.height() * bitmap.height)
            if (aspectRatio in ASPECT_RATIO_MIN..ASPECT_RATIO_MAX) {
                candidates.add(
                    PlateDetection(
                        bounds = plateBounds,
                        confidence = 0.7f,
                        plateText = "SCANNING...",
                        vehicleLabel = "Vehicle"
                    )
                )
            }
        }
        
        return candidates
    }
}

data class PlateDetection(
    val bounds: RectF,
    val confidence: Float,
    val plateText: String,
    val vehicleLabel: String
)
