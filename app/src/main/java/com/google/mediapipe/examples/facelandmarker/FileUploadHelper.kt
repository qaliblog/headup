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
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import java.io.*
import java.util.*

/**
 * Helper class for handling 3D model file uploads from storage
 */
class FileUploadHelper(
    private val context: Context,
    private val fragment: Fragment,
    private val onFileSelected: (Uri, String) -> Unit
) {
    
    companion object {
        private const val TAG = "FileUploadHelper"
        
        // Supported 3D model file formats
        val SUPPORTED_3D_FORMATS = setOf(
            "obj", "fbx", "ply", "stl", "3ds", "dae", "blend", "gltf", "glb"
        )
        
        // MIME types for 3D models
        val SUPPORTED_MIME_TYPES = arrayOf(
            "application/octet-stream",
            "model/*",
            "text/plain",
            "*/*"
        )
    }
    
    private lateinit var filePickerLauncher: ActivityResultLauncher<Intent>
    
    init {
        setupFilePickerLauncher()
    }
    
    private fun setupFilePickerLauncher() {
        filePickerLauncher = fragment.registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == android.app.Activity.RESULT_OK) {
                result.data?.data?.let { uri ->
                    handleSelectedFile(uri)
                }
            }
        }
    }
    
    /**
     * Launch file picker for 3D model selection
     */
    fun openFilePicker() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, SUPPORTED_MIME_TYPES)
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        
        val chooserIntent = Intent.createChooser(intent, "Select 3D Model File")
        filePickerLauncher.launch(chooserIntent)
    }
    
    private fun handleSelectedFile(uri: Uri) {
        try {
            val fileName = getFileName(uri)
            val fileExtension = fileName.substringAfterLast('.', "").lowercase()
            val fileSize = getFileSize(uri)
            
            Log.d(TAG, "Selected file: $fileName (${fileSize} bytes, format: $fileExtension)")
            
            // Validate file format
            if (fileExtension !in SUPPORTED_3D_FORMATS) {
                Log.w(TAG, "Unsupported file format: $fileExtension. Supported: ${SUPPORTED_3D_FORMATS.joinToString()}")
                return
            }
            
            // Validate file size (max 50MB)
            if (fileSize > 50 * 1024 * 1024) {
                Log.w(TAG, "File too large: ${fileSize} bytes (max 50MB)")
                return
            }
            
            // Validate file is accessible
            if (!isFileAccessible(uri)) {
                Log.w(TAG, "File not accessible: $uri")
                return
            }
            
            Log.d(TAG, "✅ File validation passed: $fileName")
            onFileSelected(uri, fileName)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error handling selected file", e)
        }
    }
    
    /**
     * Check if file is accessible and readable
     */
    private fun isFileAccessible(uri: Uri): Boolean {
        return try {
            context.contentResolver.openInputStream(uri)?.use { 
                it.read() != -1 // Try to read at least one byte
            } ?: false
        } catch (e: Exception) {
            Log.e(TAG, "File accessibility check failed", e)
            false
        }
    }
    
    private fun getFileName(uri: Uri): String {
        var fileName = "unknown"
        
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameIndex >= 0) {
                fileName = cursor.getString(nameIndex) ?: "unknown"
            }
        }
        
        return fileName
    }
    
    /**
     * Copy file from URI to internal storage for processing
     */
    fun copyFileToInternalStorage(uri: Uri, fileName: String): File? {
        return try {
            val internalDir = File(context.filesDir, "uploaded_models")
            if (!internalDir.exists()) {
                internalDir.mkdirs()
            }
            
            val targetFile = File(internalDir, fileName)
            
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                FileOutputStream(targetFile).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            
            Log.d(TAG, "File copied to: ${targetFile.absolutePath}")
            targetFile
        } catch (e: Exception) {
            Log.e(TAG, "Error copying file to internal storage", e)
            null
        }
    }
    
    /**
     * Read file content as string (useful for text-based formats like OBJ)
     * Optimized with larger buffer for better performance
     */
    fun readFileContent(uri: Uri): String? {
        return try {
            val fileSize = getFileSize(uri)
            Log.d(TAG, "Reading file content, size: ${fileSize} bytes")
            
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                // Use buffered reader for better performance
                val content = inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                Log.d(TAG, "Successfully read ${content.length} characters")
                content
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading file content", e)
            null
        }
    }
    
    /**
     * Read file content as byte array (useful for binary formats like GLB)
     * Optimized with pre-allocated buffer based on file size
     */
    fun readFileBytes(uri: Uri): ByteArray? {
        return try {
            val fileSize = getFileSize(uri)
            Log.d(TAG, "Reading file bytes, size: ${fileSize} bytes")
            
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                if (fileSize > 0) {
                    // Pre-allocate byte array with known size for better performance
                    val buffer = ByteArray(fileSize.toInt())
                    var totalRead = 0
                    
                    while (totalRead < fileSize) {
                        val bytesRead = inputStream.read(buffer, totalRead, (fileSize - totalRead).toInt())
                        if (bytesRead == -1) break
                        totalRead += bytesRead
                    }
                    
                    Log.d(TAG, "Successfully read ${totalRead} bytes")
                    if (totalRead == fileSize.toInt()) buffer else buffer.copyOf(totalRead)
                } else {
                    // Fallback for unknown file size
                    val result = inputStream.readBytes()
                    Log.d(TAG, "Successfully read ${result.size} bytes (unknown size)")
                    result
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading file bytes", e)
            null
        }
    }
    
    /**
     * Get file size in bytes
     */
    fun getFileSize(uri: Uri): Long {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                if (cursor.moveToFirst() && sizeIndex >= 0) {
                    cursor.getLong(sizeIndex)
                } else {
                    -1L
                }
            } ?: -1L
        } catch (e: Exception) {
            Log.e(TAG, "Error getting file size", e)
            -1L
        }
    }
    
    /**
     * Check if file format is supported
     */
    fun isSupportedFormat(fileName: String): Boolean {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return extension in SUPPORTED_3D_FORMATS
    }
}