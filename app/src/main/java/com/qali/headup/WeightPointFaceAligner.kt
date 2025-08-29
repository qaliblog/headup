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
package com.qali.headup

import android.graphics.*
import android.util.Log
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.*

/**
 * Face weight point and direction data
 */
data class FaceWeightData(
    val weightPoint: Vertex3D,      // Central weight point of face
    val headDirection: Vertex3D,    // Direction vector from weight point to nose
    val faceWidth: Float,           // Distance between eye corners
    val faceHeight: Float,          // Distance from forehead to chin
    val confidence: Float           // Quality confidence (0-1)
)

/**
 * Alignment transformation based on weight points and directions
 */
data class WeightPointAlignment(
    val translation: Vertex3D,      // Translation to align weight points
    val rotation: Vertex3D,         // Rotation to align head directions (euler angles)
    val scale: Float,               // Uniform scale factor
    val transformMatrix: FloatArray, // Combined transformation matrix
    val confidence: Float           // Alignment quality (0-1)
)

/**
 * Advanced face alignment using weight points and head direction vectors
 * This approach scans the model to generate face mesh, then matches orientations
 */
class WeightPointFaceAligner {
    
    companion object {
        private const val TAG = "WeightPointFaceAligner"
        
        // Key landmarks for weight point calculation
        private val WEIGHT_POINT_LANDMARKS = listOf(
            1,   // Face center
            9,   // Face center top
            10,  // Face center bottom
            151, // Chin center
            8,   // Face center bridge
            168  // Face center lower
        )
        
        // Key landmarks for direction calculation
        private const val NOSE_TIP = 2
        private const val FOREHEAD_CENTER = 10
        private const val CHIN_CENTER = 151
        
        // Eye landmarks for width calculation
        private const val LEFT_EYE_OUTER = 33
        private const val RIGHT_EYE_OUTER = 362
        
        // Progressive scaling parameters
        private const val INITIAL_SCALE = 0.1f    // Start very small
        private const val MAX_SCALE = 1.0f        // Maximum allowed scale
        private const val SCALE_STEP = 0.05f      // Progressive scaling step
    }
    
    /**
     * Calculate face weight point and direction from MediaPipe landmarks
     */
    fun calculateFaceWeightData(landmarks: List<NormalizedLandmark>): FaceWeightData? {
        return try {
            Log.d(TAG, "Calculating face weight data from ${landmarks.size} landmarks")
            
            // 1. Calculate weight point (average of key central landmarks)
            val weightPoint = calculateWeightPoint(landmarks)
            
            // 2. Calculate head direction vector (weight point to nose)
            val headDirection = calculateHeadDirection(landmarks, weightPoint)
            
            // 3. Calculate face dimensions
            val faceWidth = calculateFaceWidth(landmarks)
            val faceHeight = calculateFaceHeight(landmarks)
            
            // 4. Calculate confidence based on landmark quality
            val confidence = calculateConfidence(landmarks)
            
            Log.d(TAG, "Face weight data calculated: weightPoint=$weightPoint, direction=$headDirection")
            Log.d(TAG, "Face dimensions: width=$faceWidth, height=$faceHeight, confidence=$confidence")
            
            FaceWeightData(weightPoint, headDirection, faceWidth, faceHeight, confidence)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error calculating face weight data", e)
            null
        }
    }
    
    /**
     * Calculate weight point from model's face mesh (after scanning with MediaPipe)
     */
    fun calculateModelWeightData(modelFaceData: Model3DFaceData): FaceWeightData? {
        return try {
            Log.d(TAG, "Calculating model weight data from face landmarks")
            
            // Use the landmarks detected in the model
            calculateFaceWeightData(modelFaceData.landmarks)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error calculating model weight data", e)
            null
        }
    }
    
