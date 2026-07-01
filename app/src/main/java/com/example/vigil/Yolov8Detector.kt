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
    val trackId: Int = -1,
    val sectorX: Int,
    val sectorY: Int
)

data class SpeedInfo(
    val speedPxPerSec: Float = 0f,
    val speedMs: Float = 0f,
    val speedKmh: Float = 0f,
    val speedMph: Float = 0f,
    val speedMphDisplay: Int = 0,
    val direction: String = "stationary"
)

class Yolov8Detector(context: Context) : AutoCloseable {
    companion object {
        private const val TAG = "Yolov8Detector"
        private const val MODEL_PATH = "models/yolov8n_float32.tflite"
        private const val INPUT_SIZE = 640
        private const val NUM_CLASSES = 80
        private const val NUM_BOXES = 8400
        private const val DEFAULT_CONF_THRESHOLD = 0.25f
        private const val DEFAULT_IOU_THRESHOLD = 0.45f
    }

    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
    
    // Compatibility fields for MainActivity
    var lastError: String? = null
        private set
    var peakRelevantScore: Float = 0f
        private set
    var peakRelevantLabel: String = "none"
        private set

    val labels: List<String> = try {
        context.assets.open("labelmap.txt").bufferedReader().readLines().filter { it.isNotBlank() }
    } catch(e: Exception) {
        lastError = "Labels load failed: ${"${e.message}"}"
        List(80) { "Object $it" }
    }
    
