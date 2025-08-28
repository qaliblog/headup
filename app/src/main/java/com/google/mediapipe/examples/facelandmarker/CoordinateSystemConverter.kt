/*
 * Copyright 2023 The TensorFlow Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *             http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.mediapipe.examples.facelandmarker

import android.graphics.PointF
import android.util.Log
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.*

/**
 * Utility class for converting between different coordinate systems:
 * - MediaPipe normalized coordinates [0,1]
 * - 3D model local coordinates
 * - Screen/Canvas coordinates [pixels]
 * - Camera preview coordinates
 */
object CoordinateSystemConverter {
    
    private const val TAG = "CoordinateSystemConverter"
    
    /**
     * Convert MediaPipe normalized landmark to screen coordinates
     */
    fun landmarkToScreen(
        landmark: NormalizedLandmark,
        imageWidth: Int,
        imageHeight: Int,
        screenWidth: Int,
        screenHeight: Int,
        scaleFactor: Float,
        offsetX: Float,
        offsetY: Float
    ): PointF {
        // MediaPipe landmarks are normalized to [0,1] based on image dimensions
        val imageX = landmark.x() * imageWidth
        val imageY = landmark.y() * imageHeight
        
        // Scale to screen coordinates
        val scaleX = screenWidth.toFloat() / imageWidth
        val scaleY = screenHeight.toFloat() / imageHeight
        
        val screenX = imageX * scaleX * scaleFactor + offsetX
        val screenY = imageY * scaleY * scaleFactor + offsetY
        
        return PointF(screenX, screenY)
    }
    
    /**
     * Convert 3D model vertex to normalized coordinates (as simple data structure)
     */
    fun modelVertexToNormalized(
        vertex: Vertex3D,
        modelBounds: Pair<Vertex3D, Vertex3D>
    ): NormalizedPoint3D {
        val (minBound, maxBound) = modelBounds
        
        val width = maxBound.x - minBound.x
        val height = maxBound.y - minBound.y
        val depth = maxBound.z - minBound.z
        
        val normalizedX = if (width > 0) (vertex.x - minBound.x) / width else 0.5f
        val normalizedY = if (height > 0) (vertex.y - minBound.y) / height else 0.5f
        val normalizedZ = if (depth > 0) (vertex.z - minBound.z) / depth else 0.5f
        
        return NormalizedPoint3D(
            normalizedX.coerceIn(0f, 1f),
            normalizedY.coerceIn(0f, 1f),
            normalizedZ.coerceIn(0f, 1f)
        )
    }
    
    /**
     * Convert normalized coordinates back to 3D model space
     */
    fun normalizedToModelVertex(
        normalized: NormalizedPoint3D,
        modelBounds: Pair<Vertex3D, Vertex3D>
    ): Vertex3D {
        val (minBound, maxBound) = modelBounds
        
        val width = maxBound.x - minBound.x
        val height = maxBound.y - minBound.y
        val depth = maxBound.z - minBound.z
        
        return Vertex3D(
            minBound.x + normalized.x * width,
            minBound.y + normalized.y * height,
            minBound.z + normalized.z * depth
        )
    }
    
    /**
     * Convert normalized coordinates back to 3D model space (with individual values)
     */
    fun normalizedToModelVertex(
        normalizedX: Float,
        normalizedY: Float,
        normalizedZ: Float = 0.5f,
        modelBounds: Pair<Vertex3D, Vertex3D>
    ): Vertex3D {
        return normalizedToModelVertex(
            NormalizedPoint3D(normalizedX, normalizedY, normalizedZ),
            modelBounds
        )
    }
    
