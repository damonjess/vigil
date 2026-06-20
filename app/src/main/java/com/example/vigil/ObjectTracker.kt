package com.example.vigil

import android.graphics.RectF
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sqrt

/**
 * TrackedObject represents a single object being tracked across multiple frames.
 * It maintains the detection state, a unique ID, and movement history for speed estimation.
 */
class TrackedObject(
    var detection: Detection,
    val id: Int,
    val firstSeen: Long = System.currentTimeMillis()
) {
    var lastSeen: Long = System.currentTimeMillis()
    var missedFrames: Int = 0
    var age: Int = 0
    
    // History for speed and direction calculation
    val positionHistory = mutableListOf<Pair<Float, Float>>()
    val timestampHistory = mutableListOf<Long>()
    
    // Track-specific metadata that persists across frames
    var persistentPersonId: String = ""
    var persistentPlateText: String = ""

    /**
     * Estimates the current position of the object by extrapolating from its last known velocity.
     * This helps sync the visual bounding box with reality when there's processing latency.
     */
    fun getExtrapolatedBounds(): RectF {
        if (positionHistory.size < 2) return detection.bounds
        
        val now = System.currentTimeMillis()
        val dt = (now - lastSeen) / 1000f
        
        // Don't extrapolate too far (e.g. more than 200ms) to avoid wild jitter
        val capDt = dt.coerceAtMost(0.2f)
        
        val dx = positionHistory.last().first - positionHistory[positionHistory.size - 2].first
        val dy = positionHistory.last().second - positionHistory[positionHistory.size - 2].second
        val timeStep = (timestampHistory.last() - timestampHistory[timestampHistory.size - 2]) / 1000f
        
        if (timeStep <= 0) return detection.bounds
        
        val vx = dx / timeStep
        val vy = dy / timeStep
        
        val offsetLinesX = vx * capDt
        val offsetLinesY = vy * capDt
        
        return RectF(detection.bounds).apply {
            offset(offsetLinesX, offsetLinesY)
        }
    }

    fun update(newDetection: Detection) {
        // Sync persistent metadata
        if (newDetection.personId.isNotEmpty()) {
            persistentPersonId = newDetection.personId
        } else {
            newDetection.personId = persistentPersonId
        }
        
        if (newDetection.plateText.isNotEmpty()) {
            persistentPlateText = newDetection.plateText
        } else {
            newDetection.plateText = persistentPlateText
        }

        detection = newDetection.copy(trackId = id)
        lastSeen = System.currentTimeMillis()
        missedFrames = 0
        age++
        
        val centerX = newDetection.bounds.centerX()
        val centerY = newDetection.bounds.centerY()
        positionHistory.add(centerX to centerY)
        timestampHistory.add(lastSeen)
        
        // Keep a window for speed calculation (approx 0.5 - 1.0 seconds of history)
        if (positionHistory.size > 15) {
            positionHistory.removeAt(0)
            timestampHistory.removeAt(0)
        }
    }
}

/**
 * ObjectTracker implements IOU-based multi-object tracking.
 * It associates new detections with existing tracks to maintain identity over time.
 * This is a simplified version of the SORT (Simple Online and Realtime Tracking) algorithm.
 */
class ObjectTracker {
    private val nextId = AtomicInteger(1)
    private var trackedObjects = mutableListOf<TrackedObject>()
    
    companion object {
        private const val IOU_THRESHOLD = 0.25f
        private const val MAX_MISSED_FRAMES = 15 // Hold onto tracks for ~1 second at 15fps
        private const val MIN_AGE_FOR_DISPLAY = 2 // Filter out momentary flickers
    }