    /**
     * Align model face to real face using weight points and directions
     */
    fun alignFaces(
        modelWeightData: FaceWeightData,
        realWeightData: FaceWeightData,
        progressiveScale: Float = INITIAL_SCALE
    ): WeightPointAlignment? {
        
        return try {
            Log.d(TAG, "Aligning faces with progressive scale: $progressiveScale")
            
            // 1. Calculate translation to align weight points
            val translation = Vertex3D(
                realWeightData.weightPoint.x - modelWeightData.weightPoint.x,
                realWeightData.weightPoint.y - modelWeightData.weightPoint.y,
                realWeightData.weightPoint.z - modelWeightData.weightPoint.z
            )
            
            // 2. Calculate rotation to align head directions
            val rotation = calculateDirectionAlignment(
                modelWeightData.headDirection,
                realWeightData.headDirection
            )
            
            // 3. Calculate scale based on face size comparison (with progressive scaling)
            val baseScale = calculateFaceScale(modelWeightData, realWeightData)
            val finalScale = minOf(baseScale, progressiveScale) // Apply progressive limit
            
            // 4. Build transformation matrix
            val transformMatrix = buildTransformationMatrix(translation, rotation, finalScale)
            
            // 5. Calculate alignment confidence
            val confidence = minOf(modelWeightData.confidence, realWeightData.confidence)
            
            Log.d(TAG, "Face alignment calculated:")
            Log.d(TAG, "  Translation: $translation")
            Log.d(TAG, "  Rotation: $rotation")
            Log.d(TAG, "  Scale: $finalScale (base: $baseScale, progressive: $progressiveScale)")
            Log.d(TAG, "  Confidence: $confidence")
            
            WeightPointAlignment(translation, rotation, finalScale, transformMatrix, confidence)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error aligning faces", e)
            null
        }
    }
    
    /**
     * Calculate progressive scale factor for smooth scaling animation
     */
    fun calculateProgressiveScale(
        modelWeightData: FaceWeightData,
        realWeightData: FaceWeightData,
        currentScale: Float,
        targetReached: Boolean
    ): Float {
        
        val targetScale = calculateFaceScale(modelWeightData, realWeightData)
        val clampedTarget = targetScale.coerceIn(INITIAL_SCALE, MAX_SCALE)
        
        return if (targetReached || currentScale >= clampedTarget) {
            clampedTarget
        } else {
            // Progressive scaling - increase gradually
            minOf(currentScale + SCALE_STEP, clampedTarget)
        }
    }
    
    private fun calculateWeightPoint(landmarks: List<NormalizedLandmark>): Vertex3D {
        var sumX = 0f
        var sumY = 0f
        var sumZ = 0f
        var count = 0
        
        WEIGHT_POINT_LANDMARKS.forEach { landmarkIndex ->
            if (landmarkIndex < landmarks.size) {
                val landmark = landmarks[landmarkIndex]
                sumX += landmark.x()
                sumY += landmark.y()
                sumZ += landmark.z()
                count++
            }
        }
        
        return if (count > 0) {
            Vertex3D(sumX / count, sumY / count, sumZ / count)
        } else {
            // Fallback to first landmark
            val firstLandmark = landmarks[0]
            Vertex3D(firstLandmark.x(), firstLandmark.y(), firstLandmark.z())
        }
    }
    
    private fun calculateHeadDirection(landmarks: List<NormalizedLandmark>, weightPoint: Vertex3D): Vertex3D {
        // Calculate direction from weight point to nose tip
        val noseLandmark = if (NOSE_TIP < landmarks.size) {
            landmarks[NOSE_TIP]
        } else {
            landmarks[0] // Fallback
        }
        
        val direction = Vertex3D(
            noseLandmark.x() - weightPoint.x,
            noseLandmark.y() - weightPoint.y,
            noseLandmark.z() - weightPoint.z
        )
        
        // Normalize direction vector
        val length = sqrt(direction.x * direction.x + direction.y * direction.y + direction.z * direction.z)
        
        return if (length > 0.001f) {
            Vertex3D(direction.x / length, direction.y / length, direction.z / length)
        } else {
            Vertex3D(0f, 0f, 1f) // Default forward direction
        }
    }
    
    private fun calculateFaceWidth(landmarks: List<NormalizedLandmark>): Float {
        val leftEye = if (LEFT_EYE_OUTER < landmarks.size) landmarks[LEFT_EYE_OUTER] else landmarks[0]
        val rightEye = if (RIGHT_EYE_OUTER < landmarks.size) landmarks[RIGHT_EYE_OUTER] else landmarks[0]
        
        val dx = rightEye.x() - leftEye.x()
        val dy = rightEye.y() - leftEye.y()
        val dz = rightEye.z() - leftEye.z()
        
        return sqrt(dx * dx + dy * dy + dz * dz)
    }
    
