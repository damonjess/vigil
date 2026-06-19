package com.example.vigil

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object PerformanceMonitor {
    private const val TAG = "PerfMonitor"
    
    private val _inferenceTime = MutableStateFlow(0L)
    val inferenceTime: StateFlow<Long> = _inferenceTime.asStateFlow()
    
    private val _fps = MutableStateFlow(0)
    val fps: StateFlow<Int> = _fps.asStateFlow()
    
    private var frameCount = 0
    private var lastFpsUpdate = 0L
    
    fun recordInferenceTime(timeMs: Long) {
        _inferenceTime.value = timeMs
        if (timeMs > 100) {
            Log.w(TAG, "Slow inference: ${timeMs}ms")
        }
    }
    
    fun recordFrame() {
        frameCount++
        val now = System.currentTimeMillis()
        if (now - lastFpsUpdate >= 1000) {
            _fps.value = frameCount
            frameCount = 0
            lastFpsUpdate = now
        }
    }
}