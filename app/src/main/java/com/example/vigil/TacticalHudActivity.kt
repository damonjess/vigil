package com.example.vigil

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.RectF
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

class TacticalHudActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TacticalScreenTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF030908)) {
                    CameraPermissionGate {
                        TacticalMainLayout()
                    }
                }
            }
        }
    }
}

@Composable
fun CameraPermissionGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val permissions = arrayOf(Manifest.permission.CAMERA)
    
    var hasPermissions by remember {
        mutableStateOf(permissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        })
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results -> hasPermissions = results.values.all { it } }

    LaunchedEffect(Unit) {
        if (!hasPermissions) launcher.launch(permissions)
    }

    if (hasPermissions) {
        content()
    } else {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("CAMERA ACCESS REQUIRED", color = Color(0xFF00FFCC), fontSize = 18.sp)
                Spacer(Modifier.height(16.dp))
                Button(onClick = { launcher.launch(permissions) }) {
                    Text("GRANT")
                }
            }
        }
    }
}

@Composable
fun TacticalScreenTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        content = content
    )
}

@Composable
fun TacticalMainLayout() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    
    val detector = remember { Yolov8Detector(context) }
    val tracker = remember { ObjectTracker() }
    val smoother = remember { DetectionSmoother() }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }

    var detections by remember { mutableStateOf<List<Detection>>(emptyList()) }
    var currentBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var modelReady by remember { mutableStateOf(false) }
    var activeTab by remember { mutableStateOf("GEOLOG PANEL") }
    var isToolsOpen by remember { mutableStateOf(false) }

    LaunchedEffect(detector) {
        while (true) {
            modelReady = detector.isReady()
            delay(500)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        
        // 0. CAMERA PREVIEW LAYER
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
                        .build()
                        .also {
                            it.setAnalyzer(analysisExecutor) { imageProxy ->
                                if (modelReady) {
                                    val bitmap = imageProxy.toBitmap()
                                    if (bitmap != null) {
                                        scope.launch {
                                            val results = detector.detect(bitmap)
                                            val tracked = tracker.update(results, imageProxy.imageInfo.timestamp)
                                            detections = smoother.update(tracked)
                                            
                                            withContext(Dispatchers.Main) {
                                                val oldBitmap = currentBitmap
                                                currentBitmap = bitmap
                                                oldBitmap?.recycle()
                                            }
                                        }
                                    }
                                }
                                imageProxy.close()
                            }
                        }

                    cameraProvider.unbindAll()
                    try {
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis
                        )
                    } catch (e: Exception) {
                        Log.e("TacticalHud", "Binding failed", e)
                    }
                }, ContextCompat.getMainExecutor(ctx))

                previewView
            },
            modifier = Modifier.fillMaxSize()
        )

        // 1. BACKGROUND RADAR GRID & GEOMETRIC TARGET LINES
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            
            // Central Scope Reticle Retainer
            drawCircle(
                color = Color(0xFF8B6425).copy(alpha = 0.5f),
                radius = 280f,
                center = center,
                style = Stroke(width = 1.5f)
            )
            
            // Crosshairs Center Target Point
            drawLine(
                color = Color(0xFF8B6425).copy(alpha = 0.5f),
                start = Offset(center.x - 40, center.y),
                end = Offset(center.x + 40, center.y),
                strokeWidth = 2f
            )
            drawLine(
                color = Color(0xFF8B6425).copy(alpha = 0.5f),
                start = Offset(center.x, center.y - 40),
                end = Offset(center.x, center.y + 40),
                strokeWidth = 2f
            )
        }

        // Target Reticles & Data Pods Overlay
        TargetOverlayLayer(detections = detections, liveCameraBitmap = currentBitmap)

        // 2. HUD OVERLAYS
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                // Top Left Telemetry Readout
                Column(
                    modifier = Modifier
                        .background(Color(0x80030908))
                        .border(1.dp, Color(0xFF00FFCC))
                        .padding(8.dp)
                ) {
                    Text("SYSTEM STATUS: ${if(modelReady) "SCAN ACTIVE" else "LOADING..."}", color = Color(0xFF00FFCC), fontSize = 10.sp)
                    Text("MATRIX INF: DEPLOYED [OK]", color = Color(0xFF00FFCC), fontSize = 10.sp)
                    Text("FPS COMP: 60 // CACHE ACTIVE", color = Color(0xFF00FFCC), fontSize = 10.sp)
                }

                // Top Right Picture-In-Picture Target Lock Frame (Show best target)
                val bestTarget = detections.maxByOrNull { it.confidence }
                Column(
                    modifier = Modifier
                        .width(180.dp)
                        .height(110.dp)
                        .border(1.dp, Color(0xFF8B6425))
                        .background(Color(0x228B6425))
                        .padding(4.dp)
                ) {
                    Text("LOCK VIEWER / ${bestTarget?.label ?: "NO TARGET"}", color = Color(0xFF8B6425), fontSize = 9.sp)
                    Spacer(modifier = Modifier.weight(1f))
                    if (bestTarget != null) {
                        Text("X${(bestTarget.bounds.centerX()*100).toInt()} Y${(bestTarget.bounds.centerY()*100).toInt()} // CONF: ${(bestTarget.confidence*100).toInt()}%", color = Color(0xFF8B6425), fontSize = 9.sp)
                    } else {
                        Text("SCANNING FOR TARGETS...", color = Color(0xFF8B6425), fontSize = 9.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(40.dp))

            // Dynamic Tracker Middle Row Pods (Show up to 2 tracks)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                val targets = detections.filter { it.trackId != -1 }.take(2)
                targets.forEach { det ->
                    UpgradedTargetTrackingPod(title = "AUTO MAG-TRACK // TRACK-${det.trackId}", detection = det, parentFrameBitmap = currentBitmap)
                }
                repeat(2 - targets.size) {
                    UpgradedTargetTrackingPod(title = "AUTO MAG-TRACK // SCANNING", detection = null, parentFrameBitmap = null)
                }
            }
        }

        // 3. RADAR COMPASS WIDGET
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 16.dp)
                .size(80.dp)
                .border(1.dp, Color(0xFF00FFCC))
                .background(Color(0x80030908))
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawCircle(color = Color(0xFF00FFCC).copy(alpha = 0.2f), radius = size.minDimension / 2, style = Stroke(1f))
                detections.forEach { det ->
                    val dx = (det.bounds.centerX() - 0.5f) * size.width * 0.8f
                    val dy = (det.bounds.centerY() - 0.5f) * size.height * 0.8f
                    drawCircle(Color(0xFF00FFCC), radius = 3f, center = Offset(size.width/2 + dx, size.height/2 + dy))
                }
            }
            Text("RADAR", color = Color(0xFF00FFCC), fontSize = 8.sp, modifier = Modifier.align(Alignment.TopCenter))
            Text("BLIPS: ${detections.size}", color = Color(0xFF00FFCC), fontSize = 8.sp, modifier = Modifier.align(Alignment.BottomCenter))
        }

        // 4. LOWER SCREEN BOTTOM PANELS
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(Color(0xDD030908))
        ) {
            
            // Console Logging Container Area
            AnimatedVisibility(visible = !isToolsOpen) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp)
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .border(1.dp, Color(0xFF00FFCC))
                        .padding(8.dp)
                ) {
                    Text("VIGIL SCAN LOG // RECENT EVENTS", color = Color(0xFF00FFCC), fontSize = 10.sp)
                    val logs = detections.take(3)
                    LazyColumn {
                        items(logs) { det ->
                            Text("> ${det.label.uppercase()} IDENTIFIED IN SECTOR", color = Color.Green, fontSize = 9.sp)
                        }
                        if (logs.isEmpty()) {
                            item { Text("> SEARCHING FOR TARGETS...", color = Color.Gray, fontSize = 9.sp) }
                        }
                    }
                }
            }

            // 5. THE SCREEN SELECTION TACTICAL NAVIGATION FOOTER BAR
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp)
                    .border(0.5.dp, Color(0xFF00FFCC)),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = { activeTab = "GEOLOG PANEL" },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    colors = ButtonDefaults.buttonColors(containerColor = if (activeTab == "GEOLOG PANEL") Color(0x3300FFCC) else Color.Transparent)
                ) {
                    Text("GEOLOG\nPANEL", color = Color.White, fontSize = 12.sp, maxLines = 2)
                }

                Button(
                    onClick = { activeTab = "GPS TACTICAL MAP" },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    colors = ButtonDefaults.buttonColors(containerColor = if (activeTab == "GPS TACTICAL MAP") Color(0x3300FFCC) else Color.Transparent)
                ) {
                    Text("GPS\nTACTICAL MAP", color = Color.White, fontSize = 12.sp, maxLines = 2)
                }

                Button(
                    onClick = { isToolsOpen = !isToolsOpen },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    colors = ButtonDefaults.buttonColors(containerColor = if (isToolsOpen) Color(0x5500FFCC) else Color.Transparent)
                ) {
                    Text(if (isToolsOpen) "TOOLS\nCLOSE" else "TOOLS\nOPEN", color = Color.White, fontSize = 12.sp, maxLines = 2)
                }
            }

            // 6. TOOLS PANEL (Abbreviated for fix)
            AnimatedVisibility(visible = isToolsOpen) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp)
                        .background(Color(0xFF020E0C))
                        .border(1.dp, Color(0xFF00FFCC))
                        .padding(16.dp)
                ) {
                    Text("TACTICAL SETTINGS", color = Color(0xFFFF9900), fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("MODEL STATUS: ${if(modelReady) "READY" else "WAITING"}", color = Color.White, fontSize = 10.sp)
                    // ... other settings ...
                }
            }
        }
    }
}

