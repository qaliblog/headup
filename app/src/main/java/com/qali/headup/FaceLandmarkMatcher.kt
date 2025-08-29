package com.qali.headup

import android.util.Log
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.*

/**
 * Matches face landmarks between model and real face to calculate optimal alignment
 */
class FaceLandmarkMatcher {
    
    companion object {
        private const val TAG = "FaceLandmarkMatcher"
    }
    
    /**
     * Result of landmark matching and alignment calculation
     */
    data class AlignmentResult(
        val scale: Float,
        val scaleX: Float,
        val scaleY: Float,
        val offsetX: Float,
        val offsetY: Float,
        val offsetZ: Float,
        val rotationX: Float,
        val rotationY: Float,
        val rotationZ: Float,
        val confidence: Float
    )
    
    /**
     * Find the best alignment between model landmarks and real face landmarks
     */
    fun findBestAlignment(
        modelLandmarks: List<NormalizedLandmark>,
        realFaceLandmarks: List<NormalizedLandmark>
    ): AlignmentResult {
        try {
            Log.d(TAG, "Finding best alignment between ${modelLandmarks.size} model landmarks and ${realFaceLandmarks.size} real face landmarks")
            
            // Calculate face centers
            val modelCenter = calculateCenter(modelLandmarks)
            val realCenter = calculateCenter(realFaceLandmarks)
            
            // Calculate face dimensions
            val modelDimensions = calculateFaceDimensions(modelLandmarks)
            val realDimensions = calculateFaceDimensions(realFaceLandmarks)
            
            // Calculate scale factors
            val scaleX = if (modelDimensions.first > 0) realDimensions.first / modelDimensions.first else 1f
            val scaleY = if (modelDimensions.second > 0) realDimensions.second / modelDimensions.second else 1f
            val uniformScale = (scaleX + scaleY) / 2f
            
            // Calculate translation
            val offsetX = realCenter.first - modelCenter.first
            val offsetY = realCenter.second - modelCenter.second
            
            // Calculate rotation
            val rotationZ = calculateFaceRotation(modelLandmarks, realFaceLandmarks)
            
            // Calculate confidence
            val confidence = calculateAlignmentConfidence(modelLandmarks, realFaceLandmarks, uniformScale, offsetX, offsetY, rotationZ)
            
            val result = AlignmentResult(
                scale = uniformScale.coerceIn(0.3f, 3.0f),
                scaleX = scaleX.coerceIn(0.3f, 3.0f),
                scaleY = scaleY.coerceIn(0.3f, 3.0f),
                offsetX = offsetX.coerceIn(-1.0f, 1.0f),
                offsetY = offsetY.coerceIn(-1.0f, 1.0f),
                offsetZ = 0f,
                rotationX = 0f,
                rotationY = 0f,
                rotationZ = rotationZ.coerceIn(-45f, 45f),
                confidence = confidence
            )
            
            Log.d(TAG, "Alignment calculated: scale=${result.scale}, offset=(${result.offsetX}, ${result.offsetY}), rotation=${result.rotationZ}, confidence=${result.confidence}")
            
            return result
            
        } catch (e: Exception) {
            Log.e(TAG, "Error calculating alignment", e)
            return AlignmentResult(1f, 1f, 1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
        }
    }
    
    private fun calculateCenter(landmarks: List<NormalizedLandmark>): Pair<Float, Float> {
        if (landmarks.isEmpty()) return Pair(0f, 0f)
        
        val centerX = landmarks.map { it.x() }.average().toFloat()
        val centerY = landmarks.map { it.y() }.average().toFloat()
        
        return Pair(centerX, centerY)
    }
    
    private fun calculateFaceDimensions(landmarks: List<NormalizedLandmark>): Pair<Float, Float> {
        if (landmarks.isEmpty()) return Pair(0f, 0f)
        
        val minX = landmarks.minOf { it.x() }
        val maxX = landmarks.maxOf { it.x() }
        val minY = landmarks.minOf { it.y() }
        val maxY = landmarks.maxOf { it.y() }
        
        return Pair(maxX - minX, maxY - minY)
    }
    
    private fun calculateFaceRotation(
        modelLandmarks: List<NormalizedLandmark>,
        realLandmarks: List<NormalizedLandmark>
    ): Float {
        // Simple rotation calculation based on landmark distribution
        return 0f // Simplified for now
    }
    
    private fun calculateAlignmentConfidence(
        modelLandmarks: List<NormalizedLandmark>,
        realLandmarks: List<NormalizedLandmark>,
        scale: Float,
        offsetX: Float,
        offsetY: Float,
        rotation: Float
    ): Float {
        // Return a reasonable confidence score
        return if (scale in 0.5f..2.0f) 0.8f else 0.3f
    }
}