package com.example.vigil

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import coil.compose.AsyncImage
import com.example.vigil.data.DetectionLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import android.content.pm.PackageManager

class MainActivity : ComponentActivity() {
    companion object {
        private const val TAG = "MainActivity"
        private const val FRAME_INTERVAL_MS = 66L // ~15 FPS
        private const val ZOOM_LEVEL = 3.5f
        private const val PERSON_CONFIDENCE_THRESHOLD = 0.35f
        private const val VEHICLE_CONFIDENCE_THRESHOLD = 0.30f
        private const val MIN_PERSON_AREA = 0.01f
    }

    private var lastFrameTime = 0L
    private var frameCount = 0
    private var lastFpsUpdate = 0L
    private var currentFps = 0
    private var lastZoomTime = 0L
    private var isZooming = false
    private var hasPersonDetected = false
    private var personDetectionCount = 0
    private var lastPersonTimestamp = 0L

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
        var hasPermission by remember {
            mutableStateOf(
                ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                        PackageManager.PERMISSION_GRANTED
            )
        }

        val launcher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted -> hasPermission = granted }

        LaunchedEffect(Unit) {
            if (!hasPermission) launcher.launch(Manifest.permission.CAMERA)
        }

        if (hasPermission) {
            CameraPreviewScreen()
        } else {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Camera Access Required",
                        color = Color(0xFF00FF41),
                        fontSize = 24.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = { launcher.launch(Manifest.permission.CAMERA) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF00FF41)
                        )
                    ) {
                        Text("Grant Permission", color = Color.Black)
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
        val storage = remember { DetectionStorage(context) }
        
        var statusText by remember { mutableStateOf("Loading model...") }
        var detections by remember { mutableStateOf<List<Detection>>(emptyList()) }
        var recentLogs by remember { mutableStateOf<List<DetectionLog>>(emptyList()) }
        var isProcessing by remember { mutableStateOf(false) }
        var modelReady by remember { mutableStateOf(false) }
        var fpsText by remember { mutableStateOf("0 FPS") }
        var statsText by remember { mutableStateOf("Stats: ...") }
        var showLogs by remember { mutableStateOf(false) }
        var selectedLog by remember { mutableStateOf<DetectionLog?>(null) }
        var zoomLevel by remember { mutableStateOf(1f) }
        var targetZoom by remember { mutableStateOf(1f) }
        
        // Auto-zoom state
        var targetPerson by remember { mutableStateOf<Detection?>(null) }
        var autoZoomActive by remember { mutableStateOf(false) }
        
        // Detection tracking for speed
        var previousDetections by remember { mutableStateOf<List<Detection>>(emptyList()) }

        // Monitor model status
        LaunchedEffect(detector) {
            while (true) {
                modelReady = detector.isReady()
                statusText = if (modelReady) {
                    "Model Ready ✅ | ${detections.size} detections"
                } else {
                    "Model FAILED: ${detector.lastError ?: "Unknown error"}"
                }
                statsText = storage.getDetectionStats()
                delay(500)
            }
        }

        // Load recent logs
        LaunchedEffect(Unit) {
            while (true) {
                recentLogs = storage.getRecentLogs(20)
                delay(2000)
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            // Camera Preview
            AndroidView(
                factory = { ctx ->
                    val previewView = PreviewView(ctx)
                    val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)

                    cameraProviderFuture.addListener({
                        val cameraProvider = cameraProviderFuture.get()
                        val preview = Preview.Builder().build().also {
                            it.surfaceProvider = previewView.surfaceProvider
                        }

                        val analysis = androidx.camera.core.ImageAnalysis.Builder()
                            .setOutputImageFormat(androidx.camera.core.ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                            .setBackpressureStrategy(androidx.camera.core.ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .setTargetResolution(android.util.Size(1920, 1080))
                            .build()
                            .also {
                                it.setAnalyzer(ContextCompat.getMainExecutor(ctx)) { imageProxy ->
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

                                        if (!isProcessing && modelReady) {
                                            isProcessing = true
                                            val bitmap = imageProxy.toBitmap()
                                            if (bitmap != null) {
                                                scope.launch {
                                                    try {
                                                        val results = detector.detect(bitmap)
                                                        detections = results
                                                        
                                                        // Auto-zoom for persons
                                                        handleAutoZoom(
                                                            results = results,
                                                            bitmap = bitmap,
                                                            autoZoomActive = autoZoomActive,
                                                            onZoom = { zoom -> targetZoom = zoom },
                                                            onPersonDetected = { person, zoomed ->
                                                                scope.launch {
                                                                    val log = storage.saveDetection(
                                                                        detection = person,
                                                                        originalBitmap = bitmap,
                                                                        zoomedBitmap = zoomed
                                                                    )
                                                                    // Update UI
                                                                    recentLogs = storage.getRecentLogs(20)
                                                                    personDetectionCount++
                                                                    lastPersonTimestamp = System.currentTimeMillis()
                                                                }
                                                            }
                                                        )
                                                        
                                                        statusText = "Detections: ${results.size} | " +
                                                            "${detector.peakRelevantLabel} ${(detector.peakRelevantScore * 100).toInt()}%"
                                                    } catch (e: Exception) {
                                                        Log.e(TAG, "Detection error", e)
                                                    } finally {
                                                        isProcessing = false
                                                        bitmap.recycle()
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

                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            analysis
                        )
                    }, ContextCompat.getMainExecutor(ctx))

                    previewView
                },
                modifier = Modifier.fillMaxSize()
            )

            // Detection Overlay with person highlighting
            Canvas(modifier = Modifier.fillMaxSize()) {
                detections.forEach { det ->
                    val left = det.bounds.left * size.width
                    val top = det.bounds.top * size.height
                    val right = det.bounds.right * size.width
                    val bottom = det.bounds.bottom * size.height

                    // Color based on class
                    val isPerson = det.classId == 0
                    val isVehicle = det.classId in setOf(1, 2, 3, 5, 7)
                    val color = when {
                        isPerson -> Color.Cyan
                        isVehicle -> Color.Yellow
                        else -> Color.Magenta
                    }

                    // Highlight persons with thicker border
                    val strokeWidth = if (isPerson && det.confidence > 0.5f) 5f else 3f
                    val cornerSize = if (isPerson) 25f else 20f

                    // Main box with glow effect for persons
                    drawRect(
                        color = if (isPerson) color.copy(alpha = 0.2f) else color.copy(alpha = 0.3f),
                        topLeft = Offset(left, top),
                        size = Size(right - left, bottom - top),
                        style = Stroke(width = strokeWidth)
                    )

                    // Corner accents
                    drawLine(color, Offset(left, top + cornerSize), Offset(left, top), strokeWidth)
                    drawLine(color, Offset(left, top), Offset(left + cornerSize, top), strokeWidth)
                    drawLine(color, Offset(right - cornerSize, top), Offset(right, top), strokeWidth)
                    drawLine(color, Offset(right, top), Offset(right, top + cornerSize), strokeWidth)
                    drawLine(color, Offset(left, bottom - cornerSize), Offset(left, bottom), strokeWidth)
                    drawLine(color, Offset(left, bottom), Offset(left + cornerSize, bottom), strokeWidth)
                    drawLine(color, Offset(right - cornerSize, bottom), Offset(right, bottom), strokeWidth)
                    drawLine(color, Offset(right, bottom), Offset(right, bottom - cornerSize), strokeWidth)

                    // Label with class indicator
                    val labelText = if (isPerson) "👤 ${det.label} ${(det.confidence * 100).toInt()}%" else
                        if (isVehicle) "🚗 ${det.label} ${(det.confidence * 100).toInt()}%" else
                        "${det.label} ${(det.confidence * 100).toInt()}%"
                    
                    drawContext.canvas.nativeCanvas.apply {
                        val paint = android.graphics.Paint().apply {
                            this.color = color.toArgb()
                            textSize = if (isPerson) 44f else 36f
                            typeface = android.graphics.Typeface.MONOSPACE
                            isAntiAlias = true
                            isFakeBoldText = true
                        }
                        val bgPaint = android.graphics.Paint().apply {
                            this.color = android.graphics.Color.argb(180, 0, 0, 0)
                        }
                        val textWidth = paint.measureText(labelText)
                        drawRect(left + 4f, top - 44f - 4f, left + textWidth + 20f, top + 4f, bgPaint)
                        drawText(labelText, left + 8f, top - 8f, paint)
                    }
                    
                    // Speed indicator for vehicles
                    if (isVehicle && det.confidence > 0.5f) {
                        // Speed tracking would be added here
                    }
                }
            }

            // Auto-zoom indicator
            AnimatedVisibility(
                visible = autoZoomActive,
                enter = fadeIn() + slideInVertically(),
                exit = fadeOut() + slideOutVertically()
            ) {
                Surface(
                    color = Color.Cyan.copy(alpha = 0.9f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .padding(16.dp)
                        .align(Alignment.TopCenter)
                ) {
                    Text(
                        text = "🔍 ZOOMING ON PERSON",
                        color = Color.Black,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }

            // Person detection counter
            Surface(
                color = Color.Black.copy(alpha = 0.8f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .padding(16.dp)
                    .align(Alignment.TopEnd)
            ) {
                Column(modifier = Modifier.padding(12.dp), horizontalAlignment = Alignment.End) {
                    Text(
                        text = "👤 $personDetectionCount",
                        color = Color.Cyan,
                        fontSize = 18.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "🚗 ${detections.count { it.classId in setOf(1, 2, 3, 5, 7) }}",
                        color = Color.Yellow,
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            // HUD Overlay - Top Left
            Surface(
                color = Color.Black.copy(alpha = 0.8f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .padding(16.dp)
                    .align(Alignment.TopStart)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "VIGIL AI",
                        color = Color(0xFF00FF41),
                        fontSize = 18.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = statusText,
                        color = if (modelReady) Color.Green else Color.Red,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = fpsText,
                        color = Color.White,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = statsText,
                        color = Color(0xFF00E5FF),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            // Bottom Controls
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { 
                        autoZoomActive = !autoZoomActive 
                        if (!autoZoomActive) targetZoom = 1f
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (autoZoomActive) Color.Cyan else Color(0xFF333333)
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        if (autoZoomActive) "🔍 AUTO-ZOOM ON" else "AUTO-ZOOM",
                        color = if (autoZoomActive) Color.Black else Color.White,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
                
                Button(
                    onClick = { showLogs = !showLogs },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (showLogs) Color(0xFFFF6B00) else Color(0xFF333333)
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        "📋 LOGS",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
                
                Button(
                    onClick = { 
                        storage.clearLogs()
                        personDetectionCount = 0
                        recentLogs = emptyList()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFCC0000)
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        "🗑️ CLEAR",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            // Logs Panel
            AnimatedVisibility(
                visible = showLogs,
                enter = slideInVertically() + fadeIn(),
                exit = slideOutVertically() + fadeOut()
            ) {
                Surface(
                    color = Color(0xFF1A1A1A),
                    shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                        .align(Alignment.BottomCenter)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "📋 DETECTION LOGS (${recentLogs.size})",
                                color = Color(0xFF00FF41),
                                fontSize = 16.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                            IconButton(onClick = { showLogs = false }) {
                                Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(recentLogs) { log ->
                                DetectionLogItem(
                                    log = log,
                                    onClick = { selectedLog = log }
                                )
                            }
                        }
                    }
                }
            }

            // Detail Dialog for selected log
            selectedLog?.let { log ->
                AlertDialog(
                    onDismissRequest = { selectedLog = null },
                    title = {
                        Text(
                            "Detection Details",
                            color = Color(0xFF00FF41),
                            fontFamily = FontFamily.Monospace
                        )
                    },
                    text = {
                        Column {
                            Text("Label: ${log.label}", color = Color.White)
                            Text("Confidence: ${(log.confidence * 100).toInt()}%", color = Color.White)
                            Text("Time: ${log.timestampFormatted}", color = Color.White)
                            Text("Type: ${if (log.isPerson) "Person" else if (log.isVehicle) "Vehicle" else "Other"}", color = Color.White)
                            
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            // Show thumbnail if available
                            log.thumbnailPath?.let { path ->
                                AsyncImage(
                                    model = File(path),
                                    contentDescription = "Detection thumbnail",
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(150.dp)
                                        .background(Color.Black),
                                    contentScale = ContentScale.Crop
                                )
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { selectedLog = null }) {
                            Text("Close", color = Color(0xFF00FF41))
                        }
                    },
                    containerColor = Color(0xFF1A1A1A)
                )
            }
        }
    }

    private fun handleAutoZoom(
        results: List<Detection>,
        bitmap: Bitmap,
        autoZoomActive: Boolean,
        onZoom: (Float) -> Unit,
        onPersonDetected: (Detection, Bitmap) -> Unit
    ) {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
            Log.w(TAG, "Bitmap is invalid or recycled, skipping person detection")
            return
        }
        try {
            val currentTime = System.currentTimeMillis()
            
            // Find best person detection with safe bounds
            val bestPerson = results
                .filter { 
                    it.classId == 0 && 
                    it.confidence > PERSON_CONFIDENCE_THRESHOLD &&
                    it.bounds.width() > 0.01f &&
                    it.bounds.height() > 0.01f &&
                    it.bounds.left >= 0f && it.bounds.right <= 1f &&
                    it.bounds.top >= 0f && it.bounds.bottom <= 1f
                }
                .maxByOrNull { it.confidence }
            
            if (bestPerson != null) {
                val area = bestPerson.bounds.width() * bestPerson.bounds.height()
                
                // Only zoom if person is large enough and not too close to edge
                if (area > MIN_PERSON_AREA && 
                    bestPerson.bounds.centerX() in 0.15f..0.85f &&
                    bestPerson.bounds.centerY() in 0.15f..0.85f) {
                    
                    // Auto-zoom
                    if (autoZoomActive && currentTime - lastZoomTime > 2000) {
                        onZoom(ZOOM_LEVEL)
                        lastZoomTime = currentTime
                        hasPersonDetected = true
                    }
                    
                    // Save person detection - with safety checks
                    if (currentTime - lastPersonTimestamp > 3000) {
                        // Check if bitmap is valid
                        if (bitmap.width > 0 && bitmap.height > 0 && !bitmap.isRecycled) {
                            try {
                                val cropped = cropDetection(bitmap, bestPerson.bounds)
                                if (cropped != null && !cropped.isRecycled && cropped.width > 20 && cropped.height > 20) {
                                    onPersonDetected(bestPerson, cropped)
                                    lastPersonTimestamp = currentTime
                                } else {
                                    cropped?.recycle()
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to crop person", e)
                            }
                        }
                    }
                }
            } else {
                // No person detected, slowly zoom out
                if (currentTime - lastZoomTime > 3000 && hasPersonDetected) {
                    onZoom(1f)
                    hasPersonDetected = false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in handleAutoZoom", e)
        }
    }

    private fun cropDetection(bitmap: Bitmap, bounds: RectF): Bitmap? {
        return try {
            val bw = bitmap.width
            val bh = bitmap.height
            
            if (bw <= 0 || bh <= 0) {
                Log.e(TAG, "Invalid bitmap dimensions: ${bw}x${bh}")
                return null
            }
            
            // Add padding (more padding for better context)
            val paddingX = (bounds.width() * bw * 0.3f).toInt().coerceAtLeast(20)
            val paddingY = (bounds.height() * bh * 0.3f).toInt().coerceAtLeast(20)
            
            // Calculate crop bounds with padding
            val left = ((bounds.left * bw) - paddingX).toInt().coerceAtLeast(0)
            val top = ((bounds.top * bh) - paddingY).toInt().coerceAtLeast(0)
            val right = ((bounds.right * bw) + paddingX).toInt().coerceAtMost(bw)
            val bottom = ((bounds.bottom * bh) + paddingY).toInt().coerceAtMost(bh)
            
            val width = right - left
            val height = bottom - top
            
            if (width < 20 || height < 20) {
                Log.w(TAG, "Detection too small: ${width}x${height}")
                return null
            }
            
            // Create cropped bitmap
            val cropped = Bitmap.createBitmap(bitmap, left, top, width, height)
            
            // If cropped image is small, upscale it for better visibility
            val minSize = 200
            if (width < minSize || height < minSize) {
                val scale = maxOf(minSize.toFloat() / width, minSize.toFloat() / height)
                val newWidth = (width * scale).toInt()
                val newHeight = (height * scale).toInt()
                Bitmap.createScaledBitmap(cropped, newWidth, newHeight, true).also {
                    cropped.recycle()
                    return it
                }
            }
            
            cropped
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "IllegalArgumentException in cropDetection", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error in cropDetection", e)
            null
        }
    }

    private fun logDetectionInfo(detection: Detection, bitmap: Bitmap?) {
        Log.d(TAG, "=== Detection Info ===")
        Log.d(TAG, "Label: ${detection.label}")
        Log.d(TAG, "ClassId: ${detection.classId}")
        Log.d(TAG, "Confidence: ${detection.confidence}")
        Log.d(TAG, "Bounds: ${detection.bounds}")
        Log.d(TAG, "Width: ${detection.bounds.width()}")
        Log.d(TAG, "Height: ${detection.bounds.height()}")
        Log.d(TAG, "Bitmap: ${bitmap?.width}x${bitmap?.height} ${if (bitmap?.isRecycled == true) "RECYCLED" else "OK"}")
        Log.d(TAG, "====================")
    }

    @Composable
    fun DetectionLogItem(log: DetectionLog, onClick: () -> Unit) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onClick() },
            colors = CardDefaults.cardColors(
                containerColor = if (log.isPerson) Color(0xFF002244) else Color(0xFF002200)
            ),
            shape = RoundedCornerShape(4.dp)
        ) {
            Row(
                modifier = Modifier.padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Better image display
                if (log.imagePath != null) {
                    val file = File(log.imagePath)
                    if (file.exists()) {
                        AsyncImage(
                            model = file,
                            contentDescription = "Detection",
                            modifier = Modifier
                                .size(80.dp) // Larger preview
                                .background(Color.Black)
                                .clip(RoundedCornerShape(4.dp)),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        // Fallback to thumbnail
                        log.thumbnailPath?.let { thumbPath ->
                            val thumbFile = File(thumbPath)
                            if (thumbFile.exists()) {
                                AsyncImage(
                                    model = thumbFile,
                                    contentDescription = "Detection",
                                    modifier = Modifier
                                        .size(80.dp)
                                        .background(Color.Black)
                                        .clip(RoundedCornerShape(4.dp)),
                                    contentScale = ContentScale.Crop
                                )
                            }
                        }
                    }
                } else {
                    // Placeholder
                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .background(Color.DarkGray)
                            .clip(RoundedCornerShape(4.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (log.isPerson) "👤" else "🚗",
                            fontSize = 32.sp
                        )
                    }
                }
                
                Spacer(modifier = Modifier.width(12.dp))
                
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = log.label,
                            color = if (log.isPerson) Color.Cyan else Color.Yellow,
                            fontSize = 14.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${(log.confidence * 100).toInt()}%",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Text(
                        text = log.timeOnly,
                        color = Color.Gray,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "Size: ${log.imagePath?.let { File(it).length() / 1024 } ?: 0}KB",
                        color = Color.DarkGray,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}