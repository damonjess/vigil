package com.example.vigil

import android.graphics.RectF
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sqrt

class TrackedObject(var detection: Detection, val id: Int, val firstSeen: Long) {
    var lastSeen = firstSeen
    var missedFrames = 0
    var age = 0
    val positionHistory = mutableListOf<Pair<Float, Float>>()
    val timestampHistory = mutableListOf<Long>()
    var persistentPersonId = ""
    var persistentPlateText = ""

    fun getExtrapolatedBounds(currentFrameTimeNs: Long): RectF {
        if (positionHistory.size < 2) return detection.bounds
        val dt = (currentFrameTimeNs - lastSeen) / 1_000_000_000f
        val capDt = dt.coerceIn(0f, 0.2f)
        val dx = positionHistory.last().first - positionHistory[positionHistory.size - 2].first
        val dy = positionHistory.last().second - positionHistory[positionHistory.size - 2].second
        val timeStep = (timestampHistory.last() - timestampHistory[timestampHistory.size - 2]) / 1_000_000_000f
    
        if (timeStep <= 0) return detection.bounds
        return RectF(detection.bounds).apply { offset((dx / timeStep) * capDt, (dy / timeStep) * capDt) }
    }

    fun update(newDetection: Detection, frameTimeNs: Long) {
        if (newDetection.personId.isNotEmpty()) persistentPersonId = newDetection.personId else newDetection.personId = persistentPersonId
        if (newDetection.plateText.isNotEmpty()) persistentPlateText = newDetection.plateText else newDetection.plateText = persistentPlateText
        detection = newDetection.copy(trackId = id)
        lastSeen = frameTimeNs
        missedFrames = 0
        age++
        positionHistory.add(newDetection.bounds.centerX() to newDetection.bounds.centerY())
        timestampHistory.add(frameTimeNs)
        if (positionHistory.size > 15) { 
            positionHistory.removeAt(0)
            timestampHistory.removeAt(0) 
        }
    }
}

class ObjectTracker {
    private val nextId = AtomicInteger(1)
    private var trackedObjects = mutableListOf<TrackedObject>()

    fun update(detections: List<Detection>, frameTimeNs: Long): List<Detection> {
        val unmatchedTracks = trackedObjects.toMutableList()
        val unmatchedDetections = detections.toMutableList()
        val matched = mutableListOf<Pair<TrackedObject, Detection>>()
        val candidates = mutableListOf<Triple<TrackedObject, Detection, Float>>()
        
        for (track in unmatchedTracks) {
            for (det in detections) {
                if (track.detection.classId == det.classId) {
                    val iouValue = boxIou(track.detection.bounds, det.bounds)
                    if (iouValue > 0.25f) candidates.add(Triple(track, det, iouValue))
                }
            }
        }
        
        candidates.sortByDescending { it.third }
        val matchedTracks = mutableSetOf<Int>()
        val matchedDetections = mutableSetOf<Detection>()
        
        for (candidate in candidates) {
            val (track, det) = candidate
            if (track.id !in matchedTracks && det !in matchedDetections) {
                matched.add(track to det)
                matchedTracks.add(track.id)
                matchedDetections.add(det)
                unmatchedTracks.remove(track)
                unmatchedDetections.remove(det)
            }
        }
        
        for ((track, det) in matched) { 
            track.update(det, frameTimeNs)
            track.detection = track.detection.copy(speedInfo = calculateSpeed(track)) 
        }
        
        trackedObjects.removeAll { it.missedFrames++ > 60 }
        
        for (det in unmatchedDetections) { 
            val nt = TrackedObject(det, nextId.getAndIncrement(), frameTimeNs)
            nt.update(det, frameTimeNs)
            trackedObjects.add(nt) 
        }
        
        return trackedObjects.filter { (it.age >= 2 && it.missedFrames <= 3) || it.missedFrames == 0 }
            .map { it.detection.copy(bounds = it.getExtrapolatedBounds(frameTimeNs)) }
    }

    fun clear() { 
        trackedObjects.clear()
        nextId.set(1) 
    }

    private fun boxIou(a: RectF, b: RectF): Float {
        val iw = (minOf(a.right, b.right) - maxOf(a.left, b.left)).coerceAtLeast(0f)
        val ih = (minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)).coerceAtLeast(0f)
        val inter = iw * ih
        val union = a.width()*a.height() + b.width()*b.height() - inter
        return if (union <= 0f) 0f else inter / union
    }

    private fun calculateSpeed(track: TrackedObject): SpeedInfo {
        val p = track.positionHistory
        val t = track.timestampHistory
        if (p.size < 3) return SpeedInfo()
        val dt = (t.last() - t.first()) / 1_000_000_000f
        if (dt <= 0.05f) return track.detection.speedInfo
        val speedPx = sqrt((p.last().first-p.first().first).let{it*it} + (p.last().second-p.first().second).let{it*it}) / dt
        val dir = when {
            kotlin.math.abs(p.last().first-p.first().first) > kotlin.math.abs(p.last().second-p.first().second)*1.5f -> if (p.last().first > p.first().first) "Right" else "Left"
            kotlin.math.abs(p.last().second-p.first().second) > kotlin.math.abs(p.last().first-p.first().first)*1.5f -> if (p.last().second < p.first().second) "Up" else "Down"
            else -> "diagonal"
        }
        val mph = speedPx * 0.45f * 2.23694f
        return SpeedInfo(speedPx, speedPx*0.45f, speedPx*0.45f*3.6f, mph, mph.toInt(), dir)
    }
}
