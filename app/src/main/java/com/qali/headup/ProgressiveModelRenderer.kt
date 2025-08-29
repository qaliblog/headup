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

/**
 * Progressive model renderer that starts zoomed out and gradually scales up
 * Uses weight point alignment for robust face matching
 */
class ProgressiveModelRenderer {
    
    companion object {
        private const val TAG = "ProgressiveModelRenderer"
        private const val SCALING_ANIMATION_FRAMES = 30 // Frames to reach target scale
        private const val MIN_CONFIDENCE = 0.3f
    }
    
    private var currentModel: Model3D? = null
    private var modelWeightData: FaceWeightData? = null
    private var currentAlignment: WeightPointAlignment? = null
    private var currentScale = 0.1f // Start very small
    private var targetScale = 0.1f
    private var scalingFrameCount = 0
    private var isScalingComplete = false
    
    private var viewportWidth = 1
    private var viewportHeight = 1
    private var screenScaleFactor = 1f
    private var screenOffsetX = 0f
    private var screenOffsetY = 0f
    
    private val weightPointAligner = WeightPointFaceAligner()
    
    /**
     * Set the 3D model with pre-calculated face weight data
     */
    fun setModel(model: Model3D): Boolean {
        return if (model.hasFaceData) {
            currentModel = model
            
            // Calculate weight data from the model's face landmarks
            modelWeightData = weightPointAligner.calculateModelWeightData(model.faceData!!)
            
            // Reset scaling
            currentScale = 0.1f
            targetScale = 0.1f
            scalingFrameCount = 0
            isScalingComplete = false
            currentAlignment = null
            
            if (modelWeightData != null) {
                Log.d(TAG, "Model set with weight data: weightPoint=${modelWeightData!!.weightPoint}")
                true
            } else {
                Log.w(TAG, "Failed to calculate model weight data")
                false
            }
        } else {
            Log.w(TAG, "Model does not have face data - cannot use weight point alignment")
            false
        }
    }
    
    /**
     * Update screen parameters for rendering
     */
    fun updateScreenParameters(
        width: Int,
        height: Int,
        scaleFactor: Float,
        offsetX: Float,
        offsetY: Float
    ) {
        this.viewportWidth = width
        this.viewportHeight = height
        this.screenScaleFactor = scaleFactor
        this.screenOffsetX = offsetX
        this.screenOffsetY = offsetY
    }
    
