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
 * Enhanced 3D model renderer with proper material rendering and graphics
 * Renders models with filled faces, textures, lighting, and better visual quality
 */
class MaterializedModelRenderer {
    
    companion object {
        private const val TAG = "MaterializedModelRenderer"
        private const val MIN_CONFIDENCE = 0.3f
    }
    
    private var currentModel: Model3D? = null
    private var currentAlignment: PreciseLandmarkAligner.LandmarkAlignment? = null
    private var manualAdjustments: ManualAdjustmentData? = null
    private var screenWidth = 1
    private var screenHeight = 1
    private var scaleFactor = 1f
    private var offsetX = 0f
    private var offsetY = 0f
    
    private val aligner = PreciseLandmarkAligner()
    
    // Enhanced paint objects for material rendering
    private val materialPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
        isDither = true
        isFilterBitmap = true
    }
    
    private val wireframePaint = Paint().apply {
        color = Color.CYAN
        strokeWidth = 1.5f
        style = Paint.Style.STROKE
        isAntiAlias = true
        alpha = 180 // Semi-transparent wireframe overlay
    }
    
    private val shadowPaint = Paint().apply {
        color = Color.argb(60, 0, 0, 0) // Semi-transparent shadow
        style = Paint.Style.FILL
        isAntiAlias = true
        maskFilter = BlurMaskFilter(4f, BlurMaskFilter.Blur.NORMAL)
    }
    
    private val highlightPaint = Paint().apply {
        color = Color.argb(80, 255, 255, 255) // Semi-transparent highlight
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    
    /**
     * Set the 3D model to render
     */
    fun setModel(model: Model3D): Boolean {
        currentModel = model
        currentAlignment = null // Reset alignment
        Log.d(TAG, "✅ Model set: ${model.vertices.size} vertices, ${model.faces.size} faces")
        return true
    }
    
    /**
     * Check if renderer has a valid model
     */
    fun hasValidModel(): Boolean = currentModel != null
    
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
     * Set manual adjustments for the model
     */
    fun setManualAdjustments(adjustments: ManualAdjustmentData) {
        this.manualAdjustments = adjustments
        Log.d(TAG, "🎛️ Manual adjustments set: scale=${adjustments.scale}, offset=(${adjustments.offsetX}, ${adjustments.offsetY})")
    }
    
    /**
     * Update alignment based on current face landmarks
     */
    fun updateAlignment(faceLandmarks: List<NormalizedLandmark>): Boolean {
        val model = currentModel ?: return false
        
        return try {
            if (model.hasFaceData) {
                // Use precise landmark alignment if face data available
                val alignment = aligner.calculatePreciseAlignment(model.faceData!!, faceLandmarks)
                
                if (alignment != null && alignment.confidence >= MIN_CONFIDENCE) {
                    currentAlignment = alignment
                    Log.d(TAG, "🎯 Alignment updated - confidence: ${String.format("%.3f", alignment.confidence)}")
                    true
                } else {
                    Log.w(TAG, "⚠️ Alignment quality too low: ${alignment?.confidence ?: 0f}")
                    false
                }
            } else {
                // Use basic head direction alignment for models without face data
                Log.d(TAG, "📏 Using basic alignment for model without face data")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating alignment", e)
            false
        }
    }
    
    /**
     * Render the 3D model with enhanced graphics and materials
     */
    fun render(canvas: Canvas, @Suppress("UNUSED_PARAMETER") paint: Paint): Boolean {
        val model = currentModel ?: return false
        
        return try {
            Log.d(TAG, "🎨 Rendering model with enhanced materials")
            
            // Apply manual adjustments if available
            val effectiveAlignment = if (manualAdjustments != null) {
                applyManualAdjustments(currentAlignment)
            } else {
                currentAlignment
            }
            
            if (model.hasFaceData && effectiveAlignment != null) {
                // Render with precise alignment
                renderWithPreciseAlignment(canvas, model, effectiveAlignment)
            } else {
                // Render with basic positioning
                renderWithBasicPositioning(canvas, model)
            }
            
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error rendering model", e)
            false
        }
    }
    
    /**
     * Apply manual adjustments to existing alignment
     */
    private fun applyManualAdjustments(baseAlignment: PreciseLandmarkAligner.LandmarkAlignment?): PreciseLandmarkAligner.LandmarkAlignment? {
        val adjustments = manualAdjustments ?: return baseAlignment
        val base = baseAlignment ?: return null
        
        // Create modified transform with manual adjustments
        val modifiedTransform = PreciseLandmarkAligner.TransformMatrix(
            scale = base.transform.scale * adjustments.scale,
            scaleX = base.transform.scaleX * adjustments.scaleX,
            scaleY = base.transform.scaleY * adjustments.scaleY,
            rotation = PreciseLandmarkAligner.RotationMatrix(
                pitch = base.transform.rotation.pitch + adjustments.rotationX,
                yaw = base.transform.rotation.yaw + adjustments.rotationY,
                roll = base.transform.rotation.roll + adjustments.rotationZ
            ),
            translation = PreciseLandmarkAligner.Vector3D(
                base.transform.translation.x + adjustments.offsetX,
                base.transform.translation.y + adjustments.offsetY,
                base.transform.translation.z + adjustments.offsetZ
            )
        )
        
        return PreciseLandmarkAligner.LandmarkAlignment(
            modelLandmarks = base.modelLandmarks,
            faceLandmarks = base.faceLandmarks,
            transform = modifiedTransform,
            confidence = base.confidence,
            mappings = base.mappings
        )
    }
    
    /**
     * Render model with precise landmark alignment and enhanced graphics
     */
    private fun renderWithPreciseAlignment(
        canvas: Canvas,
        model: Model3D,
        alignment: PreciseLandmarkAligner.LandmarkAlignment
    ) {
        // Transform vertices based on alignment
        val transformedVertices = model.vertices.map { vertex ->
            transformVertexWithAlignment(vertex, alignment)
        }
        
        // Convert to screen coordinates
        val screenVertices = transformedVertices.map { vertex ->
            toScreenCoordinates(vertex)
        }
        
        // Render with enhanced graphics
        renderMaterializedFaces(canvas, model, screenVertices)
        renderWireframeOverlay(canvas, model, screenVertices)
        renderLandmarkDebugInfo(canvas, alignment)
    }
    
    /**
     * Render model with basic positioning for models without face data
     */
    private fun renderWithBasicPositioning(canvas: Canvas, model: Model3D) {
        val adjustments = manualAdjustments ?: ManualAdjustmentData()
        
        // Apply basic transformations
        val transformedVertices = model.vertices.map { vertex ->
            transformVertexBasic(vertex, adjustments)
        }
        
        // Convert to screen coordinates
        val screenVertices = transformedVertices.map { vertex ->
            toScreenCoordinates(vertex)
        }
        
        // Render with enhanced graphics
        renderMaterializedFaces(canvas, model, screenVertices)
        renderWireframeOverlay(canvas, model, screenVertices)
    }
    
    /**
     * Render faces with material properties and lighting
     */
    private fun renderMaterializedFaces(canvas: Canvas, model: Model3D, screenVertices: List<PointF>) {
        // Calculate lighting direction (simulate light from top-left)
        val lightDirection = Vertex3D(-0.3f, -0.5f, 1.0f).normalize()
        
        for ((@Suppress("UNUSED_VARIABLE") index, face) in model.faces.withIndex()) {
            val vertexIndices = listOf(face.v1, face.v2, face.v3)
            
            if (vertexIndices.all { it < screenVertices.size }) {
                val screenPoints = vertexIndices.map { screenVertices[it] }
                
                // Calculate face normal for lighting
                val normal = calculateFaceNormal(
                    vertexIndices.map { model.vertices[it] }
                )
                
                // Calculate lighting intensity
                val lightIntensity = max(0f, normal.dot(lightDirection))
                
                // Create face path
                val path = Path().apply {
                    moveTo(screenPoints[0].x, screenPoints[0].y)
                    lineTo(screenPoints[1].x, screenPoints[1].y)
                    lineTo(screenPoints[2].x, screenPoints[2].y)
                    close()
                }
                
                // Get actual material color from model
                val faceIndex = model.faces.indexOf(face)
                val materialIndex = if (faceIndex >= 0) face.materialIndex else 0
                val material = model.materials.getOrNull(materialIndex) ?: model.materials.firstOrNull() ?: Material3D("default")
                val baseColor = material.diffuseColor
                val litColor = applyLighting(baseColor, lightIntensity)
                
                materialPaint.color = litColor
                canvas.drawPath(path, materialPaint)
                
                // Add highlight for faces facing the light
                if (lightIntensity > 0.7f) {
                    canvas.drawPath(path, highlightPaint)
                }
                
                // Add shadow for faces away from light
                if (lightIntensity < 0.3f) {
                    canvas.drawPath(path, shadowPaint)
                }
            }
        }
    }
    
    /**
     * Render wireframe overlay for debugging
     */
    private fun renderWireframeOverlay(canvas: Canvas, model: Model3D, screenVertices: List<PointF>) {
        for (face in model.faces) {
            val vertexIndices = listOf(face.v1, face.v2, face.v3)
            
            if (vertexIndices.all { it < screenVertices.size }) {
                val screenPoints = vertexIndices.map { screenVertices[it] }
                
                val path = Path().apply {
                    moveTo(screenPoints[0].x, screenPoints[0].y)
                    lineTo(screenPoints[1].x, screenPoints[1].y)
                    lineTo(screenPoints[2].x, screenPoints[2].y)
                    close()
                }
                
                canvas.drawPath(path, wireframePaint)
            }
        }
    }
    
    /**
     * Calculate face normal for lighting calculations
     */
    private fun calculateFaceNormal(vertices: List<Vertex3D>): Vertex3D {
        if (vertices.size < 3) return Vertex3D(0f, 0f, 1f)
        
        val v1 = vertices[1] - vertices[0]
        val v2 = vertices[2] - vertices[0]
        
        return v1.cross(v2).normalize()
    }
    
    /**
     * Get base color for a face (can be enhanced with texture mapping)
     */
    private fun getFaceColor(faceIndex: Int): Int {
        // Enhanced color scheme with more realistic material colors
        val colorSchemes = arrayOf(
            // Skin-like tones for face models
            Color.rgb(255, 220, 177), // Light skin
            Color.rgb(241, 194, 125), // Medium skin  
            Color.rgb(224, 172, 105), // Tan skin
            Color.rgb(198, 134, 66),  // Dark skin
            // Hair colors
            Color.rgb(92, 51, 23),    // Brown hair
            Color.rgb(165, 42, 42),   // Auburn hair
            Color.rgb(255, 255, 0),   // Blonde hair
            Color.rgb(0, 0, 0),       // Black hair
            // Eye colors
            Color.rgb(139, 69, 19),   // Brown eyes
            Color.rgb(0, 100, 0),     // Green eyes
            Color.rgb(0, 0, 139),     // Blue eyes
            // Clothing/accessory colors
            Color.rgb(255, 0, 0),     // Red
            Color.rgb(0, 255, 0),     // Green
            Color.rgb(0, 0, 255),     // Blue
            Color.rgb(255, 165, 0),   // Orange
            Color.rgb(128, 0, 128)    // Purple
        )
        
        // Use modulo to cycle through color schemes
        val baseColorIndex = faceIndex % colorSchemes.size
        val baseColor = colorSchemes[baseColorIndex]
        
        // Add slight variation within each color family
        val variation = (faceIndex / colorSchemes.size) * 0.1f
        val factor = 1.0f + (variation - 0.05f) // ±5% variation
        
        val r = (Color.red(baseColor) * factor).toInt().coerceIn(0, 255)
        val g = (Color.green(baseColor) * factor).toInt().coerceIn(0, 255)
        val b = (Color.blue(baseColor) * factor).toInt().coerceIn(0, 255)
        
        return Color.rgb(r, g, b)
    }
    
    /**
     * Apply lighting to a base color
     */
    private fun applyLighting(baseColor: Int, lightIntensity: Float): Int {
        val r = Color.red(baseColor)
        val g = Color.green(baseColor)
        val b = Color.blue(baseColor)
        val a = Color.alpha(baseColor)
        
        val factor = (0.3f + 0.7f * lightIntensity).coerceIn(0f, 1f)
        
        return Color.argb(
            a,
            (r * factor).toInt().coerceIn(0, 255),
            (g * factor).toInt().coerceIn(0, 255),
            (b * factor).toInt().coerceIn(0, 255)
        )
    }
    
    /**
     * Transform vertex with precise alignment
     */
    private fun transformVertexWithAlignment(
        vertex: Vertex3D,
        alignment: PreciseLandmarkAligner.LandmarkAlignment
    ): Vertex3D {
        val transform = alignment.transform
        val model = currentModel ?: return vertex
        
        // Normalize vertex to [0,1] coordinates
        val normalizedVertex = normalizeVertex(vertex, model)
        
        // Apply transformations
        val scaled = Vertex3D(
            normalizedVertex.x * transform.scaleX,
            normalizedVertex.y * transform.scaleY,
            normalizedVertex.z * transform.scale
        )
        
        // Apply rotation (simplified)
        val rotated = applyRotation(scaled, transform.rotation)
        
        // Apply translation
        return Vertex3D(
            rotated.x + transform.translation.x,
            rotated.y + transform.translation.y,
            rotated.z + transform.translation.z
        )
    }
    
    /**
     * Transform vertex with basic manual adjustments
     */
    private fun transformVertexBasic(vertex: Vertex3D, adjustments: ManualAdjustmentData): Vertex3D {
        val model = currentModel ?: return vertex
        
        // Normalize vertex
        val normalizedVertex = normalizeVertex(vertex, model)
        
        // Apply manual transformations
        val scaled = Vertex3D(
            normalizedVertex.x * adjustments.scaleX,
            normalizedVertex.y * adjustments.scaleY,
            normalizedVertex.z * adjustments.scale
        )
        
        // Apply manual rotation (simplified)
        val rotated = applyBasicRotation(scaled, adjustments)
        
        // Apply manual translation
        return Vertex3D(
            rotated.x + adjustments.offsetX,
            rotated.y + adjustments.offsetY,
            rotated.z + adjustments.offsetZ
        )
    }
    
    /**
     * Normalize vertex to [0,1] coordinates
     */
    private fun normalizeVertex(vertex: Vertex3D, model: Model3D): Vertex3D {
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
        // Simplified rotation implementation
        return vertex // TODO: Implement proper 3D rotation matrices
    }
    
    /**
     * Apply basic rotation from manual adjustments
     */
    private fun applyBasicRotation(vertex: Vertex3D, adjustments: ManualAdjustmentData): Vertex3D {
        // Simplified rotation implementation using manual adjustment angles
        return vertex // TODO: Implement proper 3D rotation matrices
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
     * Render debug information for landmark alignment
     */
    private fun renderLandmarkDebugInfo(canvas: Canvas, alignment: PreciseLandmarkAligner.LandmarkAlignment) {
        val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = 20f
            isAntiAlias = true
        }
        
        val info = "Material Render | Confidence: ${String.format("%.3f", alignment.confidence)} | " +
                  "Faces: ${currentModel?.faces?.size ?: 0}"
        
        canvas.drawText(info, 20f, screenHeight - 20f, textPaint)
    }
}