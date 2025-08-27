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

import android.app.AlertDialog
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.Navigation
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.mediapipe.examples.facelandmarker.*
import com.google.mediapipe.examples.facelandmarker.databinding.FragmentModelLibraryBinding
import kotlinx.coroutines.*

class ModelLibraryFragment : Fragment() {

    companion object {
        private const val TAG = "ModelLibraryFragment"
    }

    private var _fragmentModelLibraryBinding: FragmentModelLibraryBinding? = null
    private val fragmentModelLibraryBinding get() = _fragmentModelLibraryBinding!!

    private val viewModel: MainViewModel by activityViewModels()
    private lateinit var modelStorageManager: ModelStorageManager
    private lateinit var storedModelsAdapter: StoredModelsAdapter
    private val model3DParser = Model3DParser()

    // Coroutine scope for background operations
    private val fragmentScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _fragmentModelLibraryBinding = FragmentModelLibraryBinding.inflate(inflater, container, false)
        return fragmentModelLibraryBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Initialize storage manager
        modelStorageManager = ModelStorageManager(requireContext())

        // Set up RecyclerView
        setupRecyclerView()

        // Set up UI event listeners
        setupUI()

        // Load stored models
        loadStoredModels()
    }

    private fun setupRecyclerView() {
        storedModelsAdapter = StoredModelsAdapter(
            onModelClick = { model -> onModelSelected(model) },
            onModelLongClick = { model -> showModelOptionsDialog(model) }
        )

        fragmentModelLibraryBinding.recyclerViewModels.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = storedModelsAdapter
        }
    }

    private fun setupUI() {
        // Add model button - navigate to upload fragment
        fragmentModelLibraryBinding.buttonAddModel.setOnClickListener {
            Navigation.findNavController(requireView())
                .navigate(R.id.action_library_to_model3d)
        }

        // Clear all models button
        fragmentModelLibraryBinding.buttonClearAll.setOnClickListener {
            showClearAllDialog()
        }

        // Go to camera button
        fragmentModelLibraryBinding.buttonGoToCamera.setOnClickListener {
            Navigation.findNavController(requireView())
                .navigate(R.id.action_library_to_camera)
        }

        // Refresh button
        fragmentModelLibraryBinding.buttonRefresh.setOnClickListener {
            loadStoredModels()
        }
    }

    private fun loadStoredModels() {
        fragmentScope.launch {
            try {
                fragmentModelLibraryBinding.progressBar.visibility = View.VISIBLE
                
                val models = withContext(Dispatchers.IO) {
                    modelStorageManager.getAllStoredModels()
                }
                
                storedModelsAdapter.submitList(models)
                
                // Update UI based on models count
                if (models.isEmpty()) {
                    fragmentModelLibraryBinding.layoutEmptyState.visibility = View.VISIBLE
                    fragmentModelLibraryBinding.recyclerViewModels.visibility = View.GONE
                    fragmentModelLibraryBinding.buttonClearAll.isEnabled = false
                } else {
                    fragmentModelLibraryBinding.layoutEmptyState.visibility = View.GONE
                    fragmentModelLibraryBinding.recyclerViewModels.visibility = View.VISIBLE
                    fragmentModelLibraryBinding.buttonClearAll.isEnabled = true
                }
                
                // Update storage stats
                updateStorageStats(models)
                
            } catch (e: Exception) {
                Log.e(TAG, "Error loading stored models", e)
                Toast.makeText(requireContext(), "Error loading models", Toast.LENGTH_SHORT).show()
            } finally {
                fragmentModelLibraryBinding.progressBar.visibility = View.GONE
            }
        }
    }

    private fun onModelSelected(model: StoredModel) {
        fragmentScope.launch {
            try {
                fragmentModelLibraryBinding.progressBar.visibility = View.VISIBLE
                
                // Load the model
                val model3D = withContext(Dispatchers.IO) {
                    modelStorageManager.loadModel(model, model3DParser)
                }
                
                if (model3D != null) {
                    // Set as active model in storage
                    withContext(Dispatchers.IO) {
                        modelStorageManager.setActiveModel(model.id)
                    }
                    
                    // Update ViewModel
                    viewModel.set3DModel(model3D)
                    
                    // Refresh the list to show active state
                    loadStoredModels()
                    
                    Toast.makeText(
                        requireContext(), 
                        "Selected: ${model.getDisplayName()}", 
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(
                        requireContext(), 
                        "Failed to load model", 
                        Toast.LENGTH_SHORT
                    ).show()
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error selecting model", e)
                Toast.makeText(requireContext(), "Error selecting model", Toast.LENGTH_SHORT).show()
            } finally {
                fragmentModelLibraryBinding.progressBar.visibility = View.GONE
            }
        }
    }

    private fun showModelOptionsDialog(model: StoredModel) {
        val options = arrayOf("Rename", "Delete", "View Details")
        
        AlertDialog.Builder(requireContext())
            .setTitle(model.getDisplayName())
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showRenameDialog(model)
                    1 -> showDeleteDialog(model)
                    2 -> showModelDetailsDialog(model)
                }
            }
            .show()
    }

    private fun showRenameDialog(model: StoredModel) {
        val editText = EditText(requireContext()).apply {
            setText(model.name.ifBlank { model.originalFileName })
            selectAll()
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Rename Model")
            .setView(editText)
            .setPositiveButton("Rename") { _, _ ->
                val newName = editText.text.toString().trim()
                if (newName.isNotBlank()) {
                    renameModel(model.id, newName)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDeleteDialog(model: StoredModel) {
        AlertDialog.Builder(requireContext())
            .setTitle("Delete Model")
            .setMessage("Are you sure you want to delete '${model.getDisplayName()}'?")
            .setPositiveButton("Delete") { _, _ ->
                deleteModel(model.id)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showModelDetailsDialog(model: StoredModel) {
        val details = """
            Name: ${model.getDisplayName()}
            Original File: ${model.originalFileName}
            Format: ${model.fileFormat}
            Size: ${model.getFormattedSize()}
            Vertices: ${model.vertexCount}
            Faces: ${model.faceCount}
            Added: ${model.getFormattedDate()}
            Active: ${if (model.isActive) "Yes" else "No"}
        """.trimIndent()

        AlertDialog.Builder(requireContext())
            .setTitle("Model Details")
            .setMessage(details)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showClearAllDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("Clear All Models")
            .setMessage("Are you sure you want to delete all stored models? This action cannot be undone.")
            .setPositiveButton("Clear All") { _, _ ->
                clearAllModels()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun renameModel(modelId: String, newName: String) {
        fragmentScope.launch {
            try {
                val success = withContext(Dispatchers.IO) {
                    modelStorageManager.renameModel(modelId, newName)
                }
                
                if (success) {
                    loadStoredModels()
                    Toast.makeText(requireContext(), "Model renamed", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), "Failed to rename model", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error renaming model", e)
                Toast.makeText(requireContext(), "Error renaming model", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun deleteModel(modelId: String) {
        fragmentScope.launch {
            try {
                val success = withContext(Dispatchers.IO) {
                    modelStorageManager.deleteModel(modelId)
                }
                
                if (success) {
                    loadStoredModels()
                    Toast.makeText(requireContext(), "Model deleted", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), "Failed to delete model", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error deleting model", e)
                Toast.makeText(requireContext(), "Error deleting model", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun clearAllModels() {
        fragmentScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    modelStorageManager.clearAllModels()
                }
                
                // Clear active model from ViewModel
                viewModel.clear3DModel()
                
                loadStoredModels()
                Toast.makeText(requireContext(), "All models cleared", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e(TAG, "Error clearing all models", e)
                Toast.makeText(requireContext(), "Error clearing models", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateStorageStats(models: List<StoredModel>) {
        val stats = modelStorageManager.getStorageStats()
        val totalSize = stats["totalSize"] as Long
        val formatCounts = stats["formats"] as Map<String, Int>
        
        val sizeText = when {
            totalSize < 1024 -> "$totalSize B"
            totalSize < 1024 * 1024 -> "${totalSize / 1024} KB"
            else -> "${totalSize / (1024 * 1024)} MB"
        }
        
        val formatsText = formatCounts.entries.joinToString(", ") { "${it.value} ${it.key}" }
        
        fragmentModelLibraryBinding.textStorageStats.text = 
            "${models.size} models • $sizeText • $formatsText"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        fragmentScope.cancel()
        _fragmentModelLibraryBinding = null
    }
}