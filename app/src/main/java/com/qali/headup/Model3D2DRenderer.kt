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
 * Handles rendering 3D models using the optimal 2D detection angle and consistent mapping
 */
class Model3D2DRenderer {
    
    companion object {
        private const val TAG = "Model3D2DRenderer"
    }
    
    /**
     * Render the 3D model using the stored optimal detection angle and parameters
     */
    fun renderWithDetectionData(
        model: Model3D,
        faceData: Model3DFaceData,
        targetWidth: Int,
        targetHeight: Int,
        showLandmarks: Boolean = false
    ): Bitmap {
        Log.d(TAG, "Rendering model using stored detection angle: ${faceData.detectionAngle}")
        
        val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        
        // Use the same background as successful detection
        canvas.drawColor(Color.rgb(128, 128, 128))
        
        // Set up paints
        val faceFillPaint = Paint().apply {
            color = Color.WHITE
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        
        val faceStrokePaint = Paint().apply {
            color = Color.rgb(200, 200, 200)
            strokeWidth = 1f
            style = Paint.Style.STROKE
            isAntiAlias = true
        }
        
        val landmarkPaint = Paint().apply {
            color = Color.RED
            strokeWidth = 4f
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        
        // Calculate scale to fit target dimensions while maintaining aspect ratio
        val originalWidth = faceData.rendered2DImage.width
        val originalHeight = faceData.rendered2DImage.height
        val scaleX = targetWidth.toFloat() / originalWidth
        val scaleY = targetHeight.toFloat() / originalHeight
        val scale = minOf(scaleX, scaleY)
        
        // Center the image
        val offsetX = (targetWidth - originalWidth * scale) / 2f
        val offsetY = (targetHeight - originalHeight * scale) / 2f
        
        // Render the model using the same transformation that worked for detection
        renderModelWithAngle(
            canvas, model, faceData.detectionAngle, 
            scale * faceData.imageScale, 
            offsetX + faceData.imageOffset.first * scale,
            offsetY + faceData.imageOffset.second * scale,
            faceFillPaint, faceStrokePaint
        )
        
        // Overlay landmarks if requested
        if (showLandmarks) {
            renderLandmarksOverlay(canvas, faceData.landmarks, scale, offsetX, offsetY, landmarkPaint)
        }
        
        Log.d(TAG, "Rendered model at scale=$scale, offset=($offsetX, $offsetY)")
        return bitmap
    }
    
    /**
     * Render the 3D model with specified angle and parameters
     */
    private fun renderModelWithAngle(
        canvas: Canvas,
        model: Model3D,
        angle: Triple<Float, Float, Float>,
        scale: Float,
        offsetX: Float,
        offsetY: Float,
        faceFillPaint: Paint,
        faceStrokePaint: Paint
    ) {
        val (pitchDegrees, yawDegrees, rollDegrees) = angle
        
        // Calculate model center
        val bounds = model.boundingBox
        val centerX = (bounds.first.x + bounds.second.x) / 2f
        val centerY = (bounds.first.y + bounds.second.y) / 2f
        val centerZ = (bounds.first.z + bounds.second.z) / 2f
        
        // Convert degrees to radians
        val pitchRad = Math.toRadians(pitchDegrees.toDouble()).toFloat()
        val yawRad = Math.toRadians(yawDegrees.toDouble()).toFloat()
        val rollRad = Math.toRadians(rollDegrees.toDouble()).toFloat()
        
        // Transform all vertices
        val transformedVertices = model.vertices.map { vertex ->
            // Translate to origin
            var x = vertex.x - centerX
            var y = vertex.y - centerY
            var z = vertex.z - centerZ
            
            // Apply rotations
            val cosYaw = cos(yawRad)
            val sinYaw = sin(yawRad)
            val cosPitch = cos(pitchRad)
            val sinPitch = sin(pitchRad)
            val cosRoll = cos(rollRad)
            val sinRoll = sin(rollRad)
            
            // Yaw rotation (around Y axis)
            val x1 = x * cosYaw - z * sinYaw
            val z1 = x * sinYaw + z * cosYaw
            
            // Pitch rotation (around X axis)
            val y2 = y * cosPitch - z1 * sinPitch
            val z2 = y * sinPitch + z1 * cosPitch
            
            // Roll rotation (around Z axis)
            val x3 = x1 * cosRoll - y2 * sinRoll
            val y3 = x1 * sinRoll + y2 * cosRoll
            
            // Project to screen coordinates
            Pair(x3 * scale + offsetX, y3 * scale + offsetY)
        }
        
        // Render filled faces first
        model.faces.forEach { face ->
            if (face.v1 < transformedVertices.size && face.v2 < transformedVertices.size && face.v3 < transformedVertices.size) {
                val v1 = transformedVertices[face.v1]
                val v2 = transformedVertices[face.v2]
                val v3 = transformedVertices[face.v3]
                
                val path = Path()
                path.moveTo(v1.first, v1.second)
                path.lineTo(v2.first, v2.second)
                path.lineTo(v3.first, v3.second)
                path.close()
                canvas.drawPath(path, faceFillPaint)
            }
        }
        
        // Render wireframe edges
        model.faces.forEach { face ->
            if (face.v1 < transformedVertices.size && face.v2 < transformedVertices.size && face.v3 < transformedVertices.size) {
                val v1 = transformedVertices[face.v1]
                val v2 = transformedVertices[face.v2]
                val v3 = transformedVertices[face.v3]
                
                canvas.drawLine(v1.first, v1.second, v2.first, v2.second, faceStrokePaint)
                canvas.drawLine(v2.first, v2.second, v3.first, v3.second, faceStrokePaint)
                canvas.drawLine(v3.first, v3.second, v1.first, v1.second, faceStrokePaint)
            }
        }
    }
    
    /**
     * Render landmarks overlay on the image
     */
    private fun renderLandmarksOverlay(
        canvas: Canvas,
        landmarks: List<NormalizedLandmark>,
        scale: Float,
        offsetX: Float,
        offsetY: Float,
        paint: Paint
    ) {
        landmarks.forEach { landmark ->
            // Convert normalized coordinates to screen coordinates
            val x = landmark.x() * scale + offsetX
            val y = landmark.y() * scale + offsetY
            canvas.drawCircle(x, y, 2f, paint)
        }
    }
    
    /**
     * Create a preview image that shows the exact detection result
     */
    fun createDetectionPreview(faceData: Model3DFaceData, size: Int = 300): Bitmap {
        // Scale the original detection image to the requested size
        return Bitmap.createScaledBitmap(faceData.rendered2DImage, size, size, true)
    }
    
    /**
     * Calculate the transformation matrix needed to align real face landmarks with model landmarks
     */
    fun calculateAlignmentTransform(
        modelFaceData: Model3DFaceData,
        realFaceLandmarks: List<NormalizedLandmark>,
        screenWidth: Int,
        screenHeight: Int
    ): Matrix? {
        if (realFaceLandmarks.isEmpty()) return null
        
        // This is where we map the 2D detection back to screen coordinates
        // for proper alignment with the live camera feed
        
        val matrix = Matrix()
        
        // Calculate the bounding box of real face landmarks
        val realXCoords = realFaceLandmarks.map { it.x() * screenWidth }
        val realYCoords = realFaceLandmarks.map { it.y() * screenHeight }
        
        val realMinX = realXCoords.minOrNull() ?: 0f
        val realMaxX = realXCoords.maxOrNull() ?: screenWidth.toFloat()
        val realMinY = realYCoords.minOrNull() ?: 0f
        val realMaxY = realYCoords.maxOrNull() ?: screenHeight.toFloat()
        
        val realCenterX = (realMinX + realMaxX) / 2f
        val realCenterY = (realMinY + realMaxY) / 2f
        val realWidth = realMaxX - realMinX
        val realHeight = realMaxY - realMinY
        
        // Calculate model face bounding box in detection coordinates
        val modelLandmarks = modelFaceData.landmarks
        val modelXCoords = modelLandmarks.map { it.x() }
        val modelYCoords = modelLandmarks.map { it.y() }
        
        val modelMinX = modelXCoords.minOrNull() ?: 0f
        val modelMaxX = modelXCoords.maxOrNull() ?: 1f
        val modelMinY = modelYCoords.minOrNull() ?: 0f
        val modelMaxY = modelYCoords.maxOrNull() ?: 1f
        
        val modelCenterX = (modelMinX + modelMaxX) / 2f
        val modelCenterY = (modelMinY + modelMaxY) / 2f
        val modelWidth = modelMaxX - modelMinX
        val modelHeight = modelMaxY - modelMinY
        
        // Calculate scale to match real face size
        val scaleX = if (modelWidth > 0) realWidth / modelWidth else 1f
        val scaleY = if (modelHeight > 0) realHeight / modelHeight else 1f
        val avgScale = (scaleX + scaleY) / 2f
        
        // Apply transformations: translate model center to real face center and scale
        matrix.preTranslate(-modelCenterX, -modelCenterY)
        matrix.postScale(avgScale, avgScale)
        matrix.postTranslate(realCenterX, realCenterY)
        
        Log.d(TAG, "Alignment transform: scale=$avgScale, realCenter=($realCenterX, $realCenterY), modelCenter=($modelCenterX, $modelCenterY)")
        
        return matrix
    }
}