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

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/**
 * Manages storage and retrieval of 3D models
 */
class ModelStorageManager(private val context: Context) {
    
    companion object {
        private const val TAG = "ModelStorageManager"
        private const val PREFS_NAME = "model_storage_prefs"
        private const val MODELS_LIST_KEY = "stored_models"
        private const val MODELS_DIR_NAME = "stored_models"
        private const val PREVIEWS_DIR_NAME = "model_previews"
    }
    
    private val sharedPrefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()
    private val modelsDir: File
    private val previewsDir: File
    
    init {
        modelsDir = File(context.filesDir, MODELS_DIR_NAME)
        previewsDir = File(context.filesDir, PREVIEWS_DIR_NAME)
        
        if (!modelsDir.exists()) modelsDir.mkdirs()
        if (!previewsDir.exists()) previewsDir.mkdirs()
    }
    
    /**
     * Store a 3D model from URI
     */
    suspend fun storeModel(
        uri: Uri,
        originalFileName: String,
        fileFormat: String,
        model3D: Model3D,
        customName: String = ""
    ): StoredModel? = withContext(Dispatchers.IO) {
        try {
            val fileSize = getFileSize(uri)
            val modelId = java.util.UUID.randomUUID().toString()
            val fileName = "${modelId}.${fileFormat.lowercase()}"
            val targetFile = File(modelsDir, fileName)
            
            // Copy file to internal storage
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                FileOutputStream(targetFile).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            
            // Generate preview image
            val previewPath = generatePreview(model3D, modelId)
            
            // Extract face detection information from model
            val hasFaceData = model3D.hasFaceData
            val landmarkCount = model3D.faceData?.landmarks?.size ?: 0
            
            val storedModel = StoredModel(
                id = modelId,
                name = customName,
                originalFileName = originalFileName,
                fileFormat = fileFormat.uppercase(),
                filePath = targetFile.absolutePath,
                previewImagePath = previewPath,
                fileSize = fileSize,
                vertexCount = model3D.vertices.size,
                faceCount = model3D.faces.size,
                hasFaceData = hasFaceData,
                landmarkCount = landmarkCount,
                faceDetectionAttempted = true
            )
            
            // Save to list
            val currentModels = getAllStoredModels().toMutableList()
            currentModels.add(storedModel)
            saveModelsList(currentModels)
            
            Log.d(TAG, "Model stored successfully: ${storedModel.getDisplayName()}")
            storedModel
            
        } catch (e: Exception) {
            Log.e(TAG, "Error storing model", e)
            null
        }
    }
    