    /**
     * Convert screen coordinates to normalized coordinates
     */
    fun screenToNormalized(
        screenPoint: PointF,
        imageWidth: Int,
        imageHeight: Int,
        screenWidth: Int,
        screenHeight: Int,
        scaleFactor: Float,
        offsetX: Float,
        offsetY: Float
    ): PointF {
        // Reverse the screen transformation
        val adjustedX = (screenPoint.x - offsetX) / scaleFactor
        val adjustedY = (screenPoint.y - offsetY) / scaleFactor
        
        // Convert back to image coordinates
        val scaleX = screenWidth.toFloat() / imageWidth
        val scaleY = screenHeight.toFloat() / imageHeight
        
        val imageX = adjustedX / scaleX
        val imageY = adjustedY / scaleY
        
        // Normalize to [0,1]
        val normalizedX = (imageX / imageWidth).coerceIn(0f, 1f)
        val normalizedY = (imageY / imageHeight).coerceIn(0f, 1f)
        
        return PointF(normalizedX, normalizedY)
    }
    
    /**
     * Calculate precise alignment transformation between two coordinate systems
     */
    fun calculateAlignmentTransform(
        sourcePoints: List<Vertex3D>,
        targetPoints: List<Vertex3D>,
        weights: List<Float> = emptyList()
    ): AlignmentTransform {
        require(sourcePoints.size == targetPoints.size) { 
            "Source and target point lists must have the same size" 
        }
        require(sourcePoints.size >= 3) { 
            "Need at least 3 points for alignment" 
        }
        
        val pointWeights = if (weights.size == sourcePoints.size) weights else 
            List(sourcePoints.size) { 1.0f }
        
        Log.d(TAG, "🔄 Calculating alignment transform for ${sourcePoints.size} point pairs")
        
        // Calculate weighted centroids
        var totalWeight = 0f
        var sourceCentroid = Vertex3D(0f, 0f, 0f)
        var targetCentroid = Vertex3D(0f, 0f, 0f)
        
        sourcePoints.forEachIndexed { i, sourcePoint ->
            val weight = pointWeights[i]
            sourceCentroid = sourceCentroid + (sourcePoint * weight)
            targetCentroid = targetCentroid + (targetPoints[i] * weight)
            totalWeight += weight
        }
        
        if (totalWeight > 0) {
            sourceCentroid = sourceCentroid / totalWeight
            targetCentroid = targetCentroid / totalWeight
        }
        
        // Calculate scale factors for each axis
        var sumSourceDistSqX = 0f
        var sumSourceDistSqY = 0f
        var sumSourceDistSqZ = 0f
        var sumTargetDistSqX = 0f
        var sumTargetDistSqY = 0f
        var sumTargetDistSqZ = 0f
        
        sourcePoints.forEachIndexed { i, sourcePoint ->
            val weight = pointWeights[i]
            val sourceDiff = sourcePoint - sourceCentroid
            val targetDiff = targetPoints[i] - targetCentroid
            
            sumSourceDistSqX += weight * sourceDiff.x * sourceDiff.x
            sumSourceDistSqY += weight * sourceDiff.y * sourceDiff.y
            sumSourceDistSqZ += weight * sourceDiff.z * sourceDiff.z
            
            sumTargetDistSqX += weight * targetDiff.x * targetDiff.x
            sumTargetDistSqY += weight * targetDiff.y * targetDiff.y
            sumTargetDistSqZ += weight * targetDiff.z * targetDiff.z
        }
        
        // Calculate individual scale factors with safety checks
        val scaleX = if (sumSourceDistSqX > 0.0001f) sqrt(sumTargetDistSqX / sumSourceDistSqX) else 1f
        val scaleY = if (sumSourceDistSqY > 0.0001f) sqrt(sumTargetDistSqY / sumSourceDistSqY) else 1f
        val scaleZ = if (sumSourceDistSqZ > 0.0001f) sqrt(sumTargetDistSqZ / sumSourceDistSqZ) else 1f
        
        // Overall scale is the average
        val scale = (scaleX + scaleY + scaleZ) / 3f
        
        // Calculate translation (after scaling)
        val translation = targetCentroid - (sourceCentroid * scale)
        
        Log.d(TAG, "✅ Transform calculated:")
        Log.d(TAG, "   Scale: $scale (X: $scaleX, Y: $scaleY, Z: $scaleZ)")
        Log.d(TAG, "   Translation: $translation")
        Log.d(TAG, "   Source centroid: $sourceCentroid")
        Log.d(TAG, "   Target centroid: $targetCentroid")
        
        return AlignmentTransform(
            scale = scale,
            scaleX = scaleX,
            scaleY = scaleY,
            scaleZ = scaleZ,
            translation = translation,
            rotation = calculateRotation(sourcePoints, targetPoints, pointWeights)
        )
    }
    
