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

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.*

/**
 * Custom view for previewing 3D models in the manual adjustment interface
 * Shows the model with applied transformations for real-time feedback
 */
class Model3DPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // Model data
    private var currentModel: Model3D? = null
    private var manualAdjustments: ManualAdjustmentData? = null
    
    // Rendering
    private val materializedRenderer = MaterializedModelRenderer()
    private val preciseRenderer = PreciseModelRenderer()
    private var useFilledFaces = true
    
    // Paint objects
    private val modelPaint = Paint().apply {
        color = Color.CYAN
        strokeWidth = 2f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }
    
    private val filledPaint = Paint().apply {
        color = Color.argb(200, 100, 200, 255) // More opaque blue-cyan
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    
    private val backgroundPaint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.FILL
    }
    
    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 32f
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }
    
    // View parameters
    private var centerX = 0f
    private var centerY = 0f
    private var viewScale = 1f
    
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        centerX = w / 2f
        centerY = h / 2f
        viewScale = minOf(w, h) / 400f // Base scale
        
        // Update renderer parameters
        materializedRenderer.updateScreenParameters(
            width = w, height = h, scale = viewScale, offsetX = 0f, offsetY = 0f
        )
        preciseRenderer.updateScreenParameters(
            width = w, height = h, scale = viewScale, offsetX = 0f, offsetY = 0f
        )
    }
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        // Clear background
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)
        
        val model = currentModel
        if (model == null) {
            // Show "no model" message
            canvas.drawText(
                "Load a model to preview",
                centerX, centerY,
                textPaint
            )
            return
        }
        
        // Apply manual adjustments and render model
        if (renderModelWithAdjustments(canvas, model)) {
            // Model rendered successfully
            drawModelInfo(canvas, model)
        } else {
            // Fallback to basic wireframe rendering
            renderBasicWireframe(canvas, model)
        }
    }
    
    /**
     * Render the model with applied manual adjustments
     */
    private fun renderModelWithAdjustments(canvas: Canvas, model: Model3D): Boolean {
        val adjustments = manualAdjustments
        
        try {
            if (useFilledFaces && materializedRenderer.hasValidModel()) {
                // Apply manual adjustments to materialized renderer
                if (adjustments != null) {
                    applyAdjustmentsToRenderer(materializedRenderer, adjustments)
                }
                return materializedRenderer.render(canvas, filledPaint)
            } else if (preciseRenderer.hasValidModel()) {
                // Apply manual adjustments to precise renderer
                if (adjustments != null) {
                    applyAdjustmentsToRenderer(preciseRenderer, adjustments)
                }
                return preciseRenderer.render(canvas, modelPaint)
            }
        } catch (e: Exception) {
            Log.w("Model3DPreviewView", "Advanced renderer failed, falling back to basic rendering", e)
        }
        
        // Always fall back to basic rendering if advanced renderers fail or adjustments are present
        return false
    }
    
    /**
     * Apply manual adjustments to a renderer
     */
    private fun applyAdjustmentsToRenderer(renderer: Any, adjustments: ManualAdjustmentData) {
        // Apply transform to renderer (implementation depends on renderer type)
        when (renderer) {
            is MaterializedModelRenderer -> {
                // Apply manual adjustments to materialized renderer
                renderer.setManualAdjustments(adjustments)
            }
            is PreciseModelRenderer -> {
                // For precise renderer, we'll use the basic fallback since it's more reliable
                // The precise renderer doesn't support manual adjustments yet
                Log.d("Model3DPreviewView", "Using basic rendering with manual adjustments")
            }
        }
    }
    
    /**
     * Create a transformation matrix from manual adjustments
     */
    private fun createTransformFromAdjustments(adjustments: ManualAdjustmentData): Matrix {
        val matrix = Matrix()
        
        // Apply transformations in order: scale, rotate, translate
        matrix.postScale(adjustments.scaleX, adjustments.scaleY, centerX, centerY)
        matrix.postRotate(adjustments.rotationZ, centerX, centerY) // 2D rotation for preview
        matrix.postTranslate(adjustments.offsetX * 100, adjustments.offsetY * 100)
        
        return matrix
    }
    
    /**
     * Render basic wireframe as fallback
     */
    private fun renderBasicWireframe(canvas: Canvas, model: Model3D) {
        val adjustments = manualAdjustments
        
        // Calculate base scale to fit model in view
        val modelBounds = calculateModelBounds(model)
        val modelWidth = modelBounds.second.x - modelBounds.first.x
        val modelHeight = modelBounds.second.y - modelBounds.first.y
        val maxDimension = maxOf(modelWidth, modelHeight)
        
        val baseScale = if (maxDimension > 0) minOf(width, height) * 0.3f / maxDimension else 100f
        
        // Apply manual adjustments
        val scale = baseScale * (adjustments?.scale ?: 1f)
        val scaleX = scale * (adjustments?.scaleX ?: 1f)
        val scaleY = scale * (adjustments?.scaleY ?: 1f)
        val offsetX = (adjustments?.offsetX ?: 0f) * 100
        val offsetY = (adjustments?.offsetY ?: 0f) * 100
        val rotation = adjustments?.rotationZ ?: 0f
        
        // Draw faces
        for (face in model.faces) {
            val vertices = listOf(
                getVertex(model, face.v1),
                getVertex(model, face.v2),
                getVertex(model, face.v3)
            )
            
            val transformedVertices = vertices.map { vertex ->
                transformVertex(vertex, scaleX, scaleY, offsetX, offsetY, rotation)
            }
            
            if (useFilledFaces && transformedVertices.size >= 3) {
                // Draw filled triangle first (as base)
                drawTriangle(canvas, transformedVertices, filledPaint)
                // Draw wireframe on top for definition
                drawTriangle(canvas, transformedVertices, modelPaint)
            } else {
                // Draw wireframe only
                drawTriangle(canvas, transformedVertices, modelPaint)
            }
        }
    }
    
    /**
     * Transform a vertex with manual adjustments
     */
    private fun transformVertex(
        vertex: Vertex3D,
        scaleX: Float,
        scaleY: Float,
        offsetX: Float,
        offsetY: Float,
        rotation: Float
    ): PointF {
        // Scale vertex
        var x = vertex.x * scaleX
        var y = vertex.y * scaleY
        
        // Apply rotation
        val radians = Math.toRadians(rotation.toDouble())
        val cos = cos(radians).toFloat()
        val sin = sin(radians).toFloat()
        
        val rotatedX = x * cos - y * sin
        val rotatedY = x * sin + y * cos
        
        // Translate to screen coordinates
        return PointF(
            centerX + rotatedX + offsetX,
            centerY + rotatedY + offsetY
        )
    }
    
    /**
     * Get vertex by index from model
     */
    private fun getVertex(model: Model3D, index: Int): Vertex3D {
        return if (index > 0 && index <= model.vertices.size) {
            model.vertices[index - 1] // OBJ indices are 1-based
        } else {
            Vertex3D(0f, 0f, 0f)
        }
    }
    
    /**
     * Draw a triangle on canvas
     */
    private fun drawTriangle(canvas: Canvas, vertices: List<PointF>, paint: Paint) {
        if (vertices.size >= 3) {
            val path = Path().apply {
                moveTo(vertices[0].x, vertices[0].y)
                lineTo(vertices[1].x, vertices[1].y)
                lineTo(vertices[2].x, vertices[2].y)
                close()
            }
            canvas.drawPath(path, paint)
        }
    }
    
    /**
     * Calculate model bounding box
     */
    private fun calculateModelBounds(model: Model3D): Pair<Vertex3D, Vertex3D> {
        if (model.vertices.isEmpty()) {
            return Pair(Vertex3D(0f, 0f, 0f), Vertex3D(1f, 1f, 1f))
        }
        
        var minX = model.vertices[0].x
        var maxX = model.vertices[0].x
        var minY = model.vertices[0].y
        var maxY = model.vertices[0].y
        var minZ = model.vertices[0].z
        var maxZ = model.vertices[0].z
        
        for (vertex in model.vertices) {
            minX = minOf(minX, vertex.x)
            maxX = maxOf(maxX, vertex.x)
            minY = minOf(minY, vertex.y)
            maxY = maxOf(maxY, vertex.y)
            minZ = minOf(minZ, vertex.z)
            maxZ = maxOf(maxZ, vertex.z)
        }
        
        return Pair(
            Vertex3D(minX, minY, minZ),
            Vertex3D(maxX, maxY, maxZ)
        )
    }
    
    /**
     * Draw model information overlay and landmarks if available
     */
    private fun drawModelInfo(canvas: Canvas, model: Model3D) {
        val adjustments = manualAdjustments
        if (adjustments != null) {
            val info = "Scale: ${String.format("%.2f", adjustments.scale)} " +
                      "Pos: (${String.format("%.2f", adjustments.offsetX)}, ${String.format("%.2f", adjustments.offsetY)})"
            
            val infoPaint = Paint().apply {
                color = Color.WHITE
                textSize = 24f
                isAntiAlias = true
            }
            
            canvas.drawText(info, 20f, height - 20f, infoPaint)
        }
        
        // Draw landmarks if face data is available
        if (model.hasFaceData && model.faceData?.landmarks != null) {
            drawLandmarkPreview(canvas, model)
        }
    }
    
    /**
     * Draw landmark preview on the model
     */
    private fun drawLandmarkPreview(canvas: Canvas, model: Model3D) {
        val landmarks = model.faceData?.landmarks ?: return
        val adjustments = manualAdjustments
        
        // Calculate base scale to fit model in view
        val modelBounds = calculateModelBounds(model)
        val modelWidth = modelBounds.second.x - modelBounds.first.x
        val modelHeight = modelBounds.second.y - modelBounds.first.y
        val maxDimension = maxOf(modelWidth, modelHeight)
        
        val baseScale = if (maxDimension > 0) minOf(width, height) * 0.3f / maxDimension else 100f
        
        // Apply manual adjustments
        val scale = baseScale * (adjustments?.scale ?: 1f)
        val scaleX = scale * (adjustments?.scaleX ?: 1f)
        val scaleY = scale * (adjustments?.scaleY ?: 1f)
        val offsetX = (adjustments?.offsetX ?: 0f) * 100
        val offsetY = (adjustments?.offsetY ?: 0f) * 100
        val rotation = adjustments?.rotationZ ?: 0f
        
        val landmarkPaint = Paint().apply {
            color = Color.RED
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        
        // Draw each landmark as a small circle
        for (landmark in landmarks) {
            val vertex = Vertex3D(landmark.x, landmark.y, landmark.z)
            val transformedPoint = transformVertex(vertex, scaleX, scaleY, offsetX, offsetY, rotation)
            canvas.drawCircle(transformedPoint.x, transformedPoint.y, 3f, landmarkPaint)
        }
    }
    
    /**
     * Set the 3D model to display
     */
    fun setModel(model: Model3D?) {
        currentModel = model
        
        // Set model in renderers
        model?.let {
            materializedRenderer.setModel(it)
            preciseRenderer.setModel(it)
        }
        
        invalidate()
    }
    
    /**
     * Apply manual adjustments to the preview
     */
    fun applyAdjustments(adjustments: ManualAdjustmentData) {
        manualAdjustments = adjustments
        invalidate()
    }
    
    /**
     * Toggle between wireframe and filled rendering
     */
    fun setRenderingMode(filled: Boolean) {
        useFilledFaces = filled
        invalidate()
    }
    
    /**
     * Clear the current model
     */
    fun clearModel() {
        currentModel = null
        manualAdjustments = null
        invalidate()
    }
}