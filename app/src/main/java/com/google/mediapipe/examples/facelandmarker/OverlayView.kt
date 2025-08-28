package com.google.mediapipe.examples.facelandmarker

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
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.Log
import android.view.View
import androidx.core.content.ContextCompat
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import kotlin.math.max
import kotlin.math.min

class OverlayView(context: Context?, attrs: AttributeSet?) :
    View(context, attrs) {

    private var results: FaceLandmarkerResult? = null
    private var linePaint = Paint()
    private var pointPaint = Paint()

    private var scaleFactor: Float = 1f
    private var imageWidth: Int = 1
    private var imageHeight: Int = 1
    
    // 3D model rendering components
    private val model3DRenderer = Model3DRenderer()
    private val landmarkAlignedRenderer = LandmarkAlignedRenderer() // Landmark-based renderer
    private val progressiveRenderer = ProgressiveModelRenderer() // NEW: Weight point + progressive scaling
    private val preciseRenderer = PreciseModelRenderer() // ULTRA-PRECISE: Direct landmark-to-landmark mapping
    private val headDirectionCalculator = HeadDirectionCalculator()
    private var show3DModel = false
    private var debugMode = false // Show both face mesh AND 3D model for testing
    private var useLandmarkAlignment = true // Use landmark-based alignment when available
    private var useProgressiveRenderer = false // Use progressive by default initially
    private var usePreciseRenderer = true // NEW: Use ultra-precise landmark mapping

    init {
        initPaints()
    }

    fun clear() {
        results = null
        linePaint.reset()
        pointPaint.reset()
        model3DRenderer.clearModel()
        show3DModel = false
        invalidate()
        initPaints()
    }

    private fun initPaints() {
        linePaint.color =
            ContextCompat.getColor(context!!, R.color.mp_color_primary)
        linePaint.strokeWidth = LANDMARK_STROKE_WIDTH
        linePaint.style = Paint.Style.STROKE

        pointPaint.color = Color.YELLOW
        pointPaint.strokeWidth = LANDMARK_STROKE_WIDTH
        pointPaint.style = Paint.Style.FILL
    }

    override fun draw(canvas: Canvas) {
        super.draw(canvas)

        // Clear previous drawings if results exist but have no face landmarks
        if (results?.faceLandmarks().isNullOrEmpty()) {
            clear()
            return
        }

        results?.let { faceLandmarkerResult ->

            // Calculate scaled image dimensions
            val scaledImageWidth = imageWidth * scaleFactor
            val scaledImageHeight = imageHeight * scaleFactor

            // Calculate offsets to center the image on the canvas
            val offsetX = (width - scaledImageWidth) / 2f
            val offsetY = (height - scaledImageHeight) / 2f

            // Update 3D renderer viewport
            model3DRenderer.setViewport(width, height)

            // Iterate through each detected face
            faceLandmarkerResult.faceLandmarks().forEach { faceLandmarks ->
                
                // Calculate face dimensions for proper 3D model scaling
                val faceBounds = calculateFaceBounds(faceLandmarks)
                val faceWidth = faceBounds.width() * scaleFactor
                val faceHeight = faceBounds.height() * scaleFactor
                
                // Choose rendering mode: precise landmark-to-landmark, progressive weight-point, landmark-aligned, standard 3D model, or face landmarks/mesh
                if (show3DModel && (preciseRenderer.hasValidModel() || progressiveRenderer.hasValidModel() || landmarkAlignedRenderer.hasModel() || model3DRenderer.hasModel())) {
                    // Determine which renderer to use (priority order)
                    val usePreciseRenderer = this.usePreciseRenderer && preciseRenderer.hasValidModel()
                    val useProgressiveRenderer = !usePreciseRenderer && this.useProgressiveRenderer && progressiveRenderer.hasValidModel()
                    val useAdvancedRenderer = !usePreciseRenderer && !useProgressiveRenderer && useLandmarkAlignment && landmarkAlignedRenderer.hasModel()
                    val rendererType = when {
                        usePreciseRenderer -> "precise landmark-to-landmark"
                        useProgressiveRenderer -> "progressive weight-point"
                        useAdvancedRenderer -> "landmark-aligned"
                        else -> "standard"
                    }
                    
                    try {
                        val modeText = if (debugMode) "with face mesh (debug)" else "instead of face mesh"
                        Log.d("OverlayView", "Rendering $rendererType 3D model $modeText")
                        
                        // Show face mesh in debug mode or as fallback
                        if (debugMode) {
                            drawFaceLandmarks(canvas, faceLandmarks, offsetX, offsetY)
                            drawFaceConnectors(canvas, faceLandmarks, offsetX, offsetY)
                        }
                        
                        var renderingSuccessful = false
                        
                        if (usePreciseRenderer) {
                            // Use ultra-precise landmark-to-landmark renderer
                            preciseRenderer.updateScreenParameters(
                                width = width,
                                height = height,
                                scale = scaleFactor,
                                offsetX = offsetX,
                                offsetY = offsetY
                            )
                            
                            // Update alignment with current face landmarks
                            val alignmentUpdated = preciseRenderer.updateAlignment(faceLandmarks)
                            
                            if (alignmentUpdated) {
                                renderingSuccessful = preciseRenderer.render(canvas, pointPaint)
                                Log.d("OverlayView", "🎯 Precise rendering: ${if (renderingSuccessful) "success" else "failed"}")
                            } else {
                                Log.w("OverlayView", "⚠️ Failed to update precise alignment")
                            }
                            
                        } else if (useProgressiveRenderer) {
                            // Use progressive weight-point renderer
                            progressiveRenderer.updateScreenParameters(
                                width = width,
                                height = height,
                                scaleFactor = scaleFactor,
                                offsetX = offsetX,
                                offsetY = offsetY
                            )
                            
                            // Update alignment with current face landmarks
                            val alignmentUpdated = progressiveRenderer.updateAlignment(faceLandmarks)
                            
                            if (alignmentUpdated) {
                                renderingSuccessful = progressiveRenderer.render(canvas, pointPaint)
                                Log.d("OverlayView", "Progressive rendering: ${if (renderingSuccessful) "success" else "failed"}")
                                
                                if (debugMode) {
                                    // Show alignment info
                                    val alignmentInfo = progressiveRenderer.getAlignmentInfo()
                                    Log.d("OverlayView", "Progressive alignment info: $alignmentInfo")
                                }
                            } else {
                                Log.w("OverlayView", "Failed to update progressive alignment")
                            }
                            
                        } else if (useAdvancedRenderer) {
                            // Use landmark-aligned renderer
                            landmarkAlignedRenderer.updateFaceParameters(
                                width = width,
                                height = height,
                                scaleFactor = scaleFactor,
                                offsetX = offsetX,
                                offsetY = offsetY
                            )
                            
                            // Update alignment with current face landmarks
                            val alignmentUpdated = landmarkAlignedRenderer.updateAlignment(faceLandmarks)
                            
                            if (alignmentUpdated) {
                                renderingSuccessful = landmarkAlignedRenderer.render(canvas, pointPaint)
                                Log.d("OverlayView", "Landmark-aligned rendering: ${if (renderingSuccessful) "success" else "failed"}")
                                
                                if (debugMode) {
                                    // Show alignment info
                                    val alignmentInfo = landmarkAlignedRenderer.getAlignmentInfo()
                                    Log.d("OverlayView", "Alignment info: $alignmentInfo")
                                }
                            } else {
                                Log.w("OverlayView", "Failed to update landmark alignment")
                            }
                        }
                        
                        // Fallback to standard renderer if landmark alignment fails
                        if (!renderingSuccessful && model3DRenderer.hasModel()) {
                            Log.d("OverlayView", "Falling back to standard 3D renderer")
                            model3DRenderer.updateFaceParameters(faceWidth, faceHeight, scaleFactor, offsetX, offsetY)
                            
                            val headPose = headDirectionCalculator.calculateHeadPose(faceLandmarkerResult)
                            headPose?.let { pose ->
                                model3DRenderer.updateHeadPose(pose)
                                model3DRenderer.render(canvas, pointPaint)
                                renderingSuccessful = true // Standard renderer always "succeeds"
                            }
                        }
                        
                        // Final fallback to face mesh
                        if (!renderingSuccessful && !debugMode) {
                            Log.w("OverlayView", "All 3D rendering failed, showing face mesh")
                            drawFaceLandmarks(canvas, faceLandmarks, offsetX, offsetY)
                            drawFaceConnectors(canvas, faceLandmarks, offsetX, offsetY)
                        }
                        
                    } catch (e: Exception) {
                        Log.e("OverlayView", "Error rendering 3D model, falling back to face mesh", e)
                        // Fallback to face mesh if 3D rendering fails and not already showing
                        if (!debugMode) {
                            drawFaceLandmarks(canvas, faceLandmarks, offsetX, offsetY)
                            drawFaceConnectors(canvas, faceLandmarks, offsetX, offsetY)
                        }
                    }
                } else {
                    // Face Mesh Mode: Draw traditional face landmarks and connectors
                    Log.d("OverlayView", "Rendering face mesh - show3DModel: $show3DModel, hasModel: ${model3DRenderer.hasModel()}, hasLandmarkModel: ${landmarkAlignedRenderer.hasModel()}")
                    drawFaceLandmarks(canvas, faceLandmarks, offsetX, offsetY)
                    drawFaceConnectors(canvas, faceLandmarks, offsetX, offsetY)
                }
            }
        }
    }

    /**
     * Calculate the bounding box of the face landmarks
     */
    private fun calculateFaceBounds(faceLandmarks: List<NormalizedLandmark>): RectF {
        var minX = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE
        var minY = Float.MAX_VALUE
        var maxY = Float.MIN_VALUE
        
        faceLandmarks.forEach { landmark ->
            val x = landmark.x() * imageWidth
            val y = landmark.y() * imageHeight
            
            minX = minOf(minX, x)
            maxX = maxOf(maxX, x)
            minY = minOf(minY, y)
            maxY = maxOf(maxY, y)
        }
        
        return RectF(minX, minY, maxX, maxY)
    }

    /**
     * Draws all landmarks for a single face on the canvas.
     */
    private fun drawFaceLandmarks(
        canvas: Canvas,
        faceLandmarks: List<NormalizedLandmark>,
        offsetX: Float,
        offsetY: Float
    ) {
        faceLandmarks.forEach { landmark ->
            val x = landmark.x() * imageWidth * scaleFactor + offsetX
            val y = landmark.y() * imageHeight * scaleFactor + offsetY
            canvas.drawPoint(x, y, pointPaint)
        }
    }

    /**
     * Draws all the connectors between landmarks for a single face on the canvas.
     */
    private fun drawFaceConnectors(
        canvas: Canvas,
        faceLandmarks: List<NormalizedLandmark>,
        offsetX: Float,
        offsetY: Float
    ) {
        FaceLandmarker.FACE_LANDMARKS_CONNECTORS.filterNotNull().forEach { connector ->
            val startLandmark = faceLandmarks.getOrNull(connector.start())
            val endLandmark = faceLandmarks.getOrNull(connector.end())

            if (startLandmark != null && endLandmark != null) {
                val startX = startLandmark.x() * imageWidth * scaleFactor + offsetX
                val startY = startLandmark.y() * imageHeight * scaleFactor + offsetY
                val endX = endLandmark.x() * imageWidth * scaleFactor + offsetX
                val endY = endLandmark.y() * imageHeight * scaleFactor + offsetY

                canvas.drawLine(startX, startY, endX, endY, linePaint)
            }
        }
    }

    fun setResults(
        faceLandmarkerResults: FaceLandmarkerResult,
        imageHeight: Int,
        imageWidth: Int,
        runningMode: RunningMode = RunningMode.IMAGE
    ) {
        results = faceLandmarkerResults

        this.imageHeight = imageHeight
        this.imageWidth = imageWidth

        scaleFactor = when (runningMode) {
            RunningMode.IMAGE,
            RunningMode.VIDEO -> {
                min(width * 1f / imageWidth, height * 1f / imageHeight)
            }
            RunningMode.LIVE_STREAM -> {
                // PreviewView is in FILL_START mode. So we need to scale up the
                // landmarks to match with the size that the captured images will be
                // displayed.
                max(width * 1f / imageWidth, height * 1f / imageHeight)
            }
        }
        invalidate()
    }

    /**
     * Set a 3D model to render over the face
     */
    fun set3DModel(model: Model3D) {
        // Try to set model in precise renderer first (BEST option for landmark alignment)
        val preciseRendererSet = preciseRenderer.setModel(model)
        
        // Try to set model in progressive renderer second
        val progressiveRendererSet = progressiveRenderer.setModel(model)
        
        // Try to set model in landmark-aligned renderer third
        val landmarkRendererSet = landmarkAlignedRenderer.setModel(model)
        
        // Always set in standard renderer as fallback
        model3DRenderer.setModel(model)
        
        when {
            preciseRendererSet -> {
                Log.d("OverlayView", "🎯 Model set in PRECISE landmark-to-landmark renderer (${model.faceData?.landmarks?.size} landmarks)")
            }
            progressiveRendererSet -> {
                Log.d("OverlayView", "Model set in progressive weight-point renderer (${model.faceData?.landmarks?.size} landmarks)")
            }
            landmarkRendererSet -> {
                Log.d("OverlayView", "Model set in landmark-aligned renderer (${model.faceData?.landmarks?.size} landmarks)")
            }
            else -> {
                Log.d("OverlayView", "Model set in standard renderer (no face data)")
            }
        }
        
        show3DModel = true
        invalidate()
    }
    
    /**
     * Toggle 3D model visibility
     */
    fun toggle3DModel() {
        show3DModel = !show3DModel
        invalidate()
    }
    
    /**
     * Check if 3D model is currently visible
     */
    fun is3DModelVisible(): Boolean = show3DModel && model3DRenderer.hasModel()
    
    /**
     * Hide the 3D model
     */
    fun hide3DModel() {
        show3DModel = false
        invalidate()
    }
    
    fun toggleDebugMode() {
        debugMode = !debugMode
        Log.d("OverlayView", "Debug mode ${if (debugMode) "enabled" else "disabled"}")
        invalidate()
    }
    
    fun setDebugMode(enabled: Boolean) {
        debugMode = enabled
        Log.d("OverlayView", "Debug mode ${if (debugMode) "enabled" else "disabled"}")
        invalidate()
    }

    companion object {
        private const val LANDMARK_STROKE_WIDTH = 8F
        private const val TAG = "Face Landmarker Overlay"
    }
}