@Composable
fun TargetOverlayLayer(detections: List<Detection>, liveCameraBitmap: Bitmap?) {
    Box(modifier = Modifier.fillMaxSize()) {
        detections.forEach { detection ->
            // Draw tactical sci-fi corners around each detected item
            Canvas(modifier = Modifier.fillMaxSize()) {
                val left = detection.bounds.left * size.width
                val top = detection.bounds.top * size.height
                val right = detection.bounds.right * size.width
                val bottom = detection.bounds.bottom * size.height
                val width = right - left
                val height = bottom - top
                
                val cornerLength = minOf(width, height) * 0.25f
                val tacticalColor = Color(0xFF00FFCC)

                // Top-Left Reticle Corner
                drawLine(tacticalColor, Offset(left, top), Offset(left + cornerLength, top), strokeWidth = 3f)
                drawLine(tacticalColor, Offset(left, top), Offset(left, top + cornerLength), strokeWidth = 3f)
                
                // Bottom-Right Reticle Corner
                drawLine(tacticalColor, Offset(right, bottom), Offset(right - cornerLength, bottom), strokeWidth = 3f)
                drawLine(tacticalColor, Offset(right, bottom), Offset(right, bottom - cornerLength), strokeWidth = 3f)
            }
            
            // Text Label Box tracking above the target bounding reticle
            Box(
                modifier = Modifier
                    .offset(
                        x = (detection.bounds.left * 360).dp, // Crude approximation, better would be using local density
                        y = ((detection.bounds.top * 640) - 20).dp
                    )
                    .background(Color(0xCC030908))
                    .border(0.5.dp, Color(0xFF00FFCC))
                    .padding(2.dp)
            ) {
                Text(
                    text = "${detection.label.uppercase()} [ID-${detection.trackId}] ${(detection.confidence * 100).toInt()}%",
                    color = Color(0xFF00FFCC),
                    fontSize = 8.sp
                )
            }
        }
    }
}

