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

import android.graphics.*
import android.util.Log
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.*

/**
 * Advanced 3D renderer that uses landmark-to-landmark matching for precise face alignment
 */
class LandmarkAlignedRenderer {
    
    companion object {
        private const val TAG = "LandmarkAlignedRenderer"
        private const val MIN_ALIGNMENT_SCORE = 0.3f
    }
    
    private var currentModel: Model3D? = null
    private var currentAlignment: FaceAlignmentTransform? = null
    private var viewportWidth = 1
    private var viewportHeight = 1
    private var scaleFactor = 1f
    private var offsetX = 0f
    private var offsetY = 0f
    
    private val landmarkMatcher = FaceLandmarkMatcher()
    
    /**
     * Set the 3D model to render (must have face data)
     */
    fun setModel(model: Model3D): Boolean {
        return if (model.hasFaceData) {
            currentModel = model
            currentAlignment = null // Reset alignment
            Log.d(TAG, "Model set with face data: ${model.vertices.size} vertices, ${model.faceData?.landmarks?.size} landmarks")
            true
        } else {
            Log.w(TAG, "Model does not have face data - cannot use landmark alignment")
            false
        }
    }
    
    /**
     * Update face parameters for rendering
     */
    fun updateFaceParameters(
        width: Int,
        height: Int,
        scaleFactor: Float,
        offsetX: Float,
        offsetY: Float
    ) {
        this.viewportWidth = width
        this.viewportHeight = height
        this.scaleFactor = scaleFactor
        this.offsetX = offsetX
        this.offsetY = offsetY
    }
    
    /**
     * Update alignment based on detected face landmarks
     */
    fun updateAlignment(realFaceLandmarks: List<NormalizedLandmark>): Boolean {
        val model = currentModel ?: return false
        val faceData = model.faceData ?: return false
        
        return try {
            Log.d(TAG, "Calculating landmark alignment for ${realFaceLandmarks.size} real landmarks")
            
            val alignment = landmarkMatcher.calculateFaceAlignment(
                faceData,
                realFaceLandmarks,
                viewportWidth,
                viewportHeight
            )
            
            if (alignment != null && alignment.alignmentScore >= MIN_ALIGNMENT_SCORE) {
                currentAlignment = alignment
                Log.d(TAG, "Face alignment updated: score=${alignment.alignmentScore}, correspondences=${alignment.correspondences.size}")
                true
            } else {
                Log.w(TAG, "Poor alignment quality: ${alignment?.alignmentScore ?: 0f}")
                false
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error updating alignment", e)
            false
        }
    }
    
    /**
     * Check if the renderer has a model with valid alignment
     */
    fun hasModel(): Boolean = currentModel != null && currentModel?.hasFaceData == true
    
    fun hasValidAlignment(): Boolean = currentAlignment != null && currentAlignment!!.alignmentScore >= MIN_ALIGNMENT_SCORE
    
    /**
     * Render the aligned 3D model
     */
    fun render(canvas: Canvas, paint: Paint): Boolean {
        val model = currentModel ?: run {
            Log.w(TAG, "Cannot render: no model")
            return false
        }
        
        val alignment = currentAlignment ?: run {
            Log.w(TAG, "Cannot render: no valid alignment")
            return false
        }
        
        if (!model.hasFaceData) {
            Log.w(TAG, "Cannot render: model has no face data")
            return false
        }
        
        return try {
            Log.d(TAG, "Rendering landmark-aligned model: ${model.vertices.size} vertices")
            
            // Transform all vertices using the alignment
            val transformedVertices = mutableListOf<PointF>()
            
            model.vertices.forEach { vertex ->
                // Apply landmark-based transformation
                val transformed = transformVertex(vertex, alignment.transformMatrix)
                
                // Project to screen coordinates
                val projected = projectToScreen(transformed)
                
                transformedVertices.add(projected)
            }
            
            // Render the transformed model
            renderTransformedModel(canvas, paint, model, transformedVertices, alignment)
            
            Log.d(TAG, "Landmark-aligned rendering completed successfully")
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "Error rendering landmark-aligned model", e)
            false
        }
    }
    
