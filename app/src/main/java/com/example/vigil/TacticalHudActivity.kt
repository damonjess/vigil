package com.example.vigil

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RectF
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import org.osmdroid.config.Configuration
import com.mapzen.tangram.MapController
import com.mapzen.tangram.MapView
import com.mapzen.tangram.CameraPosition
import com.mapzen.tangram.CameraUpdateFactory
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.cos
import kotlin.math.sin
import kotlin.coroutines.resume

class TacticalStateViewModel : ViewModel() {
    var activeTab by mutableStateOf("GEOLOG")
    var isToolsOpen by mutableStateOf(false)
    var aiConfidenceThreshold by mutableFloatStateOf(0.25f)
    var radarSweepEnabled by mutableStateOf(true)
    var cameraFacingFront by mutableStateOf(false)
    var renderMode by mutableStateOf("TACTICAL")
    var nightVisionMode by mutableStateOf("OFF")

    var scannerSensitivity by mutableFloatStateOf(50f) // Ranges 1 to 100
    var manualLockPoint by mutableStateOf<Offset?>(null)
    var lockedTrackId by mutableIntStateOf(-1)

    var mapZoom by mutableFloatStateOf(18f)
    var show3DBuildings by mutableStateOf(true)
    var mapTrailCount by mutableIntStateOf(6)

    // Call this to clear manual target locks
    fun clearManualLock() {
        manualLockPoint = null
        lockedTrackId = -1
    }

    private val _liveDetections = MutableStateFlow<List<Detection>>(emptyList())
    val liveDetections: StateFlow<List<Detection>> = _liveDetections

    private val _currentFrameBitmap = MutableStateFlow<Bitmap?>(null)
    val currentFrameBitmap: StateFlow<Bitmap?> = _currentFrameBitmap

    private val _consoleLogs = MutableStateFlow<List<String>>(listOf("VIGIL READY"))
    val consoleLogs: StateFlow<List<String>> = _consoleLogs

    fun updatePipeline(bitmap: Bitmap, detections: List<Detection>) {
        _currentFrameBitmap.value = bitmap
        _liveDetections.value = detections
    }

    fun addLog(message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        _consoleLogs.value = (listOf("[$time] $message") + _consoleLogs.value).take(15)
    }
}

