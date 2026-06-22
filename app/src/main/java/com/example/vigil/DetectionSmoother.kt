package com.example.vigil

import android.graphics.RectF

class DetectionSmoother {
    private val states = mutableMapOf<Int, SmoothState>()
    private val GRACE_PERIOD_MS = 600L
    private val SMOOTHING_FACTOR = 0.35f

    fun update(newDetections: List<Detection>): List<Detection> {
        val currentTime = System.currentTimeMillis()
        val result = mutableListOf<Detection>()

        // Update existing states and add new ones
        newDetections.forEach { det ->
            val state = states.getOrPut(det.trackId) { SmoothState(det) }
            state.update(det, currentTime, SMOOTHING_FACTOR)
            result.add(state.toDetection())
        }

        // Handle persistence for missing detections
        val it = states.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            if (currentTime - entry.value.lastSeenTime > GRACE_PERIOD_MS) {
                it.remove()
            } else if (newDetections.none { it.trackId == entry.key }) {
                // Carry forward the previous detection box but mark it as stale/faded if desired
                result.add(entry.value.toDetection())
            }
        }
        return result
    }

    private class SmoothState(initial: Detection) {
        var lastSeenTime = System.currentTimeMillis()
        var bounds = RectF(initial.bounds)
        var label = initial.label
        var classId = initial.classId
        var trackId = initial.trackId
        var confidence = initial.confidence
        var speedInfo = initial.speedInfo
        private val labelCounts = mutableMapOf<String, Int>()

        fun update(det: Detection, time: Long, alpha: Float) {
            lastSeenTime = time
            // Exponential moving average for bounding box
            bounds.left = bounds.left * (1 - alpha) + det.bounds.left * alpha
            bounds.top = bounds.top * (1 - alpha) + det.bounds.top * alpha
            bounds.right = bounds.right * (1 - alpha) + det.bounds.right * alpha
            bounds.bottom = bounds.bottom * (1 - alpha) + det.bounds.bottom * alpha
            
            confidence = det.confidence
            speedInfo = det.speedInfo
            
            // Majority voting for label stability
            labelCounts[det.label] = (labelCounts[det.label] ?: 0) + 1
            label = labelCounts.maxByOrNull { it.value }?.key ?: det.label
        }

        fun toDetection() = Detection(label, classId, confidence, RectF(bounds), speedInfo, "", "", trackId)
    }
}