    /**
     * Load a model from storage
     */
    suspend fun loadModel(storedModel: StoredModel, parser: Model3DParser): Model3D? = withContext(Dispatchers.IO) {
        try {
            val file = File(storedModel.filePath)
            if (!file.exists()) {
                Log.e(TAG, "Model file not found: ${storedModel.filePath}")
                return@withContext null
            }
            
            when (storedModel.fileFormat.lowercase()) {
                "obj" -> {
                    val content = file.readText()
                    parser.parseOBJ(content)
                }
                "glb" -> {
                    val bytes = file.readBytes()
                    parser.parseGLB(bytes)
                }
                else -> {
                    Log.w(TAG, "Unsupported format for loading: ${storedModel.fileFormat}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading model: ${storedModel.getDisplayName()}", e)
            null
        }
    }
    
    /**
     * Get all stored models
     */
    fun getAllStoredModels(): List<StoredModel> {
        return try {
            val json = sharedPrefs.getString(MODELS_LIST_KEY, "[]")
            val type = object : TypeToken<List<StoredModel>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Error loading stored models", e)
            emptyList()
        }
    }
    
    /**
     * Delete a stored model
     */
    suspend fun deleteModel(modelId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val models = getAllStoredModels().toMutableList()
            val modelToDelete = models.find { it.id == modelId }
            
            if (modelToDelete != null) {
                // Delete files
                File(modelToDelete.filePath).delete()
                modelToDelete.previewImagePath?.let { File(it).delete() }
                
                // Remove from list
                models.removeAll { it.id == modelId }
                saveModelsList(models)
                
                Log.d(TAG, "Model deleted: ${modelToDelete.getDisplayName()}")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting model", e)
            false
        }
    }
    
    /**
     * Rename a stored model
     */
    suspend fun renameModel(modelId: String, newName: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val models = getAllStoredModels().toMutableList()
            val index = models.indexOfFirst { it.id == modelId }
            
            if (index >= 0) {
                models[index] = models[index].copy(name = newName)
                saveModelsList(models)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error renaming model", e)
            false
        }
    }
    
    /**
     * Set active model
     */
    suspend fun setActiveModel(modelId: String) = withContext(Dispatchers.IO) {
        try {
            val models = getAllStoredModels().map { model ->
                model.copy(isActive = model.id == modelId)
            }
            saveModelsList(models)
        } catch (e: Exception) {
            Log.e(TAG, "Error setting active model", e)
        }
    }
    
    /**
     * Get currently active model
     */
    fun getActiveModel(): StoredModel? {
        return getAllStoredModels().find { it.isActive }
    }
    
    /**
     * Clear all stored models
     */
    suspend fun clearAllModels() = withContext(Dispatchers.IO) {
        try {
            // Delete all files
            modelsDir.listFiles()?.forEach { it.delete() }
            previewsDir.listFiles()?.forEach { it.delete() }
            
            // Clear preferences
            sharedPrefs.edit().remove(MODELS_LIST_KEY).apply()
            
            Log.d(TAG, "All models cleared")
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing models", e)
        }
    }
    
    /**
     * Get storage statistics
     */
    fun getStorageStats(): Map<String, Any> {
        val models = getAllStoredModels()
        val totalSize = models.sumOf { it.fileSize }
        val totalVertices = models.sumOf { it.vertexCount }
        val totalFaces = models.sumOf { it.faceCount }
        
        return mapOf(
            "modelCount" to models.size,
            "totalSize" to totalSize,
            "totalVertices" to totalVertices,
            "totalFaces" to totalFaces,
            "formats" to models.groupBy { it.fileFormat }.mapValues { it.value.size }
        )
    }
    
    private fun saveModelsList(models: List<StoredModel>) {
        val json = gson.toJson(models)
        sharedPrefs.edit().putString(MODELS_LIST_KEY, json).apply()
    }
    
    private fun getFileSize(uri: Uri): Long {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                if (cursor.moveToFirst() && sizeIndex >= 0) {
                    cursor.getLong(sizeIndex)
                } else {
                    0L
                }
            } ?: 0L
        } catch (e: Exception) {
            0L
        }
    }
    
    private fun generatePreview(model3D: Model3D, modelId: String): String? {
        return try {
            val previewSize = 200
            
            // Try to use the face analyzer for better preview
            val faceAnalyzer = Model3DFaceAnalyzer(context)
            val bitmap = faceAnalyzer.createModelPreview(model3D, previewSize, previewSize)
            
            // Save preview image
            val previewFile = File(previewsDir, "${modelId}_preview.png")
            FileOutputStream(previewFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
            }
            
            previewFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Error generating preview", e)
            null
        }
    }
    
    /**
     * Update a stored model with adjustment data
     */
    suspend fun updateModelAdjustments(modelId: String, adjustmentData: StoredAdjustmentData): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val models = getStoredModels().toMutableList()
                val modelIndex = models.indexOfFirst { it.id == modelId }
                
                if (modelIndex != -1) {
                    val updatedModel = models[modelIndex].copy(adjustmentData = adjustmentData)
                    models[modelIndex] = updatedModel
                    
                    // Save updated list
                    val modelsJson = gson.toJson(models)
                    sharedPrefs.edit().putString(MODELS_LIST_KEY, modelsJson).apply()
                    
                    Log.d(TAG, "✅ Updated model adjustments for: ${updatedModel.name}")
                    true
                } else {
                    Log.w(TAG, "❌ Model not found for adjustment update: $modelId")
                    false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error updating model adjustments", e)
                false
            }
        }
    }
    
    /**
     * Get stored adjustment data for a model
     */
    fun getModelAdjustments(modelId: String): StoredAdjustmentData? {
        val models = getStoredModels()
        return models.find { it.id == modelId }?.adjustmentData
    }
    
    /**
     * Check if a model has custom adjustments
     */
    fun hasCustomAdjustments(modelId: String): Boolean {
        return getModelAdjustments(modelId)?.hasCustomAdjustments() == true
    }
}