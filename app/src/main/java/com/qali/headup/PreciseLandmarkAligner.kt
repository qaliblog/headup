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

import android.util.Log
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.*

/**
 * Ultra-precise landmark-to-landmark alignment system
 * Maps each 3D model landmark directly to corresponding face landmarks
 */
class PreciseLandmarkAligner {
    
    companion object {
        private const val TAG = "PreciseLandmarkAligner"
        
        // Key landmark indices for MediaPipe Face Landmarker (468 landmarks)
        private val KEY_LANDMARKS = mapOf(
            // Eyes (most stable landmarks)
            "left_eye_inner" to 133,
            "left_eye_outer" to 33,
            "right_eye_inner" to 362,
            "right_eye_outer" to 263,
            "left_eye_center" to 159,
            "right_eye_center" to 386,
            
            // Nose (very stable)
            "nose_tip" to 1,
            "nose_bridge" to 6,
            "nose_left" to 31,
            "nose_right" to 261,
            
            // Mouth (important for expression)
            "mouth_left" to 61,
            "mouth_right" to 291,
            "mouth_top" to 13,
            "mouth_bottom" to 14,
            "mouth_center" to 17,
            
            // Face contour (for shape)
            "face_left" to 172,
            "face_right" to 397,
            "chin" to 175,
            "forehead" to 10,
            
            // Cheeks
            "left_cheek" to 116,
            "right_cheek" to 345
        )
    }
    
    data class LandmarkAlignment(
        val modelLandmarks: List<NormalizedLandmark>,
        val faceLandmarks: List<NormalizedLandmark>,
        val transform: TransformMatrix,
        val confidence: Float,
        val mappings: List<LandmarkPair>
    )
    
    data class LandmarkPair(
        val modelIndex: Int,
        val faceIndex: Int,
        val modelPoint: Vector3D,
        val facePoint: Vector3D,
        val weight: Float
    )
    
    data class TransformMatrix(
        val scale: Float,
        val scaleX: Float = scale, // Allow separate X scaling for stretching
        val scaleY: Float = scale, // Allow separate Y scaling for stretching
        val rotation: RotationMatrix,
        val translation: Vector3D
    )
    
    data class RotationMatrix(
        val pitch: Float,  // X rotation
        val yaw: Float,    // Y rotation  
        val roll: Float    // Z rotation
    )
    
    data class Vector3D(val x: Float, val y: Float, val z: Float) {
        operator fun plus(other: Vector3D) = Vector3D(x + other.x, y + other.y, z + other.z)
        operator fun minus(other: Vector3D) = Vector3D(x - other.x, y - other.y, z - other.z)
        operator fun times(scalar: Float) = Vector3D(x * scalar, y * scalar, z * scalar)
        fun length() = sqrt(x*x + y*y + z*z)
        fun normalize() = if (length() > 0) this * (1f / length()) else this
    }
    
