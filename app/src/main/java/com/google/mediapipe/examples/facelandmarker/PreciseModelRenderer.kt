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
 * Ultra-precise 3D model renderer using landmark-to-landmark alignment
 * Each model landmark is mapped directly to the corresponding face landmark
 */
class PreciseModelRenderer {
    
    companion object {
        private const val TAG = "PreciseModelRenderer"
        private const val MIN_CONFIDENCE = 0.4f
    }
    
    private var currentModel: Model3D? = null
    private var currentAlignment: PreciseLandmarkAligner.LandmarkAlignment? = null
    private var screenWidth = 1
    private var screenHeight = 1
    private var scaleFactor = 1f
    private var offsetX = 0f
    private var offsetY = 0f
    
    private val aligner = PreciseLandmarkAligner()
    
    // Paint objects for rendering
    private val wireframePaint = Paint().apply {
        color = Color.CYAN
        strokeWidth = 2f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }
    
    private val landmarkPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    
    private val debugPaint = Paint().apply {
        color = Color.YELLOW
        strokeWidth = 1f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }
    
    /**
     * Set the 3D model to render (must have face data for precise alignment)
     */
    fun setModel(model: Model3D): Boolean {
        return if (model.hasFaceData) {
            currentModel = model
            currentAlignment = null // Reset alignment
            Log.d(TAG, "✅ Model set with face data: ${model.vertices.size} vertices, ${model.faceData?.landmarks?.size} landmarks")
            true
        } else {
            Log.w(TAG, "❌ Model has no face data - cannot use precise alignment")
            false
        }
    }
    
    /**
     * Check if renderer has a valid model
     */
    fun hasValidModel(): Boolean = currentModel?.hasFaceData == true
    
    /**
     * Update screen parameters for coordinate transformation
     */
    fun updateScreenParameters(width: Int, height: Int, scale: Float, offsetX: Float, offsetY: Float) {
        this.screenWidth = width
        this.screenHeight = height
        this.scaleFactor = scale
        this.offsetX = offsetX
        this.offsetY = offsetY
    }
    