class TacticalHudActivity : ComponentActivity() {
    @OptIn(ExperimentalPermissionsApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val cameraPermissionState = rememberPermissionState(Manifest.permission.CAMERA)
                LaunchedEffect(Unit) { cameraPermissionState.launchPermissionRequest() }

                Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                    if (cameraPermissionState.status.isGranted) {
                        TacticalMainLayout()
                    } else {
                        Box(contentAlignment = Alignment.Center) {
                            Button(onClick = { cameraPermissionState.launchPermissionRequest() }) {
                                Text("GRANT CAMERA ACCESS")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CameraFrameAnalysisEngine(
    detector: Yolov8Detector,
    tracker: ObjectTracker,
    storage: DetectionStorage,
    plateReader: PlateOcrReader,
    viewModel: TacticalStateViewModel
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val scope = rememberCoroutineScope()

    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
        }
    }

    key(viewModel.cameraFacingFront) {
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                    cameraProviderFuture.addListener({
                        val cameraProvider = cameraProviderFuture.get()
                        cameraProvider.unbindAll()

                        val selector = if (viewModel.cameraFacingFront) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA

                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(this.surfaceProvider)
                        }

                        val analysis = ImageAnalysis.Builder()
                            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .setResolutionSelector(
                                androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
                                    .setAspectRatioStrategy(
                                        androidx.camera.core.resolutionselector.AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
                                    )
                                    .build()
                            )
                            .build()

                        analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                            val rawBitmap = imageProxy.toBitmap()
                            val rotationDegrees = imageProxy.imageInfo.rotationDegrees

                            if (rawBitmap != null) {
                                val bitmap = if (rotationDegrees != 0) {
                                    val matrix = android.graphics.Matrix().apply {
                                        postRotate(rotationDegrees.toFloat())
                                    }
                                    android.graphics.Bitmap.createBitmap(
                                        rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true
                                    ).also { if (it !== rawBitmap) rawBitmap.recycle() }
                                } else {
                                    rawBitmap
                                }

                                val results = detector.detect(bitmap, viewModel.aiConfidenceThreshold, 0.45f)
                                val tracks = tracker.update(results, imageProxy.imageInfo.timestamp)
                                viewModel.updatePipeline(bitmap, tracks)

                                // --- RESTORED LOGGING & ALPR LAYER ENGINE ---
                                tracks.forEach { det ->
                                    val isVehicle = det.classId in setOf(1, 2, 3, 5, 7) // Car, truck, bus, motorcycle
                                    val isPerson = det.classId == 0

                                    if (isPerson || isVehicle) {
                                        scope.launch {
                                            var finalPlate = ""

                                            // If it's a vehicle, process with PlateOcrReader
                                            if (isVehicle) {
                                                val croppedPlateRegion = cropPlateRegion(bitmap, det.bounds)
                                                if (croppedPlateRegion != null) {
                                                    val plate = plateReader.readPlate(bitmap, det.bounds)
                                                    if (!plate.isNullOrEmpty()) {
                                                        finalPlate = plate
                                                        det.plateText = finalPlate
                                                        viewModel.addLog("ALPR TARGET IDENTIFIED: [$finalPlate]")
                                                    }
                                                }
                                            }

                                            // Save detection to local storage
                                            val croppedTarget = cropDetectionImage(bitmap, det.bounds, isPerson)
                                            if (croppedTarget != null) {
                                                storage.saveDetection(
                                                    detection = det,
                                                    originalBitmap = bitmap,
                                                    zoomedBitmap = croppedTarget,
                                                    speedMph = det.speedInfo.speedMph.toInt(),
                                                    direction = det.speedInfo.direction,
                                                    plateText = finalPlate
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            imageProxy.close()
                        }

                        try {
                            cameraProvider.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
                        } catch (e: Exception) {
                            Log.e("TacticalHud", "Bind fail", e)
                        }
                    }, ContextCompat.getMainExecutor(ctx))
                }
            },
            modifier = Modifier.size(1.dp) // Maintain data pipeline without showing preview directly
        )
    }
}

private fun cropDetectionImage(bitmap: Bitmap, bounds: RectF, isPerson: Boolean): Bitmap? {
    val bw = bitmap.width
    val bh = bitmap.height
    val horizontalPaddingFactor = if (isPerson) 0.15f else 0.25f
    val verticalPaddingFactor = if (isPerson) 0.10f else 0.20f

    val paddingX = (bounds.width() * bw * horizontalPaddingFactor).toInt().coerceAtLeast(if (isPerson) 20 else 40)
    val paddingY = (bounds.height() * bh * verticalPaddingFactor).toInt().coerceAtLeast(if (isPerson) 20 else 30)

    val left = ((bounds.left * bw) - paddingX).toInt().coerceIn(0, bw - 1)
    val top = ((bounds.top * bh) - paddingY).toInt().coerceIn(0, bh - 1)
    val right = ((bounds.right * bw) + paddingX).toInt().coerceIn(left + 1, bw)
    val bottom = ((bounds.bottom * bh) + paddingY).toInt().coerceIn(top + 1, bh)

    val width = right - left
    val height = bottom - top
    if (width < 20 || height < 20) return null

    return try {
        Bitmap.createBitmap(bitmap, left, top, width, height)
    } catch (e: Exception) {
        null
    }
}

private fun cropPlateRegion(bitmap: Bitmap, bounds: RectF): Bitmap? {
    val bw = bitmap.width
    val bh = bitmap.height
    if (bw <= 0 || bh <= 0) return null

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

@Composable
fun TacticalMainLayout(viewModel: TacticalStateViewModel = viewModel()) {
    val context = LocalContext.current
    val liveDetections by viewModel.liveDetections.collectAsState()
    val currentFrame by viewModel.currentFrameBitmap.collectAsState()
    val logs by viewModel.consoleLogs.collectAsState()

    val detector = remember { Yolov8Detector(context) }
    val tracker = remember { ObjectTracker() }
    val storage = remember { DetectionStorage(context) }
    val plateReader = remember { PlateOcrReader() }

    DisposableEffect(Unit) {
        onDispose {
            detector.close()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // --- DATA PIPE CAMERA FRAME CAPTURE LOOP ---
        CameraFrameAnalysisEngine(
            detector = detector,
            tracker = tracker,
            storage = storage,
            plateReader = plateReader,
            viewModel = viewModel
        )

        // --- DYNAMIC CENTRAL VIEWPORT SWITCHER ---
        when (viewModel.activeTab) {
            "GPS TACTICAL MAP", "GPS TACTICAL" -> {
                // Renders the live animated radar mapping system
                MapViewportMock(viewModel = viewModel)
            }
            else -> {
                // Default: Render the live camera preview feed coming from the analyzer pipeline
                if (currentFrame != null) {
                    Image(
                        bitmap = currentFrame!!.asImageBitmap(),
                        contentDescription = "Background Matrix Viewport",
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onTap = { tapOffset ->
                                        val normalizedX = tapOffset.x / size.width
                                        val normalizedY = tapOffset.y / size.height
                                        viewModel.manualLockPoint = Offset(normalizedX, normalizedY)
                                        viewModel.addLog("MANUAL TARGET ACQUIRED AT X: ${(normalizedX * 100).toInt()}% Y: ${(normalizedY * 100).toInt()}%")
                                    }
                                )
                            },
                        contentScale = ContentScale.Crop,
                        alpha = if (viewModel.nightVisionMode != "OFF") 0.7f else 0.4f
                    )
                }
            }
        }

        // --- RETICLE SCOPE & CORNER RETICLE DRAWING LAYER ---
        if (viewModel.activeTab !in listOf("GPS TACTICAL MAP", "GPS TACTICAL")) {
            TargetTrackingReticleOverlay(detections = liveDetections, viewModel = viewModel)
        }

        // --- TELEMETRY HUD OVERLAYS (TOP PODS) ---
        if (viewModel.activeTab !in listOf("GPS TACTICAL MAP", "GPS TACTICAL")) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .windowInsetsPadding(WindowInsets.safeDrawing),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(
                    modifier = Modifier
                        .background(Color(0xCC010A08))
                        .border(0.5.dp, Color(0xFF00FFCC))
                        .padding(8.dp)
                ) {
                    Text("SYSTEM STATUS: SCAN ACTIVE", color = Color(0xFF00FFCC), fontSize = 10.sp)
                    Text(
                        "TRACKER POOL SIZE: ${liveDetections.size}",
                        color = Color.Yellow,
                        fontSize = 10.sp
                    )
                }

                val priorityTarget = liveDetections.firstOrNull()
                UpgradedTargetTrackingPod(
                    title = "PRIMARY LOCK VIEWER",
                    detection = priorityTarget,
                    parentFrameBitmap = currentFrame
                )
            }
        }

        // --- SIDE PODS ---
        if (viewModel.activeTab !in listOf("GPS TACTICAL MAP", "GPS TACTICAL") && liveDetections.size > 1) {
            Column(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 16.dp)
                    .offset(y = (-40).dp)
            ) {
                liveDetections.drop(1).take(2).forEachIndexed { i, det ->
                    UpgradedTargetTrackingPod("TRACK-${i + 1}", det, currentFrame)
                    Spacer(Modifier.height(8.dp))
                }
            }
        }

        // --- LOWER CONSOLE READOUT & CONTROLLERS BAR ---
        Column(modifier = Modifier
            .fillMaxWidth()
            .align(Alignment.BottomCenter)
            .background(Color(0xF0010706))
            .windowInsetsPadding(WindowInsets.safeDrawing)) {

            // Console Logging Monitor Frame Container (hidden when tools drawer is active or on map tab)
            AnimatedVisibility(visible = !viewModel.isToolsOpen && viewModel.activeTab !in listOf("GPS TACTICAL MAP", "GPS TACTICAL")) {
                Column(modifier = Modifier
                    .fillMaxWidth()
                    .height(110.dp)
                    .padding(8.dp)
                    .border(0.5.dp, Color(0xFF00FFCC))
                    .padding(6.dp)) {
                    Text("LOG OUTPUT MATRIX // ACTIVE TELEMETRY LOGS", color = Color(0xFF00FFCC), fontSize = 10.sp)
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(logs) { log ->
                            Text(log, color = Color.Green, fontSize = 9.sp)
                        }
                    }
                }
            }

            // --- BOTTOM TAB BAR MATRIX ---
            Row(modifier = Modifier
                .fillMaxWidth()
                .height(55.dp)
                .border(0.5.dp, Color(0xFF00FFCC)), verticalAlignment = Alignment.CenterVertically) {
                // Button 1: Geolog Camera View
                Box(modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(if (viewModel.activeTab == "GEOLOG PANEL") Color(0x3300FFCC) else Color.Transparent)
                    .clickable { viewModel.activeTab = "GEOLOG PANEL" }, contentAlignment = Alignment.Center) {
                    Text("GEOLOG PANEL", color = Color.White, fontSize = 11.sp)
                }
                // Button 2: GPS Radar View (Triggers MapViewportMock above)
                Box(modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(if (viewModel.activeTab == "GPS TACTICAL MAP" || viewModel.activeTab == "GPS TACTICAL") Color(0x3300FFCC) else Color.Transparent)
                    .clickable { viewModel.activeTab = "GPS TACTICAL MAP" }, contentAlignment = Alignment.Center) {
                    Text("GPS TACTICAL MAP", color = Color.White, fontSize = 11.sp)
                }
                // Button 3: Tools Settings Sheet Overlay Toggle
                Box(modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(if (viewModel.isToolsOpen) Color(0x5500FFCC) else Color.Transparent)
                    .clickable { viewModel.isToolsOpen = !viewModel.isToolsOpen }, contentAlignment = Alignment.Center) {
                    Text(if (viewModel.isToolsOpen) "TOOLS CLOSE" else "TOOLS OPEN", color = Color.Yellow, fontSize = 11.sp)
                }
            }

            // --- SETTINGS SLIDERS CONSOLE DRAWER ---
            AnimatedVisibility(visible = viewModel.isToolsOpen) {
                Column(modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 300.dp)
                    .background(Color(0xFF020E0C))
                    .padding(12.dp)) {
                    LazyColumn(modifier = Modifier.fillMaxWidth()) {
                        item {
                            Text("SCANNER SENSITIVITY CONSTANT: ${viewModel.scannerSensitivity.toInt()}%", color = Color.White, fontSize = 10.sp)
                            Slider(
                                value = viewModel.scannerSensitivity,
                                onValueChange = {
                                    viewModel.scannerSensitivity = it
                                    viewModel.aiConfidenceThreshold = (1f - (it / 100f)).coerceIn(0.10f, 0.85f)
                                },
                                valueRange = 1f..100f,
                                colors = SliderDefaults.colors(thumbColor = Color.Yellow, activeTrackColor = Color(0xFF00FFCC))
                            )
                        }
                        item {
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = { viewModel.cameraFacingFront = !viewModel.cameraFacingFront }, modifier = Modifier.fillMaxWidth()) {
                                Text("FLIP CAMERA")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun UpgradedTargetTrackingPod(title: String, detection: Detection?, parentFrameBitmap: Bitmap?) {
    val croppedBitmap = remember(detection, parentFrameBitmap) {
        if (detection != null && parentFrameBitmap != null && !parentFrameBitmap.isRecycled) {
            try {
                val b = detection.bounds
                val left = (b.left * parentFrameBitmap.width).toInt().coerceIn(0, parentFrameBitmap.width - 1)
                val top = (b.top * parentFrameBitmap.height).toInt().coerceIn(0, parentFrameBitmap.height - 1)
                val right = (b.right * parentFrameBitmap.width).toInt().coerceIn(left + 1, parentFrameBitmap.width)
                val bottom = (b.bottom * parentFrameBitmap.height).toInt().coerceIn(top + 1, parentFrameBitmap.height)
                Bitmap.createBitmap(parentFrameBitmap, left, top, right - left, bottom - top)
            } catch (e: Exception) { null }
        } else null
    }

    Column(modifier = Modifier.width(130.dp).height(100.dp).border(1.dp, Color.Green).background(Color(0x4D000000)).padding(2.dp)) {
        Text(title, color = Color.Green, fontSize = 8.sp, maxLines = 1)
        Box(modifier = Modifier.fillMaxWidth().height(60.dp).background(Color.Black)) {
            if (croppedBitmap != null) Image(croppedBitmap.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        Text(if(detection != null) "LOCK READY" else "SCANNING", color = Color.Green, fontSize = 7.sp)
    }
}

@Composable
fun TargetTrackingReticleOverlay(detections: List<Detection>, viewModel: TacticalStateViewModel) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val lockPoint = viewModel.manualLockPoint

        if (viewModel.radarSweepEnabled) {
            drawCircle(Color(0x4000FFCC), size.minDimension / 3f, center, style = Stroke(1f))
        }

        // Look for the detection closest to our tap point to assign lockedTrackId
        if (lockPoint != null) {
            val closestTarget = detections.minByOrNull { target ->
                val dx = target.bounds.centerX() - lockPoint.x
                val dy = target.bounds.centerY() - lockPoint.y
                (dx * dx) + (dy * dy)
            }
            if (closestTarget != null) {
                viewModel.lockedTrackId = closestTarget.trackId
            }
        }

        detections.forEach { target ->
            val tX = target.bounds.centerX() * size.width
            val tY = target.bounds.centerY() * size.height

            val isTargetLocked = (target.trackId == viewModel.lockedTrackId)
            // Swap color to sharp Orange when target lock is verified
            val boxColor = if (isTargetLocked) Color(0xFFFF9900) else Color(0xFF00FFCC)

            val left = target.bounds.left * size.width
            val top = target.bounds.top * size.height
            val right = target.bounds.right * size.width
            val bottom = target.bounds.bottom * size.height
            val cornerSize = minOf(right - left, bottom - top) * 0.25f

            // Connection line to radar center
            drawLine(if (isTargetLocked) Color(0xAAFF9900) else Color(0xAA8B6425), center, Offset(tX, tY), strokeWidth = 1f)

            // Corner bracket overlays
            drawLine(boxColor, Offset(left, top), Offset(left + cornerSize, top), strokeWidth = 4f)
            drawLine(boxColor, Offset(left, top), Offset(left, top + cornerSize), strokeWidth = 4f)
            drawLine(boxColor, Offset(right, bottom), Offset(right - cornerSize, bottom), strokeWidth = 4f)
            drawLine(boxColor, Offset(right, bottom), Offset(right, bottom - cornerSize), strokeWidth = 4f)

            if (isTargetLocked) {
                // Draw crosshairs through the center of the manually isolated target area
                drawLine(Color(0xAAFF9900), Offset(left, tY), Offset(right, tY), strokeWidth = 1f)
                drawLine(Color(0xAAFF9900), Offset(tX, top), Offset(tX, bottom), strokeWidth = 1f)
            }
        }
    }
}

@Composable
fun MapViewportMock(viewModel: TacticalStateViewModel = androidx.lifecycle.viewmodel.compose.viewModel()) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    
    // Remember MapView structure across recompositions
    val mapView = remember { MapView(context) }
    var mapController by remember { mutableStateOf<MapController?>(null) }

    // Synchronize full Android lifecycle hooks with the Compose UI lifecycle
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_CREATE -> mapView.onCreate(null)
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        
        // Ensure it starts in resumed state if we are already resumed
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            mapView.onResume()
        }

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onPause()
            mapView.onDestroy()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { _ ->
                mapView.apply {
                    getMapAsync { controller ->
                        mapController = controller
                        
                        // Configure your initial tactical HUD camera stance
                        val cameraPosition = CameraPosition().apply {
                            longitude = -0.6521
                            latitude = 53.5889
                            zoom = viewModel.mapZoom
                            tilt = 60f * (Math.PI.toFloat() / 180f)
                            rotation = 15f * (Math.PI.toFloat() / 180f)
                        }
                        controller?.updateCameraPosition(CameraUpdateFactory.newCameraPosition(cameraPosition))
                        
                        // Load the tactical scene file from assets
                        controller?.loadSceneFile("asset:///tactical_scene.yaml")
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
            update = { _ ->
                mapController?.updateCameraPosition(CameraUpdateFactory.setZoom(viewModel.mapZoom))
            }
        )

        // --- 2. HUD TEXT PARAMETER OVERLAY ---
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 25.dp, start = 20.dp, end = 20.dp)
        ) {
            Text("MINOS // TACTICAL MAP", color = Color(0xFF00FF44), fontSize = 17.sp, letterSpacing = 1.sp)
            Text("GPS: LOCAL ENGAGED", color = Color(0xFF00FF44), fontSize = 13.sp)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("TRAIL: 6", color = Color(0xFF00FF44), fontSize = 13.sp)
                Text("MARKERS: 0", color = Color(0xFF00FF44), fontSize = 13.sp)
                Text("ZOOM: ${viewModel.mapZoom.toInt()}", color = Color(0xFF00FF44), fontSize = 13.sp)
            }
            Text("MODE: 3D VECTOR   BUILDINGS: ${if (viewModel.show3DBuildings) "ON" else "OFF"}", color = Color(0xFF00FF44), fontSize = 13.sp)
        }

        // --- 3. HARDWARE CONTROL FOOTER LAYOUT INTERFACES ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(bottom = 85.dp)
                .height(40.dp)
                .background(Color(0xFF010603))
                .border(0.5.dp, Color(0x5500FF44)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            val footerBtnStyle = Modifier.weight(1f).fillMaxHeight().wrapContentHeight(Alignment.CenterVertically)

            Text("CEN", color = Color(0xFF00FF44), fontSize = 11.sp, textAlign = TextAlign.Center, modifier = footerBtnStyle)
            Text("FOLL", color = Color(0xFF00FF44), fontSize = 11.sp, textAlign = TextAlign.Center, modifier = footerBtnStyle)
            Text("3D", color = if (viewModel.show3DBuildings) Color.White else Color(0xFF00FF44), fontSize = 11.sp, textAlign = TextAlign.Center, modifier = footerBtnStyle.clickable { viewModel.show3DBuildings = !viewModel.show3DBuildings })
            Text("BUILD", color = Color(0xFF00FF44), fontSize = 11.sp, textAlign = TextAlign.Center, modifier = footerBtnStyle)
            Text("ADD", color = Color(0xFF00FF44), fontSize = 11.sp, textAlign = TextAlign.Center, modifier = footerBtnStyle)
            Text("LOA", color = Color(0xFF00FF44), fontSize = 11.sp, textAlign = TextAlign.Center, modifier = footerBtnStyle)

            Text("ZOO+", color = Color(0xFF00FF44), fontSize = 11.sp, textAlign = TextAlign.Center,
                modifier = footerBtnStyle.clickable { viewModel.mapZoom = (viewModel.mapZoom + 0.5f).coerceAtMost(21f) }
            )
            Text("ZOO-", color = Color(0xFF00FF44), fontSize = 11.sp, textAlign = TextAlign.Center,
                modifier = footerBtnStyle.clickable { viewModel.mapZoom = (viewModel.mapZoom - 0.5f).coerceAtLeast(10f) }
            )
        }

        // Peripheral tech frame layout lines
        Box(modifier = Modifier.fillMaxSize().border(2.dp, Color(0xFF00FF44)))
    }
}
