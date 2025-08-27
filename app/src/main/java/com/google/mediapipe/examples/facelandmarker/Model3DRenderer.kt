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
import kotlin.math.*

/**
 * Simple 3D renderer for overlaying 3D models on the camera view
 */
class Model3DRenderer {
    
    companion object {
        private const val TAG = "Model3DRenderer"
    }
    
    private var currentModel: Model3D? = null
    private var currentHeadPose: HeadPose? = null
    private var viewportWidth = 1
    private var viewportHeight = 1
    
    // Face-based scaling parameters
    private var faceWidth = 1f
    private var faceHeight = 1f
    private var scaleFactor = 1f
    private var offsetX = 0f
    private var offsetY = 0f
    
    // Camera parameters for projection
    private val focalLength = 1000f
    private val principalPointX = 0.5f
    private val principalPointY = 0.5f
    
    /**
     * Set the 3D model to render
     */
    fun setModel(model: Model3D) {
        currentModel = model
        Log.d(TAG, "Model set: ${model.vertices.size} vertices, ${model.faces.size} faces")
    }
    
    /**
     * Update head pose for alignment
     */
    fun updateHeadPose(headPose: HeadPose) {
        currentHeadPose = headPose
    }
    
    /**
     * Update rendering parameters based on face landmarks and screen coordinates
     */
    fun updateFaceParameters(
        faceWidth: Float, 
        faceHeight: Float, 
        scaleFactor: Float, 
        offsetX: Float, 
        offsetY: Float
    ) {
        this.faceWidth = faceWidth
        this.faceHeight = faceHeight
        this.scaleFactor = scaleFactor
        this.offsetX = offsetX
        this.offsetY = offsetY
        Log.d(TAG, "Face parameters updated: width=$faceWidth, height=$faceHeight, scale=$scaleFactor")
    }
    
    /**
     * Set viewport dimensions
     */
    fun setViewport(width: Int, height: Int) {
        viewportWidth = width
        viewportHeight = height
    }
    
    /**
     * Render the 3D model onto the canvas
     */
    fun render(canvas: Canvas, paint: Paint) {
        val model = currentModel ?: run {
            Log.w(TAG, "Cannot render: no current model")
            return
        }
        val headPose = currentHeadPose ?: run {
            Log.w(TAG, "Cannot render: no head pose")
            return
        }
        
        try {
            Log.d(TAG, "Rendering model with ${model.vertices.size} vertices, ${model.faces.size} faces")
            
            // Calculate transformation matrix
            val transformMatrix = calculateTransformMatrix(headPose)
            
            // Transform and project vertices
            val projectedVertices = mutableListOf<PointF>()
            model.vertices.forEach { vertex ->
                val transformed = transformVertex(vertex, transformMatrix)
                val projected = projectVertex(transformed)
                projectedVertices.add(projected)
            }
            
            Log.d(TAG, "Projected ${projectedVertices.size} vertices")
            
            // Render wireframe
            renderWireframe(canvas, paint, model.faces, projectedVertices)
            
            Log.d(TAG, "Wireframe rendering completed")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error rendering 3D model", e)
        }
    }
    
    private fun calculateTransformMatrix(headPose: HeadPose): FloatArray {
        // Get the base transformation matrix from head pose
        val baseMatrix = headPose.getTransformationMatrix()
        
        // Calculate face-based scaling - model should fit the detected face size
        val faceBasedScale = max(faceWidth, faceHeight) * 0.8f // Scale to 80% of face size
        val scale = if (faceBasedScale > 0.01f) faceBasedScale else 0.2f // Fallback to fixed scale
        
        // No offset - position model directly at face center
        val offsetY = 0f
        
        // Create final transformation matrix
        val matrix = FloatArray(16)
        
        // Apply scale to rotation part
        for (i in 0..2) {
            for (j in 0..2) {
                matrix[i * 4 + j] = baseMatrix[i * 4 + j] * scale
            }
        }
        
        // Copy translation and apply offset
        matrix[12] = baseMatrix[12] // X translation
        matrix[13] = baseMatrix[13] + offsetY // Y translation with offset
        matrix[14] = baseMatrix[14] // Z translation
        matrix[15] = 1f
        
        return matrix
    }
    