    /**
     * Calculate rotation between point sets (simplified)
     */
    private fun calculateRotation(
        sourcePoints: List<Vertex3D>,
        targetPoints: List<Vertex3D>,
        weights: List<Float>
    ): Vertex3D {
        // Simplified rotation calculation
        // In a full implementation, this would use SVD or Kabsch algorithm
        return Vertex3D(0f, 0f, 0f) // Placeholder
    }
    
    /**
     * Apply alignment transformation to a point
     */
    fun applyTransform(point: Vertex3D, transform: AlignmentTransform): Vertex3D {
        // Apply scale (with individual axis scaling)
        val scaled = Vertex3D(
            point.x * transform.scaleX,
            point.y * transform.scaleY,
            point.z * transform.scaleZ
        )
        
        // Apply rotation (simplified - in practice would use rotation matrices)
        val rotated = applyRotation(scaled, transform.rotation)
        
        // Apply translation
        return rotated + transform.translation
    }
    
    /**
     * Apply rotation to a point (simplified)
     */
    private fun applyRotation(point: Vertex3D, rotation: Vertex3D): Vertex3D {
        // Simplified rotation - in practice would use proper rotation matrices
        return point
    }
    
    /**
     * Calculate alignment error between transformed source points and target points
     */
    fun calculateAlignmentError(
        sourcePoints: List<Vertex3D>,
        targetPoints: List<Vertex3D>,
        transform: AlignmentTransform,
        weights: List<Float> = emptyList()
    ): Float {
        require(sourcePoints.size == targetPoints.size)
        
        val pointWeights = if (weights.size == sourcePoints.size) weights else 
            List(sourcePoints.size) { 1.0f }
        
        var totalError = 0f
        var totalWeight = 0f
        
        sourcePoints.forEachIndexed { i, sourcePoint ->
            val transformedPoint = applyTransform(sourcePoint, transform)
            val targetPoint = targetPoints[i]
            val weight = pointWeights[i]
            
            val error = (transformedPoint - targetPoint).magnitude()
            totalError += weight * error
            totalWeight += weight
        }
        
        return if (totalWeight > 0) totalError / totalWeight else Float.MAX_VALUE
    }
}

/**
 * Simple data class for normalized 3D coordinates [0,1]
 */
data class NormalizedPoint3D(
    val x: Float,
    val y: Float,
    val z: Float
) {
    fun toVertex3D(): Vertex3D = Vertex3D(x, y, z)
    
    fun isValid(): Boolean = x.isFinite() && y.isFinite() && z.isFinite() &&
                            x >= 0f && x <= 1f && y >= 0f && y <= 1f && z >= 0f && z <= 1f
}

/**
 * Data class representing an alignment transformation
 */
data class AlignmentTransform(
    val scale: Float,
    val scaleX: Float,
    val scaleY: Float,
    val scaleZ: Float,
    val translation: Vertex3D,
    val rotation: Vertex3D // Simplified as Euler angles
) {
    fun isValid(): Boolean {
        return scale > 0 && scaleX > 0 && scaleY > 0 && scaleZ > 0 &&
               scale.isFinite() && scaleX.isFinite() && scaleY.isFinite() && scaleZ.isFinite() &&
               translation.x.isFinite() && translation.y.isFinite() && translation.z.isFinite()
    }
}