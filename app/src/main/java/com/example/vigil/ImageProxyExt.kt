package com.example.vigil

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream

fun ImageProxy.toBitmap(): Bitmap? {
    if (format == PixelFormat.RGBA_8888) {
        val buffer = planes[0].buffer
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        buffer.rewind()
        bitmap.copyPixelsFromBuffer(buffer)
        return bitmap
    }
    
    // Handle YUV_420_888
    try {
        val yBuffer = planes[0].buffer
        val uBuffer = planes[1].buffer
        val vBuffer = planes[2].buffer
        
        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()
        
        val nv21 = ByteArray(ySize + uSize + vSize)
        
        // Copy Y
        yBuffer.get(nv21, 0, ySize)
        
        // Copy UV (interleaved)
        val uArray = ByteArray(uSize)
        val vArray = ByteArray(vSize)
        uBuffer.get(uArray)
        vBuffer.get(vArray)
        
        // Interleave V and U (NV21 format)
        for (i in 0 until uSize) {
            nv21[ySize + i * 2] = vArray[i]
            nv21[ySize + i * 2 + 1] = uArray[i]
        }
        
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, width, height), 70, out)
        val imageBytes = out.toByteArray()
        return android.graphics.BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
    } catch (e: Exception) {
        return null
    }
}