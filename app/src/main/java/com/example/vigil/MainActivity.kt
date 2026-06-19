package com.example.vigil

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.RectF
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
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
import androidx.core.content.FileProvider
import androidx.core.graphics.scale
import coil.compose.AsyncImage
import com.example.vigil.data.DetectionLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

class MainActivity : ComponentActivity() {
    companion object {
        private const val TAG = "MainActivity"
        private const val FRAME_INTERVAL_MS = 33L
        private const val ZOOM_LEVEL = 3.5f
        private const val PERSON_CONFIDENCE_THRESHOLD = 0.35f
        private const val MIN_PERSON_AREA = 0.01f
        private const val DETECTION_COOLDOWN_MS = 100L
    }

    private var lastFrameTime = 0L
    private var frameCount = 0
    private var lastFpsUpdate = 0L
    private var currentFps = 0
    private var lastZoomTime = 0L
    private var hasPersonDetected = false
    private var personDetectionCount = 0
    private var lastPersonTimestamp = 0L
    private var lastDetectionTime = 0L


    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            Toast.makeText(this, "Storage permission granted", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Storage permission needed to save images", Toast.LENGTH_SHORT).show()
        }
    }

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
                    Icon(
                        Icons.Default.Camera,
                        contentDescription = null,
                        tint = Color(0xFF00FF41),
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Camera Access Required",
                        color = Color(0xFF00FF41),
                        fontSize = 24.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Vigil needs camera access for AI detection",
                        color = Color.Gray,
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(
                        onClick = { launcher.launch(Manifest.permission.CAMERA) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF00FF41)
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
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
        DisposableEffect(detector) {
            onDispose {
                detector.close()
            }
        }
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
        
        var autoZoomActive by remember { mutableStateOf(true) }
        var showStats by remember { mutableStateOf(true) }
        var targetZoom by remember { mutableFloatStateOf(1f) }
        
        // Animations
        val infiniteTransition = rememberInfiniteTransition()
        val pulseAlpha by infiniteTransition.animateFloat(
            initialValue = 0.3f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(1000, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            )
        )

        // Monitor model status
        LaunchedEffect(detector) {
            while (true) {
                modelReady = detector.isReady()
                statusText = if (modelReady) {
                    "Active"
                } else {
                    "Model FAILED: ${detector.lastError ?: "Unknown error"}"
                }
                statsText = storage.getDetectionStats()
                delay(500.milliseconds)
            }
        }

        // Load recent logs
        LaunchedEffect(Unit) {
            while (true) {
                recentLogs = storage.getRecentLogs(20)
                delay(2000.milliseconds)
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
                            .setResolutionSelector(
                                androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
                                    .setResolutionStrategy(
                                        androidx.camera.core.resolutionselector.ResolutionStrategy(
                                            android.util.Size(1920, 1080),
                                            androidx.camera.core.resolutionselector.ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                                        )
                                    )
                                    .build()
                            )
                            .build()
                            .also {
                                it.setAnalyzer(ContextCompat.getMainExecutor(ctx)) { imageProxy ->
                                    val currentTime = System.currentTimeMillis()
                                    if (currentTime - lastDetectionTime < DETECTION_COOLDOWN_MS) {
                                        imageProxy.close()
                                        return@setAnalyzer
                                    }
                                    lastDetectionTime = currentTime

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
                                                        
                                                        handleAutoZoom(
                                                            results = results,
                                                            bitmap = bitmap,
                                                            autoZoomActive = autoZoomActive,
                                                            onZoom = { zoom -> targetZoom = zoom },
                                                            onPersonDetected = { person, zoomed ->
                                                                scope.launch {
                                                                    storage.saveDetection(
                                                                        detection = person,
                                                                        originalBitmap = bitmap,
                                                                        zoomedBitmap = zoomed
                                                                    )
                                                                    recentLogs = storage.getRecentLogs(20)
                                                                    personDetectionCount++
                                                                    lastPersonTimestamp = System.currentTimeMillis()
                                                                }
                                                            }
                                                        )
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

            // Detection Overlay - Modern Style
            Canvas(modifier = Modifier.fillMaxSize()) {
                detections.forEach { det ->
                    val left = det.bounds.left * size.width
                    val top = det.bounds.top * size.height
                    val right = det.bounds.right * size.width
                    val bottom = det.bounds.bottom * size.height

                    val isPerson = det.classId == 0
                    val isVehicle = det.classId in setOf(1, 2, 3, 5, 7)
                    
                    val color = when {
                        isPerson -> Color(0xFF00FF41) // Neon Green
                        isVehicle -> Color(0xFFFF6B00) // Orange
                        else -> Color(0xFF00E5FF) // Cyan
                    }

                    val glowColor = color.copy(alpha = 0.2f)
                    val strokeWidth = if (isPerson) 4f else 3f

                    // Glow effect (outer box)
                    drawRect(
                        color = glowColor,
                        topLeft = Offset(left - 4f, top - 4f),
                        size = Size(right - left + 8f, bottom - top + 8f),
                        style = Stroke(width = 12f)
                    )

                    // Main box with rounded corners
                    drawRoundRect(
                        color = color,
                        topLeft = Offset(left, top),
                        size = Size(right - left, bottom - top),
                        style = Stroke(width = strokeWidth),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(8f, 8f)
                    )

                    // Corner accents
                    val cornerSize = 20f
                    val accentStroke = 3f
                    
                    // Top-left
                    drawLine(color, Offset(left + 4f, top + cornerSize), Offset(left + 4f, top + 4f), accentStroke)
                    drawLine(color, Offset(left + 4f, top + 4f), Offset(left + cornerSize, top + 4f), accentStroke)
                    
                    // Top-right
                    drawLine(color, Offset(right - 4f, top + cornerSize), Offset(right - 4f, top + 4f), accentStroke)
                    drawLine(color, Offset(right - 4f, top + 4f), Offset(right - cornerSize, top + 4f), accentStroke)
                    
                    // Bottom-left
                    drawLine(color, Offset(left + 4f, bottom - cornerSize), Offset(left + 4f, bottom - 4f), accentStroke)
                    drawLine(color, Offset(left + 4f, bottom - 4f), Offset(left + cornerSize, bottom - 4f), accentStroke)
                    
                    // Bottom-right
                    drawLine(color, Offset(right - 4f, bottom - cornerSize), Offset(right - 4f, bottom - 4f), accentStroke)
                    drawLine(color, Offset(right - 4f, bottom - 4f), Offset(right - cornerSize, bottom - 4f), accentStroke)

                    // Label with modern styling
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
                        
                        // Background pill
                        drawRoundRect(
                            left + 4f,
                            top - height - 4f,
                            left + textWidth + padding * 2 + 8f,
                            top + 4f,
                            8f,
                            8f,
                            bgPaint
                        )
                        
                        // Border pill
                        this.drawRoundRect(
                            left + 4f,
                            top - height - 4f,
                            left + textWidth + padding * 2 + 8f,
                            top + 4f,
                            8f,
                            8f,
                            android.graphics.Paint().apply {
                                val pColor = color.toArgb()
                                this.color = pColor
                                style = android.graphics.Paint.Style.STROKE
                                setStrokeWidth(1f)
                            }
                        )
                        
                        drawText(displayText, left + padding + 8f, top - 8f, paint)
                    }
                }
            }

            // Scanning animation overlay
            if (detections.isEmpty()) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val scanY = size.height * (0.3f + 0.4f * (System.currentTimeMillis() % 3000) / 3000f)
                    drawLine(
                        color = Color(0xFF00FF41).copy(alpha = 0.15f),
                        start = Offset(0f, scanY),
                        end = Offset(size.width, scanY),
                        strokeWidth = 2f
                    )
                }
            }

            // Top Status Bar - Modern Glass Effect
            Surface(
                color = Color.Black.copy(alpha = 0.6f),
                shape = RoundedCornerShape(0.dp, 0.dp, 16.dp, 16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
                    .align(Alignment.TopCenter)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // App Name with pulse dot
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(
                                    color = if (modelReady) Color(0xFF00FF41).copy(alpha = pulseAlpha) else Color.Red,
                                    shape = CircleShape
                                )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "VIGIL",
                            color = Color(0xFF00FF41),
                            fontSize = 18.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 2.sp
                        )
                    }
                    
                    // Status indicators
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        StatusPill(
                            text = detections.size.toString(),
                            icon = Icons.Default.Visibility,
                            color = Color(0xFF00FF41)
                        )
                        StatusPill(
                            text = fpsText,
                            icon = Icons.Default.Speed,
                            color = Color(0xFF00E5FF)
                        )
                    }
                }
            }

            // Bottom Controls - Modern Floating Bar
            Surface(
                color = Color.Black.copy(alpha = 0.8f),
                shape = RoundedCornerShape(16.dp, 16.dp, 0.dp, 0.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
                    .align(Alignment.BottomCenter)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    // Stats row
                    if (showStats) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            StatItem("👤", personDetectionCount.toString(), Color(0xFF00FF41))
                            StatItem("🚗", detections.count { it.classId in setOf(1, 2, 3, 5, 7) }.toString(), Color(0xFFFF6B00))
                            StatItem("📊", statsText.take(20), Color(0xFF00E5FF))
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                    
                    // Control buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // ZOOM button
                        Button(
                            onClick = { autoZoomActive = !autoZoomActive },
                            modifier = Modifier.weight(1f).height(44.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (autoZoomActive) Color(0xFF00FF41).copy(alpha = 0.2f) else Color(0xFF1A1A1A)
                            ),
                            shape = RoundedCornerShape(8.dp),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (autoZoomActive) Color(0xFF00FF41) else Color.Gray.copy(alpha = 0.3f)
                            )
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    if (autoZoomActive) Icons.Default.ZoomIn else Icons.Default.ZoomOut,
                                    contentDescription = null,
                                    tint = if (autoZoomActive) Color(0xFF00FF41) else Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (autoZoomActive) "ZOOM ON" else "ZOOM",
                                    color = if (autoZoomActive) Color(0xFF00FF41) else Color.White,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = if (autoZoomActive) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                        
                        // LOGS button
                        Button(
                            onClick = { showLogs = !showLogs },
                            modifier = Modifier.weight(1f).height(44.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (showLogs) Color(0xFFFF6B00).copy(alpha = 0.2f) else Color(0xFF1A1A1A)
                            ),
                            shape = RoundedCornerShape(8.dp),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (showLogs) Color(0xFFFF6B00) else Color.Gray.copy(alpha = 0.3f)
                            )
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.AutoMirrored.Filled.List,
                                    contentDescription = null,
                                    tint = if (showLogs) Color(0xFFFF6B00) else Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "LOGS (${recentLogs.size})",
                                    color = if (showLogs) Color(0xFFFF6B00) else Color.White,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = if (showLogs) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                        
                        // CLEAR button (keep this)
                        IconButton(
                            onClick = { 
                                storage.clearLogs()
                                personDetectionCount = 0
                                recentLogs = emptyList()
                            },
                            modifier = Modifier
                                .size(44.dp)
                                .background(
                                    color = Color.Red.copy(alpha = 0.2f),
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .border(
                                    width = 1.dp,
                                    color = Color.Red.copy(alpha = 0.3f),
                                    shape = RoundedCornerShape(8.dp)
                                )
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Clear",
                                tint = Color.Red
                            )
                        }
                    }
                }
            }

            // Logs Panel - Modern Slide-up
            AnimatedVisibility(
                visible = showLogs,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
            ) {
                Surface(
                    color = Color(0xFF1A1A1A),
                    shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(320.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                    ) {
                        // Header
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "📋 DETECTION LOGS (${recentLogs.size})",
                                color = Color(0xFF00FF41),
                                fontSize = 14.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                            Row {
                                // Export All button
                                IconButton(
                                    onClick = { 
                                        if (recentLogs.isNotEmpty()) {
                                            exportAllToGallery(recentLogs)
                                        } else {
                                            Toast.makeText(context, "No logs to export", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Save,
                                        contentDescription = "Export All",
                                        tint = Color(0xFF00FF41),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                
                                Spacer(modifier = Modifier.width(4.dp))
                                
                                IconButton(
                                    onClick = { showLogs = false },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Close",
                                        tint = Color.White,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        // Log list
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(recentLogs) { log ->
                                ModernDetectionLogItem(
                                    log = log,
                                    onClick = { selectedLog = log },
                                    onExport = { exportToGallery(it) },
                                    onShare = { shareImage(it) }
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
                            
                            log.imagePath?.let { path ->
                                val file = File(path)
                                if (file.exists()) {
                                    AsyncImage(
                                        model = file,
                                        contentDescription = "Detection",
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(200.dp)
                                            .background(Color.Black)
                                            .clip(RoundedCornerShape(8.dp)),
                                        contentScale = ContentScale.Crop
                                    )
                                }
                            }
                        }
                    },
                    confirmButton = {
                        Row {
                            TextButton(onClick = { shareImage(log) }) {
                                Text("Share", color = Color(0xFF00E5FF))
                            }
                            TextButton(onClick = { exportToGallery(log) }) {
                                Text("Save", color = Color(0xFF00FF41))
                            }
                            TextButton(onClick = { selectedLog = null }) {
                                Text("Close", color = Color.Gray)
                            }
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
                cropped.scale(newWidth, newHeight, true).also {
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

    @Composable
    fun StatusPill(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color) {
        Surface(
            color = color.copy(alpha = 0.15f),
            shape = RoundedCornerShape(12.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.3f))
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = text,
                    color = color,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }

    @Composable
    fun StatItem(icon: String, text: String, color: Color) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = icon, fontSize = 14.sp)
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = text,
                color = color,
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
        onExport: (DetectionLog) -> Unit,
        onShare: (DetectionLog) -> Unit
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
                modifier = Modifier.padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Image
                if (log.imagePath != null) {
                    val file = File(log.imagePath)
                    if (file.exists()) {
                        AsyncImage(
                            model = file,
                            contentDescription = "Detection",
                            modifier = Modifier
                                .size(60.dp)
                                .background(Color.Black)
                                .clip(RoundedCornerShape(4.dp)),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(60.dp)
                                .background(Color.DarkGray)
                                .clip(RoundedCornerShape(4.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(text = if (log.isPerson) "👤" else "🚗", fontSize = 24.sp)
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
                        Text(text = if (log.isPerson) "👤" else "🚗", fontSize = 24.sp)
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
                            color = if (log.isPerson) Color(0xFF00FF41) else Color(0xFFFF6B00),
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${(log.confidence * 100).toInt()}%",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Text(
                        text = log.timeOnly,
                        color = Color.Gray,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
                
                // Action buttons
                Row {
                    // Export button
                    IconButton(
                        onClick = { onExport(log) },
                        modifier = Modifier
                            .size(32.dp)
                            .background(Color(0xFF00FF41).copy(alpha = 0.15f), CircleShape)
                    ) {
                        Icon(
                            Icons.Default.Save,
                            contentDescription = "Export",
                            tint = Color(0xFF00FF41),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    
                    Spacer(modifier = Modifier.width(4.dp))
                    
                    // Share button
                    IconButton(
                        onClick = { onShare(log) },
                        modifier = Modifier
                            .size(32.dp)
                            .background(Color(0xFF00E5FF).copy(alpha = 0.15f), CircleShape)
                    ) {
                        Icon(
                            Icons.Default.Share,
                            contentDescription = "Share",
                            tint = Color(0xFF00E5FF),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }

    private fun exportAllToGallery(logs: List<DetectionLog>) {
        // For Android 9 and below, check storage permission
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                Toast.makeText(this, "Grant storage permission to save images", Toast.LENGTH_SHORT).show()
                return
            }
        }

        val exported = mutableListOf<String>()
        val failed = mutableListOf<String>()
        
        logs.forEach { log ->
            val imagePath = log.imagePath ?: log.thumbnailPath
            if (imagePath != null) {
                val file = File(imagePath)
                if (file.exists()) {
                    if (saveToGallery(file)) {
                        exported.add(log.label)
                    } else {
                        failed.add(log.label)
                    }
                } else {
                    failed.add(log.label)
                }
            } else {
                failed.add(log.label)
            }
        }
        
        val message = if (failed.isEmpty() && exported.isNotEmpty()) {
            "Exported ${exported.size} images to Gallery ✓"
        } else if (exported.isEmpty() && failed.isEmpty()) {
            "No images to export"
        } else {
            "Exported ${exported.size} images. Failed: ${failed.size}"
        }
        
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun exportToGallery(log: DetectionLog) {
        // For Android 9 and below, check storage permission
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                Toast.makeText(this, "Grant storage permission to save images", Toast.LENGTH_SHORT).show()
                return
            }
        }

        try {
            // Check if image exists
            val imagePath = log.imagePath ?: log.thumbnailPath
            if (imagePath == null) {
                Toast.makeText(this, "No image to export", Toast.LENGTH_SHORT).show()
                return
            }
            
            val file = File(imagePath)
            if (!file.exists()) {
                Toast.makeText(this, "Image file not found", Toast.LENGTH_SHORT).show()
                return
            }
            
            // Save to gallery
            val success = saveToGallery(file)
            if (success) {
                Toast.makeText(this, "Image saved to Gallery ✓", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "Failed to save image", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Export failed", e)
            Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveToGallery(file: File): Boolean {
        return try {
            val context = this
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ (API 29+)
                val resolver = context.contentResolver
                val contentValues = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "Vigil_${System.currentTimeMillis()}.jpg")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Vigil")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                if (uri != null) {
                    resolver.openOutputStream(uri).use { outputStream ->
                        file.inputStream().use { inputStream ->
                            inputStream.copyTo(outputStream!!)
                        }
                    }
                    contentValues.clear()
                    contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(uri, contentValues, null, null)
                    true
                } else {
                    false
                }
            } else {
                // Android 9 and below
                @Suppress("DEPRECATION")
                val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                val vigilDir = File(picturesDir, "Vigil")
                if (!vigilDir.exists()) {
                    vigilDir.mkdirs()
                }
                
                val destFile = File(vigilDir, "Vigil_${System.currentTimeMillis()}.jpg")
                file.copyTo(destFile, overwrite = true)
                
                // Notify media scanner
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(destFile.absolutePath),
                    null,
                    null
                )
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Save to gallery failed", e)
            false
        }
    }

    private fun shareImage(log: DetectionLog) {
        try {
            val imagePath = log.imagePath ?: log.thumbnailPath
            if (imagePath == null) {
                Toast.makeText(this, "No image to share", Toast.LENGTH_SHORT).show()
                return
            }
            
            val file = File(imagePath)
            if (!file.exists()) {
                Toast.makeText(this, "Image file not found", Toast.LENGTH_SHORT).show()
                return
            }
            
            val uri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                file
            )
            
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "image/jpeg"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            
            startActivity(Intent.createChooser(shareIntent, "Share Detection Image"))
        } catch (e: Exception) {
            Log.e(TAG, "Share failed", e)
            Toast.makeText(this, "Share failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}