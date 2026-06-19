package com.example.vigil

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

data class Detection(
    val label: String,
    val classId: Int,
    val confidence: Float,
    val bounds: RectF, // normalized 0..1
    val speedKmh: Float = 0f,
    val speedMph: Float = 0f
)

fun Detection.copySafe(
    label: String = this.label,
    classId: Int = this.classId,
    confidence: Float = this.confidence,
    bounds: RectF = this.bounds,
    speedKmh: Float = this.speedKmh,
    speedMph: Float = this.speedMph
): Detection {
    return Detection(
        label = label,
        classId = classId,
        confidence = confidence,
        bounds = RectF(
            bounds.left.coerceIn(0f, 1f),
            bounds.top.coerceIn(0f, 1f),
            bounds.right.coerceIn(0f, 1f),
            bounds.bottom.coerceIn(0f, 1f)
        ),
        speedKmh = speedKmh,
        speedMph = speedMph
    )
}

class Yolov8Detector(context: Context) {
    companion object {
        private const val MODEL_PATH = "models/yolov8n_float32.tflite"
        private const val INPUT_SIZE = 640
        private const val NUM_CLASSES = 80
        private const val NUM_BOXES = 8400
        private const val CONF_THRESHOLD = 0.25f
        private const val IOU_THRESHOLD = 0.45f
        private val PERSON = 0
        private val VEHICLES = setOf(1, 2, 3, 5, 7)
        private val RELEVANT = VEHICLES + PERSON
        
        private const val MAX_HISTORY = 5
    }

    private var interpreter: Interpreter? = null
    private val labels: List<String> = context.assets.open("labelmap.txt")
        .bufferedReader().readLines().filter { it.isNotBlank() }

    // Diagnostics — raw best score across ALL classes, no filtering at all
    var lastRawMaxScore: Float = 0f
        private set
    var lastRawMaxLabel: String = "none"
        private set
    var lastError: String? = null
        private set

    // Session peak — best score ever seen for a PERSON/VEHICLE class specifically
    var peakRelevantScore: Float = 0f
        private set
    var peakRelevantLabel: String = "none"
        private set

    // Speed tracking state
    private val positionHistory = mutableMapOf<Int, MutableList<Pair<Float, Float>>>()
    private val timestampHistory = mutableMapOf<Int, MutableList<Long>>()
    private var frameWidth = 0
    private var frameHeight = 0

    fun resetPeak() {
        peakRelevantScore = 0f
        peakRelevantLabel = "none"
        positionHistory.clear()
        timestampHistory.clear()
    }

    init {
        try {
            val afd = context.assets.openFd(MODEL_PATH)
            val buffer: MappedByteBuffer = FileInputStream(afd.fileDescriptor).channel
                .map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
            interpreter = Interpreter(buffer, Interpreter.Options().apply { setNumThreads(4) })
        } catch (e: Exception) {
            lastError = "${e::class.simpleName}: ${e.message}"
        }
    }

    fun isReady() = interpreter != null

    fun detect(bitmap: Bitmap): List<Detection> {
        val engine = interpreter ?: return emptyList()
        frameWidth = bitmap.width
        frameHeight = bitmap.height
        
        try {
            val input = preprocess(bitmap)
            val output = Array(1) { Array(4 + NUM_CLASSES) { FloatArray(NUM_BOXES) } }
            engine.run(input, output)
            val baseDetections = decode(output[0])
            return trackSpeed(baseDetections)
        } catch (e: Exception) {
            lastError = "${e::class.simpleName}: ${e.message}"
            return emptyList()
        }
    }

    private fun preprocess(bitmap: Bitmap): ByteBuffer {
        val resized = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
        val buffer = ByteBuffer.allocateDirect(4 * INPUT_SIZE * INPUT_SIZE * 3)
        buffer.order(ByteOrder.nativeOrder())
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        resized.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        for (p in pixels) {
            buffer.putFloat(((p shr 16) and 0xFF) / 255.0f)
            buffer.putFloat(((p shr 8) and 0xFF) / 255.0f)
            buffer.putFloat((p and 0xFF) / 255.0f)
        }
        buffer.rewind()
        if (resized !== bitmap) resized.recycle()
        return buffer
    }

    private data class RawBox(val cx: Float, val cy: Float, val w: Float, val h: Float, val classId: Int, val conf: Float)

