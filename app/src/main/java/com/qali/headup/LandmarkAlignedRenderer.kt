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

import android.graphics.*
import android.util.Log
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.*

// Simple data classes for alignment functionality
data class FaceAlignmentTransform(
    val scale: Triple<Float, Float, Float> = Triple(1f, 1f, 1f),
    val translation: Triple<Float, Float, Float> = Triple(0f, 0f, 0f),
    val rotation: Triple<Float, Float, Float> = Triple(0f, 0f, 0f),
    val transformMatrix: FloatArray = FloatArray(16),
    val alignmentScore: Float = 0f,
    val correspondences: List<LandmarkCorrespondence> = emptyList()
) {
    // Helper properties for easier access
    val x: Float get() = translation.first
    val y: Float get() = translation.second
    val z: Float get() = translation.third
}

data class LandmarkCorrespondence(
    val modelIndex: Int,
    val realIndex: Int,
    val confidence: Float,
    val modelVertex: Triple<Float, Float, Float> = Triple(0f, 0f, 0f),
    val realLandmark: Triple<Float, Float, Float> = Triple(0f, 0f, 0f)
) {
    // Helper properties for easier access
    val x: Float get() = realLandmark.first
    val y: Float get() = realLandmark.second
    val z: Float get() = realLandmark.third
}

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
            
            val alignmentResult = landmarkMatcher.findBestAlignment(
                faceData.landmarks ?: emptyList(),
                realFaceLandmarks
            )
            
            val alignment = FaceAlignmentTransform(
                scale = Triple(alignmentResult.scale, alignmentResult.scaleX, alignmentResult.scaleY),
                translation = Triple(alignmentResult.offsetX, alignmentResult.offsetY, alignmentResult.offsetZ),
                rotation = Triple(alignmentResult.rotationX, alignmentResult.rotationY, alignmentResult.rotationZ),
                alignmentScore = alignmentResult.confidence
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
            Log.d(TAG, "Alignment scale: ${alignment.scale}, score: ${alignment.alignmentScore}")
            Log.d(TAG, "Viewport: ${viewportWidth}x${viewportHeight}, scaleFactor: $scaleFactor, offset: ($offsetX, $offsetY)")
            
            // Transform all vertices using the alignment
            val transformedVertices = mutableListOf<PointF>()
            
            model.vertices.forEachIndexed { index, vertex ->
                // Normalize vertex first (to match the coordinate system used in alignment calculation)
                val normalizedVertex = normalizeModelVertex(vertex, model)
                
                // Apply landmark-based transformation
                val transformed = transformVertex(normalizedVertex, alignment.transformMatrix)
                
                // Project to screen coordinates
                val projected = projectToScreen(transformed)
                
                if (index < 3) { // Log first few vertices for debugging
                    Log.d(TAG, "Vertex $index: $vertex -> transformed: $transformed -> screen: $projected")
                    Log.d(TAG, "  Transformation matrix first row: [${alignment.transformMatrix[0]}, ${alignment.transformMatrix[1]}, ${alignment.transformMatrix[2]}, ${alignment.transformMatrix[3]}]")
                }
                
                transformedVertices.add(projected)
            }
            
            // Render the transformed model
            renderTransformedModel(canvas, paint, model, transformedVertices, alignment)
            
            // TEMPORARY: Simple fallback rendering for debugging
            renderSimplePoints(canvas, transformedVertices)
            
            // EMERGENCY: Draw a big circle at face center to verify coordinate system
            renderEmergencyMarker(canvas, alignment)
            
            Log.d(TAG, "Landmark-aligned rendering completed successfully")
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "Error rendering landmark-aligned model", e)
            false
        }
    }
    
    /**
     * Normalize model vertex to [0,1] coordinate system to match MediaPipe landmarks
     */
    private fun normalizeModelVertex(vertex: Vertex3D, model: Model3D): Vertex3D {
        val bounds = model.boundingBox
        val width = bounds.second.x - bounds.first.x
        val height = bounds.second.y - bounds.first.y
        val depth = bounds.second.z - bounds.first.z
        
        // Normalize to [0,1] based on model's bounding box
        val normalizedX = if (width > 0) (vertex.x - bounds.first.x) / width else 0.5f
        val normalizedY = if (height > 0) (vertex.y - bounds.first.y) / height else 0.5f
        val normalizedZ = if (depth > 0) (vertex.z - bounds.first.z) / depth else 0.5f
        
        return Vertex3D(normalizedX, normalizedY, normalizedZ)
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
        // Convert normalized coordinates [0,1] to screen coordinates
        // The transformation matrix already handles the face alignment,
        // now we just need to map to screen space
        val screenX = vertex.x * viewportWidth * scaleFactor + offsetX
        val screenY = vertex.y * viewportHeight * scaleFactor + offsetY
        
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
            // Render main model wireframe with enhanced visibility
            paint.color = Color.YELLOW // More visible color
            paint.strokeWidth = 4f // Thicker lines
            paint.style = Paint.Style.STROKE
            paint.isAntiAlias = true
            
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
            
            // Render model bounding box for debugging
            renderModelBoundingBox(canvas, transformedVertices, paint)
            
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
     * Render model bounding box for debugging visibility
     */
    private fun renderModelBoundingBox(
        canvas: Canvas,
        transformedVertices: List<PointF>,
        paint: Paint
    ) {
        if (transformedVertices.isEmpty()) return
        
        // Find bounding box of transformed vertices
        var minX = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE
        var minY = Float.MAX_VALUE
        var maxY = Float.MIN_VALUE
        
        transformedVertices.forEach { point ->
            minX = minOf(minX, point.x)
            maxX = maxOf(maxX, point.x)
            minY = minOf(minY, point.y)
            maxY = maxOf(maxY, point.y)
        }
        
        // Draw bounding box
        val boundingBoxPaint = Paint(paint).apply {
            color = Color.RED
            strokeWidth = 3f
            style = Paint.Style.STROKE
        }
        
        canvas.drawRect(minX, minY, maxX, maxY, boundingBoxPaint)
        
        // Draw center point
        val centerX = (minX + maxX) / 2f
        val centerY = (minY + maxY) / 2f
        val centerPaint = Paint(paint).apply {
            color = Color.RED
            style = Paint.Style.FILL
        }
        canvas.drawCircle(centerX, centerY, 8f, centerPaint)
        
        Log.d(TAG, "Model bounding box: ($minX, $minY) to ($maxX, $maxY), center: ($centerX, $centerY)")
    }
    
    /**
     * Simple fallback rendering - just draw vertices as large circles
     */
    private fun renderSimplePoints(canvas: Canvas, transformedVertices: List<PointF>) {
        val pointPaint = Paint().apply {
            color = Color.MAGENTA
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        
        Log.d(TAG, "Rendering ${transformedVertices.size} simple points")
        
        transformedVertices.forEachIndexed { index, point ->
            if (index % 10 == 0) { // Only draw every 10th point to avoid clutter
                canvas.drawCircle(point.x, point.y, 8f, pointPaint)
                
                if (index < 5) { // Log first few points
                    Log.d(TAG, "Point $index at (${point.x}, ${point.y})")
                }
            }
        }
    }
    
    /**
     * EMERGENCY: Draw a big visible marker at the calculated face center
     */
    private fun renderEmergencyMarker(canvas: Canvas, alignment: FaceAlignmentTransform) {
        // Get face center from translation
        val centerX = alignment.x * viewportWidth * scaleFactor + offsetX
        val centerY = alignment.y * viewportHeight * scaleFactor + offsetY
        
        val emergencyPaint = Paint().apply {
            color = Color.GREEN
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        
        // Draw a big green circle at the face center
        canvas.drawCircle(centerX, centerY, 30f, emergencyPaint)
        
        // Draw cross lines through center
        val linePaint = Paint().apply {
            color = Color.WHITE
            strokeWidth = 3f
            style = Paint.Style.STROKE
        }
        
        canvas.drawLine(centerX - 40f, centerY, centerX + 40f, centerY, linePaint)
        canvas.drawLine(centerX, centerY - 40f, centerX, centerY + 40f, linePaint)
        
        Log.d(TAG, "Emergency marker at screen coords: ($centerX, $centerY)")
        Log.d(TAG, "Translation from alignment: ${alignment.translation}")
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
            val modelVertex = Vertex3D(correspondence.modelVertex.first, correspondence.modelVertex.second, correspondence.modelVertex.third)
            val modelScreen = projectToScreen(modelVertex)
            val realScreen = PointF(
                correspondence.x * scaleFactor + offsetX,
                correspondence.y * scaleFactor + offsetY
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
        return true // Always show for debugging purposes
    }
    
    /**
     * Get alignment information for debugging
     */
    fun getAlignmentInfo(): String? {
        val alignment = currentAlignment ?: return null
        
        return """
            Alignment Score: ${alignment.alignmentScore}
            Correspondences: ${alignment.correspondences.size}
            Translation: (${alignment.translation.first}, ${alignment.translation.second}, ${alignment.translation.third})
            Rotation: (${alignment.rotation.first}, ${alignment.rotation.second}, ${alignment.rotation.third})
            Scale: (${alignment.scale.first}, ${alignment.scale.second}, ${alignment.scale.third})
        """.trimIndent()
    }
    
    fun cleanup() {
        currentModel = null
        currentAlignment = null
    }
    
    /**
     * Calculate face alignment transformation
     */
    private fun calculateFaceAlignment(
        modelLandmarks: List<NormalizedLandmark>,
        realLandmarks: List<NormalizedLandmark>
    ): FaceAlignmentTransform {
        // Simple implementation for compilation
        val correspondences = listOf<LandmarkCorrespondence>()
        return FaceAlignmentTransform(
            scale = Triple(1f, 1f, 1f),
            alignmentScore = 0.5f,
            correspondences = correspondences
        )
    }
}