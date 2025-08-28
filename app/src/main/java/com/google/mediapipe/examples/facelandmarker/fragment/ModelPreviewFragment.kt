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

import android.graphics.*
import android.os.Bundle
import android.util.Log
import android.view.*
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.google.mediapipe.examples.facelandmarker.*
import com.google.mediapipe.examples.facelandmarker.databinding.FragmentModelPreviewBinding
import kotlinx.coroutines.*
import java.io.File
import kotlin.math.*

class ModelPreviewFragment : Fragment() {

    companion object {
        private const val TAG = "ModelPreviewFragment"
    }

    private var _fragmentModelPreviewBinding: FragmentModelPreviewBinding? = null
    private val fragmentModelPreviewBinding get() = _fragmentModelPreviewBinding!!

    private val viewModel: MainViewModel by activityViewModels()
    private lateinit var modelStorageManager: ModelStorageManager
    private lateinit var model3DParser: Model3DParser
    private lateinit var faceAnalyzer: Model3DFaceAnalyzer
    
    private var currentModel: Model3D? = null
    private var currentRotationX = 0f
    private var currentRotationY = 0f
    private var currentScale = 1f
    private var showFaceDetection = false

    // Coroutine scope for background operations
    private val fragmentScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _fragmentModelPreviewBinding = FragmentModelPreviewBinding.inflate(inflater, container, false)
        return fragmentModelPreviewBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Initialize managers
        modelStorageManager = ModelStorageManager(requireContext())
        model3DParser = Model3DParser(requireContext())
        faceAnalyzer = Model3DFaceAnalyzer(requireContext())

        // Set up UI
        setupUI()
        loadStoredModels()
        
