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

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.Navigation
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textview.MaterialTextView
import com.google.mediapipe.examples.facelandmarker.FileUploadHelper
import com.google.mediapipe.examples.facelandmarker.MainViewModel
import com.google.mediapipe.examples.facelandmarker.Model3D
import com.google.mediapipe.examples.facelandmarker.Model3DParser
import com.google.mediapipe.examples.facelandmarker.ModelStorageManager
import com.google.mediapipe.examples.facelandmarker.R
import com.google.mediapipe.examples.facelandmarker.databinding.FragmentModel3dBinding
import kotlinx.coroutines.*

class Model3DFragment : Fragment() {

    companion object {
        private const val TAG = "Model3DFragment"
    }

    private var _fragmentModel3dBinding: FragmentModel3dBinding? = null
    private val fragmentModel3dBinding get() = _fragmentModel3dBinding!!

    private val viewModel: MainViewModel by activityViewModels()
    private lateinit var fileUploadHelper: FileUploadHelper
    private lateinit var modelStorageManager: ModelStorageManager
    private lateinit var model3DParser: Model3DParser

    // Coroutine scope for background processing
    private val fragmentScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _fragmentModel3dBinding = FragmentModel3dBinding.inflate(inflater, container, false)
        return fragmentModel3dBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Initialize file upload helper and storage manager
        fileUploadHelper = FileUploadHelper(
            context = requireContext(),
            fragment = this,
            onFileSelected = { uri, fileName -> onFileSelected(uri, fileName) }
        )
        
        modelStorageManager = ModelStorageManager(requireContext())
        model3DParser = Model3DParser(requireContext())