    /**
     * Update alignment based on detected face landmarks
     */
    fun updateAlignment(realFaceLandmarks: List<NormalizedLandmark>): Boolean {
        val model = currentModel ?: return false
        val modelWeight = modelWeightData ?: return false
        
        return try {
            Log.d(TAG, "Updating alignment with ${realFaceLandmarks.size} real landmarks")
            
            // Calculate real face weight data
            val realWeightData = weightPointAligner.calculateFaceWeightData(realFaceLandmarks)
            
            if (realWeightData == null) {
                Log.w(TAG, "Failed to calculate real face weight data")
                return false
            }
            
            // Update progressive scale
            val newTargetScale = weightPointAligner.calculateProgressiveScale(
                modelWeight, 
                realWeightData, 
                currentScale, 
                isScalingComplete
            )
            
            if (newTargetScale != targetScale) {
                targetScale = newTargetScale
                scalingFrameCount = 0
                Log.d(TAG, "New target scale: $targetScale")
            }
            
            // Animate to target scale
            animateToTargetScale()
            
            // Calculate alignment with current progressive scale
            val alignment = weightPointAligner.alignFaces(
                modelWeight,
                realWeightData,
                currentScale
            )
            
            if (alignment != null && alignment.confidence >= MIN_CONFIDENCE) {
                currentAlignment = alignment
                Log.d(TAG, "Alignment updated: scale=$currentScale, confidence=${alignment.confidence}")
                true
            } else {
                Log.w(TAG, "Poor alignment quality or calculation failed")
                false
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error updating alignment", e)
            false
        }
    }
    
    /**
     * Check if the renderer has a valid model and alignment
     */
    fun hasValidModel(): Boolean = currentModel != null && currentModel?.hasFaceData == true
    
    fun hasValidAlignment(): Boolean = currentAlignment != null && currentAlignment!!.confidence >= MIN_CONFIDENCE
    
    /**
     * Render the progressively scaled model
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
        
        return try {
            Log.d(TAG, "Rendering progressive model: scale=$currentScale, target=$targetScale")
            
            // Transform and render model
            val transformedVertices = transformModelVertices(model, alignment)
            
            // Render with multiple visual elements
            renderProgressiveModel(canvas, paint, model, transformedVertices, alignment)
            
            Log.d(TAG, "Progressive rendering completed successfully")
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "Error rendering progressive model", e)
            false
        }
    }
    
    private fun animateToTargetScale() {
        if (currentScale < targetScale) {
            val scaleDiff = targetScale - currentScale
            val increment = scaleDiff / maxOf(1, SCALING_ANIMATION_FRAMES - scalingFrameCount)
            currentScale = minOf(currentScale + increment, targetScale)
            scalingFrameCount++
            
            if (abs(currentScale - targetScale) < 0.01f) {
                currentScale = targetScale
                isScalingComplete = true
                Log.d(TAG, "Scaling animation completed at scale: $currentScale")
            }
        } else {
            isScalingComplete = true
        }
    }
    
    private fun transformModelVertices(model: Model3D, alignment: WeightPointAlignment): List<PointF> {
        val transformedVertices = mutableListOf<PointF>()
        
        model.vertices.forEach { vertex ->
            // Normalize vertex to [0,1] coordinate space
            val normalizedVertex = normalizeModelVertex(vertex, model)
            
            // Apply weight point alignment transformation
            val transformed = transformVertex(normalizedVertex, alignment.transformMatrix)
            
            // Project to screen coordinates
            val projected = projectToScreen(transformed)
            
            transformedVertices.add(projected)
        }
        
        return transformedVertices
    }
    
    private fun normalizeModelVertex(vertex: Vertex3D, model: Model3D): Vertex3D {
        val bounds = model.boundingBox
        val width = bounds.second.x - bounds.first.x
        val height = bounds.second.y - bounds.first.y
        val depth = bounds.second.z - bounds.first.z
        
        val normalizedX = if (width > 0) (vertex.x - bounds.first.x) / width else 0.5f
        val normalizedY = if (height > 0) (vertex.y - bounds.first.y) / height else 0.5f
        val normalizedZ = if (depth > 0) (vertex.z - bounds.first.z) / depth else 0.5f
        
        return Vertex3D(normalizedX, normalizedY, normalizedZ)
    }
    
    private fun transformVertex(vertex: Vertex3D, matrix: FloatArray): Vertex3D {
        val x = vertex.x * matrix[0] + vertex.y * matrix[4] + vertex.z * matrix[8] + matrix[12]
        val y = vertex.x * matrix[1] + vertex.y * matrix[5] + vertex.z * matrix[9] + matrix[13]
        val z = vertex.x * matrix[2] + vertex.y * matrix[6] + vertex.z * matrix[10] + matrix[14]
        
        return Vertex3D(x, y, z)
    }
    
    private fun projectToScreen(vertex: Vertex3D): PointF {
        val screenX = vertex.x * viewportWidth * screenScaleFactor + screenOffsetX
        val screenY = vertex.y * viewportHeight * screenScaleFactor + screenOffsetY
        
        return PointF(screenX, screenY)
    }
    
    private fun renderProgressiveModel(
        canvas: Canvas,
        paint: Paint,
        model: Model3D,
        transformedVertices: List<PointF>,
        alignment: WeightPointAlignment
    ) {
        val originalColor = paint.color
        val originalStrokeWidth = paint.strokeWidth
        
        try {
            // 1. Render weight point as large marker
            renderWeightPointMarker(canvas, alignment)
            
            // 2. Render model wireframe
            renderWireframe(canvas, paint, model, transformedVertices)
            
            // 3. Render scaling progress indicator
            renderScalingProgress(canvas, paint)
            
            // 4. Render face direction vector
            renderDirectionVector(canvas, alignment)
            
        } finally {
            paint.color = originalColor
            paint.strokeWidth = originalStrokeWidth
        }
    }
    
    private fun renderWeightPointMarker(canvas: Canvas, alignment: WeightPointAlignment) {
        // Calculate weight point screen position
        val weightScreenX = alignment.translation.x * viewportWidth * screenScaleFactor + screenOffsetX
        val weightScreenY = alignment.translation.y * viewportHeight * screenScaleFactor + screenOffsetY
        
        val markerPaint = Paint().apply {
            color = Color.CYAN
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        
        // Draw weight point as cyan circle
        canvas.drawCircle(weightScreenX, weightScreenY, 12f, markerPaint)
        
        // Draw cross through weight point
        val crossPaint = Paint().apply {
            color = Color.WHITE
            strokeWidth = 2f
            style = Paint.Style.STROKE
        }
        
        canvas.drawLine(weightScreenX - 15f, weightScreenY, weightScreenX + 15f, weightScreenY, crossPaint)
        canvas.drawLine(weightScreenX, weightScreenY - 15f, weightScreenX, weightScreenY + 15f, crossPaint)
    }
    
    private fun renderWireframe(
        canvas: Canvas,
        paint: Paint,
        model: Model3D,
        transformedVertices: List<PointF>
    ) {
        // IMMEDIATE DEBUG: Draw large visible markers first
        val debugPaint = Paint().apply {
            isAntiAlias = true
        }
        
        // 1. Draw large red cross at screen center to prove rendering works
        debugPaint.color = Color.RED
        debugPaint.strokeWidth = 8f
        debugPaint.style = Paint.Style.STROKE
        val centerX = viewportWidth / 2f
        val centerY = viewportHeight / 2f
        canvas.drawLine(centerX - 100, centerY, centerX + 100, centerY, debugPaint)
        canvas.drawLine(centerX, centerY - 100, centerX, centerY + 100, debugPaint)
        
        // 2. Draw large yellow circles for first few vertices
        debugPaint.color = Color.YELLOW
        debugPaint.style = Paint.Style.FILL
        transformedVertices.take(5).forEachIndexed { index, vertex ->
            canvas.drawCircle(vertex.x, vertex.y, 20f, debugPaint)
            
            // Draw index number
            debugPaint.color = Color.BLACK
            debugPaint.textSize = 24f
            canvas.drawText("$index", vertex.x - 8, vertex.y + 8, debugPaint)
            debugPaint.color = Color.YELLOW
        }
        
        // 3. Draw status text
        debugPaint.color = Color.WHITE
        debugPaint.style = Paint.Style.FILL
        debugPaint.textSize = 20f
        canvas.drawText("Progressive Renderer ACTIVE", 50f, 50f, debugPaint)
        canvas.drawText("Vertices: ${transformedVertices.size}", 50f, 80f, debugPaint)
        canvas.drawText("Scale: ${String.format("%.2f", currentScale)}", 50f, 110f, debugPaint)
        canvas.drawText("Target: ${String.format("%.2f", targetScale)}", 50f, 140f, debugPaint)
        
        // 4. Draw bounding box of all vertices
        if (transformedVertices.isNotEmpty()) {
            val minX = transformedVertices.minOf { it.x }
            val maxX = transformedVertices.maxOf { it.x }
            val minY = transformedVertices.minOf { it.y }
            val maxY = transformedVertices.maxOf { it.y }
            
            debugPaint.color = Color.MAGENTA
            debugPaint.strokeWidth = 4f
            debugPaint.style = Paint.Style.STROKE
            canvas.drawRect(minX, minY, maxX, maxY, debugPaint)
            
            debugPaint.color = Color.WHITE
            debugPaint.style = Paint.Style.FILL
            canvas.drawText("Bounds: ${String.format("%.0f", maxX - minX)}x${String.format("%.0f", maxY - minY)}", 50f, 170f, debugPaint)
        }
        
        // 5. Original wireframe rendering (make it highly visible)
        paint.color = Color.GREEN
        paint.strokeWidth = 5f // Thicker lines
        paint.style = Paint.Style.STROKE
        paint.isAntiAlias = true
        
        // Only render first 50 faces to avoid overwhelming display
        model.faces.take(50).forEach { face ->
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
    }
    
    private fun renderScalingProgress(canvas: Canvas, paint: Paint) {
        // Draw scaling progress bar
        val progressBarX = 50f
        val progressBarY = 100f
        val progressBarWidth = 200f
        val progressBarHeight = 10f
        
        // Background
        val bgPaint = Paint().apply {
            color = Color.GRAY
            style = Paint.Style.FILL
        }
        canvas.drawRect(progressBarX, progressBarY, progressBarX + progressBarWidth, progressBarY + progressBarHeight, bgPaint)
        
        // Progress
        val progress = currentScale / targetScale
        val progressPaint = Paint().apply {
            color = Color.YELLOW
            style = Paint.Style.FILL
        }
        canvas.drawRect(
            progressBarX, 
            progressBarY, 
            progressBarX + progressBarWidth * progress, 
            progressBarY + progressBarHeight, 
            progressPaint
        )
    }
    
    private fun renderDirectionVector(canvas: Canvas, alignment: WeightPointAlignment) {
        // This could render the head direction vector for debugging
        // Implementation could be added here if needed
    }
    
    fun getAlignmentInfo(): String? {
        val alignment = currentAlignment ?: return null
        
        return """
            Current Scale: $currentScale / $targetScale
            Translation: ${alignment.translation}
            Rotation: ${alignment.rotation}
            Confidence: ${alignment.confidence}
            Scaling Complete: $isScalingComplete
        """.trimIndent()
    }
    
    fun cleanup() {
        currentModel = null
        modelWeightData = null
        currentAlignment = null
    }
}