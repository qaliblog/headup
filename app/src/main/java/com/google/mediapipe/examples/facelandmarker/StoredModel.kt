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
    val isActive: Boolean = false
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
}