    /**
     * Transform a vertex using the alignment matrix
     */
    private fun transformVertex(vertex: Vertex3D, matrix: FloatArray): Vertex3D {
        val x = vertex.x * matrix[0] + vertex.y * matrix[4] + vertex.z * matrix[8] + matrix[12]
        val y = vertex.x * matrix[1] + vertex.y * matrix[5] + vertex.z * matrix[9] + matrix[13]
        val z = vertex.x * matrix[2] + vertex.y * matrix[6] + vertex.z * matrix[10] + matrix[14]
        
        return Vertex3D(x, y, z)
    }
    
    /**
     * Project 3D coordinate to screen coordinates
     */
    private fun projectToScreen(vertex: Vertex3D): PointF {
        // Apply screen scaling and offsets
        val screenX = vertex.x * scaleFactor + offsetX
        val screenY = vertex.y * scaleFactor + offsetY
        
        return PointF(screenX, screenY)
    }
    
    /**
     * Render the transformed model with enhanced visualization
     */
    private fun renderTransformedModel(
        canvas: Canvas,
        paint: Paint,
        model: Model3D,
        transformedVertices: List<PointF>,
        alignment: FaceAlignmentTransform
    ) {
        val originalColor = paint.color
        val originalStrokeWidth = paint.strokeWidth
        
        try {
            // Render main model wireframe
            paint.color = Color.CYAN
            paint.strokeWidth = 2f
            paint.style = Paint.Style.STROKE
            
            model.faces.forEach { face ->
                if (face.v1 < transformedVertices.size && 
                    face.v2 < transformedVertices.size && 
                    face.v3 < transformedVertices.size) {
                    
                    val p1 = transformedVertices[face.v1]
                    val p2 = transformedVertices[face.v2]
                    val p3 = transformedVertices[face.v3]
                    
                    canvas.drawLine(p1.x, p1.y, p2.x, p2.y, paint)
                    canvas.drawLine(p2.x, p2.y, p3.x, p3.y, paint)
                    canvas.drawLine(p3.x, p3.y, p1.x, p1.y, paint)
                }
            }
            
            // Highlight landmark correspondences if in debug mode
            if (shouldShowCorrespondences()) {
                renderLandmarkCorrespondences(canvas, alignment.correspondences)
            }
            
        } finally {
            // Restore original paint settings
            paint.color = originalColor
            paint.strokeWidth = originalStrokeWidth
        }
    }
    
    /**
     * Render landmark correspondences for debugging
     */
    private fun renderLandmarkCorrespondences(
        canvas: Canvas,
        correspondences: List<LandmarkCorrespondence>
    ) {
        val paint = Paint().apply {
            style = Paint.Style.FILL
            strokeWidth = 4f
        }
        
        correspondences.forEach { correspondence ->
            // Project model vertex to screen
            val modelScreen = projectToScreen(correspondence.modelVertex)
            val realScreen = PointF(
                correspondence.realLandmark.x * scaleFactor + offsetX,
                correspondence.realLandmark.y * scaleFactor + offsetY
            )
            
            // Draw model landmark in green
            paint.color = Color.GREEN
            canvas.drawCircle(modelScreen.x, modelScreen.y, 6f, paint)
            
            // Draw real landmark in red
            paint.color = Color.RED
            canvas.drawCircle(realScreen.x, realScreen.y, 4f, paint)
            
            // Draw correspondence line
            paint.color = Color.YELLOW
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1f
            canvas.drawLine(modelScreen.x, modelScreen.y, realScreen.x, realScreen.y, paint)
            paint.style = Paint.Style.FILL
        }
    }
    
    private fun shouldShowCorrespondences(): Boolean {
        // For now, always show correspondences in debug builds
        // This could be controlled by a debug flag
        return BuildConfig.DEBUG
    }
    
    /**
     * Get alignment information for debugging
     */
    fun getAlignmentInfo(): String? {
        val alignment = currentAlignment ?: return null
        
        return """
            Alignment Score: ${alignment.alignmentScore}
            Correspondences: ${alignment.correspondences.size}
            Translation: (${alignment.translation.x}, ${alignment.translation.y}, ${alignment.translation.z})
            Rotation: (${alignment.rotation.x}, ${alignment.rotation.y}, ${alignment.rotation.z})
            Scale: (${alignment.scale.x}, ${alignment.scale.y}, ${alignment.scale.z})
        """.trimIndent()
    }
    
    fun cleanup() {
        currentModel = null
        currentAlignment = null
    }
}