@Composable
fun UpgradedTargetTrackingPod(title: String, detection: Detection?, parentFrameBitmap: Bitmap?) {
    // Generate a cropped sub-bitmap image live for the locked target pod
    val croppedBitmap = remember(detection, parentFrameBitmap) {
        if (detection != null && parentFrameBitmap != null) {
            cropTargetBounds(parentFrameBitmap, detection.bounds)
        } else null
    }

    Column(
        modifier = Modifier
            .width(160.dp)
            .height(115.dp)
            .border(1.dp, Color.Green)
            .background(Color(0x1100FF00))
            .padding(4.dp)
    ) {
        Text(title, color = Color.Green, fontSize = 8.sp, maxLines = 1)
        Spacer(modifier = Modifier.height(2.dp))
        
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(65.dp)
                .background(Color.Black)
                .border(0.5.dp, Color(0x3300FF00))
        ) {
            if (croppedBitmap != null && !croppedBitmap.isRecycled) {
                // Renders the live zoomed target crop inside the tactical frame
                Image(
                    bitmap = croppedBitmap.asImageBitmap(),
                    contentDescription = "Target Lock Video Source",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
                
                // Intersecting Target Grid overlay on top of the image crop
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawLine(Color(0x6600FF00), Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), strokeWidth = 1f)
                    drawLine(Color(0x6600FF00), Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = 1f)
                }
            } else {
                Text(
                    "NO TARGET LOCK", 
                    color = Color.DarkGray, 
                    fontSize = 9.sp, 
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }
        
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = if (detection != null) {
                "LOCK VERIFIED // CLS: ${detection.classId} // POS: X${detection.sectorX}"
            } else "SYSTEM SCANNING...",
            color = Color.Green,
            fontSize = 7.sp,
            maxLines = 1
        )
    }
}

fun cropTargetBounds(sourceBitmap: Bitmap, normalizedBounds: android.graphics.RectF): Bitmap? {
    if (sourceBitmap.isRecycled) return null
    try {
        // Convert normalized coordinates (0.0 to 1.0) to actual pixel dimensions
        val left = (normalizedBounds.left * sourceBitmap.width).toInt().coerceIn(0, sourceBitmap.width - 1)
        val top = (normalizedBounds.top * sourceBitmap.height).toInt().coerceIn(0, sourceBitmap.height - 1)
        val right = (normalizedBounds.right * sourceBitmap.width).toInt().coerceIn(left + 1, sourceBitmap.width)
        val bottom = (normalizedBounds.bottom * sourceBitmap.height).toInt().coerceIn(top + 1, sourceBitmap.height)
        
        val width = right - left
        val height = bottom - top
        if (width <= 0 || height <= 0) return null
        
        return Bitmap.createBitmap(sourceBitmap, left, top, width, height)
    } catch (e: Exception) {
        return null
    }
}
