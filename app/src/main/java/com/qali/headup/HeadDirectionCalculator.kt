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
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import kotlin.math.*

/**
 * Data class representing head pose and direction
 */
data class HeadPose(
    val center: Vertex3D,
    val nosePosition: Vertex3D,
    val direction: Vertex3D,
    val yaw: Float,    // Rotation around Y-axis (left/right)
    val pitch: Float,  // Rotation around X-axis (up/down)
    val roll: Float    // Rotation around Z-axis (tilt)
) {
    /**
     * Get transformation matrix for 3D object alignment
     */
    fun getTransformationMatrix(): FloatArray {
        // Create a 4x4 transformation matrix
        val matrix = FloatArray(16)
        
        // Calculate rotation matrix from yaw, pitch, roll
        val cosYaw = cos(yaw)
        val sinYaw = sin(yaw)
        val cosPitch = cos(pitch)
        val sinPitch = sin(pitch)
        val cosRoll = cos(roll)
        val sinRoll = sin(roll)
        
        // Combined rotation matrix (ZYX order)
        matrix[0] = (cosYaw * cosRoll + sinYaw * sinPitch * sinRoll).toFloat()
        matrix[1] = (cosRoll * sinYaw * sinPitch - cosYaw * sinRoll).toFloat()
        matrix[2] = (cosPitch * sinYaw).toFloat()
        matrix[3] = 0f
        
        matrix[4] = (cosPitch * sinRoll).toFloat()
        matrix[5] = (cosPitch * cosRoll).toFloat()
        matrix[6] = (-sinPitch).toFloat()
        matrix[7] = 0f
        
        matrix[8] = (cosYaw * sinPitch * sinRoll - cosRoll * sinYaw).toFloat()
        matrix[9] = (cosYaw * cosRoll * sinPitch + sinYaw * sinRoll).toFloat()
        matrix[10] = (cosYaw * cosPitch).toFloat()
        matrix[11] = 0f
        
        // Translation
        matrix[12] = center.x
        matrix[13] = center.y
        matrix[14] = center.z
        matrix[15] = 1f
        
        return matrix
    }
}

/**
 * Calculator for head direction based on face landmarks
 */
class HeadDirectionCalculator {
    
    companion object {
        private const val TAG = "HeadDirectionCalculator"
        
        // Key face landmark indices (MediaPipe Face Mesh)
        private const val NOSE_TIP = 1
        private const val NOSE_BRIDGE = 6
        private const val LEFT_EYE_INNER = 133
        private const val RIGHT_EYE_INNER = 362
        private const val LEFT_EYE_OUTER = 33
        private const val RIGHT_EYE_OUTER = 263
        private const val LEFT_MOUTH = 61
        private const val RIGHT_MOUTH = 291
        private const val FOREHEAD_CENTER = 9
        private const val CHIN_CENTER = 175
        
        // Key points for head center calculation
        private val HEAD_CENTER_POINTS = intArrayOf(
            NOSE_BRIDGE, LEFT_EYE_INNER, RIGHT_EYE_INNER,
            LEFT_MOUTH, RIGHT_MOUTH, FOREHEAD_CENTER
        )
    }
    
    /**
     * Calculate head pose from face landmarks
     */
    fun calculateHeadPose(faceLandmarkerResult: FaceLandmarkerResult): HeadPose? {
        if (faceLandmarkerResult.faceLandmarks().isEmpty()) {
            Log.w(TAG, "No face landmarks found")
            return null
        }
        
        val landmarks = faceLandmarkerResult.faceLandmarks()[0]
        
        return try {
            // Calculate head center as weighted average of key points
            val headCenter = calculateHeadCenter(landmarks)
            
            // Get nose position
            val nosePosition = if (landmarks.size > NOSE_TIP) {
                val noseLandmark = landmarks[NOSE_TIP]
                Vertex3D(noseLandmark.x(), noseLandmark.y(), noseLandmark.z())
            } else {
                headCenter
            }
            
            // Calculate direction vector from head center to nose
            val direction = (nosePosition - headCenter).normalize()
            
            // Calculate head rotation angles
            val (yaw, pitch, roll) = calculateRotationAngles(landmarks)
            
            Log.d(TAG, "Head pose calculated - Center: $headCenter, Direction: $direction")
            Log.d(TAG, "Rotation - Yaw: ${Math.toDegrees(yaw.toDouble())}°, Pitch: ${Math.toDegrees(pitch.toDouble())}°, Roll: ${Math.toDegrees(roll.toDouble())}°")
            
            HeadPose(headCenter, nosePosition, direction, yaw, pitch, roll)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error calculating head pose", e)
            null
        }
    }
    
