package com.google.mediapipe.examples.facelandmarker
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

import androidx.lifecycle.ViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.LiveData
import kotlin.math.cos
import kotlin.math.sin

/**
 *  This ViewModel is used to store face landmarker helper settings and 3D model state
 */
class MainViewModel : ViewModel() {

    private var _delegate: Int = FaceLandmarkerHelper.DELEGATE_CPU
    private var _minFaceDetectionConfidence: Float =
        FaceLandmarkerHelper.DEFAULT_FACE_DETECTION_CONFIDENCE
    private var _minFaceTrackingConfidence: Float = FaceLandmarkerHelper
        .DEFAULT_FACE_TRACKING_CONFIDENCE
    private var _minFacePresenceConfidence: Float = FaceLandmarkerHelper
        .DEFAULT_FACE_PRESENCE_CONFIDENCE
    private var _maxFaces: Int = FaceLandmarkerHelper.DEFAULT_NUM_FACES
    
    // 3D Model state
    private val _current3DModel = MutableLiveData<Model3D?>()
    val current3DModel: LiveData<Model3D?> = _current3DModel
    
    private var _currentModelId: String? = null
    val currentModelId: String? get() = _currentModelId
    
    private var _is3DModelVisible: Boolean = true
    
    // Manual adjustment state
    private val _manualAdjustments = MutableLiveData<ManualAdjustmentData>()
    val manualAdjustments: LiveData<ManualAdjustmentData> = _manualAdjustments
    
    // Landmark detection settings
    private val _landmarkDetectionSettings = MutableLiveData<LandmarkDetectionSettings>()
    val landmarkDetectionSettings: LiveData<LandmarkDetectionSettings> = _landmarkDetectionSettings
    
    init {
        // Initialize with default values - use postValue to avoid threading issues
        _manualAdjustments.postValue(ManualAdjustmentData())
        _landmarkDetectionSettings.postValue(LandmarkDetectionSettings())
    }

    val currentDelegate: Int get() = _delegate
    val currentMinFaceDetectionConfidence: Float
        get() =
            _minFaceDetectionConfidence
    val currentMinFaceTrackingConfidence: Float
        get() =
            _minFaceTrackingConfidence
    val currentMinFacePresenceConfidence: Float
        get() =
            _minFacePresenceConfidence
    val currentMaxFaces: Int get() = _maxFaces

    fun setDelegate(delegate: Int) {
        _delegate = delegate
    }

    fun setMinFaceDetectionConfidence(confidence: Float) {
        _minFaceDetectionConfidence = confidence
    }
    fun setMinFaceTrackingConfidence(confidence: Float) {
        _minFaceTrackingConfidence = confidence
    }
    fun setMinFacePresenceConfidence(confidence: Float) {
        _minFacePresenceConfidence = confidence
    }

    fun setMaxFaces(maxResults: Int) {
        _maxFaces = maxResults
    }
    
    // 3D Model methods
    fun set3DModel(model: Model3D, modelId: String? = null) {
        _current3DModel.postValue(model)
        _currentModelId = modelId
        _is3DModelVisible = true
    }
    
    fun get3DModel(): Model3D? = _current3DModel.value
    
    fun has3DModel(): Boolean = _current3DModel.value != null
    
    fun clear3DModel() {
        _current3DModel.postValue(null)
        _currentModelId = null
        _is3DModelVisible = false
    }
    
    fun toggle3DModelVisibility() {
        _is3DModelVisible = !_is3DModelVisible
    }
    
    fun set3DModelVisibility(visible: Boolean) {
        _is3DModelVisible = visible
    }
    
    fun is3DModelVisible(): Boolean = _is3DModelVisible && _current3DModel.value != null
    
    // Manual adjustment methods
    fun setManualAdjustments(
        scale: Float = 1.0f,
        scaleX: Float = 1.0f,
        scaleY: Float = 1.0f,
        offsetX: Float = 0.0f,
        offsetY: Float = 0.0f,
        offsetZ: Float = 0.0f,
        rotationX: Float = 0.0f,
        rotationY: Float = 0.0f,
        rotationZ: Float = 0.0f
    ) {
        _manualAdjustments.postValue(ManualAdjustmentData(
            scale, scaleX, scaleY, offsetX, offsetY, offsetZ, rotationX, rotationY, rotationZ
        ))
    }
    