    /**
     * Create ultra-precise alignment between 3D model landmarks and real face landmarks
     */
    fun calculatePreciseAlignment(
        modelFaceData: Model3DFaceData,
        realFaceLandmarks: List<NormalizedLandmark>
    ): LandmarkAlignment? {
        
        return try {
            Log.d(TAG, "🎯 Starting ultra-precise landmark alignment")
            Log.d(TAG, "Model landmarks: ${modelFaceData.landmarks.size}, Real landmarks: ${realFaceLandmarks.size}")
            
            // Step 1: Establish high-confidence landmark correspondences
            val correspondences = establishKeyCorrespondences(modelFaceData.landmarks, realFaceLandmarks)
            if (correspondences.size < 8) {
                Log.w(TAG, "⚠️ Insufficient correspondences: ${correspondences.size} (need 8+)")
                return null
            }
            
            Log.d(TAG, "✅ Found ${correspondences.size} key correspondences")
            
            // Step 2: Calculate initial head direction alignment
            val headDirectionTransform = calculateHeadDirectionAlignment(correspondences)
            
            // Step 3: Calculate precise scale using multiple reference distances
            val (scale, scaleX, scaleY) = calculatePreciseScaleWithStretching(correspondences)
            
            // Step 4: Fine-tune with all landmark correspondences
            val fineTunedTransform = fineTuneAlignment(correspondences, headDirectionTransform, scale, scaleX, scaleY)
            
            // Step 5: Calculate confidence based on alignment quality
            val confidence = calculateAlignmentConfidence(correspondences, fineTunedTransform)
            
            Log.d(TAG, "🎯 Precise alignment complete:")
            Log.d(TAG, "   Scale: ${fineTunedTransform.scale} (X: ${fineTunedTransform.scaleX}, Y: ${fineTunedTransform.scaleY})")
            Log.d(TAG, "   Rotation: pitch=${fineTunedTransform.rotation.pitch}°, yaw=${fineTunedTransform.rotation.yaw}°, roll=${fineTunedTransform.rotation.roll}°")
            Log.d(TAG, "   Translation: ${fineTunedTransform.translation}")
            Log.d(TAG, "   Confidence: ${String.format("%.3f", confidence)}")
            
            LandmarkAlignment(
                modelLandmarks = modelFaceData.landmarks,
                faceLandmarks = realFaceLandmarks,
                transform = fineTunedTransform,
                confidence = confidence,
                mappings = correspondences
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Error in precise alignment calculation", e)
            null
        }
    }
    
    /**
     * Establish correspondences between key landmarks using spatial relationships
     */
    private fun establishKeyCorrespondences(
        modelLandmarks: List<NormalizedLandmark>,
        faceLandmarks: List<NormalizedLandmark>
    ): List<LandmarkPair> {
        
        val correspondences = mutableListOf<LandmarkPair>()
        
        // Convert landmarks to normalized coordinates
        val modelPoints = modelLandmarks.map { Vector3D(it.x(), it.y(), it.z() ?: 0f) }
        val facePoints = faceLandmarks.mapIndexed { index, landmark ->
            IndexedValue(index, Vector3D(landmark.x(), landmark.y(), landmark.z() ?: 0f))
        }
        
        // For each key landmark type, find the best match in model landmarks
        for ((landmarkName, faceIndex) in KEY_LANDMARKS) {
            if (faceIndex >= faceLandmarks.size) continue
            
            val facePoint = Vector3D(
                faceLandmarks[faceIndex].x(), 
                faceLandmarks[faceIndex].y(), 
                faceLandmarks[faceIndex].z() ?: 0f
            )
            
            // Find the best matching model landmark using spatial context
            val bestModelMatch = findBestModelMatch(facePoint, faceIndex, modelPoints, facePoints, landmarkName)
            
            if (bestModelMatch != null) {
                val weight = getLandmarkWeight(landmarkName)
                correspondences.add(
                    LandmarkPair(
                        modelIndex = bestModelMatch,
                        faceIndex = faceIndex,
                        modelPoint = modelPoints[bestModelMatch],
                        facePoint = facePoint,
                        weight = weight
                    )
                )
                
                Log.d(TAG, "📍 $landmarkName: model[$bestModelMatch] ↔ face[$faceIndex] (weight: $weight)")
            }
        }
        
        return correspondences
    }
    
    /**
     * Find best matching model landmark using spatial relationships and constraints
     */
    private fun findBestModelMatch(
        facePoint: Vector3D,
        faceIndex: Int,
        modelPoints: List<Vector3D>,
        facePoints: List<IndexedValue<Vector3D>>,
        landmarkName: String
    ): Int? {
        
        // Calculate relative position of this landmark in face space
        val faceCenter = calculateCenter(facePoints.map { it.value })
        val relativePos = facePoint - faceCenter
        
        // Find model center
        val modelCenter = calculateCenter(modelPoints)
        
        var bestMatch = -1
        var bestScore = Float.MAX_VALUE
        
        for (modelIndex in modelPoints.indices) {
            val modelPoint = modelPoints[modelIndex]
            val modelRelativePos = modelPoint - modelCenter
            
            // Calculate similarity score based on relative position
            val positionDiff = (relativePos - modelRelativePos).length()
            
            // Weight based on landmark type
            val landmarkTypeWeight = when {
                landmarkName.contains("eye") -> 1.0f      // Eyes are most stable
                landmarkName.contains("nose") -> 1.2f     // Nose is very stable
                landmarkName.contains("mouth") -> 1.5f    // Mouth is important but moves
                landmarkName.contains("face") -> 2.0f     // Face outline is less precise
                else -> 1.8f
            }
            
            val score = positionDiff * landmarkTypeWeight
            
            if (score < bestScore) {
                bestScore = score
                bestMatch = modelIndex
            }
        }
        
        return if (bestMatch >= 0 && bestScore < 0.3f) bestMatch else null
    }
    
    /**
     * Calculate center point of a list of 3D points
     */
    private fun calculateCenter(points: List<Vector3D>): Vector3D {
        if (points.isEmpty()) return Vector3D(0f, 0f, 0f)
        
        val sum = points.reduce { acc, point -> acc + point }
        return sum * (1f / points.size)
    }
    
    /**
     * Get weight for different landmark types (higher = more important)
     */
    private fun getLandmarkWeight(landmarkName: String): Float {
        return when {
            landmarkName.contains("eye") -> 3.0f      // Eyes are most stable and important
            landmarkName.contains("nose") -> 2.5f     // Nose is very stable
            landmarkName.contains("mouth_center") -> 2.0f // Mouth center is important
            landmarkName.contains("mouth") -> 1.5f    // Other mouth points
            landmarkName.contains("chin") -> 1.8f     // Chin is fairly stable
            landmarkName.contains("forehead") -> 1.6f // Forehead reference
            else -> 1.0f
        }
    }
    
    /**
     * Calculate head direction alignment using eye and nose landmarks
     */
    private fun calculateHeadDirectionAlignment(correspondences: List<LandmarkPair>): TransformMatrix {
        
        // Find eye and nose correspondences
        val eyeCorrespondences = correspondences.filter { pair ->
            val faceIndex = pair.faceIndex
            faceIndex in listOf(133, 33, 362, 263, 159, 386) // Eye landmarks
        }
        
        val noseCorrespondences = correspondences.filter { pair ->
            val faceIndex = pair.faceIndex
            faceIndex in listOf(1, 6, 31, 261) // Nose landmarks
        }
        
        // Calculate head directions
        val faceHeadDirection = calculateHeadDirection(
            eyeCorrespondences.map { it.facePoint },
            noseCorrespondences.map { it.facePoint }
        )
        
        val modelHeadDirection = calculateHeadDirection(
            eyeCorrespondences.map { it.modelPoint },
            noseCorrespondences.map { it.modelPoint }
        )
        
        // Calculate rotation to align head directions
        val rotation = calculateRotationBetweenDirections(modelHeadDirection, faceHeadDirection)
        
        return TransformMatrix(
            scale = 1.0f, // Will be calculated separately
            rotation = rotation,
            translation = Vector3D(0f, 0f, 0f) // Will be calculated later
        )
    }
    
    /**
     * Calculate head direction vector from eye and nose points
     */
    private fun calculateHeadDirection(eyePoints: List<Vector3D>, nosePoints: List<Vector3D>): Vector3D {
        if (eyePoints.isEmpty() || nosePoints.isEmpty()) {
            return Vector3D(0f, 0f, 1f) // Default forward direction
        }
        
        val eyeCenter = calculateCenter(eyePoints)
        val noseCenter = calculateCenter(nosePoints)
        
        // Head direction is from eye center to nose center (forward)
        return (noseCenter - eyeCenter).normalize()
    }
    
    /**
     * Calculate rotation matrix to align two direction vectors
     */
    private fun calculateRotationBetweenDirections(from: Vector3D, to: Vector3D): RotationMatrix {
        
        // Calculate the angle between the vectors
        val dot = from.x * to.x + from.y * to.y + from.z * to.z
        val angle = acos(dot.coerceIn(-1f, 1f))
        
        if (angle < 0.01f) {
            // Vectors are already aligned
            return RotationMatrix(0f, 0f, 0f)
        }
        
        // Calculate rotation axis (cross product)
        val axis = Vector3D(
            from.y * to.z - from.z * to.y,
            from.z * to.x - from.x * to.z,
            from.x * to.y - from.y * to.x
        ).normalize()
        
        // Convert to Euler angles (simplified)
        val yaw = atan2(axis.y, axis.x) * 180f / PI.toFloat()
        val pitch = atan2(axis.z, sqrt(axis.x*axis.x + axis.y*axis.y)) * 180f / PI.toFloat()
        val roll = angle * 180f / PI.toFloat()
        
        return RotationMatrix(pitch, yaw, roll)
    }
    
    /**
     * Calculate precise scale with proper stretching for face dimensions
     * Returns Triple(scale, scaleX, scaleY) for uniform and directional scaling
     */
    private fun calculatePreciseScaleWithStretching(correspondences: List<LandmarkPair>): Triple<Float, Float, Float> {
        
        val scaleFactors = mutableListOf<Float>()
        val horizontalScales = mutableListOf<Float>()
        val verticalScales = mutableListOf<Float>()
        
        // Calculate scale using various facial feature distances
        for (i in correspondences.indices) {
            for (j in i + 1 until correspondences.size) {
                val pair1 = correspondences[i]
                val pair2 = correspondences[j]
                
                val modelDist = (pair1.modelPoint - pair2.modelPoint).length()
                val faceDist = (pair1.facePoint - pair2.facePoint).length()
                
                if (modelDist > 0.01f && faceDist > 0.01f) {
                    val scale = faceDist / modelDist
                    val weight = (pair1.weight + pair2.weight) / 2f
                    
                    // Determine if this is horizontal or vertical measurement
                    val deltaX = abs(pair1.facePoint.x - pair2.facePoint.x)
                    val deltaY = abs(pair1.facePoint.y - pair2.facePoint.y)
                    
                    if (deltaX > deltaY) {
                        // Primarily horizontal measurement (like eye-to-eye distance)
                        repeat(weight.toInt().coerceAtLeast(1)) {
                            horizontalScales.add(scale)
                        }
                    } else {
                        // Primarily vertical measurement (like forehead-to-chin)
                        repeat(weight.toInt().coerceAtLeast(1)) {
                            verticalScales.add(scale)
                        }
                    }
                    
                    // Add to overall scale factors
                    repeat(weight.toInt().coerceAtLeast(1)) {
                        scaleFactors.add(scale)
                    }
                }
            }
        }
        
        val (finalScale, finalScaleX, finalScaleY) = if (scaleFactors.isNotEmpty()) {
            // Calculate overall scale
            val medianScale = scaleFactors.sorted()[scaleFactors.size / 2]
            val zoomedOutScale = medianScale * 0.3f // Start 30% of calculated size (more zoomed out)
            
            // Calculate separate X and Y scales for stretching
            val scaleX = if (horizontalScales.isNotEmpty()) {
                val medianX = horizontalScales.sorted()[horizontalScales.size / 2]
                (medianX * 0.3f).coerceIn(0.1f, 3.0f) // Allow more horizontal stretching
            } else {
                zoomedOutScale
            }
            
            val scaleY = if (verticalScales.isNotEmpty()) {
                val medianY = verticalScales.sorted()[verticalScales.size / 2]
                (medianY * 0.3f).coerceIn(0.1f, 3.0f) // Allow more vertical stretching
            } else {
                zoomedOutScale
            }
            
            Triple(
                zoomedOutScale.coerceIn(0.1f, 2.0f), // Overall scale
                scaleX, // Horizontal scale
                scaleY  // Vertical scale
            )
        } else {
            Triple(0.2f, 0.2f, 0.2f) // Default very small size if no correspondences
        }
        
        return Triple(finalScale, finalScaleX, finalScaleY)
    }
    
    /**
     * Fine-tune alignment using all correspondences with separate X/Y scaling
     */
    private fun fineTuneAlignment(
        correspondences: List<LandmarkPair>,
        initialTransform: TransformMatrix,
        scale: Float,
        scaleX: Float,
        scaleY: Float
    ): TransformMatrix {
        
        // Calculate weighted centroid translation
        var weightedModelCenter = Vector3D(0f, 0f, 0f)
        var weightedFaceCenter = Vector3D(0f, 0f, 0f)
        var totalWeight = 0f
        
        for (pair in correspondences) {
            weightedModelCenter = weightedModelCenter + (pair.modelPoint * pair.weight)
            weightedFaceCenter = weightedFaceCenter + (pair.facePoint * pair.weight)
            totalWeight += pair.weight
        }
        
        if (totalWeight > 0) {
            weightedModelCenter = weightedModelCenter * (1f / totalWeight)
            weightedFaceCenter = weightedFaceCenter * (1f / totalWeight)
        }
        
        val translation = weightedFaceCenter - (weightedModelCenter * scale)
        
        return TransformMatrix(
            scale = scale,
            scaleX = scaleX,
            scaleY = scaleY,
            rotation = initialTransform.rotation,
            translation = translation
        )
    }
    
    /**
     * Calculate alignment confidence based on landmark correspondence quality
     */
    private fun calculateAlignmentConfidence(
        correspondences: List<LandmarkPair>,
        transform: TransformMatrix
    ): Float {
        
        if (correspondences.isEmpty()) return 0f
        
        var totalError = 0f
        var totalWeight = 0f
        
        for (pair in correspondences) {
            // Transform model point and compare with face point
            val transformedModel = transformPoint(pair.modelPoint, transform)
            val error = (transformedModel - pair.facePoint).length()
            
            totalError += error * pair.weight
            totalWeight += pair.weight
        }
        
        val averageError = if (totalWeight > 0) totalError / totalWeight else 1f
        
        // Convert error to confidence (0-1 scale)
        val confidence = exp(-averageError * 10f).coerceIn(0f, 1f)
        
        return confidence
    }
    
    /**
     * Transform a 3D point using the calculated transformation matrix
     */
    private fun transformPoint(point: Vector3D, transform: TransformMatrix): Vector3D {
        // Apply scale
        val scaled = point * transform.scale
        
        // Apply rotation (simplified - in practice would use proper rotation matrices)
        val rotated = scaled // TODO: Implement proper 3D rotation
        
        // Apply translation
        return rotated + transform.translation
    }
}