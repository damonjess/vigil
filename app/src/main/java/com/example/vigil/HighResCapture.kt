package com.example.vigil

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.util.Log
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import java.util.concurrent.Executor

/**
 * Helper to handle high-resolution still captures from CameraX.
 * This is used to get a higher quality crop for saved detections compared to
 * the analysis frames.
 */
class HighResCapture {
    companion object {
        private const val TAG = "HighResCapture"
    }

    val imageCapture: ImageCapture = ImageCapture.Builder()
        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
        .build()

    fun captureAndCrop(
        executor: Executor,
        bounds: RectF,
        onResult: (Bitmap?) -> Unit
    ) {
        imageCapture.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val bitmap = image.toBitmap()
                val rotation = image.imageInfo.rotationDegrees
                image.close()

                if (bitmap != null) {
                    // Orientation correction
                    val rotatedBitmap = if (rotation != 0) {
                        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                        try {
                            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also {
                                if (it != bitmap) bitmap.recycle()
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Rotation failed", e)
                            bitmap
                        }
                    } else {
                        bitmap
                    }
                    
                    val cropped = crop(rotatedBitmap, bounds)
                    onResult(cropped)
                } else {
                    onResult(null)
                }
            }

            override fun onError(exception: ImageCaptureException) {
                Log.e(TAG, "High-res capture failed: ${exception.message}", exception)
                onResult(null)
            }
        })
    }

    private fun crop(bitmap: Bitmap, bounds: RectF): Bitmap? {
        return try {
            val w = bitmap.width
            val h = bitmap.height
            
            // Add padding (30% like in MainActivity.cropDetection)
            val paddingX = (bounds.width() * w * 0.3f).toInt().coerceAtLeast(20)
            val paddingY = (bounds.height() * h * 0.3f).toInt().coerceAtLeast(20)
            
            val left = ((bounds.left * w) - paddingX).toInt().coerceAtLeast(0)
            val top = ((bounds.top * h) - paddingY).toInt().coerceAtLeast(0)
            val right = ((bounds.right * w) + paddingX).toInt().coerceAtMost(w)
            val bottom = ((bounds.bottom * h) + paddingY).toInt().coerceAtMost(h)
            
            val width = right - left
            val height = bottom - top
            
            if (width >= 20 && height >= 20) {
                Bitmap.createBitmap(bitmap, left, top, width, height)
            } else {
                Log.w(TAG, "Crop too small: ${width}x${height}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Crop failed", e)
            null
        }
    }
}