    private val contentCanvasBitmap = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888)
    private val executionCanvas = android.graphics.Canvas(contentCanvasBitmap)
    private val matrixTransform = android.graphics.Matrix()
    private val framePixelsBuffer = IntArray(INPUT_SIZE * INPUT_SIZE)
    private val byteBufferAllocation = ByteBuffer.allocateDirect(4 * INPUT_SIZE * INPUT_SIZE * 3).apply {
        order(ByteOrder.nativeOrder())
    }

    init {
        try {
            val afd = context.assets.openFd(MODEL_PATH)
            val buffer: MappedByteBuffer = FileInputStream(afd.fileDescriptor).channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
            val options = Interpreter.Options().apply {
                try {
                    gpuDelegate = GpuDelegate()
                    addDelegate(gpuDelegate)
                } catch (e: Exception) {
                    Log.w(TAG, "GPU Delegate failed, falling back to CPU", e)
                    setUseXNNPACK(true)
                    setNumThreads(4)
                }
            }
            interpreter = Interpreter(buffer, options)
        } catch (e: Exception) {
            lastError = "${"${e::class.simpleName}"}: ${"${e.message}"}"
            Log.e(TAG, "Interpreter init failed", e)
        }
    }

    fun isReady() = interpreter != null

    fun detect(
        bitmap: Bitmap, 
        confidenceThreshold: Float = DEFAULT_CONF_THRESHOLD, 
        iouThreshold: Float = DEFAULT_IOU_THRESHOLD
    ): List<Detection> {
        val engine = interpreter ?: return emptyList()
        try {
            val preprocessed = preprocess(bitmap)
            val output = Array(1) { Array(4 + NUM_CLASSES) { FloatArray(NUM_BOXES) } }
            engine.run(preprocessed.buffer, output)
            return decode(output[0], preprocessed, confidenceThreshold, iouThreshold)
        } catch (e: Exception) {
            lastError = "Detection error: ${"${e.message}"}"
            return emptyList()
        }
    }

    private data class PreprocessingResult(val buffer: ByteBuffer, val scale: Float, val padX: Float, val padY: Float, val origW: Int, val origH: Int)

    private fun preprocess(bitmap: Bitmap): PreprocessingResult {
        val srcW = bitmap.width
        val srcH = bitmap.height
        val scale = minOf(INPUT_SIZE.toFloat() / srcW, INPUT_SIZE.toFloat() / srcH)
        val padX = (INPUT_SIZE - srcW * scale) / 2f
        val padY = (INPUT_SIZE - srcH * scale) / 2f

        executionCanvas.drawColor(android.graphics.Color.rgb(114, 114, 114))
        matrixTransform.reset()
        matrixTransform.postScale(scale, scale)
        matrixTransform.postTranslate(padX, padY)
        executionCanvas.drawBitmap(bitmap, matrixTransform, null)
        
        byteBufferAllocation.rewind()
        contentCanvasBitmap.getPixels(framePixelsBuffer, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        
        for (p in framePixelsBuffer) {
            byteBufferAllocation.putFloat(((p shr 16) and 0xFF) / 255.0f)
            byteBufferAllocation.putFloat(((p shr 8) and 0xFF) / 255.0f)
            byteBufferAllocation.putFloat((p and 0xFF) / 255.0f)
        }
        byteBufferAllocation.rewind()
        return PreprocessingResult(byteBufferAllocation, scale, padX, padY, srcW, srcH)
    }

    private fun decode(output: Array<FloatArray>, preprocessed: PreprocessingResult, confThreshold: Float, iouThreshold: Float): List<Detection> {
        val candidates = mutableListOf<RawBox>()
        for (i in 0 until NUM_BOXES) {
            var bestClass = -1
            var bestScore = 0f
            for (c in 0 until NUM_CLASSES) {
                val s = output[4 + c][i]
                if (s > bestScore) { bestScore = s; bestClass = c }
            }
            
            // Update peak stats for any class detected above a minimal threshold
            if (bestScore > peakRelevantScore) {
                peakRelevantScore = bestScore
                peakRelevantLabel = labels.getOrElse(bestClass) { "Object $bestClass" }
            }

            if (bestScore >= confThreshold) {
                candidates.add(RawBox(output[0][i], output[1][i], output[2][i], output[3][i], bestClass, bestScore))
            }
        }
        
        val finalBoxes = mutableListOf<RawBox>()
        val groupedClasses = candidates.groupBy { it.classId }
        for ((_, boxes) in groupedClasses) {
            val sortedBoxes = boxes.sortedByDescending { it.conf }.toMutableList()
            while (sortedBoxes.isNotEmpty()) {
                val best = sortedBoxes.removeAt(0)
                finalBoxes.add(best)
                sortedBoxes.removeAll { iou(best, it) > iouThreshold }
            }
        }
        
        return finalBoxes.map { raw ->
            val cxPix = raw.cx * INPUT_SIZE
            val cyPix = raw.cy * INPUT_SIZE
            val wPix = raw.w * INPUT_SIZE
            val hPix = raw.h * INPUT_SIZE
            val origCx = (cxPix - preprocessed.padX) / preprocessed.scale
            val origCy = (cyPix - preprocessed.padY) / preprocessed.scale
            val origW = wPix / preprocessed.scale
            val origH = hPix / preprocessed.scale
            
            val bounds = RectF(
                ((origCx - origW / 2f) / preprocessed.origW).coerceIn(0f, 1f),
                ((origCy - origH / 2f) / preprocessed.origH).coerceIn(0f, 1f),
                ((origCx + origW / 2f) / preprocessed.origW).coerceIn(0f, 1f),
                ((origCy + origH / 2f) / preprocessed.origH).coerceIn(0f, 1f)
            )
            Detection(labels.getOrElse(raw.classId) { "Object" }, raw.classId, raw.conf, bounds, sectorX = ((bounds.centerX() * 1000).toInt()), sectorY = ((bounds.centerY() * 1000).toInt()))
        }
    }

    private data class RawBox(val cx: Float, val cy: Float, val w: Float, val h: Float, val classId: Int, val conf: Float)
    private fun iou(a: RawBox, b: RawBox): Float {
        val ax1 = a.cx - a.w/2f; val ay1 = a.cy - a.h/2f; val ax2 = a.cx + a.w/2f; val ay2 = a.cy + a.h/2f
        val bx1 = b.cx - b.w/2f; val by1 = b.cy - b.h/2f; val bx2 = b.cx + b.w/2f; val by2 = b.cy + b.h/2f
        val iw = (minOf(ax2, bx2) - maxOf(ax1, bx1)).coerceAtLeast(0f)
        val ih = (minOf(ay2, by2) - maxOf(ay1, by1)).coerceAtLeast(0f)
        val inter = iw * ih
        val union = (ax2-ax1)*(ay2-ay1) + (bx2-bx1)*(by2-by1) - inter
        return if (union <= 0f) 0f else inter / union
    }

    override fun close() {
        interpreter?.close()
        gpuDelegate?.close()
        contentCanvasBitmap.recycle()
    }
}