    private fun calculateFaceHeight(landmarks: List<NormalizedLandmark>): Float {
        val forehead = if (FOREHEAD_CENTER < landmarks.size) landmarks[FOREHEAD_CENTER] else landmarks[0]
        val chin = if (CHIN_CENTER < landmarks.size) landmarks[CHIN_CENTER] else landmarks[0]
        
        val dx = chin.x() - forehead.x()
        val dy = chin.y() - forehead.y()
        val dz = chin.z() - forehead.z()
        
        return sqrt(dx * dx + dy * dy + dz * dz)
    }
    
    private fun calculateConfidence(landmarks: List<NormalizedLandmark>): Float {
        // Simple confidence based on landmark availability and face size
        val requiredLandmarks = WEIGHT_POINT_LANDMARKS.count { it < landmarks.size }
        val availabilityScore = requiredLandmarks.toFloat() / WEIGHT_POINT_LANDMARKS.size
        
        // Additional checks for face quality could be added here
        
        return availabilityScore.coerceIn(0f, 1f)
    }
    
    private fun calculateDirectionAlignment(modelDirection: Vertex3D, realDirection: Vertex3D): Vertex3D {
        // Calculate rotation needed to align model direction with real direction
        // This is a simplified approach - more complex 3D rotation calculation could be used
        
        // Calculate angle differences
        val yawDiff = atan2(realDirection.x, realDirection.z) - atan2(modelDirection.x, modelDirection.z)
        val pitchDiff = asin(realDirection.y) - asin(modelDirection.y)
        
        return Vertex3D(pitchDiff, yawDiff, 0f) // pitch, yaw, roll
    }
    
    private fun calculateFaceScale(modelWeightData: FaceWeightData, realWeightData: FaceWeightData): Float {
        // Calculate scale based on face width (most reliable)
        val widthScale = if (modelWeightData.faceWidth > 0.001f) {
            realWeightData.faceWidth / modelWeightData.faceWidth
        } else {
            1f
        }
        
        // Calculate scale based on face height
        val heightScale = if (modelWeightData.faceHeight > 0.001f) {
            realWeightData.faceHeight / modelWeightData.faceHeight
        } else {
            1f
        }
        
        // Use average of width and height scales
        val averageScale = (widthScale + heightScale) / 2f
        
        // Apply conservative scaling - start small and gradually increase
        return averageScale * 0.5f // Conservative factor
    }
    
    private fun buildTransformationMatrix(
        translation: Vertex3D,
        rotation: Vertex3D,
        scale: Float
    ): FloatArray {
        val matrix = FloatArray(16)
        
        // Create transformation matrix (scale + rotation + translation)
        val cosPitch = cos(rotation.x)
        val sinPitch = sin(rotation.x)
        val cosYaw = cos(rotation.y)
        val sinYaw = sin(rotation.y)
        val cosRoll = cos(rotation.z)
        val sinRoll = sin(rotation.z)
        
        // Combined rotation and scale matrix
        matrix[0] = (cosYaw * cosRoll * scale).toFloat()
        matrix[1] = (-cosYaw * sinRoll * scale).toFloat()
        matrix[2] = (sinYaw * scale).toFloat()
        matrix[3] = 0f
        
        matrix[4] = (sinRoll * cosPitch * scale).toFloat()
        matrix[5] = (cosRoll * cosPitch * scale).toFloat()
        matrix[6] = (-sinPitch * scale).toFloat()
        matrix[7] = 0f
        
        matrix[8] = (-sinYaw * cosRoll * scale).toFloat()
        matrix[9] = (sinYaw * sinRoll * scale).toFloat()
        matrix[10] = (cosYaw * scale).toFloat()
        matrix[11] = 0f
        
        // Translation
        matrix[12] = translation.x
        matrix[13] = translation.y
        matrix[14] = translation.z
        matrix[15] = 1f
        
        return matrix
    }
}