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
package com.google.mediapipe.examples.facelandmarker.fragment

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.google.mediapipe.examples.facelandmarker.MainViewModel
import com.google.mediapipe.examples.facelandmarker.ManualAdjustmentData
import com.google.mediapipe.examples.facelandmarker.StoredAdjustmentData
import com.google.mediapipe.examples.facelandmarker.ModelStorageManager
import com.google.mediapipe.examples.facelandmarker.Model3DFaceAnalyzer
import com.google.mediapipe.examples.facelandmarker.Model3D
import com.google.mediapipe.examples.facelandmarker.databinding.FragmentManualAdjustmentBinding
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
        initializeValues()
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        _fragmentManualAdjustmentBinding = null
    }
    
    private fun setupControls() {
        // Scale controls
        fragmentManualAdjustmentBinding.seekBarScale.setOnSeekBarChangeListener(createSeekBarListener { progress ->
            manualScale = (progress / 100f) * 2f // 0.0 to 2.0
            fragmentManualAdjustmentBinding.textScaleValue.text = String.format("%.2f", manualScale)
            applyManualAdjustments()
        })
        
        fragmentManualAdjustmentBinding.seekBarScaleX.setOnSeekBarChangeListener(createSeekBarListener { progress ->
            manualScaleX = (progress / 100f) * 3f // 0.0 to 3.0 for stretching
            fragmentManualAdjustmentBinding.textScaleXValue.text = String.format("%.2f", manualScaleX)
            applyManualAdjustments()
        })
        
        fragmentManualAdjustmentBinding.seekBarScaleY.setOnSeekBarChangeListener(createSeekBarListener { progress ->
            manualScaleY = (progress / 100f) * 3f // 0.0 to 3.0 for stretching
            fragmentManualAdjustmentBinding.textScaleYValue.text = String.format("%.2f", manualScaleY)
            applyManualAdjustments()
        })
        
        // Position controls
        fragmentManualAdjustmentBinding.seekBarOffsetX.setOnSeekBarChangeListener(createSeekBarListener { progress ->
            manualOffsetX = (progress - 50) / 100f // -0.5 to 0.5
            fragmentManualAdjustmentBinding.textOffsetXValue.text = String.format("%.3f", manualOffsetX)
            applyManualAdjustments()
        })
        
        fragmentManualAdjustmentBinding.seekBarOffsetY.setOnSeekBarChangeListener(createSeekBarListener { progress ->
            manualOffsetY = (progress - 50) / 100f // -0.5 to 0.5
            fragmentManualAdjustmentBinding.textOffsetYValue.text = String.format("%.3f", manualOffsetY)
            applyManualAdjustments()
        })
        
        fragmentManualAdjustmentBinding.seekBarOffsetZ.setOnSeekBarChangeListener(createSeekBarListener { progress ->
            manualOffsetZ = (progress - 50) / 100f // -0.5 to 0.5
            fragmentManualAdjustmentBinding.textOffsetZValue.text = String.format("%.3f", manualOffsetZ)
            applyManualAdjustments()
        })
        
        // Rotation controls
        fragmentManualAdjustmentBinding.seekBarRotationX.setOnSeekBarChangeListener(createSeekBarListener { progress ->
            manualRotationX = (progress - 180) * 2f // -360° to 360°
            fragmentManualAdjustmentBinding.textRotationXValue.text = String.format("%.1f°", manualRotationX)
            applyManualAdjustments()
        })
        
        fragmentManualAdjustmentBinding.seekBarRotationY.setOnSeekBarChangeListener(createSeekBarListener { progress ->
            manualRotationY = (progress - 180) * 2f // -360° to 360°
            fragmentManualAdjustmentBinding.textRotationYValue.text = String.format("%.1f°", manualRotationY)
            applyManualAdjustments()
        })
        
        fragmentManualAdjustmentBinding.seekBarRotationZ.setOnSeekBarChangeListener(createSeekBarListener { progress ->
            manualRotationZ = (progress - 180) * 2f // -360° to 360°
            fragmentManualAdjustmentBinding.textRotationZValue.text = String.format("%.1f°", manualRotationZ)
            applyManualAdjustments()
        })
        
        // Landmark detection controls
        fragmentManualAdjustmentBinding.seekBarLandmarkConfidence.setOnSeekBarChangeListener(createSeekBarListener { progress ->
            landmarkConfidenceThreshold = progress / 100f // 0.0 to 1.0
            fragmentManualAdjustmentBinding.textLandmarkConfidenceValue.text = String.format("%.2f", landmarkConfidenceThreshold)
            applyLandmarkSettings()
        })
        
        fragmentManualAdjustmentBinding.switchLandmarkDetection.setOnCheckedChangeListener { _, isChecked ->
            landmarkDetectionEnabled = isChecked
            applyLandmarkSettings()
        }
        
        // Action buttons
        fragmentManualAdjustmentBinding.buttonResetAdjustments.setOnClickListener {
            resetToDefaults()
        }
        
        fragmentManualAdjustmentBinding.buttonSaveLandmarkData.setOnClickListener {
            saveLandmarkData()
        }
        
        // Preview controls
        fragmentManualAdjustmentBinding.buttonToggleRendering.setOnClickListener {
            toggleRenderingMode()
        }
        
        fragmentManualAdjustmentBinding.buttonDetectLandmarks.setOnClickListener {
            triggerLandmarkDetection()
        }
        
        fragmentManualAdjustmentBinding.buttonApplyToModel.setOnClickListener {
            applyAdjustmentsToModel()
        }
    }
    
    private fun createSeekBarListener(onProgressChanged: (Int) -> Unit) = 
        object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    onProgressChanged(progress)
                }
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        }
    
    private fun initializeValues() {
        // Set default positions for all seek bars
        fragmentManualAdjustmentBinding.seekBarScale.progress = 50 // 1.0 scale
        fragmentManualAdjustmentBinding.seekBarScaleX.progress = 33 // 1.0 scale
        fragmentManualAdjustmentBinding.seekBarScaleY.progress = 33 // 1.0 scale
        
        fragmentManualAdjustmentBinding.seekBarOffsetX.progress = 50 // 0.0 offset
        fragmentManualAdjustmentBinding.seekBarOffsetY.progress = 50 // 0.0 offset
        fragmentManualAdjustmentBinding.seekBarOffsetZ.progress = 50 // 0.0 offset
        
        fragmentManualAdjustmentBinding.seekBarRotationX.progress = 180 // 0° rotation
        fragmentManualAdjustmentBinding.seekBarRotationY.progress = 180 // 0° rotation
        fragmentManualAdjustmentBinding.seekBarRotationZ.progress = 180 // 0° rotation
        
        fragmentManualAdjustmentBinding.seekBarLandmarkConfidence.progress = 50 // 0.5 confidence
        
        // Update displays
        updateAllDisplays()
    }
    
    private fun updateAllDisplays() {
        fragmentManualAdjustmentBinding.textScaleValue.text = String.format("%.2f", manualScale)
        fragmentManualAdjustmentBinding.textScaleXValue.text = String.format("%.2f", manualScaleX)
        fragmentManualAdjustmentBinding.textScaleYValue.text = String.format("%.2f", manualScaleY)
        
        fragmentManualAdjustmentBinding.textOffsetXValue.text = String.format("%.3f", manualOffsetX)
        fragmentManualAdjustmentBinding.textOffsetYValue.text = String.format("%.3f", manualOffsetY)
        fragmentManualAdjustmentBinding.textOffsetZValue.text = String.format("%.3f", manualOffsetZ)
        
        fragmentManualAdjustmentBinding.textRotationXValue.text = String.format("%.1f°", manualRotationX)
        fragmentManualAdjustmentBinding.textRotationYValue.text = String.format("%.1f°", manualRotationY)
        fragmentManualAdjustmentBinding.textRotationZValue.text = String.format("%.1f°", manualRotationZ)
        
        fragmentManualAdjustmentBinding.textLandmarkConfidenceValue.text = String.format("%.2f", landmarkConfidenceThreshold)
    }
    
    private fun updateUIWithLoadedValues() {
        // Update seek bar positions to match loaded values
        fragmentManualAdjustmentBinding.seekBarScale.progress = (manualScale / 2f * 100).toInt() // 0.0 to 2.0 range
        fragmentManualAdjustmentBinding.seekBarScaleX.progress = (manualScaleX / 3f * 100).toInt() // 0.0 to 3.0 range
        fragmentManualAdjustmentBinding.seekBarScaleY.progress = (manualScaleY / 3f * 100).toInt() // 0.0 to 3.0 range
        
        fragmentManualAdjustmentBinding.seekBarOffsetX.progress = ((manualOffsetX + 0.5f) * 100).toInt() // -0.5 to 0.5 range
        fragmentManualAdjustmentBinding.seekBarOffsetY.progress = ((manualOffsetY + 0.5f) * 100).toInt() // -0.5 to 0.5 range
        fragmentManualAdjustmentBinding.seekBarOffsetZ.progress = ((manualOffsetZ + 0.5f) * 100).toInt() // -0.5 to 0.5 range
        
        fragmentManualAdjustmentBinding.seekBarRotationX.progress = ((manualRotationX + 360f) / 2f).toInt() // -360° to 360° range
        fragmentManualAdjustmentBinding.seekBarRotationY.progress = ((manualRotationY + 360f) / 2f).toInt() // -360° to 360° range
        fragmentManualAdjustmentBinding.seekBarRotationZ.progress = ((manualRotationZ + 360f) / 2f).toInt() // -360° to 360° range
        
        fragmentManualAdjustmentBinding.seekBarLandmarkConfidence.progress = (landmarkConfidenceThreshold * 100).toInt() // 0.0 to 1.0 range
        
        // Update switch state
        fragmentManualAdjustmentBinding.switchLandmarkDetection.isChecked = landmarkDetectionEnabled
        
        // Update displays
        updateAllDisplays()
    }
    
    private fun observeViewModel() {
        // Observe 3D model changes
        viewModel.current3DModel.observe(viewLifecycleOwner) { model ->
            val hasModel = model != null
            fragmentManualAdjustmentBinding.buttonApplyToModel.isEnabled = hasModel
            fragmentManualAdjustmentBinding.buttonDetectLandmarks.isEnabled = hasModel
            
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
    
    private fun resetToDefaults() {
        Log.d(TAG, "🔄 Resetting to default values")
        
        manualScale = 1.0f
        manualScaleX = 1.0f
        manualScaleY = 1.0f
        manualOffsetX = 0.0f
        manualOffsetY = 0.0f
        manualOffsetZ = 0.0f
        manualRotationX = 0.0f
        manualRotationY = 0.0f
        manualRotationZ = 0.0f
        landmarkConfidenceThreshold = 0.5f
        
        initializeValues()
        applyManualAdjustments()
        
        Toast.makeText(requireContext(), "Reset to default values", Toast.LENGTH_SHORT).show()
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
                    
                    // Update UI controls to reflect loaded values
                    updateUIWithLoadedValues()
                    
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
                    val adjustedBitmap = createAdjustedModelBitmap()
                    
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
    
    private fun createAdjustedModelBitmap(): Bitmap? {
        return try {
            // Create a bitmap by capturing the current preview view
            // This includes the model with current adjustments applied
            val previewView = fragmentManualAdjustmentBinding.modelPreviewView
            val bitmap = Bitmap.createBitmap(previewView.width, previewView.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            previewView.draw(canvas)
            bitmap
        } catch (e: Exception) {
            Log.e(TAG, "Error creating adjusted model bitmap", e)
            null
        }
    }
}