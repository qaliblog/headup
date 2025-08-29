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
package com.qali.headup.fragment

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.qali.headup.MainViewModel
import com.qali.headup.ManualAdjustmentData
import com.qali.headup.StoredAdjustmentData
import com.qali.headup.ModelStorageManager
import com.qali.headup.Model3DFaceAnalyzer
import com.qali.headup.Model3D
import com.qali.headup.databinding.FragmentManualAdjustmentBinding
import com.qali.headup.FaceLandmarkMatcher
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Fragment for manual adjustment of 3D model positioning and landmark detection
 */
class ManualAdjustmentFragment : Fragment() {
    
    companion object {
        private const val TAG = "ManualAdjustmentFragment"
    }
    
    private var _fragmentManualAdjustmentBinding: FragmentManualAdjustmentBinding? = null
    private val fragmentManualAdjustmentBinding get() = _fragmentManualAdjustmentBinding!!
    
    private val viewModel: MainViewModel by activityViewModels()
    
    // Manual adjustment parameters
    private var manualScale = 1.0f
    private var manualScaleX = 1.0f
    private var manualScaleY = 1.0f
    private var manualOffsetX = 0.0f
    private var manualOffsetY = 0.0f
    private var manualOffsetZ = 0.0f
    private var manualRotationX = 0.0f
    private var manualRotationY = 0.0f
    private var manualRotationZ = 0.0f
    