    /**
     * Update alignment based on current face landmarks
     */
    fun updateAlignment(faceLandmarks: List<NormalizedLandmark>): Boolean {
        val model = currentModel ?: return false
        val faceData = model.faceData ?: return false
        
        return try {
            val alignment = aligner.calculatePreciseAlignment(faceData, faceLandmarks)
            
            if (alignment != null && alignment.confidence >= MIN_CONFIDENCE) {
                currentAlignment = alignment
                Log.d(TAG, "🎯 Precise alignment updated - confidence: ${String.format("%.3f", alignment.confidence)}")
                true
            } else {
                val confidence = alignment?.confidence ?: 0f
                Log.w(TAG, "⚠️ Alignment quality too low: ${String.format("%.3f", confidence)} < $MIN_CONFIDENCE")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating alignment", e)
            false
        }
    }
    
    /**
     * Render the 3D model with precise landmark alignment
     */
    fun render(canvas: Canvas, paint: Paint): Boolean {
        val model = currentModel ?: return false
        val alignment = currentAlignment ?: return false
        
        return try {
            Log.d(TAG, "🎨 Rendering model with precise alignment (confidence: ${String.format("%.3f", alignment.confidence)})")
            
            // Render wireframe with precise landmark mapping
            renderPreciseWireframe(canvas, model, alignment)
            
            // Render landmark correspondences (debug)
            renderLandmarkCorrespondences(canvas, alignment)
            
            // Render alignment info
            renderAlignmentInfo(canvas, alignment)
            
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error rendering precise model", e)
            false
        }
    }
    
    /**
     * Render wireframe using precise landmark transformations
     */
    private fun renderPreciseWireframe(
        canvas: Canvas, 
        model: Model3D, 
        alignment: PreciseLandmarkAligner.LandmarkAlignment
    ) {
        
        // Transform each vertex precisely based on landmark alignment
        val transformedVertices = model.vertices.map { vertex ->
            transformVertexPrecisely(vertex, alignment)
        }
        
        // Convert to screen coordinates
        val screenVertices = transformedVertices.map { vertex ->
            toScreenCoordinates(vertex)
        }
        
        // Render faces as wireframe
        for (face in model.faces) {
            val vertexIndices = listOf(face.v1, face.v2, face.v3)
            val path = Path()
            
            for ((i, vertexIndex) in vertexIndices.withIndex()) {
                if (vertexIndex < screenVertices.size) {
                    val screenPoint = screenVertices[vertexIndex]
                    
                    if (i == 0) {
                        path.moveTo(screenPoint.x, screenPoint.y)
                    } else {
                        path.lineTo(screenPoint.x, screenPoint.y)
                    }
                }
            }
            
            path.close()
            canvas.drawPath(path, wireframePaint)
        }
        
        Log.d(TAG, "🎨 Rendered ${model.faces.size} faces with precise alignment")
    }
    
    /**
     * Transform a vertex using precise landmark alignment
     */
    private fun transformVertexPrecisely(
        vertex: Vertex3D, 
        alignment: PreciseLandmarkAligner.LandmarkAlignment
    ): Vertex3D {
        
        val transform = alignment.transform
        
        // Convert vertex to normalized coordinates (same as landmarks)
        val normalizedVertex = normalizeVertex(vertex)
        
        // Apply scale
        val scaled = Vertex3D(
            normalizedVertex.x * transform.scale,
            normalizedVertex.y * transform.scale,
            normalizedVertex.z * transform.scale
        )
        
        // Apply rotation (simplified for now)
        val rotated = applyRotation(scaled, transform.rotation)
        
        // Apply translation
        val translated = Vertex3D(
            rotated.x + transform.translation.x,
            rotated.y + transform.translation.y,
            rotated.z + transform.translation.z
        )
        
        return translated
    }
    
    /**
     * Normalize vertex to [0,1] coordinates to match MediaPipe landmarks
     */
    private fun normalizeVertex(vertex: Vertex3D): Vertex3D {
        val model = currentModel ?: return vertex
        val bounds = model.boundingBox
        
        val width = bounds.second.x - bounds.first.x
        val height = bounds.second.y - bounds.first.y
        val depth = bounds.second.z - bounds.first.z
        
        return if (width > 0 && height > 0) {
            Vertex3D(
                (vertex.x - bounds.first.x) / width,
                (vertex.y - bounds.first.y) / height,
                if (depth > 0) (vertex.z - bounds.first.z) / depth else 0f
            )
        } else {
            vertex
        }
    }
    
    /**
     * Apply rotation transformation
     */
    private fun applyRotation(vertex: Vertex3D, rotation: PreciseLandmarkAligner.RotationMatrix): Vertex3D {
        // Convert angles to radians
        val pitchRad = rotation.pitch * PI.toFloat() / 180f
        val yawRad = rotation.yaw * PI.toFloat() / 180f
        val rollRad = rotation.roll * PI.toFloat() / 180f
        
        // Apply rotations (simplified - proper 3D rotation matrices would be better)
        var x = vertex.x
        var y = vertex.y
        var z = vertex.z
        
        // Pitch (X rotation)
        val tempY = y * cos(pitchRad) - z * sin(pitchRad)
        val tempZ = y * sin(pitchRad) + z * cos(pitchRad)
        y = tempY
        z = tempZ
        
        // Yaw (Y rotation)  
        val tempX = x * cos(yawRad) + z * sin(yawRad)
        z = -x * sin(yawRad) + z * cos(yawRad)
        x = tempX
        
        // Roll (Z rotation)
        val finalX = x * cos(rollRad) - y * sin(rollRad)
        val finalY = x * sin(rollRad) + y * cos(rollRad)
        
        return Vertex3D(finalX, finalY, z)
    }
    
    /**
     * Convert normalized coordinates to screen coordinates
     */
    private fun toScreenCoordinates(vertex: Vertex3D): PointF {
        val x = vertex.x * screenWidth * scaleFactor + offsetX
        val y = vertex.y * screenHeight * scaleFactor + offsetY
        return PointF(x, y)
    }
    
    /**
     * Render landmark correspondences for debugging
     */
    private fun renderLandmarkCorrespondences(
        canvas: Canvas,
        alignment: PreciseLandmarkAligner.LandmarkAlignment
    ) {
        
        for (mapping in alignment.mappings.take(10)) { // Show first 10 correspondences
            // Face landmark position
            val facePoint = PointF(
                mapping.facePoint.x * screenWidth * scaleFactor + offsetX,
                mapping.facePoint.y * screenHeight * scaleFactor + offsetY
            )
            
            // Transformed model landmark position
            val transformedModel = transformVertexPrecisely(
                Vertex3D(mapping.modelPoint.x, mapping.modelPoint.y, mapping.modelPoint.z),
                alignment
            )
            val modelPoint = toScreenCoordinates(transformedModel)
            
            // Draw correspondence line
            canvas.drawLine(facePoint.x, facePoint.y, modelPoint.x, modelPoint.y, debugPaint)
            
            // Draw points
            canvas.drawCircle(facePoint.x, facePoint.y, 4f, landmarkPaint)
            canvas.drawCircle(modelPoint.x, modelPoint.y, 3f, debugPaint)
        }
    }
    
    /**
     * Render alignment information
     */
    private fun renderAlignmentInfo(canvas: Canvas, alignment: PreciseLandmarkAligner.LandmarkAlignment) {
        val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = 24f
            isAntiAlias = true
        }
        
        val info = "Confidence: ${String.format("%.3f", alignment.confidence)} | " +
                  "Mappings: ${alignment.mappings.size} | " +
                  "Scale: ${String.format("%.2f", alignment.transform.scale)}"
        
        canvas.drawText(info, 20f, screenHeight - 40f, textPaint)
    }
}