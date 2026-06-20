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
    var plateText: String = "",
    val trackId: Int = -1 // -1 until ObjectTracker.update() assigns a real one
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
        private const val CONF_THRESHOLD = 0.18f
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

    fun resetPeak() {
        peakRelevantScore = 0f
        peakRelevantLabel = "none"
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
            val results = decode(output[0])
            PerformanceMonitor.recordInferenceTime(System.currentTimeMillis() - startTime)
            return results
        } catch (e: Exception) {
            lastError = "${e::class.simpleName}: ${e.message}"
            Log.e(TAG, "Detection failed", e)
            return emptyList()
        }
    }

    // Letterbox state from the most recent preprocess() call — decode() needs these
    // to map model-space boxes back to the original frame correctly, instead of
    // assuming a plain stretch (which distorts non-square frames at an angle).
    private var letterboxScale = 1f
    private var letterboxPadX = 0f
    private var letterboxPadY = 0f
    private var imgWidth = 0
    private var imgHeight = 0

    private fun preprocess(bitmap: Bitmap): ByteBuffer {
        val srcW = bitmap.width
        val srcH = bitmap.height
        imgWidth = srcW
        imgHeight = srcH

        val scale = minOf(INPUT_SIZE.toFloat() / srcW, INPUT_SIZE.toFloat() / srcH)
        val scaledW = (srcW * scale).toInt().coerceAtLeast(1)
        val scaledH = (srcH * scale).toInt().coerceAtLeast(1)
        val padX = (INPUT_SIZE - scaledW) / 2f
        val padY = (INPUT_SIZE - scaledH) / 2f

        letterboxScale = scale
        letterboxPadX = padX
        letterboxPadY = padY

        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, scaledW, scaledH, true)

        // Gray canvas (114,114,114) — the Ultralytics letterbox convention — with the
        // scaled image centered on it, instead of stretching to fill the full square.
        val canvas = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888)
        val canvasObj = android.graphics.Canvas(canvas)
        canvasObj.drawColor(android.graphics.Color.rgb(114, 114, 114))
        canvasObj.drawBitmap(scaledBitmap, padX, padY, null)
        if (scaledBitmap !== bitmap) scaledBitmap.recycle()

        val buffer = ByteBuffer.allocateDirect(4 * INPUT_SIZE * INPUT_SIZE * 3)
        buffer.order(ByteOrder.nativeOrder())
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        canvas.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        for (p in pixels) {
            buffer.putFloat(((p shr 16) and 0xFF) / 255.0f)
            buffer.putFloat(((p shr 8) and 0xFF) / 255.0f)
            buffer.putFloat((p and 0xFF) / 255.0f)
        }
        buffer.rewind()
        canvas.recycle()
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

        return finalBoxes.mapNotNull { raw ->
            // raw.cx/cy/w/h are normalized 0..1 against the padded 640x640 input.
            // Convert to pixel space in that 640x640 frame, remove the letterbox
            // padding/scale, then re-normalize against the ORIGINAL frame size.
            val cxPix = raw.cx * INPUT_SIZE
            val cyPix = raw.cy * INPUT_SIZE
            val wPix = raw.w * INPUT_SIZE
            val hPix = raw.h * INPUT_SIZE

            val origCx = (cxPix - letterboxPadX) / letterboxScale
            val origCy = (cyPix - letterboxPadY) / letterboxScale
            val origW = wPix / letterboxScale
            val origH = hPix / letterboxScale

            // Reject implausibly tiny boxes — a common pattern in low-confidence
            // false positives (tree branches, sky texture, etc.)
            val boxArea = origW * origH
            val frameArea = imgWidth.toFloat() * imgHeight.toFloat()
            if (boxArea / frameArea < 0.0015f) return@mapNotNull null

            val bounds = RectF(
                ((origCx - origW / 2f) / imgWidth).coerceIn(0f, 1f),
                ((origCy - origH / 2f) / imgHeight).coerceIn(0f, 1f),
                ((origCx + origW / 2f) / imgWidth).coerceIn(0f, 1f),
                ((origCy + origH / 2f) / imgHeight).coerceIn(0f, 1f)
            )
            
            Detection(
                label = labels.getOrElse(raw.classId) { "?" },
                classId = raw.classId,
                confidence = raw.conf,
                bounds = bounds,
                speedInfo = SpeedInfo() // ObjectTracker fills this in after matching across frames
            )
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

    fun close() {
        interpreter?.close()
        gpuDelegate?.close()
    }
}
