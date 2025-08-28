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

import android.util.Log
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.*

/**
 * Data class representing a landmark correspondence between model and real face
 */
data class LandmarkCorrespondence(
    val modelLandmarkIndex: Int,
    val realLandmarkIndex: Int,
    val modelVertex: Vertex3D,
    val realLandmark: Vertex3D,
    val weight: Float = 1f // Weight for this correspondence in transformation calculation
)

/**
 * Transformation data for aligning 3D model with real face
 */
data class FaceAlignmentTransform(
    val translation: Vertex3D,
    val rotation: Vertex3D, // Euler angles (yaw, pitch, roll)
    val scale: Vertex3D,    // Scale factors (x, y, z)
    val transformMatrix: FloatArray,
    val correspondences: List<LandmarkCorrespondence>,
    val alignmentScore: Float // Quality score of the alignment (0-1)
)

/**
 * Matches facial landmarks between 3D model face and real detected face
 */
class FaceLandmarkMatcher {
    
    companion object {
        private const val TAG = "FaceLandmarkMatcher"
        
        // Key landmark pairs for alignment (ModelLandmark to MediaPipe landmark index)
        private val PRIMARY_LANDMARKS = mapOf(
            1 to 1,     // Face center
            9 to 9,     // Face center top
            151 to 151, // Chin center
            33 to 33,   // Left eye outer corner
            362 to 362, // Right eye outer corner
            130 to 130, // Left eye inner corner
            359 to 359, // Right eye inner corner
            2 to 2,     // Nose tip
            164 to 164, // Lip center top
            18 to 18,   // Lip center bottom
            234 to 234, // Left cheek
            454 to 454  // Right cheek
        )
        
        // Secondary landmarks for refinement
        private val SECONDARY_LANDMARKS = mapOf(
            0 to 0,     // Nose bridge
            10 to 10,   // Forehead center
            152 to 152, // Chin tip
            172 to 172, // Lip corner left
            397 to 397, // Lip corner right
            93 to 93,   // Left eyebrow
            323 to 323  // Right eyebrow
        )
        
        // Weight factors for different landmark types
        private const val PRIMARY_WEIGHT = 1.0f
        private const val SECONDARY_WEIGHT = 0.5f
        private const val MIN_CORRESPONDENCES = 6
    }
    