    private fun transformVertex(vertex: Vertex3D, matrix: FloatArray): Vertex3D {
        val x = vertex.x * matrix[0] + vertex.y * matrix[4] + vertex.z * matrix[8] + matrix[12]
        val y = vertex.x * matrix[1] + vertex.y * matrix[5] + vertex.z * matrix[9] + matrix[13]
        val z = vertex.x * matrix[2] + vertex.y * matrix[6] + vertex.z * matrix[10] + matrix[14]
        
        return Vertex3D(x, y, z)
    }
    
    private fun projectVertex(vertex: Vertex3D): PointF {
        // Use face-based coordinate system to align with MediaPipe coordinates
        // MediaPipe coordinates are normalized (0-1), so we use the same system
        
        // Apply scale factor and offsets from OverlayView
        val screenX = (vertex.x * viewportWidth * scaleFactor) + offsetX
        val screenY = (vertex.y * viewportHeight * scaleFactor) + offsetY
        
        return PointF(screenX, screenY)
    }
    
    private fun renderWireframe(
        canvas: Canvas,
        paint: Paint,
        faces: List<Face3D>,
        projectedVertices: List<PointF>
    ) {
        // Set up paint for wireframe
        val wireframePaint = Paint(paint).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = Color.CYAN
            alpha = 180
        }
        
        // Draw each face as lines
        faces.forEach { face ->
            if (face.v1 < projectedVertices.size && 
                face.v2 < projectedVertices.size && 
                face.v3 < projectedVertices.size) {
                
                val p1 = projectedVertices[face.v1]
                val p2 = projectedVertices[face.v2]
                val p3 = projectedVertices[face.v3]
                
                // Draw triangle edges
                canvas.drawLine(p1.x, p1.y, p2.x, p2.y, wireframePaint)
                canvas.drawLine(p2.x, p2.y, p3.x, p3.y, wireframePaint)
                canvas.drawLine(p3.x, p3.y, p1.x, p1.y, wireframePaint)
            }
        }
    }
    
    /**
     * Render filled faces (more advanced rendering)
     */
    private fun renderFilled(
        canvas: Canvas,
        paint: Paint,
        faces: List<Face3D>,
        projectedVertices: List<PointF>,
        transformedVertices: List<Vertex3D>
    ) {
        // Set up paint for filled faces
        val fillPaint = Paint(paint).apply {
            style = Paint.Style.FILL
            color = Color.argb(120, 0, 255, 255) // Semi-transparent cyan
        }
        
        // Sort faces by depth (simple painter's algorithm)
        val sortedFaces = faces.mapIndexed { index, face ->
            val avgZ = if (face.v1 < transformedVertices.size && 
                          face.v2 < transformedVertices.size && 
                          face.v3 < transformedVertices.size) {
                (transformedVertices[face.v1].z + 
                 transformedVertices[face.v2].z + 
                 transformedVertices[face.v3].z) / 3f
            } else 0f
            Pair(face, avgZ)
        }.sortedByDescending { it.second }
        
        // Draw faces back to front
        sortedFaces.forEach { (face, _) ->
            if (face.v1 < projectedVertices.size && 
                face.v2 < projectedVertices.size && 
                face.v3 < projectedVertices.size) {
                
                val p1 = projectedVertices[face.v1]
                val p2 = projectedVertices[face.v2]
                val p3 = projectedVertices[face.v3]
                
                val path = Path().apply {
                    moveTo(p1.x, p1.y)
                    lineTo(p2.x, p2.y)
                    lineTo(p3.x, p3.y)
                    close()
                }
                
                canvas.drawPath(path, fillPaint)
            }
        }
    }
    
    /**
     * Clear the current model
     */
    fun clearModel() {
        currentModel = null
        Log.d(TAG, "Model cleared")
    }
    
    /**
     * Check if a model is currently loaded
     */
    fun hasModel(): Boolean = currentModel != null
}