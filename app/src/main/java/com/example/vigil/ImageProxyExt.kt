package com.example.vigil

import android.graphics.*
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream

fun ImageProxy.toBitmap(): Bitmap? {
    // Standard RGBA_8888 format (from ImageAnalysis output)
    // format 1 corresponds to PixelFormat.RGBA_8888
    if (format == 1) {
        val buffer = planes[0].buffer
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        buffer.rewind()
        bitmap.copyPixelsFromBuffer(buffer)
        return bitmap
    }
    
    // Handle YUV_420_888 (standard CameraX format)
    try {
        val yBuffer = planes[0].buffer
        val uBuffer = planes[1].buffer
        val vBuffer = planes[2].buffer
        
        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()
        
        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        
        val uArray = ByteArray(uSize)
        val vArray = ByteArray(vSize)
        uBuffer.get(uArray)
        vBuffer.get(vArray)
        
        for (i in 0 until uSize) {
            nv21[ySize + i * 2] = vArray[i]
            nv21[ySize + i * 2 + 1] = uArray[i]
        }
        
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val out = ByteArrayOutputStream()
        // Using quality 80 as requested
        yuvImage.compressToJpeg(Rect(0, 0, width, height), 80, out)
        val imageBytes = out.toByteArray()
        return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
    } catch (e: Exception) {
        return null
    }
}