    fun setLandmarkDetectionSettings(enabled: Boolean, confidenceThreshold: Float) {
        _landmarkDetectionSettings.postValue(LandmarkDetectionSettings(enabled, confidenceThreshold))
    }
    
    fun getCurrentModelIdForSaving(): String? {
        return if (_current3DModel.value != null && _currentModelId != null) {
            _currentModelId
        } else {
            null
        }
    }
    
    suspend fun triggerLandmarkDetection(confidenceThreshold: Float): Boolean {
        return try {
            val currentModel = _current3DModel.value
            val adjustments = _manualAdjustments.value
            
            if (currentModel != null) {
                // This method should trigger landmark detection on the adjusted model
                // The actual detection should be handled by a component that can:
                // 1. Render the model with current adjustments to a bitmap
                // 2. Run MediaPipe face detection on that bitmap 
                // 3. Map the detected landmarks back to model space
                // 4. Update the model's face data with new landmarks
                
                // For now, we'll trigger a flag that indicates landmark detection should happen
                // This will be picked up by components that have access to the Model3DFaceAnalyzer
                _landmarkDetectionRequested.postValue(true)
                
                true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }
    
    // Flag to indicate landmark detection has been requested
    private val _landmarkDetectionRequested = MutableLiveData<Boolean>()
    val landmarkDetectionRequested: LiveData<Boolean> = _landmarkDetectionRequested
    
    fun clearLandmarkDetectionRequest() {
        _landmarkDetectionRequested.postValue(false)
    }
    
    /**
     * Create a copy of the model with adjustments applied and re-detect landmarks
     */
    private fun createAdjustedModel(originalModel: Model3D, adjustments: ManualAdjustmentData): Model3D {
        // Apply transformations to vertices
        val adjustedVertices = originalModel.vertices.map { vertex ->
            // Apply scale, rotation, and translation
            val scaledX = vertex.x * adjustments.scaleX * adjustments.scale
            val scaledY = vertex.y * adjustments.scaleY * adjustments.scale
            val scaledZ = vertex.z * adjustments.scale
            
            // Apply rotation (simplified 2D rotation for now)
            val radians = Math.toRadians(adjustments.rotationZ.toDouble())
            val cos = kotlin.math.cos(radians).toFloat()
            val sin = kotlin.math.sin(radians).toFloat()
            
            val rotatedX = scaledX * cos - scaledY * sin
            val rotatedY = scaledX * sin + scaledY * cos
            
            // Apply translation
            Vertex3D(
                rotatedX + adjustments.offsetX,
                rotatedY + adjustments.offsetY,
                scaledZ + adjustments.offsetZ
            )
        }
        
        // Create new model with adjusted vertices
        // Note: This is a simplified implementation - a full implementation would
        // re-run face detection on the adjusted model geometry
        return Model3D(
            vertices = adjustedVertices,
            faces = originalModel.faces,
            centroid = originalModel.centroid,
            boundingBox = originalModel.boundingBox,
            faceData = originalModel.faceData // Keep original face data for now
        )
    }
    
    fun applyManualAdjustmentsToModel(
        scale: Float, scaleX: Float, scaleY: Float,
        offsetX: Float, offsetY: Float, offsetZ: Float,
        rotationX: Float, rotationY: Float, rotationZ: Float
    ) {
        // Update the manual adjustments for preview and camera rendering
        // Note: This doesn't modify the actual model geometry, just the adjustments applied during rendering
        setManualAdjustments(scale, scaleX, scaleY, offsetX, offsetY, offsetZ, rotationX, rotationY, rotationZ)
    }
}

/**
 * Data class for manual adjustment parameters
 */
data class ManualAdjustmentData(
    val scale: Float = 1.0f,
    val scaleX: Float = 1.0f,
    val scaleY: Float = 1.0f,
    val offsetX: Float = 0.0f,
    val offsetY: Float = 0.0f,
    val offsetZ: Float = 0.0f,
    val rotationX: Float = 0.0f,
    val rotationY: Float = 0.0f,
    val rotationZ: Float = 0.0f
)

/**
 * Data class for landmark detection settings
 */
data class LandmarkDetectionSettings(
    val enabled: Boolean = true,
    val confidenceThreshold: Float = 0.5f
)