    /**
     * Calculate alignment transform between model face and real face
     */
    fun calculateFaceAlignment(
        modelFaceData: Model3DFaceData,
        realFaceLandmarks: List<NormalizedLandmark>,
        imageWidth: Int,
        imageHeight: Int
    ): FaceAlignmentTransform? {
        
        try {
            Log.d(TAG, "Calculating face alignment between 3D model and real face")
            
            // Step 1: Establish landmark correspondences
            val correspondences = establishCorrespondences(
                modelFaceData, 
                realFaceLandmarks, 
                imageWidth, 
                imageHeight
            )
            
            if (correspondences.size < MIN_CORRESPONDENCES) {
                Log.w(TAG, "Insufficient landmark correspondences: ${correspondences.size} < $MIN_CORRESPONDENCES")
                return null
            }
            
            // Step 2: Calculate optimal transformation
            val transform = calculateOptimalTransform(correspondences)
            
            // Step 3: Calculate alignment quality score
            val alignmentScore = calculateAlignmentScore(correspondences, transform)
            
            Log.d(TAG, "Face alignment calculated: ${correspondences.size} correspondences, score: $alignmentScore")
            
            return FaceAlignmentTransform(
                translation = transform.translation,
                rotation = transform.rotation,
                scale = transform.scale,
                transformMatrix = transform.transformMatrix,
                correspondences = correspondences,
                alignmentScore = alignmentScore
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Error calculating face alignment", e)
            return null
        }
    }
    
    /**
     * Establish correspondences between model landmarks and real face landmarks
     */
    private fun establishCorrespondences(
        modelFaceData: Model3DFaceData,
        realFaceLandmarks: List<NormalizedLandmark>,
        imageWidth: Int,
        imageHeight: Int
    ): List<LandmarkCorrespondence> {
        
        val correspondences = mutableListOf<LandmarkCorrespondence>()
        
        // Primary landmark correspondences
        PRIMARY_LANDMARKS.forEach { (modelLandmarkIdx, realLandmarkIdx) ->
            val modelVertexIdx = modelFaceData.landmarkToVertexMapping[modelLandmarkIdx]
            
            if (modelVertexIdx != null && 
                modelVertexIdx < modelFaceData.originalModel.vertices.size &&
                realLandmarkIdx < realFaceLandmarks.size) {
                
                val modelVertex = modelFaceData.originalModel.vertices[modelVertexIdx]
                val realLandmark = realFaceLandmarks[realLandmarkIdx]
                
                correspondences.add(LandmarkCorrespondence(
                    modelLandmarkIndex = modelLandmarkIdx,
                    realLandmarkIndex = realLandmarkIdx,
                    modelVertex = modelVertex,
                    realLandmark = Vertex3D(
                        realLandmark.x() * imageWidth,
                        realLandmark.y() * imageHeight,
                        realLandmark.z() * imageWidth // Approximate depth scaling
                    ),
                    weight = PRIMARY_WEIGHT
                ))
            }
        }
        
        // Secondary landmark correspondences
        SECONDARY_LANDMARKS.forEach { (modelLandmarkIdx, realLandmarkIdx) ->
            val modelVertexIdx = modelFaceData.landmarkToVertexMapping[modelLandmarkIdx]
            
            if (modelVertexIdx != null && 
                modelVertexIdx < modelFaceData.originalModel.vertices.size &&
                realLandmarkIdx < realFaceLandmarks.size) {
                
                val modelVertex = modelFaceData.originalModel.vertices[modelVertexIdx]
                val realLandmark = realFaceLandmarks[realLandmarkIdx]
                
                correspondences.add(LandmarkCorrespondence(
                    modelLandmarkIndex = modelLandmarkIdx,
                    realLandmarkIndex = realLandmarkIdx,
                    modelVertex = modelVertex,
                    realLandmark = Vertex3D(
                        realLandmark.x() * imageWidth,
                        realLandmark.y() * imageHeight,
                        realLandmark.z() * imageWidth
                    ),
                    weight = SECONDARY_WEIGHT
                ))
            }
        }
        
        Log.d(TAG, "Established ${correspondences.size} landmark correspondences")
        return correspondences
    }
    
    /**
     * Calculate optimal transformation using weighted least squares
     */
    private fun calculateOptimalTransform(correspondences: List<LandmarkCorrespondence>): FaceAlignmentTransform {
        
        // Calculate centroids
        val modelCentroid = calculateWeightedCentroid(correspondences) { it.modelVertex }
        val realCentroid = calculateWeightedCentroid(correspondences) { it.realLandmark }
        
        // Calculate scale factors
        val scaleFactors = calculateScaleFactors(correspondences, modelCentroid, realCentroid)
        
        // Calculate rotation (simplified - using key landmark vectors)
        val rotation = calculateRotation(correspondences, modelCentroid, realCentroid)
        
        // Calculate translation
        val translation = Vertex3D(
            realCentroid.x - modelCentroid.x * scaleFactors.x,
            realCentroid.y - modelCentroid.y * scaleFactors.y,
            realCentroid.z - modelCentroid.z * scaleFactors.z
        )
        
        // Build transformation matrix
        val transformMatrix = buildTransformMatrix(translation, rotation, scaleFactors)
        
        return FaceAlignmentTransform(
            translation = translation,
            rotation = rotation,
            scale = scaleFactors,
            transformMatrix = transformMatrix,
            correspondences = correspondences,
            alignmentScore = 0f // Will be calculated separately
        )
    }
    
    private fun calculateWeightedCentroid(
        correspondences: List<LandmarkCorrespondence>,
        vertexSelector: (LandmarkCorrespondence) -> Vertex3D
    ): Vertex3D {
        var sumX = 0f
        var sumY = 0f
        var sumZ = 0f
        var totalWeight = 0f
        
        correspondences.forEach { correspondence ->
            val vertex = vertexSelector(correspondence)
            val weight = correspondence.weight
            
            sumX += vertex.x * weight
            sumY += vertex.y * weight
            sumZ += vertex.z * weight
            totalWeight += weight
        }
        
        return if (totalWeight > 0) {
            Vertex3D(sumX / totalWeight, sumY / totalWeight, sumZ / totalWeight)
        } else {
            Vertex3D(0f, 0f, 0f)
        }
    }
    
    private fun calculateScaleFactors(
        correspondences: List<LandmarkCorrespondence>,
        modelCentroid: Vertex3D,
        realCentroid: Vertex3D
    ): Vertex3D {
        var modelDistanceSum = 0f
        var realDistanceSum = 0f
        var count = 0
        
        correspondences.forEach { correspondence ->
            val modelDistance = distance(correspondence.modelVertex, modelCentroid)
            val realDistance = distance(correspondence.realLandmark, realCentroid)
            
            if (modelDistance > 0.001f) { // Avoid division by very small numbers
                modelDistanceSum += modelDistance * correspondence.weight
                realDistanceSum += realDistance * correspondence.weight
                count++
            }
        }
        
        val averageScale = if (count > 0 && modelDistanceSum > 0) {
            realDistanceSum / modelDistanceSum
        } else {
            1f
        }
        
        // For now, use uniform scaling (can be enhanced to calculate per-axis scaling)
        return Vertex3D(averageScale, averageScale, averageScale)
    }
    
    private fun calculateRotation(
        correspondences: List<LandmarkCorrespondence>,
        modelCentroid: Vertex3D,
        realCentroid: Vertex3D
    ): Vertex3D {
        // Simplified rotation calculation using key vectors
        // Find eye-to-eye vector for yaw calculation
        var modelEyeVector: Vertex3D? = null
        var realEyeVector: Vertex3D? = null
        
        val leftEyeModel = correspondences.find { it.realLandmarkIndex == 33 }?.modelVertex
        val rightEyeModel = correspondences.find { it.realLandmarkIndex == 362 }?.modelVertex
        val leftEyeReal = correspondences.find { it.realLandmarkIndex == 33 }?.realLandmark
        val rightEyeReal = correspondences.find { it.realLandmarkIndex == 362 }?.realLandmark
        
        if (leftEyeModel != null && rightEyeModel != null && leftEyeReal != null && rightEyeReal != null) {
            modelEyeVector = Vertex3D(
                rightEyeModel.x - leftEyeModel.x,
                rightEyeModel.y - leftEyeModel.y,
                rightEyeModel.z - leftEyeModel.z
            )
            realEyeVector = Vertex3D(
                rightEyeReal.x - leftEyeReal.x,
                rightEyeReal.y - leftEyeReal.y,
                rightEyeReal.z - leftEyeReal.z
            )
        }
        
        // Calculate yaw from eye vector difference
        val yaw = if (modelEyeVector != null && realEyeVector != null) {
            val modelAngle = atan2(modelEyeVector.y, modelEyeVector.x)
            val realAngle = atan2(realEyeVector.y, realEyeVector.x)
            realAngle - modelAngle
        } else {
            0f
        }
        
        // For now, simplified rotation (can be enhanced with full 3D rotation calculation)
        return Vertex3D(0f, yaw, 0f) // (pitch, yaw, roll)
    }
    
    private fun buildTransformMatrix(
        translation: Vertex3D,
        rotation: Vertex3D,
        scale: Vertex3D
    ): FloatArray {
        val matrix = FloatArray(16)
        
        // Create transformation matrix (simplified - scale + rotation + translation)
        val cosYaw = cos(rotation.y)
        val sinYaw = sin(rotation.y)
        val cosPitch = cos(rotation.x)
        val sinPitch = sin(rotation.x)
        val cosRoll = cos(rotation.z)
        val sinRoll = sin(rotation.z)
        
        // Combined rotation and scale matrix
        matrix[0] = (cosYaw * cosRoll * scale.x).toFloat()
        matrix[1] = (-cosYaw * sinRoll * scale.x).toFloat()
        matrix[2] = (sinYaw * scale.x).toFloat()
        matrix[3] = 0f
        
        matrix[4] = (sinRoll * scale.y).toFloat()
        matrix[5] = (cosRoll * scale.y).toFloat()
        matrix[6] = 0f
        matrix[7] = 0f
        
        matrix[8] = (-sinYaw * cosRoll * scale.z).toFloat()
        matrix[9] = (sinYaw * sinRoll * scale.z).toFloat()
        matrix[10] = (cosYaw * scale.z).toFloat()
        matrix[11] = 0f
        
        // Translation
        matrix[12] = translation.x
        matrix[13] = translation.y
        matrix[14] = translation.z
        matrix[15] = 1f
        
        return matrix
    }
    
    private fun calculateAlignmentScore(
        correspondences: List<LandmarkCorrespondence>,
        transform: FaceAlignmentTransform
    ): Float {
        var totalError = 0f
        var totalWeight = 0f
        
        correspondences.forEach { correspondence ->
            // Transform model vertex
            val transformed = transformVertex(correspondence.modelVertex, transform.transformMatrix)
            
            // Calculate error distance
            val error = distance(transformed, correspondence.realLandmark)
            
            totalError += error * correspondence.weight
            totalWeight += correspondence.weight
        }
        
        val averageError = if (totalWeight > 0) totalError / totalWeight else Float.MAX_VALUE
        
        // Convert error to score (0-1, where 1 is perfect alignment)
        val maxAcceptableError = 50f // pixels
        return maxOf(0f, 1f - (averageError / maxAcceptableError))
    }
    
    private fun transformVertex(vertex: Vertex3D, matrix: FloatArray): Vertex3D {
        val x = vertex.x * matrix[0] + vertex.y * matrix[4] + vertex.z * matrix[8] + matrix[12]
        val y = vertex.x * matrix[1] + vertex.y * matrix[5] + vertex.z * matrix[9] + matrix[13]
        val z = vertex.x * matrix[2] + vertex.y * matrix[6] + vertex.z * matrix[10] + matrix[14]
        
        return Vertex3D(x, y, z)
    }
    
    private fun distance(v1: Vertex3D, v2: Vertex3D): Float {
        val dx = v1.x - v2.x
        val dy = v1.y - v2.y
        val dz = v1.z - v2.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }
}