    /**
     * Processes a new list of detections and updates the internal tracking state.
     * Returns the list of currently active detections from the tracked objects.
     */
    fun update(detections: List<Detection>): List<Detection> {
        val unmatchedTracks = trackedObjects.toMutableList()
        val unmatchedDetections = detections.toMutableList()
        val matched = mutableListOf<Pair<TrackedObject, Detection>>()

        // 1. Compute IOU between all tracks and detections
        val candidates = mutableListOf<Triple<TrackedObject, Detection, Float>>()
        for (track in unmatchedTracks) {
            for (det in detections) {
                // Only match objects of the same class
                if (track.detection.classId == det.classId) {
                    val iouValue = boxIou(track.detection.bounds, det.bounds)
                    if (iouValue > IOU_THRESHOLD) {
                        candidates.add(Triple(track, det, iouValue))
                    }
                }
            }
        }
        
        // 2. Greedy assignment starting from highest IOU
        candidates.sortByDescending { it.third }
        
        val matchedTracks = mutableSetOf<Int>()
        val matchedDetections = mutableSetOf<Detection>()
        
        for (candidate in candidates) {
            val track = candidate.first
            val det = candidate.second
            
            if (track.id !in matchedTracks && det !in matchedDetections) {
                matched.add(track to det)
                matchedTracks.add(track.id)
                matchedDetections.add(det)
                unmatchedTracks.remove(track)
                unmatchedDetections.remove(det)
            }
        }

        // 3. Update matched tracks with new detection data
        for ((track, det) in matched) {
            track.update(det)
            // Update the detection's speed info based on tracking history
            track.detection = track.detection.copy(speedInfo = calculateSpeed(track))
        }

        // 4. Handle unmatched tracks (aging)
        val tracksToRemove = mutableListOf<TrackedObject>()
        for (track in unmatchedTracks) {
            track.missedFrames++
            if (track.missedFrames > MAX_MISSED_FRAMES) {
                tracksToRemove.add(track)
            }
        }
        trackedObjects.removeAll(tracksToRemove)

        // 5. Create new tracks for unmatched detections
        for (det in unmatchedDetections) {
            val newTrack = TrackedObject(det, nextId.getAndIncrement())
            newTrack.update(det)
            newTrack.detection = newTrack.detection.copy(speedInfo = calculateSpeed(newTrack))
            trackedObjects.add(newTrack)
        }

        // 6. Return detections from active tracks (those seen recently and passing age threshold)
        return trackedObjects.filter { 
            (it.age >= MIN_AGE_FOR_DISPLAY && it.missedFrames <= 3) || (it.missedFrames == 0)
        }.map { 
            val extrapolated = it.getExtrapolatedBounds()
            it.detection.copy(bounds = extrapolated)
        }
    }

    fun clear() {
        trackedObjects.clear()
        nextId.set(1)
    }

    /**
     * Calculates the Intersection over Union (IOU) of two bounding boxes.
     */
    private fun boxIou(a: RectF, b: RectF): Float {
        val iw = (minOf(a.right, b.right) - maxOf(a.left, b.left)).coerceAtLeast(0f)
        val ih = (minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)).coerceAtLeast(0f)
        val inter = iw * ih
        val union = (a.width() * a.height()) + (b.width() * b.height()) - inter
        return if (union <= 0f) 0f else inter / union
    }

    /**
     * Estimates speed and direction based on position history.
     */
    private fun calculateSpeed(track: TrackedObject): SpeedInfo {
        val positions = track.positionHistory
        val timestamps = track.timestampHistory
        
        // Need at least a few points for a stable reading
        if (positions.size < 3) return SpeedInfo()
        
        val dx = positions.last().first - positions.first().first
        val dy = positions.last().second - positions.first().second
        val dt = (timestamps.last() - timestamps.first()) / 1000f
        
        // Prevent division by zero or extremely high speeds from frame jitter
        if (dt <= 0.1f) return track.detection.speedInfo
        
        val speedPxPerSec = sqrt(dx*dx + dy*dy) / dt
        
        val direction = when {
            kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.5f -> {
                if (dx > 0) "Right" else "Left"
            }
            kotlin.math.abs(dy) > kotlin.math.abs(dx) * 1.5f -> {
                if (dy < 0) "Up" else "Down"
            }
            else -> "diagonal"
        }
        
        // Calibration factor: maps normalized pixel movement to approximate MPH
        // 0.45 is a heuristic for typical traffic distance and focal length
        val speedMs = speedPxPerSec * 0.45f 
        val speedMph = speedMs * 2.23694f
        
        return SpeedInfo(
            speedPxPerSec = speedPxPerSec,
            speedMs = speedMs,
            speedKmh = speedMs * 3.6f,
            speedMph = speedMph,
            speedMphDisplay = speedMph.toInt(),
            direction = direction
        )
    }
}
