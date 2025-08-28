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

import java.util.*

/**
 * Data class for storing manual adjustment parameters
 */
data class StoredAdjustmentData(
    val scale: Float = 1.0f,
    val scaleX: Float = 1.0f,
    val scaleY: Float = 1.0f,
    val offsetX: Float = 0.0f,
    val offsetY: Float = 0.0f,
    val offsetZ: Float = 0.0f,
    val rotationX: Float = 0.0f,
    val rotationY: Float = 0.0f,
    val rotationZ: Float = 0.0f,
    val confidenceThreshold: Float = 0.5f,
    val savedDate: Long = System.currentTimeMillis()
) {
    fun getFormattedSaveDate(): String {
        val date = Date(savedDate)
        return try {
            java.text.SimpleDateFormat("MMM dd, yyyy HH:mm", java.util.Locale.getDefault()).format(date)
        } catch (e: Exception) {
            "Unknown date"
        }
    }
    
    fun hasCustomAdjustments(): Boolean {
        return scale != 1.0f || scaleX != 1.0f || scaleY != 1.0f ||
               offsetX != 0.0f || offsetY != 0.0f || offsetZ != 0.0f ||
               rotationX != 0.0f || rotationY != 0.0f || rotationZ != 0.0f
    }
}

/**
 * Data class representing a stored 3D model
 */
data class StoredModel(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val originalFileName: String,
    val fileFormat: String,
    val filePath: String,
    val previewImagePath: String? = null,
    val dateAdded: Long = System.currentTimeMillis(),
    val fileSize: Long,
    val vertexCount: Int,
    val faceCount: Int,
    val isActive: Boolean = false,
    val hasFaceData: Boolean = false,
    val landmarkCount: Int = 0,
    val faceDetectionAttempted: Boolean = false,
    val adjustmentData: StoredAdjustmentData? = null
) {
    fun getDisplayName(): String = if (name.isBlank()) originalFileName else name
    
    fun getFormattedSize(): String {
        return when {
            fileSize < 1024 -> "$fileSize B"
            fileSize < 1024 * 1024 -> "${fileSize / 1024} KB"
            else -> "${fileSize / (1024 * 1024)} MB"
        }
    }
    
    fun getFormattedDate(): String {
        val date = Date(dateAdded)
        return try {
            java.text.SimpleDateFormat("MMM dd, yyyy", java.util.Locale.getDefault()).format(date)
        } catch (e: Exception) {
            "Unknown date"
        }
    }
    
    fun getFaceDetectionStatus(): String {
        return when {
            !faceDetectionAttempted -> "Face detection pending..."
            hasFaceData -> "✓ Face detected ($landmarkCount landmarks)"
            else -> "⚠ No face detected"
        }
    }
    
    fun getFaceDetectionStatusColor(): Int {
        return when {
            !faceDetectionAttempted -> android.R.color.darker_gray
            hasFaceData -> android.R.color.holo_green_dark
            else -> android.R.color.holo_orange_dark
        }
    }
    
    fun getAdjustmentStatus(): String {
        return if (adjustmentData?.hasCustomAdjustments() == true) {
            "✓ Custom adjustments saved (${adjustmentData.getFormattedSaveDate()})"
        } else {
            "Default adjustments"
        }
    }
    
    fun hasCustomAdjustments(): Boolean = adjustmentData?.hasCustomAdjustments() == true
}