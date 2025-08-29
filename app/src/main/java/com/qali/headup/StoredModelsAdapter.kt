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
package com.qali.headup

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import java.io.File

/**
 * Adapter for displaying stored 3D models in a RecyclerView
 */
class StoredModelsAdapter(
    private val onModelClick: (StoredModel) -> Unit,
    private val onModelLongClick: (StoredModel) -> Unit
) : ListAdapter<StoredModel, StoredModelsAdapter.ModelViewHolder>(ModelDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ModelViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_stored_model, parent, false)
        return ModelViewHolder(view)
    }

    override fun onBindViewHolder(holder: ModelViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ModelViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val cardView: CardView = itemView.findViewById(R.id.card_model)
        private val previewImage: ImageView = itemView.findViewById(R.id.image_preview)
        private val nameText: TextView = itemView.findViewById(R.id.text_model_name)
        private val detailsText: TextView = itemView.findViewById(R.id.text_model_details)
        private val formatText: TextView = itemView.findViewById(R.id.text_format)
        private val faceStatusText: TextView = itemView.findViewById(R.id.text_face_status)
        private val dateText: TextView = itemView.findViewById(R.id.text_date_added)
        private val activeIndicator: View = itemView.findViewById(R.id.indicator_active)

        fun bind(model: StoredModel) {
            nameText.text = model.getDisplayName()
            detailsText.text = "${model.vertexCount} vertices • ${model.faceCount} faces • ${model.getFormattedSize()}"
            formatText.text = model.fileFormat
            dateText.text = "Added ${model.getFormattedDate()}"
            
            // Show face detection status
            faceStatusText.text = model.getFaceDetectionStatus()
            faceStatusText.setTextColor(ContextCompat.getColor(itemView.context, model.getFaceDetectionStatusColor()))
            
            // Show active indicator
            activeIndicator.visibility = if (model.isActive) View.VISIBLE else View.GONE
            
            // Set card background based on active state
            if (model.isActive) {
                cardView.setCardBackgroundColor(
                    ContextCompat.getColor(itemView.context, android.R.color.holo_blue_light)
                )
            } else {
                cardView.setCardBackgroundColor(
                    ContextCompat.getColor(itemView.context, android.R.color.white)
                )
            }
            
            // Load preview image
            loadPreviewImage(model)
            
            // Set click listeners
            itemView.setOnClickListener { onModelClick(model) }
            itemView.setOnLongClickListener { 
                onModelLongClick(model)
                true
            }
        }
        
        private fun loadPreviewImage(model: StoredModel) {
            if (model.previewImagePath != null) {
                val previewFile = File(model.previewImagePath)
                if (previewFile.exists()) {
                    val bitmap = BitmapFactory.decodeFile(previewFile.absolutePath)
                    if (bitmap != null) {
                        previewImage.setImageBitmap(bitmap)
                        return
                    }
                }
            }
            
            // Default preview based on format
            when (model.fileFormat.lowercase()) {
                "obj" -> previewImage.setImageResource(android.R.drawable.ic_menu_gallery)
                "glb" -> previewImage.setImageResource(android.R.drawable.ic_menu_view)
                else -> previewImage.setImageResource(android.R.drawable.ic_menu_help)
            }
        }
    }

    class ModelDiffCallback : DiffUtil.ItemCallback<StoredModel>() {
        override fun areItemsTheSame(oldItem: StoredModel, newItem: StoredModel): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: StoredModel, newItem: StoredModel): Boolean {
            return oldItem == newItem
        }
    }
}