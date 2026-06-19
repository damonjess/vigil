package com.example.vigil

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

data class Detection(
    val label: String,
    val classId: Int,
    val confidence: Float,
    val bounds: RectF,
    val speedInfo: SpeedInfo = SpeedInfo(),
    var personId: String = "",
    var plateText: String = ""
)

data class SpeedInfo(
    val speedPxPerSec: Float = 0f,
    val speedMs: Float = 0f,
    val speedKmh: Float = 0f,
    val speedMph: Float = 0f,
    val speedMphDisplay: Int = 0,
    val direction: String = "stationary"
)

class Yolov8Detector(context: Context) {
    companion object {
        private const val TAG = "Yolov8Detector"
        private const val MODEL_PATH = "models/yolov8n_float32.tflite"
        private const val INPUT_SIZE = 640
        private const val NUM_CLASSES = 80
        private const val NUM_BOXES = 8400
        private const val CONF_THRESHOLD = 0.25f
        private const val IOU_THRESHOLD = 0.45f
        private val PERSON = 0
        private val VEHICLES = setOf(1, 2, 3, 5, 7)
    }

    var relevantClasses: Set<Int> = VEHICLES + PERSON

    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
    private val labels: List<String> = context.assets.open("labelmap.txt")
        .bufferedReader().readLines().filter { it.isNotBlank() }

    // Diagnostics
    var lastRawMaxScore: Float = 0f
        private set
    var lastRawMaxLabel: String = "none"
        private set
    var lastError: String? = null
        private set

    // Session peak
    var peakRelevantScore: Float = 0f
        private set
    var peakRelevantLabel: String = "none"
        private set

    // Speed tracking
    private val positionHistory = mutableMapOf<Int, MutableList<Pair<Float, Float>>>()
    private val timestampHistory = mutableMapOf<Int, MutableList<Long>>()
    private val MAX_HISTORY = 5
    private var frameWidth = 0
    private var frameHeight = 0

    fun resetPeak() {
        peakRelevantScore = 0f
        peakRelevantLabel = "none"
    }

    fun clearHistory() {
        positionHistory.clear()
        timestampHistory.clear()
    }

    init {
        try {
            val afd = context.assets.openFd(MODEL_PATH)
            val buffer: MappedByteBuffer = FileInputStream(afd.fileDescriptor).channel
                .map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
            
            val options = Interpreter.Options().apply {
                try {
                    gpuDelegate = GpuDelegate()
                    addDelegate(gpuDelegate)
                    setNumThreads(1) // GPU coordination
                    Log.d(TAG, "GPU delegate enabled")
                } catch (e: Exception) {
                    Log.w(TAG, "GPU delegate failed, using CPU", e)
                    setNumThreads(4)
                }
            }
            
            interpreter = Interpreter(buffer, options)
            Log.d(TAG, "YOLOv8 detector initialized")
            
        } catch (e: Exception) {
            lastError = "${e::class.simpleName}: ${e.message}"
            Log.e(TAG, "Initialization failed", e)
        }
    }

    fun isReady() = interpreter != null

    fun detect(bitmap: Bitmap): List<Detection> {
        val engine = interpreter ?: return emptyList()
        val startTime = System.currentTimeMillis()
        try {
            val input = preprocess(bitmap)
            val output = Array(1) { Array(4 + NUM_CLASSES) { FloatArray(NUM_BOXES) } }
            engine.run(input, output)
            val results = decode(output[0], bitmap.width, bitmap.height)
            
            PerformanceMonitor.recordInferenceTime(System.currentTimeMillis() - startTime)
            
            return results
        } catch (e: Exception) {
            lastError = "${e::class.simpleName}: ${e.message}"
            Log.e(TAG, "Detection failed", e)
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

    private fun decode(output: Array<FloatArray>, imgWidth: Int, imgHeight: Int): List<Detection> {
        this.frameWidth = imgWidth
        this.frameHeight = imgHeight

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

            if (bestClass in relevantClasses && bestScore > peakRelevantScore) {
                peakRelevantScore = bestScore
                peakRelevantLabel = labels.getOrElse(bestClass) { "?" }
            }

            if (bestScore >= CONF_THRESHOLD && bestClass in relevantClasses) {
                candidates.add(RawBox(output[0][i], output[1][i], output[2][i], output[3][i], bestClass, bestScore))
            }
        }

        lastRawMaxScore = rawBest
        lastRawMaxLabel = labels.getOrElse(rawBestClass) { "none" }

        val finalBoxes = mutableListOf<RawBox>()
        for (cls in relevantClasses) {
            val boxes = candidates.filter { it.classId == cls }.sortedByDescending { it.conf }.toMutableList()
            while (boxes.isNotEmpty()) {
                val best = boxes.removeAt(0)
                finalBoxes.add(best)
                boxes.removeAll { iou(best, it) > IOU_THRESHOLD }
            }
        }

        return finalBoxes.map { raw ->
            val bounds = RectF(
                (raw.cx - raw.w / 2f).coerceIn(0f, 1f),
                (raw.cy - raw.h / 2f).coerceIn(0f, 1f),
                (raw.cx + raw.w / 2f).coerceIn(0f, 1f),
                (raw.cy + raw.h / 2f).coerceIn(0f, 1f)
            )
            
            val detection = Detection(
                label = labels.getOrElse(raw.classId) { "?" },
                classId = raw.classId,
                confidence = raw.conf,
                bounds = bounds,
                speedInfo = SpeedInfo()
            )
            
            val speedInfo = calculateSpeed(detection)
            detection.copy(speedInfo = speedInfo)
        }
    }

    fun calculateSpeed(detection: Detection): SpeedInfo {
        val key = detection.classId * 1000 + (detection.bounds.centerX() * 100).toInt()
        val centerX = detection.bounds.centerX()
        val centerY = detection.bounds.centerY()
        val now = System.currentTimeMillis()
        
        val positions = positionHistory.getOrPut(key) { mutableListOf() }
        val timestamps = timestampHistory.getOrPut(key) { mutableListOf() }
        
        positions.add(centerX to centerY)
        timestamps.add(now)
        
        if (positions.size > MAX_HISTORY) {
            positions.removeAt(0)
            timestamps.removeAt(0)
        }
        
        var speedPxPerSec = 0f
        var direction = "stationary"
        
        if (positions.size >= 2) {
            val dx = positions.last().first - positions.first().first
            val dy = positions.last().second - positions.first().second
            val dt = (timestamps.last() - timestamps.first()) / 1000f
            
            if (dt > 0.1f) {
                speedPxPerSec = kotlin.math.sqrt(dx*dx + dy*dy) / dt
                
                direction = when {
                    kotlin.math.abs(dx) > kotlin.math.abs(dy) -> {
                        if (dx > 0) "→ Right" else "← Left"
                    }
                    dy < 0 -> "↑ Up"
                    dy > 0 -> "↓ Down"
                    else -> "stationary"
                }
            }
        }
        
        val speedMs = speedPxPerSec * 0.3f
        val speedKmh = speedMs * 3.6f
        val speedMph = speedMs * 2.23694f
        
        return SpeedInfo(
            speedPxPerSec = speedPxPerSec,
            speedMs = speedMs,
            speedKmh = speedKmh,
            speedMph = speedMph,
            speedMphDisplay = speedMph.toInt(),
            direction = direction
        )
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

    fun close() {
        interpreter?.close()
        gpuDelegate?.close()
    }
}