        // Set up UI event listeners
        setupUI()
    }

    private fun setupUI() {
        // Upload 3D model button
        fragmentModel3dBinding.buttonUpload3d.setOnClickListener {
            fileUploadHelper.openFilePicker()
        }

        // Test with cube button
        fragmentModel3dBinding.buttonTestCube.setOnClickListener {
            loadTestCube()
        }

        // Toggle 3D model visibility button
        fragmentModel3dBinding.buttonToggle3d.setOnClickListener {
            toggleModel3DVisibility()
        }

        // Navigation to camera
        fragmentModel3dBinding.buttonGoToCamera.setOnClickListener {
            Navigation.findNavController(requireView())
                .navigate(R.id.action_model3d_to_camera)
        }
        
        // Navigation to model library (will add this button to layout)
        // fragmentModel3dBinding.buttonViewLibrary.setOnClickListener {
        //     Navigation.findNavController(requireView())
        //         .navigate(R.id.action_model3d_to_library)
        // }

        // Clear model button
        fragmentModel3dBinding.buttonClearModel.setOnClickListener {
            clearModel()
        }

        // Update UI state
        updateUIState()
    }

    private fun onFileSelected(uri: Uri, fileName: String) {
        Log.d(TAG, "File selected: $fileName")
        
        fragmentModel3dBinding.textModelStatus.text = "Processing file: $fileName"
        fragmentModel3dBinding.progressBar.visibility = View.VISIBLE
        
        // Get custom name from input field
        val customName = fragmentModel3dBinding.editCustomName.text.toString().trim()
        
        // Process file in background
        fragmentScope.launch {
            try {
                val (success, model3D) = withContext(Dispatchers.IO) {
                    processAndStoreModelFile(uri, fileName, customName)
                }
                
                if (success && model3D != null) {
                    fragmentModel3dBinding.textModelStatus.text = "Model stored and loaded: ${customName.ifBlank { fileName }}"
                    Toast.makeText(requireContext(), "3D model stored successfully!", Toast.LENGTH_SHORT).show()
                    
                    // Clear the custom name field for next upload
                    fragmentModel3dBinding.editCustomName.text?.clear()
                    
                    // Set as active model
                    viewModel.set3DModel(model3D)
                } else {
                    fragmentModel3dBinding.textModelStatus.text = "Failed to process model"
                    Toast.makeText(requireContext(), "Failed to process 3D model", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing model file", e)
                fragmentModel3dBinding.textModelStatus.text = "Error processing model"
                Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                fragmentModel3dBinding.progressBar.visibility = View.GONE
                updateUIState()
            }
        }
    }

    private suspend fun processAndStoreModelFile(uri: Uri, fileName: String, customName: String): Pair<Boolean, Model3D?> {
        return try {
            val fileExtension = fileName.substringAfterLast('.', "").lowercase()
            
            val model3D = when (fileExtension) {
                "obj" -> {
                    // Read OBJ file content as text
                    val content = fileUploadHelper.readFileContent(uri)
                    if (content != null) {
                        model3DParser.parseOBJ(content)
                    } else {
                        Log.w(TAG, "Failed to read OBJ file content")
                        null
                    }
                }
                "glb" -> {
                    // Read GLB file content as binary
                    val bytes = fileUploadHelper.readFileBytes(uri)
                    if (bytes != null) {
                        model3DParser.parseGLB(bytes)
                    } else {
                        Log.w(TAG, "Failed to read GLB file content")
                        null
                    }
                }
                else -> {
                    Log.w(TAG, "Unsupported file format: $fileExtension")
                    null
                }
            }
            
            if (model3D != null) {
                // Store the model
                val storedModel = modelStorageManager.storeModel(
                    uri = uri,
                    originalFileName = fileName,
                    fileFormat = fileExtension,
                    model3D = model3D,
                    customName = customName
                )
                
                if (storedModel != null) {
                    // Set as active model in storage
                    modelStorageManager.setActiveModel(storedModel.id)
                    Log.d(TAG, "Model stored successfully: ${storedModel.getDisplayName()}")
                    Pair(true, model3D)
                } else {
                    Log.w(TAG, "Failed to store model")
                    Pair(false, null)
                }
            } else {
                Log.w(TAG, "Failed to parse model file")
                Pair(false, null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing and storing model file", e)
            Pair(false, null)
        }
    }

    private fun loadTestCube() {
        fragmentModel3dBinding.textModelStatus.text = "Loading test cube..."
        
        fragmentScope.launch {
            try {
                val testCube = withContext(Dispatchers.IO) {
                    model3DParser.createTestCube(1.0f)
                }
                
                viewModel.set3DModel(testCube)
                fragmentModel3dBinding.textModelStatus.text = "Test cube loaded"
                Toast.makeText(requireContext(), "Test cube loaded!", Toast.LENGTH_SHORT).show()
                updateUIState()
            } catch (e: Exception) {
                Log.e(TAG, "Error loading test cube", e)
                fragmentModel3dBinding.textModelStatus.text = "Error loading test cube"
                Toast.makeText(requireContext(), "Error loading test cube", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun toggleModel3DVisibility() {
        viewModel.toggle3DModelVisibility()
        updateUIState()
    }

    private fun clearModel() {
        viewModel.clear3DModel()
        fragmentModel3dBinding.textModelStatus.text = "No model loaded"
        updateUIState()
        Toast.makeText(requireContext(), "3D model cleared", Toast.LENGTH_SHORT).show()
    }

    private fun updateUIState() {
        val hasModel = viewModel.has3DModel()
        val isVisible = viewModel.is3DModelVisible()

        fragmentModel3dBinding.buttonToggle3d.isEnabled = hasModel
        fragmentModel3dBinding.buttonToggle3d.text = if (isVisible) "Hide 3D Model" else "Show 3D Model"
        fragmentModel3dBinding.buttonClearModel.isEnabled = hasModel
        fragmentModel3dBinding.buttonGoToCamera.isEnabled = hasModel

        // Update model info card visibility
        fragmentModel3dBinding.cardModelInfo.visibility = if (hasModel) View.VISIBLE else View.GONE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        fragmentScope.cancel()
        _fragmentModel3dBinding = null
    }
}