    private fun decode(output: Array<FloatArray>): List<Detection> {
        val candidates = mutableListOf<RawBox>()
        var rawBest = 0f
        var rawBestClass = -1

        for (i in 0 until NUM_BOXES) {
            var bestClass = -1
            var bestScore = 0f
            for (c in 0 until NUM_CLASSES) {
                val s = output[4 + c][i]
                if (s > bestScore) { bestScore = s; bestClass = c }
            }
            if (bestScore > rawBest) { rawBest = bestScore; rawBestClass = bestClass }

            if (bestClass in RELEVANT && bestScore > peakRelevantScore) {
                peakRelevantScore = bestScore
                peakRelevantLabel = labels.getOrElse(bestClass) { "?" }
            }

            if (bestScore >= CONF_THRESHOLD && bestClass in RELEVANT) {
                candidates.add(RawBox(output[0][i], output[1][i], output[2][i], output[3][i], bestClass, bestScore))
            }
        }

        lastRawMaxScore = rawBest
        lastRawMaxLabel = labels.getOrElse(rawBestClass) { "none" }

        val finalBoxes = mutableListOf<RawBox>()
        for (cls in RELEVANT) {
            val boxes = candidates.filter { it.classId == cls }.sortedByDescending { it.conf }.toMutableList()
            while (boxes.isNotEmpty()) {
                val best = boxes.removeAt(0)
                finalBoxes.add(best)
                boxes.removeAll { iou(best, it) > IOU_THRESHOLD }
            }
        }

        return finalBoxes.map {
            Detection(
                label = labels.getOrElse(it.classId) { "?" },
                classId = it.classId,
                confidence = it.conf,
                bounds = RectF(
                    (it.cx - it.w / 2f).coerceIn(0f, 1f),
                    (it.cy - it.h / 2f).coerceIn(0f, 1f),
                    (it.cx + it.w / 2f).coerceIn(0f, 1f),
                    (it.cy + it.h / 2f).coerceIn(0f, 1f)
                )
            )
        }
    }

    private fun trackSpeed(detections: List<Detection>): List<Detection> {
        val now = System.currentTimeMillis()
        
        // Clean up stale history (older than 2 seconds)
        val keysToRemove = mutableListOf<Int>()
        timestampHistory.forEach { (key, timestamps) ->
            if (now - timestamps.last() > 2000) {
                keysToRemove.add(key)
            }
        }
        keysToRemove.forEach { 
            positionHistory.remove(it)
            timestampHistory.remove(it)
        }

        return detections.map { det ->
            // Improved tracking key: use classId and discretized position to "stick" to objects
            // In a real app, you'd use a proper Kalman filter or SORT algorithm
            val gridX = (det.bounds.centerX() * 20).toInt()
            val gridY = (det.bounds.centerY() * 20).toInt()
            val key = det.classId * 10000 + gridX * 100 + gridY
            
            val centerX = det.bounds.centerX()
            val centerY = det.bounds.centerY()
            
            val positions = positionHistory.getOrPut(key) { mutableListOf() }
            val timestamps = timestampHistory.getOrPut(key) { mutableListOf() }
            
            positions.add(centerX to centerY)
            timestamps.add(now)
            
            if (positions.size > MAX_HISTORY) {
                positions.removeAt(0)
                timestamps.removeAt(0)
            }
            
            var speedKmh = 0f
            var speedMph = 0f
            
            if (positions.size >= 2) {
                val dx = (positions.last().first - positions.first().first) * frameWidth
                val dy = (positions.last().second - positions.first().second) * frameHeight
                val dt = (timestamps.last() - timestamps.first()) / 1000f
                
                if (dt > 0.1f) { // Need at least 100ms for stable calculation
                    val speedPxPerSec = kotlin.math.sqrt(dx*dx + dy*dy) / dt
                    
                    // HEURISTIC: Assume average object is 10 meters away and occupies a certain portion of the screen
                    // This is a VERY rough estimate for speed tracking without depth info
                    val metersPerPixel = 0.005f 
                    val speedMs = speedPxPerSec * metersPerPixel
                    speedKmh = speedMs * 3.6f
                    speedMph = speedMs * 2.23694f
                }
            }
            
            det.copy(speedKmh = speedKmh, speedMph = speedMph)
        }
    }

    private fun iou(a: RawBox, b: RawBox): Float {
        val ax1 = a.cx - a.w/2f; val ay1 = a.cy - a.h/2f; val ax2 = a.cx + a.w/2f; val ay2 = a.cy + a.h/2f
        val bx1 = b.cx - b.w/2f; val by1 = b.cy - b.h/2f; val bx2 = b.cx + b.w/2f; val by2 = b.cy + b.h/2f
        val iw = (minOf(ax2, bx2) - maxOf(ax1, bx1)).coerceAtLeast(0f)
        val ih = (minOf(ay2, by2) - maxOf(ay1, by1)).coerceAtLeast(0f)
        val inter = iw * ih
        val union = (ax2-ax1)*(ay2-ay1) + (bx2-bx1)*(by2-by1) - inter
        return if (union <= 0f) 0f else inter / union
    }
}