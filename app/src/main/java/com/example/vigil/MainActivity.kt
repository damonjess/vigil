package com.example.vigil

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.view.PreviewView
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.example.vigil.data.DetectionLog
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.coroutines.resume

class MainActivity : ComponentActivity() {
    companion object {
        private const val TAG = "MainActivity"
        private const val FRAME_INTERVAL_MS = 30L
        private const val PERSON_CONFIDENCE_THRESHOLD = 0.35f
        private const val MIN_PERSON_AREA = 0.001f
    }

    private var lastFrameTime = 0L
    private var frameCount = 0
    private var lastFpsUpdate = 0L
    private var currentFps = 0
    private var personDetectionCount = 0
    private var lastPersonTimestamp = 0L
    private var lastVehicleTimestamp = 0L
    private var lastVideoTimestamp = 0L
    private var isProcessing = false
    private var currentRecording: Recording? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF0A0A0A)) {
                    CameraPermissionGate()
                }
            }
        }
    }

    @Composable
    fun CameraPermissionGate() {
        val context = LocalContext.current
        val permissions = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
        
        var hasPermissions by remember {
            mutableStateOf(
                permissions.all {
                    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
                }
            )
        }

        val launcher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { results -> hasPermissions = results.values.all { it } }

        LaunchedEffect(Unit) {
            if (!hasPermissions) launcher.launch(permissions)
        }

        if (hasPermissions) {
            CameraPreviewScreen()
        } else {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Camera, null, tint = Color(0xFF00FF41), modifier = Modifier.size(64.dp))
                    Spacer(Modifier.height(16.dp))
                    Text("Camera Access Required", color = Color(0xFF00FF41), fontSize = 24.sp, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.height(8.dp))
                    Text("Vigil needs camera access for AI detection", color = Color.Gray, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = { 
                        launcher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)) 
                    },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00FF41)),
                        shape = RoundedCornerShape(8.dp)) {
                        Text("Grant Permission", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    @Composable
    fun CameraPreviewScreen() {
        val context = LocalContext.current
        val lifecycleOwner = LocalLifecycleOwner.current
        val scope = rememberCoroutineScope()
        
        val detector = remember { Yolov8Detector(context) }
        val tracker = remember { ObjectTracker() }
        val storage = remember { DetectionStorage(context) }
        val reId = remember { PersonReId() }
        val plateReader = remember { PlateOcrReader() }
        val lastSavedByTrack = remember { mutableMapOf<Int, Long>() }
        val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
        
        DisposableEffect(Unit) {
            onDispose {
                plateReader.close()
                analysisExecutor.shutdown()
            }
        }
        
        var statusText by remember { mutableStateOf("Loading model...") }
        var detections by remember { mutableStateOf<List<Detection>>(emptyList()) }
        var recentLogs by remember { mutableStateOf<List<DetectionLog>>(emptyList()) }
        var modelReady by remember { mutableStateOf(false) }
        var fpsText by remember { mutableStateOf("0 FPS") }
        var statsText by remember { mutableStateOf("Stats: ...") }
        var showLogs by remember { mutableStateOf(false) }
        var selectedLog by remember { mutableStateOf<DetectionLog?>(null) }
        var autoZoomActive by remember { mutableStateOf(true) }
        var showStats by remember { mutableStateOf(true) }
        var detectorEnabled by remember { mutableStateOf(true) }
        var imageCaptureUseCase by remember { mutableStateOf<ImageCapture?>(null) }
        var videoCaptureUseCase by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }



        val infiniteTransition = rememberInfiniteTransition()
        val pulseAlpha by infiniteTransition.animateFloat(
            initialValue = 0.3f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(1000, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            )
        )

        LaunchedEffect(detector) {
            while (true) {
                modelReady = detector.isReady()
                statusText = if (modelReady) "Active" else "Model FAILED: ${detector.lastError ?: "Unknown error"}"
                statsText = storage.getDetectionStats()
                delay(500)
            }
        }

        LaunchedEffect(Unit) {
            while (true) {
                recentLogs = storage.getRecentLogs(20)
                delay(2000)
            }
        }

        // MAIN BOX WITH SAFE INSETS
        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
        ) {
            AndroidView(
                factory = { ctx ->
                    val previewView = PreviewView(ctx)
                    val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)

                    cameraProviderFuture.addListener({
                        val cameraProvider = cameraProviderFuture.get()
                        val preview = Preview.Builder().build().also {
                            it.surfaceProvider = previewView.surfaceProvider
                        }

                        val analysis = ImageAnalysis.Builder()
                            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .setResolutionSelector(
                                androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
                                    .setResolutionStrategy(
                                        androidx.camera.core.resolutionselector.ResolutionStrategy(
                                            android.util.Size(1280, 720),
                                            androidx.camera.core.resolutionselector.ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                                        )
                                    )
                                    .build()
                            )
                            .build()
                            .also {
                                it.setAnalyzer(analysisExecutor) { imageProxy ->
                                    val currentTime = System.currentTimeMillis()
                                    if (currentTime - lastFrameTime >= FRAME_INTERVAL_MS) {
                                        lastFrameTime = currentTime
                                        frameCount++
                                        
                                        if (currentTime - lastFpsUpdate >= 1000) {
                                            currentFps = frameCount
                                            frameCount = 0
                                            lastFpsUpdate = currentTime
                                            fpsText = "$currentFps FPS"
                                        }

                                        if (!isProcessing && modelReady && detectorEnabled) {
                                            isProcessing = true
                                            val rawBitmap = imageProxy.toBitmap()
                                            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                                            val bitmap = if (rawBitmap != null && rotationDegrees != 0) {
                                                val matrix = android.graphics.Matrix().apply {
                                                    postRotate(rotationDegrees.toFloat())
                                                }
                                                android.graphics.Bitmap.createBitmap(
                                                    rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true
                                                ).also { if (it !== rawBitmap) rawBitmap.recycle() }
                                            } else {
                                                rawBitmap
                                            }

                                            if (bitmap != null) {
                                                scope.launch {
                                                    try {
                                                        // Move detection to Default dispatcher for better UI performance
                                                        val rawResults = withContext(Dispatchers.Default) {
                                                            detector.detect(bitmap)
                                                        }
                                                        val results = tracker.update(rawResults)
                                                        detections = results
                                                        
                                                        // Person detection with Re-ID and throttling
                                                        val bestPerson = results
                                                            .filter { it.classId == 0 && it.confidence > 0.35f }
                                                            .maxByOrNull { it.confidence }
                                                        
                                                        val personLastSaved = bestPerson?.let { lastSavedByTrack[it.trackId] } ?: 0L
                                                        if (bestPerson != null && currentTime - personLastSaved > 3000) {
                                                            val area = bestPerson.bounds.width() * bestPerson.bounds.height()
                                                            if (area > MIN_PERSON_AREA) {
                                                                try {
                                                                    val cropped = imageCaptureUseCase?.let { ic ->
                                                                        try {
                                                                            captureHighResCrop(ic, bestPerson.bounds, ContextCompat.getMainExecutor(context))
                                                                        } catch (e: Exception) { null }
                                                                    } ?: cropDetection(bitmap, bestPerson.bounds, isPerson = true)
                                                                    
                                                                    // TRIGGER VIDEO CLIP (throttled)
                                                                    var videoPath: String? = null
                                                                    if (currentTime - lastVideoTimestamp > 30000 && currentRecording == null) {
                                                                        val file = File(context.filesDir, "vigil_${currentTime}.mp4")
                                                                        val outputOptions = FileOutputOptions.Builder(file).build()
                                                                        videoCaptureUseCase?.let { vc ->
                                                                            currentRecording = vc.output.prepareRecording(context, outputOptions)
                                                                                .start(ContextCompat.getMainExecutor(context)) { event ->
                                                                                    if (event is VideoRecordEvent.Finalize) {
                                                                                        currentRecording = null
                                                                                        if (event.hasError()) file.delete()
                                                                                    }
                                                                                }
                                                                            lastVideoTimestamp = currentTime
                                                                            videoPath = file.absolutePath
                                                                            // Auto-stop after 5 seconds
                                                                            scope.launch {
                                                                                delay(5000)
                                                                                currentRecording?.stop()
                                                                            }
                                                                        }
                                                                    }

                                                                    if (cropped != null && !cropped.isRecycled) {
                                                                        // Re-identify person
                                                                        val reIdResult = reId.identifyPerson(bitmap, bestPerson)
                                                                        
                                                                        withContext(Dispatchers.IO) {
                                                                            storage.saveDetection(
                                                                                detection = bestPerson,
                                                                                originalBitmap = bitmap,
                                                                                zoomedBitmap = cropped,
                                                                                personId = reIdResult.personId,
                                                                                videoPath = videoPath
                                                                            )
                                                                        }
                                                                        
                                                                        // Safe recycle: check if it's a new instance
                                                                        if (cropped != bitmap) cropped.recycle()
                                                                        
                                                                        personDetectionCount++
                                                                        lastPersonTimestamp = currentTime
                                                                        lastSavedByTrack[bestPerson.trackId] = currentTime
                                                                        recentLogs = storage.getRecentLogs(20)
                                                                    }
                                                                } catch (e: Exception) {
                                                                    Log.e(TAG, "Failed to save person", e)
                                                                }
                                                            }
                                                        }
                                                        
                                                        // Vehicle handling: plate OCR + logging, throttled by time
                                                        val vehicles = results.filter { it.classId in setOf(2, 3, 5, 7) && it.confidence > 0.45f }
                                                        val bestVehicle = vehicles.maxByOrNull { it.confidence }
                                                        
                                                        val vehicleLastSaved = bestVehicle?.let { lastSavedByTrack[it.trackId] } ?: 0L
                                                        if (bestVehicle != null && currentTime - vehicleLastSaved > 4000) {
                                                            val cropped = imageCaptureUseCase?.let { ic ->
                                                                try {
                                                                    captureHighResCrop(ic, bestVehicle.bounds, ContextCompat.getMainExecutor(context))
                                                                } catch (e: Exception) { null }
                                                            } ?: cropDetection(bitmap, bestVehicle.bounds, isPerson = false)
                                                            if (cropped != null && !cropped.isRecycled) {
                                                                val plateText = try {
                                                                    plateReader.readPlate(bitmap, bestVehicle.bounds)
                                                                } catch (e: Exception) {
                                                                    Log.e(TAG, "Plate OCR failed", e)
                                                                    null
                                                                }
                                                                val vehicleWithPlate = bestVehicle.copy(
                                                                    plateText = plateText ?: ""
                                                                )

                                                                // TRIGGER VIDEO CLIP (throttled)
                                                                var videoPath: String? = null
                                                                if (currentTime - lastVideoTimestamp > 45000 && currentRecording == null) {
                                                                    val file = File(context.filesDir, "vigil_v_${currentTime}.mp4")
                                                                    val outputOptions = FileOutputOptions.Builder(file).build()
                                                                    videoCaptureUseCase?.let { vc ->
                                                                        try {
                                                                            currentRecording = vc.output.prepareRecording(context, outputOptions)
                                                                                .start(ContextCompat.getMainExecutor(context)) { event ->
                                                                                    if (event is VideoRecordEvent.Finalize) {
                                                                                        currentRecording = null
                                                                                        if (event.hasError()) {
                                                                                            Log.e("MainActivity", "Video error: ${event.error}")
                                                                                            file.delete()
                                                                                        }
                                                                                    }
                                                                                }
                                                                            lastVideoTimestamp = currentTime
                                                                            videoPath = file.absolutePath
                                                                            scope.launch {
                                                                                delay(5000)
                                                                                currentRecording?.stop()
                                                                            }
                                                                        } catch (e: Exception) {
                                                                            Log.e("MainActivity", "Failed to start recording", e)
                                                                        }
                                                                    }
                                                                }

                                                                withContext(Dispatchers.IO) {
                                                                    storage.saveDetection(
                                                                        detection = vehicleWithPlate,
                                                                        originalBitmap = bitmap,
                                                                        zoomedBitmap = cropped,
                                                                        speedMph = bestVehicle.speedInfo.speedMphDisplay,
                                                                        direction = bestVehicle.speedInfo.direction,
                                                                        plateText = plateText ?: "",
                                                                        videoPath = videoPath
                                                                    )
                                                                }
                                                                if (cropped != bitmap) cropped.recycle()
                                                                lastVehicleTimestamp = currentTime
                                                                lastSavedByTrack[bestVehicle.trackId] = currentTime
                                                                recentLogs = storage.getRecentLogs(20)
                                                            }
                                                        }
                                                        
                                                        statusText = "Detections: ${results.size} | ${detector.peakRelevantLabel} ${(detector.peakRelevantScore * 100).toInt()}%"
                                                        
                                                    } catch (e: Exception) {
                                                        Log.e(TAG, "Detection error", e)
                                                    } finally {
                                                        isProcessing = false
                                                        if (!bitmap.isRecycled) bitmap.recycle()
                                                    }
                                                }
                                            } else {
                                                isProcessing = false
                                            }
                                        }
                                    }
                                    imageProxy.close()
                                }
                            }

                        val imageCapture = ImageCapture.Builder()
                            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                            .setResolutionSelector(
                                androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
                                    .setAspectRatioStrategy(
                                        androidx.camera.core.resolutionselector.AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
                                    )
                                    .build()
                            )
                            .build()

                        val recorder = Recorder.Builder()
                            .setQualitySelector(QualitySelector.from(Quality.HD))
                            .build()
                        val videoCapture = VideoCapture.withOutput(recorder)

                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis, imageCapture, videoCapture
                        )
                        imageCaptureUseCase = imageCapture
                        videoCaptureUseCase = videoCapture
                    }, ContextCompat.getMainExecutor(ctx))

                    previewView
                },
                modifier = Modifier.fillMaxSize()
            )

            // Detection Overlay
            Canvas(modifier = Modifier.fillMaxSize()) {
                detections.forEach { det ->
                    val left = det.bounds.left * size.width
                    val top = det.bounds.top * size.height
                    val right = det.bounds.right * size.width
                    val bottom = det.bounds.bottom * size.height

                    val isPerson = det.classId == 0
                    val isVehicle = det.classId in setOf(1, 2, 3, 5, 7)
                    
                    val color = when {
                        isPerson -> Color(0xFF00FF41)
                        isVehicle -> Color(0xFFFF6B00)
                        else -> Color(0xFF00E5FF)
                    }

                    val strokeWidth = if (isPerson) 4f else 3f

                    drawRect(
                        color = color.copy(alpha = 0.15f),
                        topLeft = Offset(left - 4f, top - 4f),
                        size = Size(right - left + 8f, bottom - top + 8f),
                        style = Stroke(width = 12f)
                    )

                    drawRect(
                        color = color,
                        topLeft = Offset(left, top),
                        size = Size(right - left, bottom - top),
                        style = Stroke(width = strokeWidth)
                    )

                    val cornerSize = 20f
                    val accentStroke = 3f
                    
                    drawLine(color, Offset(left + 4f, top + cornerSize), Offset(left + 4f, top + 4f), accentStroke)
                    drawLine(color, Offset(left + 4f, top + 4f), Offset(left + cornerSize, top + 4f), accentStroke)
                    drawLine(color, Offset(right - 4f, top + cornerSize), Offset(right - 4f, top + 4f), accentStroke)
                    drawLine(color, Offset(right - 4f, top + 4f), Offset(right - cornerSize, top + 4f), accentStroke)
                    drawLine(color, Offset(left + 4f, bottom - cornerSize), Offset(left + 4f, bottom - 4f), accentStroke)
                    drawLine(color, Offset(left + 4f, bottom - 4f), Offset(left + cornerSize, bottom - 4f), accentStroke)
                    drawLine(color, Offset(right - 4f, bottom - cornerSize), Offset(right - 4f, bottom - 4f), accentStroke)
                    drawLine(color, Offset(right - 4f, bottom - 4f), Offset(right - cornerSize, bottom - 4f), accentStroke)

                    // Label
                    val confidenceText = "${(det.confidence * 100).toInt()}%"
                    val labelText = if (isPerson) "👤 ${det.label}" else if (isVehicle) "🚗 ${det.label}" else det.label
                    val displayText = "$labelText $confidenceText"
                    
                    drawContext.canvas.nativeCanvas.apply {
                        val paint = android.graphics.Paint().apply {
                            this.color = color.toArgb()
                            textSize = 40f
                            typeface = android.graphics.Typeface.create("monospace", android.graphics.Typeface.BOLD)
                            isAntiAlias = true
                        }
                        
                        val bgPaint = android.graphics.Paint().apply {
                            this.color = android.graphics.Color.argb(200, 0, 0, 0)
                        }
                        
                        val textWidth = paint.measureText(displayText)
                        val padding = 12f
                        val height = 44f
                        
                        drawRoundRect(left + 4f, top - height - 4f, left + textWidth + padding * 2 + 8f, top + 4f, 8f, 8f, bgPaint)
                        drawRoundRect(left + 4f, top - height - 4f, left + textWidth + padding * 2 + 8f, top + 4f, 8f, 8f,
                            android.graphics.Paint().apply {
                                this.color = color.toArgb()
                                style = android.graphics.Paint.Style.STROKE
                                this.strokeWidth = 1f
                            }
                        )
                        drawText(displayText, left + padding + 8f, top - 8f, paint)
                    }

                    // PLATE TEXT
                    if (isVehicle && det.plateText.isNotEmpty()) {
                        drawContext.canvas.nativeCanvas.apply {
                            val paint = android.graphics.Paint().apply {
                                this.color = Color(0xFF00E5FF).toArgb()
                                textSize = 34f
                                typeface = android.graphics.Typeface.create("monospace", android.graphics.Typeface.BOLD)
                                isAntiAlias = true
                            }
                            val bgPaint = android.graphics.Paint().apply {
                                this.color = android.graphics.Color.argb(180, 0, 0, 0)
                            }
                            val text = "PLATE: ${det.plateText}"
                            val textWidth = paint.measureText(text)
                            val yPos = top - 50f
                            drawRoundRect(left + 4f, yPos - 30f, left + textWidth + 20f, yPos + 8f, 8f, 8f, bgPaint)
                            drawText(text, left + 12f, yPos, paint)
                        }
                    }

                    // SPEED IN MPH
                    if (isVehicle && det.speedInfo.speedMph > 1f) {
                        val speedText = "${det.speedInfo.speedMphDisplay} MPH ${det.speedInfo.direction}"
                        drawContext.canvas.nativeCanvas.apply {
                            val paint = android.graphics.Paint().apply {
                                this.color = Color(0xFFFF6B00).toArgb()
                                textSize = 36f
                                typeface = android.graphics.Typeface.create("monospace", android.graphics.Typeface.BOLD)
                                isAntiAlias = true
                            }
                            val bgPaint = android.graphics.Paint().apply {
                                this.color = android.graphics.Color.argb(180, 0, 0, 0)
                            }
                            val textWidth = paint.measureText(speedText)
                            val yPos = bottom + 40f
                            drawRoundRect(left + 4f, yPos - 34f, left + textWidth + 20f, yPos + 8f, 8f, 8f, bgPaint)
                            drawRoundRect(left + 4f, yPos - 34f, left + textWidth + 20f, yPos + 8f, 8f, 8f,
                                android.graphics.Paint().apply {
                                    this.color = Color(0xFFFF6B00).toArgb()
                                    style = android.graphics.Paint.Style.STROKE
                                    this.strokeWidth = 1.5f
                                }
                            )
                            drawText(speedText, left + 12f, yPos, paint)
                        }
                    }

                    // Person ID — now the real per-box tracker ID, not just "whatever was last registered"
                    if (isPerson) {
                        val personId = "ID: ${det.trackId}"
                        drawContext.canvas.nativeCanvas.apply {
                            val paint = android.graphics.Paint().apply {
                                this.color = Color(0xFF00FF41).toArgb()
                                textSize = 28f
                                typeface = android.graphics.Typeface.create("monospace", android.graphics.Typeface.BOLD)
                                isAntiAlias = true
                            }
                            val bgPaint = android.graphics.Paint().apply {
                                this.color = android.graphics.Color.argb(180, 0, 0, 0)
                            }
                            val textWidth = paint.measureText(personId)
                            val yPos = bottom + 40f
                            drawRoundRect(left + 4f, yPos - 34f, left + textWidth + 20f, yPos + 8f, 8f, 8f, bgPaint)
                            drawText(personId, left + 12f, yPos, paint)
                        }
                    }
                }
            }

            // Scanning animation
            if (detections.isEmpty() && detectorEnabled) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val scanY = size.height * (0.3f + 0.4f * (System.currentTimeMillis() % 3000) / 3000f)
                    drawLine(Color(0xFF00FF41).copy(alpha = 0.15f), Offset(0f, scanY), Offset(size.width, scanY), strokeWidth = 2f)
                }
            }

            // Top Status Bar - with safe insets
            Surface(
                color = Color.Black.copy(alpha = 0.7f),
                shape = RoundedCornerShape(0.dp, 0.dp, 16.dp, 16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(12.dp)
                    .align(Alignment.TopCenter)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier.size(8.dp).background(
                                color = if (modelReady) Color(0xFF00FF41).copy(alpha = pulseAlpha) else Color.Red,
                                shape = CircleShape
                            )
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("VIGIL", color = Color(0xFF00FF41), fontSize = 18.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        StatusPill("LIVE: ${detections.size}", Icons.Default.Visibility, Color(0xFF00FF41))
                        StatusPill(fpsText, Icons.Default.Speed, Color(0xFF00E5FF))
                        StatusPill("${reId.getPersonCount()}", Icons.Default.Person, Color(0xFFFF6B00))
                    }
                }
            }

            // Bottom Controls - with safe insets
            Surface(
                color = Color.Black.copy(alpha = 0.85f),
                shape = RoundedCornerShape(16.dp, 16.dp, 0.dp, 0.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(12.dp)
                    .align(Alignment.BottomCenter)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    if (showStats) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            StatItem("👤", personDetectionCount.toString(), Color(0xFF00FF41))
                            StatItem("🚗", detections.count { it.classId in setOf(1, 2, 3, 5, 7) }.toString(), Color(0xFFFF6B00))
                            StatItem("📊", statsText.take(20), Color(0xFF00E5FF))
                        }
                        Spacer(Modifier.height(12.dp))
                        HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                        Spacer(Modifier.height(12.dp))
                    }
                    
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ControlButton(
                            text = if (detectorEnabled) "🛑 STOP" else "▶ START",
                            active = detectorEnabled,
                            color = if (detectorEnabled) Color.Red else Color(0xFF00FF41),
                            modifier = Modifier.weight(1f)
                        ) { 
                            detectorEnabled = !detectorEnabled 
                            if (!detectorEnabled) detections = emptyList()
                        }

                        ControlButton(
                            text = if (autoZoomActive) "🔍 ZOOM ON" else "ZOOM",
                            active = autoZoomActive,
                            color = Color(0xFF00FF41),
                            modifier = Modifier.weight(1f)
                        ) { autoZoomActive = !autoZoomActive }
                        
                        ControlButton(
                            text = "📋 LOGS (${recentLogs.size})",
                            active = showLogs,
                            color = Color(0xFFFF6B00),
                            modifier = Modifier.weight(1f)
                        ) { showLogs = !showLogs }
                        
                        IconButton(
                            onClick = { 
                                storage.clearLogs()
                                reId.clear()
                                tracker.clear()
                                lastSavedByTrack.clear()
                                personDetectionCount = 0
                                recentLogs = emptyList()
                            },
                            modifier = Modifier
                                .size(44.dp)
                                .background(Color.Red.copy(alpha = 0.2f), shape = RoundedCornerShape(8.dp))
                                .border(1.dp, Color.Red.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                        ) {
                            Icon(Icons.Default.Delete, "Clear", tint = Color.Red)
                        }
                    }
                }
            }

            // Logs Panel - with safe insets
            AnimatedVisibility(
                visible = showLogs,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
            ) {
                Surface(
                    color = Color(0xFF1A1A1A),
                    shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(320.dp)
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .align(Alignment.BottomCenter)
                ) {
                    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("📋 DETECTION LOGS (${recentLogs.size})", color = Color(0xFF00FF41), fontSize = 14.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                            IconButton(onClick = { showLogs = false }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Default.Close, "Close", tint = Color.White, modifier = Modifier.size(18.dp))
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            items(recentLogs) { log ->
                                ModernDetectionLogItem(
                                    log = log,
                                    onClick = { selectedLog = log },
                                    onExport = { logToExport ->
                                        scope.launch {
                                            storage.exportToGallery(logToExport)
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // Detail Dialog
            selectedLog?.let { log ->
                AlertDialog(
                    onDismissRequest = { selectedLog = null },
                    title = { Text("Detection Details", color = Color(0xFF00FF41), fontFamily = FontFamily.Monospace) },
                    text = {
                        Column {
                            Text("Label: ${log.label}", color = Color.White)
                            Text("Confidence: ${(log.confidence * 100).toInt()}%", color = Color.White)
                            Text("Time: ${log.timestampFormatted}", color = Color.White)
                            Text("Type: ${if (log.isPerson) "Person" else if (log.isVehicle) "Vehicle" else "Other"}", color = Color.White)
                            if (log.isVehicle && log.speedMph > 0) {
                                Text("Speed: ${log.speedMph} MPH ${log.direction}", color = Color(0xFFFF6B00), fontWeight = FontWeight.Bold)
                            }
                            if (log.isPerson && log.personId.isNotEmpty()) {
                                Text("Person ID: ${log.personId}", color = Color(0xFF00FF41), fontWeight = FontWeight.Bold)
                            }
                            if (log.videoPath != null) {
                                Button(
                                    onClick = {
                                        val file = File(log.videoPath)
                                        if (file.exists()) {
                                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                                setDataAndType(uri, "video/mp4")
                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            }
                                            context.startActivity(intent)
                                        }
                                    },
                                    modifier = Modifier.padding(top = 8.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00FF41))
                                ) {
                                    Icon(Icons.Default.PlayArrow, null, tint = Color.Black)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Play Video Clip", color = Color.Black)
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            log.imagePath?.let { path ->
                                val file = File(path)
                                if (file.exists()) {
                                    AsyncImage(
                                        model = file,
                                        contentDescription = "Detection",
                                        modifier = Modifier.fillMaxWidth().height(200.dp).background(Color.Black).clip(RoundedCornerShape(8.dp)),
                                        contentScale = ContentScale.Crop
                                    )
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { selectedLog = null }) {
                            Text("Close", color = Color.Gray)
                        }
                    },
                    containerColor = Color(0xFF1A1A1A)
                )
            }
        }
    }

    private suspend fun captureHighResCrop(
        imageCapture: ImageCapture,
        bounds: RectF,
        executor: Executor
    ): Bitmap? = suspendCancellableCoroutine { continuation ->
        imageCapture.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val bitmap = image.toBitmap()
                val rotation = image.imageInfo.rotationDegrees
                image.close()
                if (bitmap != null) {
                    val rotated = if (rotation != 0) {
                        val matrix = android.graphics.Matrix().apply { postRotate(rotation.toFloat()) }
                        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also {
                            if (it !== bitmap) bitmap.recycle()
                        }
                    } else bitmap
                    continuation.resume(cropDetection(rotated, bounds))
                } else {
                    continuation.resume(null)
                }
            }
            override fun onError(exception: ImageCaptureException) {
                continuation.resume(null)
            }
        })
    }

    private fun cropDetection(bitmap: Bitmap, bounds: RectF, isPerson: Boolean = false): Bitmap? {
        return try {
            val bw = bitmap.width
            val bh = bitmap.height
            if (bw <= 0 || bh <= 0) return null
            
            // CUSTOM PADDING BASED ON TARGET TYPE
            // Vehicles get wide horizontal padding to catch them at speed.
            // People get tighter, uniform padding for a better "Zoom" look.
            val horizontalPaddingFactor = if (isPerson) 0.2f else 0.6f
            val verticalPaddingFactor = if (isPerson) 0.2f else 0.3f

            val paddingX = (bounds.width() * bw * horizontalPaddingFactor).toInt().coerceAtLeast(if (isPerson) 20 else 40)
            val paddingY = (bounds.height() * bh * verticalPaddingFactor).toInt().coerceAtLeast(if (isPerson) 20 else 30)
            
            val left = ((bounds.left * bw) - paddingX).toInt().coerceAtLeast(0)
            val top = ((bounds.top * bh) - paddingY).toInt().coerceAtLeast(0)
            val right = ((bounds.right * bw) + paddingX).toInt().coerceAtMost(bw)
            val bottom = ((bounds.bottom * bh) + paddingY).toInt().coerceAtMost(bh)
            
            val width = right - left
            val height = bottom - top
            if (width < 20 || height < 20) return null
            
            Bitmap.createBitmap(bitmap, left, top, width, height)
        } catch (e: Exception) {
            Log.e(TAG, "Error in cropDetection", e)
            null
        }
    }

    @Composable
    fun StatusPill(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color) {
        Surface(
            color = color.copy(alpha = 0.2f),
            shape = RoundedCornerShape(12.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.4f))
        ) {
            Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = color, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(text, color = color, fontSize = 13.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            }
        }
    }

    @Composable
    fun StatItem(icon: String, text: String, color: Color) {
        Surface(
            color = color.copy(alpha = 0.1f),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.padding(horizontal = 4.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(icon, fontSize = 18.sp)
                Spacer(Modifier.width(8.dp))
                Text(text, color = color, fontSize = 14.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            }
        }
    }

    @Composable
    fun ControlButton(text: String, active: Boolean, color: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
        Button(
            onClick = onClick,
            modifier = modifier.height(48.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (active) color.copy(alpha = 0.25f) else Color(0xFF252525)
            ),
            shape = RoundedCornerShape(10.dp),
            border = androidx.compose.foundation.BorderStroke(1.5.dp, if (active) color else Color.Gray.copy(alpha = 0.5f)),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text(
                text, 
                color = if (active) color else Color.White, 
                fontSize = 12.sp, 
                fontFamily = FontFamily.Monospace, 
                fontWeight = FontWeight.Bold
            )
        }
    }

    @Composable
    fun ModernDetectionLogItem(
        log: DetectionLog,
        onClick: () -> Unit,
        onExport: (DetectionLog) -> Unit
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onClick() },
            colors = CardDefaults.cardColors(
                containerColor = if (log.isPerson) Color(0xFF002244) else Color(0xFF002200)
            ),
            shape = RoundedCornerShape(8.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Image Thumbnail
                if (log.imagePath != null) {
                    val file = File(log.imagePath)
                    if (file.exists()) {
                        Box(contentAlignment = Alignment.BottomEnd) {
                            AsyncImage(
                                model = file,
                                contentDescription = "Detection",
                                modifier = Modifier
                                    .size(60.dp)
                                    .background(Color.Black)
                                    .clip(RoundedCornerShape(4.dp)),
                                contentScale = ContentScale.Crop
                            )
                            if (log.videoPath != null) {
                                Icon(
                                    Icons.Default.Videocam,
                                    null,
                                    tint = Color.White,
                                    modifier = Modifier
                                        .padding(4.dp)
                                        .size(16.dp)
                                        .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                                )
                            }
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .size(60.dp)
                                .background(Color.DarkGray)
                                .clip(RoundedCornerShape(4.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(if (log.isPerson) "👤" else "🚗", fontSize = 24.sp)
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .size(60.dp)
                            .background(Color.DarkGray)
                            .clip(RoundedCornerShape(4.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(if (log.isPerson) "👤" else "🚗", fontSize = 24.sp)
                    }
                }
                
                Spacer(modifier = Modifier.width(12.dp))
                
                // Details Column
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            log.label,
                            color = if (log.isPerson) Color(0xFF00FF41) else Color(0xFFFF6B00),
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "${(log.confidence * 100).toInt()}%",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    
                    Text(
                        log.timeOnly,
                        color = Color.Gray,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    
                    if (log.isVehicle && log.speedMph > 0) {
                        Text(
                            "🚗 ${log.speedMph} MPH ${log.direction}",
                            color = Color(0xFFFF6B00),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    
                    if (log.isPerson && log.personId.isNotEmpty()) {
                        Text(
                            "👤 ${log.personId}",
                            color = Color(0xFF00FF41),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                
                // EXPORT / SAVE BUTTON - FIXED
                IconButton(
                    onClick = { onExport(log) },
                    modifier = Modifier
                        .size(36.dp)
                        .background(
                            color = Color(0xFF00FF41).copy(alpha = 0.2f),
                            shape = CircleShape
                        )
                        .border(
                            width = 1.dp,
                            color = Color(0xFF00FF41).copy(alpha = 0.5f),
                            shape = CircleShape
                        )
                ) {
                    Icon(
                        Icons.Default.Save,
                        contentDescription = "Save to Gallery",
                        tint = Color(0xFF00FF41),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}
