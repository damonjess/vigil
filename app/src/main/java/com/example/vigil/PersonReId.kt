package com.example.vigil

import android.graphics.Bitmap
import android.graphics.RectF

class PersonReId {
    companion object {
        private const val MAX_HISTORY = 10
    }
    
    private val personHistory = mutableMapOf<String, MutableList<PersonSignature>>()
    private var nextId = 1
    
    data class PersonSignature(
        val personId: String,
        val colorHistogram: FloatArray,
        val boundingBox: RectF,
        val confidence: Float
    )
    
    data class ReIdResult(
        val personId: String,
        val confidence: Float,
        val isNewPerson: Boolean
    )
    
    fun identifyPerson(bitmap: Bitmap, detection: Detection): ReIdResult {
        val histogram = extractColorHistogram(bitmap, detection.bounds)
        
        var bestMatch = ""
        var bestScore = 0f
        
        personHistory.forEach { (id, signatures) ->
            signatures.forEach { signature ->
                val score = compareHistograms(histogram, signature.colorHistogram)
                if (score > bestScore) {
                    bestScore = score
                    bestMatch = id
                }
            }
        }
        
        val threshold = 0.7f
        return if (bestScore > threshold && bestMatch.isNotEmpty()) {
            val id = bestMatch
            addPerson(id, histogram, detection)
            ReIdResult(
                personId = id,
                confidence = bestScore,
                isNewPerson = false
            )
        } else {
            val id = "P${nextId++}"
            addPerson(id, histogram, detection)
            ReIdResult(
                personId = id,
                confidence = 1f,
                isNewPerson = true
            )
        }
    }
    
    private fun extractColorHistogram(bitmap: Bitmap, bounds: RectF): FloatArray {
        val left = (bounds.left * bitmap.width).toInt().coerceAtLeast(0)
        val top = (bounds.top * bitmap.height).toInt().coerceAtLeast(0)
        val right = (bounds.right * bitmap.width).toInt().coerceAtMost(bitmap.width)
        val bottom = (bounds.bottom * bitmap.height).toInt().coerceAtMost(bitmap.height)
        
        val width = right - left
        val height = bottom - top
        
        if (width <= 0 || height <= 0) return FloatArray(64)
        
        val histogram = FloatArray(64)
        val crop = try {
            Bitmap.createBitmap(bitmap, left, top, width, height)
        } catch (e: Exception) {
            return FloatArray(64)
        }
        
        for (x in 0 until width step 4) {
            for (y in 0 until height step 4) {
                val pixel = crop.getPixel(x, y)
                val r = ((pixel shr 16) and 0xFF) / 255f
                val g = ((pixel shr 8) and 0xFF) / 255f
                val b = (pixel and 0xFF) / 255f
                
                val rBin = (r * 3.99f).toInt()
                val gBin = (g * 3.99f).toInt()
                val bBin = (b * 3.99f).toInt()
                
                val index = rBin * 16 + gBin * 4 + bBin
                histogram[index] += 1
            }
        }
        
        val total = histogram.sum()
        if (total > 0) {
            for (i in histogram.indices) {
                histogram[i] /= total
            }
        }
        
        crop.recycle()
        return histogram
    }
    
    private fun compareHistograms(a: FloatArray, b: FloatArray): Float {
        var similarity = 0f
        for (i in a.indices) {
            similarity += kotlin.math.min(a[i], b[i])
        }
        return similarity
    }
    
    private fun addPerson(id: String, histogram: FloatArray, detection: Detection) {
        val signature = PersonSignature(
            personId = id,
            colorHistogram = histogram,
            boundingBox = detection.bounds,
            confidence = detection.confidence
        )
        
        val history = personHistory.getOrPut(id) { mutableListOf() }
        history.add(signature)
        if (history.size > MAX_HISTORY) {
            history.removeAt(0)
        }
    }
    
    fun getPersonCount(): Int = personHistory.size
    fun getPersonIds(): List<String> = personHistory.keys.toList()
    fun clear() { personHistory.clear(); nextId = 1 }
}