    private fun calculateHeadCenter(landmarks: List<NormalizedLandmark>): Vertex3D {
        var sumX = 0f
        var sumY = 0f
        var sumZ = 0f
        var count = 0
        
        HEAD_CENTER_POINTS.forEach { index ->
            if (index < landmarks.size) {
                val landmark = landmarks[index]
                sumX += landmark.x()
                sumY += landmark.y()
                sumZ += landmark.z()
                count++
            }
        }
        
        return if (count > 0) {
            Vertex3D(sumX / count, sumY / count, sumZ / count)
        } else {
            // Fallback to first landmark if key points not available
            val firstLandmark = landmarks[0]
            Vertex3D(firstLandmark.x(), firstLandmark.y(), firstLandmark.z())
        }
    }
    
    private fun calculateRotationAngles(landmarks: List<NormalizedLandmark>): Triple<Float, Float, Float> {
        return try {
            // Get key landmarks for angle calculation
            val leftEye = if (LEFT_EYE_INNER < landmarks.size) landmarks[LEFT_EYE_INNER] else null
            val rightEye = if (RIGHT_EYE_INNER < landmarks.size) landmarks[RIGHT_EYE_INNER] else null
            val nose = if (NOSE_TIP < landmarks.size) landmarks[NOSE_TIP] else null
            val chin = if (CHIN_CENTER < landmarks.size) landmarks[CHIN_CENTER] else null
            val forehead = if (FOREHEAD_CENTER < landmarks.size) landmarks[FOREHEAD_CENTER] else null
            
            var yaw = 0f
            var pitch = 0f
            var roll = 0f
            
            // Calculate yaw (left/right rotation)
            if (leftEye != null && rightEye != null && nose != null) {
                val eyeCenter = Vertex3D(
                    (leftEye.x() + rightEye.x()) / 2,
                    (leftEye.y() + rightEye.y()) / 2,
                    (leftEye.z() + rightEye.z()) / 2
                )
                val nosePos = Vertex3D(nose.x(), nose.y(), nose.z())
                val noseToEye = eyeCenter - nosePos
                yaw = atan2(noseToEye.x, noseToEye.z)
            }
            
            // Calculate pitch (up/down rotation)
            if (forehead != null && chin != null && nose != null) {
                val foreheadPos = Vertex3D(forehead.x(), forehead.y(), forehead.z())
                val chinPos = Vertex3D(chin.x(), chin.y(), chin.z())
                val nosePos = Vertex3D(nose.x(), nose.y(), nose.z())
                
                val faceVertical = foreheadPos - chinPos
                val noseToChin = chinPos - nosePos
                
                pitch = atan2(noseToChin.y, sqrt(noseToChin.x * noseToChin.x + noseToChin.z * noseToChin.z))
            }
            
            // Calculate roll (tilt rotation)
            if (leftEye != null && rightEye != null) {
                val eyeVector = Vertex3D(
                    rightEye.x() - leftEye.x(),
                    rightEye.y() - leftEye.y(),
                    rightEye.z() - leftEye.z()
                )
                roll = atan2(eyeVector.y, eyeVector.x)
            }
            
            Triple(yaw, pitch, roll)
            
        } catch (e: Exception) {
            Log.w(TAG, "Error calculating rotation angles", e)
            Triple(0f, 0f, 0f)
        }
    }
    
    /**
     * Calculate 3D object position and scale relative to head
     */
    fun calculate3DObjectTransform(headPose: HeadPose, objectSize: Float = 0.3f): FloatArray {
        // Position the object slightly above and in front of the head
        val offsetPosition = headPose.center + headPose.direction * 0.1f + Vertex3D(0f, 0.1f, 0f)
        
        // Create transformation matrix
        val matrix = headPose.getTransformationMatrix()
        
        // Apply position offset
        matrix[12] = offsetPosition.x
        matrix[13] = offsetPosition.y
        matrix[14] = offsetPosition.z
        
        // Apply scale
        for (i in 0..2) {
            matrix[i * 4] *= objectSize
            matrix[i * 4 + 1] *= objectSize
            matrix[i * 4 + 2] *= objectSize
        }
        
        return matrix
    }
}