    // Landmark detection parameters
    private var landmarkDetectionEnabled = true
    private var landmarkConfidenceThreshold = 0.5f
    private var customLandmarkMappings = mutableMapOf<Int, Int>()
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _fragmentManualAdjustmentBinding = FragmentManualAdjustmentBinding.inflate(inflater, container, false)
        return fragmentManualAdjustmentBinding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        setupControls()
        observeViewModel()
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        _fragmentManualAdjustmentBinding = null
    }
    
    private fun setupControls() {
        // Setup touch controls for the preview view
        fragmentManualAdjustmentBinding.modelPreviewView.setOnAdjustmentChangedListener { adjustments ->
            // Update the current adjustments display
            updateAdjustmentsDisplay(adjustments)
            
            // Update the internal values
            manualScale = adjustments.scale
            manualScaleX = adjustments.scaleX
            manualScaleY = adjustments.scaleY
            manualOffsetX = adjustments.offsetX
            manualOffsetY = adjustments.offsetY
            manualOffsetZ = adjustments.offsetZ
            manualRotationX = adjustments.rotationX
            manualRotationY = adjustments.rotationY
            manualRotationZ = adjustments.rotationZ
            
            // Update ViewModel
            viewModel.setManualAdjustments(
                manualScale, manualScaleX, manualScaleY,
                manualOffsetX, manualOffsetY, manualOffsetZ,
                manualRotationX, manualRotationY, manualRotationZ
            )
        }
        
        // Action buttons
        fragmentManualAdjustmentBinding.buttonResetAdjustments.setOnClickListener {
            resetToDefaults()
        }
        
        fragmentManualAdjustmentBinding.buttonCaptureAndDetect.setOnClickListener {
            captureAndDetectLandmarks()
        }
        
        fragmentManualAdjustmentBinding.buttonSaveLandmarkData.setOnClickListener {
            saveLandmarkData()
        }
        
        // Preview controls
        fragmentManualAdjustmentBinding.buttonToggleRendering.setOnClickListener {
            toggleRenderingMode()
        }
    }
    

    
    private fun observeViewModel() {
        // Observe 3D model changes
        viewModel.current3DModel.observe(viewLifecycleOwner) { model ->
            val hasModel = model != null
            fragmentManualAdjustmentBinding.buttonCaptureAndDetect.isEnabled = hasModel
            
            // Update preview view
            fragmentManualAdjustmentBinding.modelPreviewView.setModel(model)
            
            if (hasModel) {
                fragmentManualAdjustmentBinding.textModelStatus.text = "Model loaded: ${model?.vertices?.size} vertices"
                fragmentManualAdjustmentBinding.textPreviewStatus.text = "${model?.vertices?.size} vertices, ${model?.faces?.size} faces"
                if (model?.hasFaceData == true) {
                    fragmentManualAdjustmentBinding.textLandmarkStatus.text = 
                        "Face detected: ${model.faceData?.landmarks?.size} landmarks"
                } else {
                    fragmentManualAdjustmentBinding.textLandmarkStatus.text = "No face data detected"
                }
                
                // Load saved adjustments for this model
                loadSavedAdjustments()
            } else {
                fragmentManualAdjustmentBinding.textModelStatus.text = "No model loaded"
                fragmentManualAdjustmentBinding.textLandmarkStatus.text = "Load a model first"
                fragmentManualAdjustmentBinding.textPreviewStatus.text = "Load a model to preview"
            }
        }
        
        // Observe manual adjustments changes
        viewModel.manualAdjustments.observe(viewLifecycleOwner) { adjustments ->
            adjustments?.let {
                fragmentManualAdjustmentBinding.modelPreviewView.applyAdjustments(it)
            }
        }
        
        // Observe landmark detection requests
        viewModel.landmarkDetectionRequested.observe(viewLifecycleOwner) { requested ->
            if (requested == true) {
                performLandmarkDetectionOnAdjustedModel()
                viewModel.clearLandmarkDetectionRequest()
            }
        }
    }
    
    private fun applyManualAdjustments() {
        Log.d(TAG, "🎛️ Applying manual adjustments:")
        Log.d(TAG, "   Scale: $manualScale (X: $manualScaleX, Y: $manualScaleY)")
        Log.d(TAG, "   Offset: X=$manualOffsetX, Y=$manualOffsetY, Z=$manualOffsetZ")
        Log.d(TAG, "   Rotation: X=${manualRotationX}°, Y=${manualRotationY}°, Z=${manualRotationZ}°")
        
        // Create adjustment data
        val adjustmentData = ManualAdjustmentData(
            scale = manualScale,
            scaleX = manualScaleX,
            scaleY = manualScaleY,
            offsetX = manualOffsetX,
            offsetY = manualOffsetY,
            offsetZ = manualOffsetZ,
            rotationX = manualRotationX,
            rotationY = manualRotationY,
            rotationZ = manualRotationZ
        )
        
        // Update preview immediately for real-time feedback
        fragmentManualAdjustmentBinding.modelPreviewView.applyAdjustments(adjustmentData)
        
        // Update ViewModel
        viewModel.setManualAdjustments(
            scale = manualScale,
            scaleX = manualScaleX,
            scaleY = manualScaleY,
            offsetX = manualOffsetX,
            offsetY = manualOffsetY,
            offsetZ = manualOffsetZ,
            rotationX = manualRotationX,
            rotationY = manualRotationY,
            rotationZ = manualRotationZ
        )
    }
    
    private fun applyLandmarkSettings() {
        Log.d(TAG, "🎯 Applying landmark settings:")
        Log.d(TAG, "   Detection enabled: $landmarkDetectionEnabled")
        Log.d(TAG, "   Confidence threshold: $landmarkConfidenceThreshold")
        
        // TODO: Apply to landmark detector through ViewModel
        viewModel.setLandmarkDetectionSettings(
            enabled = landmarkDetectionEnabled,
            confidenceThreshold = landmarkConfidenceThreshold
        )
    }
    
    private fun updateAdjustmentsDisplay(adjustments: ManualAdjustmentData) {
        val displayText = "Scale: ${String.format("%.2f", adjustments.scale)} | " +
                         "Rotation: X=${String.format("%.0f", adjustments.rotationX)}° " +
                         "Y=${String.format("%.0f", adjustments.rotationY)}° " +
                         "Z=${String.format("%.0f", adjustments.rotationZ)}°"
        
        fragmentManualAdjustmentBinding.textCurrentAdjustments.text = displayText
    }
    
    private fun resetToDefaults() {
        Log.d(TAG, "🔄 Resetting to default values")
        
        // Reset the preview view directly
        fragmentManualAdjustmentBinding.modelPreviewView.resetAdjustments()
        
        Toast.makeText(requireContext(), "Reset to default values", Toast.LENGTH_SHORT).show()
    }
    
    private fun captureAndDetectLandmarks() {
        Log.d(TAG, "📸 Capturing model view and detecting landmarks for head placement")
        
        lifecycleScope.launch {
            try {
                fragmentManualAdjustmentBinding.progressBar.visibility = View.VISIBLE
                fragmentManualAdjustmentBinding.textLandmarkStatus.text = "Capturing model view and detecting landmarks..."
                
                val currentModel = viewModel.get3DModel()
                if (currentModel != null) {
                    // Get current rotation and scale statistics from touch controls
                    val currentAdjustments = fragmentManualAdjustmentBinding.modelPreviewView.getCurrentAdjustments()
                    
                    Log.d(TAG, "Model rotation statistics: " +
                        "rotX=${currentAdjustments.rotationX}, " +
                        "rotY=${currentAdjustments.rotationY}, " +
                        "rotZ=${currentAdjustments.rotationZ}, " +
                        "scale=${currentAdjustments.scale}")
                    
                    // Create a 2D bitmap of the current model view (as adjusted by user)
                    val model2DBitmap = captureModelBitmap()
                    
                    if (model2DBitmap != null) {
                        // Detect landmarks in the 2D model view
                        val detectedLandmarks = withContext(Dispatchers.IO) {
                            val faceAnalyzer = Model3DFaceAnalyzer(requireContext())
                            faceAnalyzer.detectLandmarksInBitmap(model2DBitmap)
                        }
                        
                        if (detectedLandmarks.isNotEmpty()) {
                            // Create enhanced model with rotation statistics and detected landmarks
                            val enhancedModel = createModelWithRotationStatistics(
                                currentModel, 
                                currentAdjustments, 
                                detectedLandmarks
                            )
                            
                            // Update the model in ViewModel with rotation statistics
                            viewModel.updateModelWithRotationStatistics(enhancedModel)
                            
                            fragmentManualAdjustmentBinding.textLandmarkStatus.text = 
                                "✅ Model prepared for head placement: ${detectedLandmarks.size} landmarks detected"
                            
                            Toast.makeText(requireContext(), 
                                "🎯 Model ready for accurate head placement with rotation data!", 
                                Toast.LENGTH_LONG).show()
                        } else {
                            fragmentManualAdjustmentBinding.textLandmarkStatus.text = "⚠️ No landmarks detected in model view"
                            Toast.makeText(requireContext(), 
                                "⚠️ No face detected in model view. Try rotating model to show face clearly.", 
                                Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        fragmentManualAdjustmentBinding.textLandmarkStatus.text = "❌ Failed to capture model view"
                        Toast.makeText(requireContext(), 
                            "❌ Failed to capture model view", 
                            Toast.LENGTH_SHORT).show()
                    }
                } else {
                    fragmentManualAdjustmentBinding.textLandmarkStatus.text = "❌ No model loaded"
                    Toast.makeText(requireContext(), 
                        "❌ No model loaded for landmark detection", 
                        Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during model capture and landmark detection", e)
                fragmentManualAdjustmentBinding.textLandmarkStatus.text = "❌ Error: ${e.message}"
                Toast.makeText(requireContext(), 
                    "❌ Error: ${e.message}", 
                    Toast.LENGTH_SHORT).show()
            } finally {
                fragmentManualAdjustmentBinding.progressBar.visibility = View.GONE
            }
        }
    }
    
    private fun saveLandmarkData() {
        Log.d(TAG, "💾 Saving landmark data with current adjustments")
        
        val modelId = viewModel.getCurrentModelIdForSaving()
        if (modelId == null) {
            Toast.makeText(requireContext(), 
                "❌ No model loaded or model ID not available", 
                Toast.LENGTH_SHORT).show()
            return
        }
        
        lifecycleScope.launch {
            try {
                val adjustmentData = StoredAdjustmentData(
                    scale = manualScale,
                    scaleX = manualScaleX,
                    scaleY = manualScaleY,
                    offsetX = manualOffsetX,
                    offsetY = manualOffsetY,
                    offsetZ = manualOffsetZ,
                    rotationX = manualRotationX,
                    rotationY = manualRotationY,
                    rotationZ = manualRotationZ,
                    confidenceThreshold = landmarkConfidenceThreshold,
                    savedDate = System.currentTimeMillis()
                )
                
                val modelStorageManager = ModelStorageManager(requireContext())
                val success = modelStorageManager.updateModelAdjustments(modelId, adjustmentData)
                
                if (success) {
                    Toast.makeText(requireContext(), 
                        "✅ Adjustment data saved successfully!", 
                        Toast.LENGTH_LONG).show()
                    Log.d(TAG, "✅ Saved adjustments for model: $modelId")
                } else {
                    Toast.makeText(requireContext(), 
                        "❌ Failed to save adjustment data", 
                        Toast.LENGTH_SHORT).show()
                    Log.w(TAG, "❌ Failed to save adjustments for model: $modelId")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error saving adjustment data", e)
                Toast.makeText(requireContext(), 
                    "❌ Error saving adjustment data: ${e.message}", 
                    Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun triggerLandmarkDetection() {
        Log.d(TAG, "🔍 Triggering manual landmark detection")
        
        lifecycleScope.launch {
            try {
                fragmentManualAdjustmentBinding.progressBar.visibility = View.VISIBLE
                fragmentManualAdjustmentBinding.textLandmarkStatus.text = "Detecting landmarks..."
                
                val success = viewModel.triggerLandmarkDetection(landmarkConfidenceThreshold)
                
                if (success) {
                    Toast.makeText(requireContext(), 
                        "🎯 Landmark detection completed!", 
                        Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), 
                        "⚠️ No landmarks detected", 
                        Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during landmark detection", e)
                Toast.makeText(requireContext(), 
                    "Error: ${e.message}", 
                    Toast.LENGTH_SHORT).show()
            } finally {
                fragmentManualAdjustmentBinding.progressBar.visibility = View.GONE
            }
        }
    }
    
    private fun applyAdjustmentsToModel() {
        Log.d(TAG, "✅ Applying all adjustments to current model")
        
        val currentModel = viewModel.get3DModel()
        if (currentModel != null) {
            viewModel.applyManualAdjustmentsToModel(
                scale = manualScale,
                scaleX = manualScaleX,
                scaleY = manualScaleY,
                offsetX = manualOffsetX,
                offsetY = manualOffsetY,
                offsetZ = manualOffsetZ,
                rotationX = manualRotationX,
                rotationY = manualRotationY,
                rotationZ = manualRotationZ
            )
            
            Toast.makeText(requireContext(), 
                "🎯 Adjustments applied to model!", 
                Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(requireContext(), 
                "⚠️ No model loaded to apply adjustments", 
                Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun loadSavedAdjustments() {
        val modelId = viewModel.getCurrentModelIdForSaving()
        if (modelId != null) {
            try {
                val modelStorageManager = ModelStorageManager(requireContext())
                val savedAdjustments = modelStorageManager.getModelAdjustments(modelId)
                
                if (savedAdjustments != null) {
                    Log.d(TAG, "📥 Loading saved adjustments for model: $modelId")
                    
                    // Update manual variables
                    manualScale = savedAdjustments.scale
                    manualScaleX = savedAdjustments.scaleX
                    manualScaleY = savedAdjustments.scaleY
                    manualOffsetX = savedAdjustments.offsetX
                    manualOffsetY = savedAdjustments.offsetY
                    manualOffsetZ = savedAdjustments.offsetZ
                    manualRotationX = savedAdjustments.rotationX
                    manualRotationY = savedAdjustments.rotationY
                    manualRotationZ = savedAdjustments.rotationZ
                    landmarkConfidenceThreshold = savedAdjustments.confidenceThreshold
                    
                    // Apply the loaded adjustments
                    applyManualAdjustments()
                    
                    Toast.makeText(requireContext(),
                        "📥 Loaded saved adjustments (${savedAdjustments.getFormattedSaveDate()})",
                        Toast.LENGTH_SHORT).show()
                        
                    Log.d(TAG, "✅ Loaded adjustments: scale=${savedAdjustments.scale}, offsets=(${savedAdjustments.offsetX}, ${savedAdjustments.offsetY})")
                } else {
                    Log.d(TAG, "📝 No saved adjustments found for model: $modelId, using defaults")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading saved adjustments", e)
            }
        }
    }
    
    private fun toggleRenderingMode() {
        val currentMode = fragmentManualAdjustmentBinding.buttonToggleRendering.text == "Fill"
        if (currentMode) {
            // Switch to wireframe mode
            fragmentManualAdjustmentBinding.modelPreviewView.setRenderingMode(false)
            fragmentManualAdjustmentBinding.buttonToggleRendering.text = "Wire"
        } else {
            // Switch to filled mode
            fragmentManualAdjustmentBinding.modelPreviewView.setRenderingMode(true)
            fragmentManualAdjustmentBinding.buttonToggleRendering.text = "Fill"
        }
    }
    
    private fun performLandmarkDetectionOnAdjustedModel() {
        Log.d(TAG, "🎯 Performing landmark detection on adjusted model")
        
        lifecycleScope.launch {
            try {
                fragmentManualAdjustmentBinding.progressBar.visibility = View.VISIBLE
                fragmentManualAdjustmentBinding.textLandmarkStatus.text = "Detecting landmarks on adjusted model..."
                
                val currentModel = viewModel.get3DModel()
                if (currentModel != null) {
                    // Create Model3DFaceAnalyzer for landmark detection
                    val faceAnalyzer = Model3DFaceAnalyzer(requireContext())
                    
                    // Create a bitmap of the adjusted model
                    val adjustedBitmap = captureModelBitmap()
                    
                    if (adjustedBitmap != null) {
                        // Run face detection on the adjusted model
                        val faceData = withContext(Dispatchers.IO) {
                            faceAnalyzer.analyzeModel3DFace(currentModel)
                        }
                        
                        if (faceData != null) {
                            // Create updated model with new face data
                            val updatedModel = Model3D(
                                vertices = currentModel.vertices,
                                faces = currentModel.faces,
                                centroid = currentModel.centroid,
                                boundingBox = currentModel.boundingBox,
                                faceData = faceData
                            )
                            
                            // Update the model with new landmark data
                            viewModel.set3DModel(updatedModel, viewModel.currentModelId)
                            
                            fragmentManualAdjustmentBinding.textLandmarkStatus.text = 
                                "✅ Landmarks detected on adjusted model: ${faceData.landmarks.size} landmarks"
                            
                            Toast.makeText(requireContext(), 
                                "🎯 Landmark detection completed on adjusted model!", 
                                Toast.LENGTH_SHORT).show()
                        } else {
                            fragmentManualAdjustmentBinding.textLandmarkStatus.text = "⚠️ No landmarks detected on adjusted model"
                            Toast.makeText(requireContext(), 
                                "⚠️ No landmarks detected on adjusted model", 
                                Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        fragmentManualAdjustmentBinding.textLandmarkStatus.text = "❌ Failed to render adjusted model"
                        Toast.makeText(requireContext(), 
                            "❌ Failed to render adjusted model for detection", 
                            Toast.LENGTH_SHORT).show()
                    }
                } else {
                    fragmentManualAdjustmentBinding.textLandmarkStatus.text = "❌ No model loaded"
                    Toast.makeText(requireContext(), 
                        "❌ No model loaded for landmark detection", 
                        Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during landmark detection on adjusted model", e)
                fragmentManualAdjustmentBinding.textLandmarkStatus.text = "❌ Error: ${e.message}"
                Toast.makeText(requireContext(), 
                    "❌ Error during landmark detection: ${e.message}", 
                    Toast.LENGTH_SHORT).show()
            } finally {
                fragmentManualAdjustmentBinding.progressBar.visibility = View.GONE
            }
        }
    }
    
    private fun captureModelBitmap(): Bitmap? {
        return try {
            // Create a bitmap by capturing the current preview view
            // This includes the model with current adjustments applied
            val previewView = fragmentManualAdjustmentBinding.modelPreviewView
            val bitmap = Bitmap.createBitmap(previewView.width, previewView.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            previewView.draw(canvas)
            bitmap
        } catch (e: Exception) {
            Log.e(TAG, "Error creating model bitmap", e)
            null
        }
    }
    
    private fun createModelWithRotationStatistics(
        originalModel: Model3D,
        adjustments: ManualAdjustmentData,
        detectedLandmarks: List<NormalizedLandmark>
    ): Model3D {
        // Create model with embedded rotation statistics for camera placement
        val rotationStatistics = mapOf(
            "rotationX" to adjustments.rotationX,
            "rotationY" to adjustments.rotationY, 
            "rotationZ" to adjustments.rotationZ,
            "scale" to adjustments.scale,
            "viewAngle" to calculateViewAngle(adjustments),
            "landmarkCount" to detectedLandmarks.size,
            "captureTimestamp" to System.currentTimeMillis()
        )
        
        Log.d(TAG, "Created model with rotation statistics: $rotationStatistics")
        
        // Create enhanced face data with detected landmarks and rotation info
        val enhancedFaceData = if (originalModel.hasFaceData && originalModel.faceData != null) {
            // Update existing face data with new landmarks and rotation statistics
            Model3DFaceData(
                landmarks = detectedLandmarks,
                boundingBox = originalModel.faceData!!.boundingBox ?: calculateLandmarkBoundingBox(detectedLandmarks),
                confidence = calculateLandmarkConfidence(detectedLandmarks),
                rotationStatistics = rotationStatistics
            )
        } else {
            // Create new face data
            Model3DFaceData(
                landmarks = detectedLandmarks,
                boundingBox = calculateLandmarkBoundingBox(detectedLandmarks),
                confidence = calculateLandmarkConfidence(detectedLandmarks),
                rotationStatistics = rotationStatistics
            )
        }
        
        // Return enhanced model
        return originalModel.copy(
            faceData = enhancedFaceData
        )
    }
    
    private fun calculateViewAngle(adjustments: ManualAdjustmentData): Float {
        // Calculate overall viewing angle based on rotation components
        return kotlin.math.sqrt(
            adjustments.rotationX * adjustments.rotationX + 
            adjustments.rotationY * adjustments.rotationY + 
            adjustments.rotationZ * adjustments.rotationZ
        )
    }
    
    private fun calculateLandmarkBoundingBox(landmarks: List<NormalizedLandmark>): RectF {
        if (landmarks.isEmpty()) return RectF(0f, 0f, 1f, 1f)
        
        val minX = landmarks.minOf { it.x() }
        val maxX = landmarks.maxOf { it.x() }
        val minY = landmarks.minOf { it.y() }
        val maxY = landmarks.maxOf { it.y() }
        
        return RectF(minX, minY, maxX, maxY)
    }
    
    private fun calculateLandmarkConfidence(landmarks: List<NormalizedLandmark>): Float {
        // Simple confidence calculation based on landmark count and distribution
        return kotlin.math.min(1f, landmarks.size / 468f) // MediaPipe has 468 landmarks max
    }
}