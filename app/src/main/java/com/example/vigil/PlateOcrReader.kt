package com.example.vigil

import android.graphics.Bitmap
import android.graphics.RectF
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Runs ML Kit's on-device text recognizer against the lower-center region of a
 * vehicle's bounding box (where plates typically sit) and filters the result down
 * to something that looks like a plate rather than arbitrary text.
 */
class PlateOcrReader {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun readPlate(bitmap: Bitmap, vehicleBounds: RectF): String? {
        val crop = cropPlateRegion(bitmap, vehicleBounds) ?: return null
        val rawText = try {
            runRecognizer(crop)
        } finally {
            if (crop !== bitmap) crop.recycle()
        }
        return cleanPlateCandidate(rawText)
    }

    private suspend fun runRecognizer(bitmap: Bitmap): String =
        suspendCancellableCoroutine { cont ->
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image)
                .addOnSuccessListener { result -> cont.resume(result.text) }
                .addOnFailureListener { cont.resume("") }
        }

    private fun cropPlateRegion(bitmap: Bitmap, bounds: RectF): Bitmap? {
        val bw = bitmap.width
        val bh = bitmap.height
        if (bw <= 0 || bh <= 0) return null

        // Plates sit roughly in the lower-middle 60% width / bottom 30% height of the vehicle box
        val left = (bounds.left + bounds.width() * 0.2f) * bw
        val right = (bounds.right - bounds.width() * 0.2f) * bw
        val top = (bounds.bottom - bounds.height() * 0.3f) * bh
        val bottom = bounds.bottom * bh

        val l = left.toInt().coerceIn(0, bw - 1)
        val t = top.toInt().coerceIn(0, bh - 1)
        val r = right.toInt().coerceIn(l + 1, bw)
        val b = bottom.toInt().coerceIn(t + 1, bh)

        val w = r - l
        val h = b - t
        if (w < 20 || h < 10) return null

        return try {
            Bitmap.createBitmap(bitmap, l, t, w, h)
        } catch (e: Exception) {
            null
        }
    }

    // Plates are short, alphanumeric, no lowercase/punctuation in practice once cleaned.
    // This rejects OCR noise (background text, random characters) rather than trying
    // to validate any specific country's plate format.
    private fun cleanPlateCandidate(rawText: String): String? {
        val cleaned = rawText
            .uppercase()
            .replace(Regex("[^A-Z0-9]"), "")

        if (cleaned.length < 4 || cleaned.length > 10) return null
        if (!cleaned.any { it.isDigit() }) return null // real plates almost always have a digit
        return cleaned
    }

    fun close() {
        recognizer.close()
    }
}