        // Observe active model from ViewModel
        observeActiveModel()
    }

    private fun setupUI() {
        // Model selection spinner
        fragmentModelPreviewBinding.buttonSelectModel.setOnClickListener {
            showModelSelectionDialog()
        }
        
        // Rendering controls
        fragmentModelPreviewBinding.seekBarRotationX.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    currentRotationX = (progress - 180).toFloat() // -180 to +180
                    fragmentModelPreviewBinding.textRotationX.text = "X: ${currentRotationX.toInt()}°"
                    renderCurrentModel()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        fragmentModelPreviewBinding.seekBarRotationY.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    currentRotationY = (progress - 180).toFloat() // -180 to +180
                    fragmentModelPreviewBinding.textRotationY.text = "Y: ${currentRotationY.toInt()}°"
                    renderCurrentModel()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        fragmentModelPreviewBinding.seekBarScale.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    currentScale = progress / 100f // 0.01 to 2.0
                    fragmentModelPreviewBinding.textScale.text = "Scale: ${String.format("%.2f", currentScale)}"
                    renderCurrentModel()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        // Face detection toggle
        fragmentModelPreviewBinding.switchFaceDetection.setOnCheckedChangeListener { _, isChecked ->
            showFaceDetection = isChecked
            renderCurrentModel()
        }
        
        // Reset button
        fragmentModelPreviewBinding.buttonReset.setOnClickListener {
            resetView()
        }
        
        // Test face detection button
        fragmentModelPreviewBinding.buttonTestFaceDetection.setOnClickListener {
            testFaceDetection()
        }
        
        // Initialize controls
        fragmentModelPreviewBinding.seekBarRotationX.progress = 180 // 0 degrees
        fragmentModelPreviewBinding.seekBarRotationY.progress = 180 // 0 degrees
        fragmentModelPreviewBinding.seekBarScale.progress = 100 // 1.0 scale
        fragmentModelPreviewBinding.textRotationX.text = "X: 0°"
        fragmentModelPreviewBinding.textRotationY.text = "Y: 0°"
        fragmentModelPreviewBinding.textScale.text = "Scale: 1.00"
    }
    
    private fun observeActiveModel() {
        viewModel.current3DModel.observe(viewLifecycleOwner) { model ->
            if (model != null) {
                loadModel(model)
                fragmentModelPreviewBinding.textCurrentModel.text = "Active: ${model.vertices.size} vertices, ${model.faces.size} faces"
            }
        }
    }

    private fun loadStoredModels() {
        fragmentScope.launch {
            val models = withContext(Dispatchers.IO) {
                modelStorageManager.getAllStoredModels()
            }
            
            if (models.isNotEmpty()) {
                fragmentModelPreviewBinding.textModelCount.text = "${models.size} models available"
            } else {
                fragmentModelPreviewBinding.textModelCount.text = "No models available"
            }
        }
    }

    private fun showModelSelectionDialog() {
        fragmentScope.launch {
            val models = withContext(Dispatchers.IO) {
                modelStorageManager.getAllStoredModels()
            }
            
            if (models.isEmpty()) {
                Toast.makeText(requireContext(), "No models available", Toast.LENGTH_SHORT).show()
                return@launch
            }
            
            val modelNames = models.map { "${it.getDisplayName()} (${it.vertexCount} vertices)" }.toTypedArray()
            
            android.app.AlertDialog.Builder(requireContext())
                .setTitle("Select Model to Preview")
                .setItems(modelNames) { _, which ->
                    val selectedModel = models[which]
                    loadStoredModel(selectedModel)
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }
    
    private fun loadStoredModel(storedModel: StoredModel) {
        fragmentScope.launch {
            try {
                fragmentModelPreviewBinding.progressBar.visibility = View.VISIBLE
                fragmentModelPreviewBinding.textStatus.text = "Loading model..."
                
                val model3D = withContext(Dispatchers.IO) {
                    val file = File(storedModel.filePath)
                    if (!file.exists()) {
                        Log.e(TAG, "Model file not found: ${storedModel.filePath}")
                        return@withContext null
                    }
                    
                    when (storedModel.fileFormat.lowercase()) {
                        "obj" -> {
                            val content = file.readText()
                            model3DParser.parseOBJ(content)
                        }
                        "glb" -> {
                            val bytes = file.readBytes()
                            model3DParser.parseGLB(bytes)
                        }
                        else -> {
                            Log.e(TAG, "Unsupported format: ${storedModel.fileFormat}")
                            null
                        }
                    }
                }
                
                if (model3D != null) {
                    loadModel(model3D)
                    fragmentModelPreviewBinding.textStatus.text = "Model loaded successfully"
                    fragmentModelPreviewBinding.textCurrentModel.text = "Preview: ${model3D.vertices.size} vertices, ${model3D.faces.size} faces"
                    
                    // Show face detection info
                    if (model3D.hasFaceData) {
                        fragmentModelPreviewBinding.textFaceDetectionInfo.text = 
                            "✓ Face detected: ${model3D.faceData?.landmarks?.size} landmarks"
                        fragmentModelPreviewBinding.textFaceDetectionInfo.setTextColor(requireContext().getColor(android.R.color.holo_green_dark))
                    } else {
                        fragmentModelPreviewBinding.textFaceDetectionInfo.text = "⚠ No face detected"
                        fragmentModelPreviewBinding.textFaceDetectionInfo.setTextColor(requireContext().getColor(android.R.color.holo_orange_dark))
                    }
                } else {
                    fragmentModelPreviewBinding.textStatus.text = "Failed to load model"
                    Toast.makeText(requireContext(), "Failed to load model", Toast.LENGTH_SHORT).show()
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error loading model", e)
                fragmentModelPreviewBinding.textStatus.text = "Error: ${e.message}"
                Toast.makeText(requireContext(), "Error loading model: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                fragmentModelPreviewBinding.progressBar.visibility = View.GONE
            }
        }
    }

    private fun loadModel(model: Model3D) {
        currentModel = model
        resetView()
        renderCurrentModel()
    }

    private fun resetView() {
        currentRotationX = 0f
        currentRotationY = 0f
        currentScale = 1f
        
        fragmentModelPreviewBinding.seekBarRotationX.progress = 180
        fragmentModelPreviewBinding.seekBarRotationY.progress = 180
        fragmentModelPreviewBinding.seekBarScale.progress = 100
        
        fragmentModelPreviewBinding.textRotationX.text = "X: 0°"
        fragmentModelPreviewBinding.textRotationY.text = "Y: 0°"
        fragmentModelPreviewBinding.textScale.text = "Scale: 1.00"
        
        renderCurrentModel()
    }

    private fun renderCurrentModel() {
        val model = currentModel ?: return
        
        fragmentScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                renderModelToBitmap(model)
            }
            fragmentModelPreviewBinding.imagePreview.setImageBitmap(bitmap)
        }
    }
    
    private suspend fun renderModelToBitmap(model: Model3D): Bitmap = withContext(Dispatchers.Default) {
        val size = 800
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        
        // Clear background
        canvas.drawColor(Color.BLACK)
        
        // Calculate model bounds
        val bounds = model.boundingBox
        val modelWidth = bounds.second.x - bounds.first.x
        val modelHeight = bounds.second.y - bounds.first.y
        val modelDepth = bounds.second.z - bounds.first.z
        
        val baseScale = minOf(size * 0.7f / modelWidth, size * 0.7f / modelHeight)
        val finalScale = baseScale * currentScale
        
        val centerX = (bounds.first.x + bounds.second.x) / 2f
        val centerY = (bounds.first.y + bounds.second.y) / 2f
        val centerZ = (bounds.first.z + bounds.second.z) / 2f
        
        // Convert rotation to radians
        val rotX = Math.toRadians(currentRotationX.toDouble()).toFloat()
        val rotY = Math.toRadians(currentRotationY.toDouble()).toFloat()
        
        // Set up paints
        val vertexPaint = Paint().apply {
            color = Color.WHITE
            strokeWidth = 2f
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        
        val facePaint = Paint().apply {
            color = Color.GREEN
            strokeWidth = 1f
            style = Paint.Style.STROKE
            isAntiAlias = true
        }
        
        val landmarkPaint = Paint().apply {
            color = Color.RED
            strokeWidth = 3f
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        
        // Transform and render vertices
        model.vertices.forEachIndexed { index, vertex ->
            // Translate to origin
            var x = vertex.x - centerX
            var y = vertex.y - centerY
            var z = vertex.z - centerZ
            
            // Apply rotations
            val cosX = cos(rotX)
            val sinX = sin(rotX)
            val cosY = cos(rotY)
            val sinY = sin(rotY)
            
            // Rotation around X axis
            val y1 = y * cosX - z * sinX
            val z1 = y * sinX + z * cosX
            
            // Rotation around Y axis
            val x2 = x * cosY + z1 * sinY
            val z2 = -x * sinY + z1 * cosY
            
            // Project to screen
            val screenX = x2 * finalScale + size / 2f
            val screenY = y1 * finalScale + size / 2f
            
            if (screenX >= 0 && screenX < size && screenY >= 0 && screenY < size) {
                canvas.drawCircle(screenX, screenY, 1f, vertexPaint)
            }
        }
        
        // Render faces as wireframe
        model.faces.forEach { face ->
            if (face.v1 < model.vertices.size && face.v2 < model.vertices.size && face.v3 < model.vertices.size) {
                val vertices = listOf(model.vertices[face.v1], model.vertices[face.v2], model.vertices[face.v3])
                
                val screenPoints = vertices.map { vertex ->
                    var x = vertex.x - centerX
                    var y = vertex.y - centerY
                    var z = vertex.z - centerZ
                    
                    val cosX = cos(rotX)
                    val sinX = sin(rotX)
                    val cosY = cos(rotY)
                    val sinY = sin(rotY)
                    
                    val y1 = y * cosX - z * sinX
                    val z1 = y * sinX + z * cosX
                    val x2 = x * cosY + z1 * sinY
                    
                    Pair(x2 * finalScale + size / 2f, y1 * finalScale + size / 2f)
                }
                
                for (i in 0 until 3) {
                    val j = (i + 1) % 3
                    canvas.drawLine(
                        screenPoints[i].first, screenPoints[i].second,
                        screenPoints[j].first, screenPoints[j].second,
                        facePaint
                    )
                }
            }
        }
        
        // If face detection is enabled and model has face data, highlight landmarks
        if (showFaceDetection && model.hasFaceData) {
            model.faceData?.landmarks?.forEach { landmark ->
                val x = landmark.x() * size
                val y = landmark.y() * size
                canvas.drawCircle(x, y, 3f, landmarkPaint)
            }
        }
        
        bitmap
    }
    
    private fun testFaceDetection() {
        val model = currentModel ?: run {
            Toast.makeText(requireContext(), "No model loaded", Toast.LENGTH_SHORT).show()
            return
        }
        
        fragmentScope.launch {
            try {
                fragmentModelPreviewBinding.progressBar.visibility = View.VISIBLE
                fragmentModelPreviewBinding.textStatus.text = "Testing face detection..."
                
                val faceData = withContext(Dispatchers.IO) {
                    faceAnalyzer.analyzeModel3DFace(model)
                }
                
                if (faceData != null) {
                    fragmentModelPreviewBinding.textStatus.text = "Face detection successful!"
                    fragmentModelPreviewBinding.textFaceDetectionInfo.text = 
                        "✓ Face detected: ${faceData.landmarks.size} landmarks"
                    fragmentModelPreviewBinding.textFaceDetectionInfo.setTextColor(requireContext().getColor(android.R.color.holo_green_dark))
                    
                    // Update current model with face data
                    currentModel = Model3D(
                        vertices = model.vertices,
                        faces = model.faces,
                        centroid = model.centroid,
                        boundingBox = model.boundingBox,
                        faceData = faceData
                    )
                    
                    // Enable face detection view
                    fragmentModelPreviewBinding.switchFaceDetection.isChecked = true
                    showFaceDetection = true
                    renderCurrentModel()
                    
                    Toast.makeText(requireContext(), "Face detected! ${faceData.landmarks.size} landmarks found", Toast.LENGTH_LONG).show()
                } else {
                    fragmentModelPreviewBinding.textStatus.text = "No face detected"
                    fragmentModelPreviewBinding.textFaceDetectionInfo.text = "⚠ No face detected"
                    fragmentModelPreviewBinding.textFaceDetectionInfo.setTextColor(requireContext().getColor(android.R.color.holo_orange_dark))
                    Toast.makeText(requireContext(), "No face detected in this model", Toast.LENGTH_LONG).show()
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error testing face detection", e)
                fragmentModelPreviewBinding.textStatus.text = "Face detection error: ${e.message}"
                Toast.makeText(requireContext(), "Face detection error: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                fragmentModelPreviewBinding.progressBar.visibility = View.GONE
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        fragmentScope.cancel()
        _fragmentModelPreviewBinding = null
    }
}