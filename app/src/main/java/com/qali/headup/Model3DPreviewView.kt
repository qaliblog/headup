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

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
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
    
    // Touch control variables
    private var touchScale = 1f
    private var touchRotationX = 0f
    private var touchRotationY = 0f
    private var touchRotationZ = 0f
    
    // Gesture detection
    private lateinit var scaleGestureDetector: ScaleGestureDetector
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    
    // Touch callback for external updates
    private var onAdjustmentChangedListener: ((ManualAdjustmentData) -> Unit)? = null
    
    init {
        // Initialize gesture detectors
        scaleGestureDetector = ScaleGestureDetector(context, ScaleListener())
    }
    
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
        
        // Always use basic wireframe rendering for now to ensure touch controls work
        renderBasicWireframe(canvas, model)
        drawModelInfo(canvas, model)
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
        
        // Draw faces with depth-based shading
        for (face in model.faces) {
            val vertices = listOf(
                getVertex(model, face.v1),
                getVertex(model, face.v2),
                getVertex(model, face.v3)
            )
            
            // Apply 3D transformations to vertices first to get proper depth
            val transformed3DVertices = vertices.map { vertex ->
                apply3DTransformations(vertex, scaleX, scaleY, 
                    adjustments?.rotationX ?: 0f,
                    adjustments?.rotationY ?: 0f, 
                    adjustments?.rotationZ ?: 0f)
            }
            
            // Calculate average Z depth after 3D transformations for proper depth sorting
            val avgZ = transformed3DVertices.map { it.z }.average().toFloat()
            
            // Apply perspective projection for 2D display
            val transformedVertices = transformed3DVertices.map { vertex ->
                applyPerspectiveProjection(vertex, offsetX, offsetY)
            }
            
            // Calculate face normal for back-face culling (optional - makes it more 3D-like)
            val normal = calculateFaceNormal(transformed3DVertices)
            val facingViewer = normal.z > 0 // Simple check if face is facing towards viewer
            
            // Create depth-based paint for this face
            val depthFactor = (avgZ + 300f) / 600f // Normalize depth to 0-1 range  
            val clampedDepth = depthFactor.coerceIn(0.2f, 1.0f) // Keep minimum visibility
            
            // Only render faces that are facing the viewer (optional back-face culling)
            if (!facingViewer) {
                continue // Skip back-facing triangles for more 3D appearance
            }
            
            if (useFilledFaces && transformedVertices.size >= 3) {
                // Create depth-shaded fill paint
                val depthFilledPaint = Paint(filledPaint).apply {
                    alpha = (clampedDepth * 255).toInt()
                }
                // Draw filled triangle first (as base) with depth shading
                drawTriangle(canvas, transformedVertices, depthFilledPaint)
                // Draw wireframe on top for definition
                drawTriangle(canvas, transformedVertices, modelPaint)
            } else {
                // Draw wireframe only with depth-based alpha
                val depthWirePaint = Paint(modelPaint).apply {
                    alpha = (clampedDepth * 255).toInt()
                }
                drawTriangle(canvas, transformedVertices, depthWirePaint)
            }
        }
    }
    
    /**
     * Apply 3D transformations (scaling and rotations) without perspective projection
     */
    private fun apply3DTransformations(
        vertex: Vertex3D,
        scaleX: Float,
        scaleY: Float,
        rotationX: Float, // Pitch
        rotationY: Float, // Yaw  
        rotationZ: Float  // Roll
    ): Vertex3D {
        // Start with scaled vertex
        var x = vertex.x * scaleX
        var y = vertex.y * scaleY  
        var z = vertex.z
        
        // Apply 3D rotations in order: X (pitch) -> Y (yaw) -> Z (roll)
        
        // Rotation around X-axis (Pitch)
        if (rotationX != 0f) {
            val radX = Math.toRadians(rotationX.toDouble())
            val cosX = cos(radX).toFloat()
            val sinX = sin(radX).toFloat()
            val newY = y * cosX - z * sinX
            val newZ = y * sinX + z * cosX
            y = newY
            z = newZ
        }
        
        // Rotation around Y-axis (Yaw)  
        if (rotationY != 0f) {
            val radY = Math.toRadians(rotationY.toDouble())
            val cosY = cos(radY).toFloat()
            val sinY = sin(radY).toFloat()
            val newX = x * cosY + z * sinY
            val newZ = -x * sinY + z * cosY
            x = newX
            z = newZ
        }
        
        // Rotation around Z-axis (Roll)
        if (rotationZ != 0f) {
            val radZ = Math.toRadians(rotationZ.toDouble())
            val cosZ = cos(radZ).toFloat()
            val sinZ = sin(radZ).toFloat()
            val newX = x * cosZ - y * sinZ
            val newY = x * sinZ + y * cosZ
            x = newX
            y = newY
        }
        
        return Vertex3D(x, y, z)
    }
    
    /**
     * Apply perspective projection to convert 3D point to 2D screen coordinates
     */
    private fun applyPerspectiveProjection(vertex3D: Vertex3D, offsetX: Float, offsetY: Float): PointF {
        // Apply perspective projection to convert 3D to 2D
        val perspectiveDistance = 500f // Distance from viewer to projection plane
        val zOffset = 200f // Push model away from viewer to avoid division by zero
        
        // Perspective projection: scale by distance/z to create depth effect
        val projectedZ = vertex3D.z + zOffset
        val perspectiveFactor = if (projectedZ > 0) perspectiveDistance / projectedZ else 1f
        
        val projectedX = vertex3D.x * perspectiveFactor
        val projectedY = vertex3D.y * perspectiveFactor
        
        // Translate to screen coordinates with perspective
        return PointF(
            centerX + projectedX + offsetX,
            centerY + projectedY + offsetY
        )
    }
    
    /**
     * Calculate face normal vector for back-face culling
     */
    private fun calculateFaceNormal(vertices: List<Vertex3D>): Vertex3D {
        if (vertices.size < 3) return Vertex3D(0f, 0f, 1f)
        
        val v1 = vertices[0]
        val v2 = vertices[1] 
        val v3 = vertices[2]
        
        // Calculate two edge vectors
        val edge1 = Vertex3D(v2.x - v1.x, v2.y - v1.y, v2.z - v1.z)
        val edge2 = Vertex3D(v3.x - v1.x, v3.y - v1.y, v3.z - v1.z)
        
        // Cross product to get normal
        val normal = Vertex3D(
            edge1.y * edge2.z - edge1.z * edge2.y,
            edge1.z * edge2.x - edge1.x * edge2.z,
            edge1.x * edge2.y - edge1.y * edge2.x
        )
        
        return normal
    }

    /**
     * Transform a vertex with manual adjustments including 3D rotations (LEGACY METHOD)
     */
    private fun transformVertex(
        vertex: Vertex3D,
        scaleX: Float,
        scaleY: Float,
        offsetX: Float,
        offsetY: Float,
        rotationX: Float, // Pitch
        rotationY: Float, // Yaw  
        rotationZ: Float  // Roll
    ): PointF {
        // Start with scaled vertex
        var x = vertex.x * scaleX
        var y = vertex.y * scaleY  
        var z = vertex.z
        
        // Apply 3D rotations in order: X (pitch) -> Y (yaw) -> Z (roll)
        
        // Rotation around X-axis (Pitch)
        if (rotationX != 0f) {
            val radX = Math.toRadians(rotationX.toDouble())
            val cosX = cos(radX).toFloat()
            val sinX = sin(radX).toFloat()
            val newY = y * cosX - z * sinX
            val newZ = y * sinX + z * cosX
            y = newY
            z = newZ
        }
        
        // Rotation around Y-axis (Yaw)  
        if (rotationY != 0f) {
            val radY = Math.toRadians(rotationY.toDouble())
            val cosY = cos(radY).toFloat()
            val sinY = sin(radY).toFloat()
            val newX = x * cosY + z * sinY
            val newZ = -x * sinY + z * cosY
            x = newX
            z = newZ
        }
        
        // Rotation around Z-axis (Roll)
        if (rotationZ != 0f) {
            val radZ = Math.toRadians(rotationZ.toDouble())
            val cosZ = cos(radZ).toFloat()
            val sinZ = sin(radZ).toFloat()
            val newX = x * cosZ - y * sinZ
            val newY = x * sinZ + y * cosZ
            x = newX
            y = newY
        }
        
        // Apply perspective projection to convert 3D to 2D
        val perspectiveDistance = 500f // Distance from viewer to projection plane
        val zOffset = 200f // Push model away from viewer to avoid division by zero
        
        // Perspective projection: scale by distance/z to create depth effect
        val projectedZ = z + zOffset
        val perspectiveFactor = if (projectedZ > 0) perspectiveDistance / projectedZ else 1f
        
        val projectedX = x * perspectiveFactor
        val projectedY = y * perspectiveFactor
        
        // Translate to screen coordinates with perspective
        return PointF(
            centerX + projectedX + offsetX,
            centerY + projectedY + offsetY
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
     * Draw landmark preview on the model (landmarks are NOT affected by adjustments)
     */
    private fun drawLandmarkPreview(canvas: Canvas, model: Model3D) {
        val landmarks = model.faceData?.landmarks ?: return
        
        // Calculate base scale to fit model in view (same as model, but no adjustments applied)
        val modelBounds = calculateModelBounds(model)
        val modelWidth = modelBounds.second.x - modelBounds.first.x
        val modelHeight = modelBounds.second.y - modelBounds.first.y
        val maxDimension = maxOf(modelWidth, modelHeight)
        
        val baseScale = if (maxDimension > 0) minOf(width, height) * 0.3f / maxDimension else 100f
        
        // Landmarks use original model positioning (NO manual adjustments applied)
        val scale = baseScale
        val scaleX = scale
        val scaleY = scale
        val offsetX = 0f
        val offsetY = 0f
        
        val landmarkPaint = Paint().apply {
            color = Color.RED
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        
        // Draw each landmark as a small circle at original positions
        for (landmark in landmarks) {
            val vertex = Vertex3D(landmark.x(), landmark.y(), landmark.z())
            val transformedPoint = transformVertex(vertex, scaleX, scaleY, offsetX, offsetY, 0f, 0f, 0f)
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
    
    /**
     * Set listener for adjustment changes
     */
    fun setOnAdjustmentChangedListener(listener: (ManualAdjustmentData) -> Unit) {
        onAdjustmentChangedListener = listener
    }
    
    /**
     * Handle touch events for model manipulation
     */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Always consume touch events to prevent scrolling
        parent?.requestDisallowInterceptTouchEvent(true)
        
        scaleGestureDetector.onTouchEvent(event)
        
        when (event.action and MotionEvent.ACTION_MASK) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                return true
            }
            
            MotionEvent.ACTION_MOVE -> {
                if (!scaleGestureDetector.isInProgress && event.pointerCount == 1) {
                    // Single finger - rotation
                    val dx = event.x - lastTouchX
                    val dy = event.y - lastTouchY
                    
                    // Calculate center of view for Z-axis rotation
                    val centerX = width / 2f
                    val centerY = height / 2f
                    
                    // If touch is in the outer area, add Z-axis rotation
                    val distanceFromCenter = kotlin.math.sqrt((event.x - centerX) * (event.x - centerX) + (event.y - centerY) * (event.y - centerY))
                    val maxRadius = kotlin.math.min(width, height) / 2f
                    
                    if (distanceFromCenter > maxRadius * 0.6f) {
                        // Outer area - add some Z-axis rotation based on circular motion
                        val angle1 = kotlin.math.atan2(lastTouchY - centerY, lastTouchX - centerX)
                        val angle2 = kotlin.math.atan2(event.y - centerY, event.x - centerX)
                        val deltaAngle = angle2 - angle1
                        touchRotationZ += Math.toDegrees(deltaAngle.toDouble()).toFloat() * 0.5f
                    }
                    
                    // Convert linear movement to rotation
                    touchRotationY += dx * 0.5f  // Horizontal movement -> Y-axis rotation (yaw)
                    touchRotationX += dy * 0.5f  // Vertical movement -> X-axis rotation (pitch)
                    
                    lastTouchX = event.x
                    lastTouchY = event.y
                    
                    updateAdjustments()
                }
                return true
            }
            
            MotionEvent.ACTION_UP -> {
                // Allow parent to intercept touch events again
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
            
            MotionEvent.ACTION_POINTER_UP -> {
                // Allow parent to intercept touch events again when all fingers lift
                if (event.pointerCount <= 1) {
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
                return true
            }
        }
        
        return true
    }
    

    
    /**
     * Update adjustments and notify listener
     */
    private fun updateAdjustments() {
        val adjustmentData = ManualAdjustmentData(
            scale = touchScale,
            scaleX = touchScale,
            scaleY = touchScale,
            offsetX = 0f,  // No panning
            offsetY = 0f,  // No panning
            offsetZ = 0f,
            rotationX = touchRotationX,
            rotationY = touchRotationY,
            rotationZ = touchRotationZ
        )
        
        // Debug logging
        Log.d("Model3DPreviewView", "Touch rotations: X=${touchRotationX}, Y=${touchRotationY}, Z=${touchRotationZ}")
        
        // Update internal adjustments
        manualAdjustments = adjustmentData
        
        // Notify listener
        onAdjustmentChangedListener?.invoke(adjustmentData)
        
        // Redraw
        invalidate()
    }
    
    /**
     * Scale gesture listener
     */
    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            touchScale *= detector.scaleFactor
            touchScale = maxOf(0.1f, minOf(touchScale, 3.0f)) // Limit scale range
            
            updateAdjustments()
            return true
        }
    }
    
    /**
     * Reset touch adjustments to default
     */
    fun resetAdjustments() {
        touchScale = 1f
        touchRotationX = 0f
        touchRotationY = 0f
        touchRotationZ = 0f
        updateAdjustments()
    }
    
    /**
     * Get current touch-based adjustments
     */
    fun getCurrentAdjustments(): ManualAdjustmentData {
        return ManualAdjustmentData(
            scale = touchScale,
            scaleX = touchScale,
            scaleY = touchScale,
            offsetX = 0f,  // No panning
            offsetY = 0f,  // No panning
            offsetZ = 0f,
            rotationX = touchRotationX,
            rotationY = touchRotationY,
            rotationZ = touchRotationZ
        )
    }
    
    /**
     * Apply specific adjustments to the model
     */
    fun applyAdjustments(adjustments: ManualAdjustmentData) {
        touchScale = adjustments.scale
        touchRotationX = adjustments.rotationX
        touchRotationY = adjustments.rotationY
        touchRotationZ = adjustments.rotationZ
        
        // Update internal adjustments
        manualAdjustments = adjustments
        
        // Notify listener
        onAdjustmentChangedListener?.invoke(adjustments)
        
        // Redraw
        invalidate()
    }
    

}