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
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import kotlin.math.*

/**
 * Data class to store analyzed 3D model face information
 */
data class Model3DFaceData(
    val landmarks: List<NormalizedLandmark>,
    val originalModel: Model3D,
    val faceRegion: Model3D, // Extracted face portion of the model
    val landmarkToVertexMapping: Map<Int, Int>, // Maps MediaPipe landmark indices to 3D model vertex indices
    val faceBounds: RectF,
    val faceCenter: Vertex3D,
    val faceScale: Float
)

/**
 * Analyzes uploaded 3D models to detect facial landmarks and prepare them for face matching
 */
class Model3DFaceAnalyzer(private val context: Context) {
    
    companion object {
        private const val TAG = "Model3DFaceAnalyzer"
        private const val FACE_LANDMARKER_TASK = "face_landmarker.task"
        
        // Key facial landmarks for matching (MediaPipe landmark indices)
        private val KEY_LANDMARKS = listOf(
            1,   // Face center
            9,   // Face center top
            10,  // Face center bottom
            151, // Chin center
            33,  // Left eye outer corner
            362, // Right eye outer corner
            130, // Left eye inner corner
            359, // Right eye inner corner
            2,   // Nose tip
            0,   // Nose bridge
            164, // Lip center top
            18,  // Lip center bottom
            234, // Left cheek
            454, // Right cheek
            10,  // Forehead center
            152  // Chin tip
        )
    }
    
    private var faceLandmarker: FaceLandmarker? = null
    
    init {
        setupFaceLandmarker()
    }
    
    private fun setupFaceLandmarker() {
        try {
            val baseOptionsBuilder = BaseOptions.builder()
                .setModelAssetPath(FACE_LANDMARKER_TASK)
            
            val options = FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(baseOptionsBuilder.build())
                .setRunningMode(RunningMode.IMAGE)
                .setNumFaces(1)
                .setMinFaceDetectionConfidence(0.3f)
                .setMinFacePresenceConfidence(0.3f)
                .setMinTrackingConfidence(0.3f)
                .build()
            
            faceLandmarker = FaceLandmarker.createFromOptions(context, options)
            Log.d(TAG, "Face landmarker initialized successfully")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up face landmarker", e)
        }
    }
    
    /**
     * Analyze a 3D model to detect facial landmarks using multi-angle scanning
     */
    fun analyzeModel3DFace(model: Model3D): Model3DFaceData? {
        return try {
            Log.d(TAG, "Starting multi-angle 3D model face analysis for model with ${model.vertices.size} vertices")
            
            // Try multiple angles to improve face detection
            val angles = listOf(
                Triple(0f, 0f, 0f),      // Front view
                Triple(0f, 30f, 0f),     // Slightly rotated right
                Triple(0f, -30f, 0f),    // Slightly rotated left
                Triple(15f, 0f, 0f),     // Slightly tilted up
                Triple(-15f, 0f, 0f)     // Slightly tilted down
            )
            
            var bestResult: Model3DFaceData? = null
            var maxLandmarks = 0
            
            for ((index, angle) in angles.withIndex()) {
                Log.d(TAG, "Trying angle ${index + 1}/${angles.size}: pitch=${angle.first}°, yaw=${angle.second}°, roll=${angle.third}°")
                
                // Step 1: Render 3D model from this angle
                val renderedImage = render3DModelToImageWithRotation(model, angle.first, angle.second, angle.third)
                
                // Step 2: Detect face landmarks in the rendered image
                val landmarks = detectFaceLandmarks(renderedImage)
                
                Log.d(TAG, "Angle ${index + 1}: detected ${landmarks.size} landmarks")
                
                if (landmarks.size > maxLandmarks) {
                    Log.d(TAG, "New best result with ${landmarks.size} landmarks")
                    
                    // Step 3: Map 2D landmarks back to 3D model vertices
                    val landmarkToVertexMapping = mapLandmarksTo3DVertices(landmarks, model, renderedImage.width, renderedImage.height)
                    
                    // Step 4: Extract face region from the full model
                    val faceRegion = extractFaceRegion(model, landmarkToVertexMapping)
                    
                    // Step 5: Calculate face bounds and center
                    val faceBounds = calculateModelFaceBounds(landmarks)
                    val faceCenter = calculateModelFaceCenter(landmarks, model, landmarkToVertexMapping)
                    val faceScale = calculateModelFaceScale(faceBounds)
                    
                    bestResult = Model3DFaceData(
                        landmarks = landmarks,
                        originalModel = model,
                        faceRegion = faceRegion,
                        landmarkToVertexMapping = landmarkToVertexMapping,
                        faceBounds = faceBounds,
                        faceCenter = faceCenter,
                        faceScale = faceScale
                    )
                    maxLandmarks = landmarks.size
                }
                
                // If we found a good amount of landmarks, we can stop early
                if (landmarks.size >= 450) { // MediaPipe typically detects ~468 landmarks
                    Log.d(TAG, "Found sufficient landmarks (${landmarks.size}), stopping scan")
                    break
                }
            }
            
            if (bestResult != null) {
                Log.d(TAG, "Multi-angle analysis completed: best result has ${maxLandmarks} landmarks")
            } else {
                Log.w(TAG, "No face landmarks detected in any angle")
            }
            
            bestResult
            
        } catch (e: Exception) {
            Log.e(TAG, "Error analyzing 3D model face", e)
            null
        }
    }
    
    /**
     * Render 3D model to a 2D image with rotation for MediaPipe analysis
     */
    private fun render3DModelToImageWithRotation(
        model: Model3D, 
        pitchDegrees: Float = 0f, 
        yawDegrees: Float = 0f, 
        rollDegrees: Float = 0f,
        width: Int = 512, 
        height: Int = 512
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        
        // Clear background
        canvas.drawColor(Color.BLACK)
        
        // Set up paint
        val paint = Paint().apply {
            color = Color.WHITE
            strokeWidth = 2f
            style = Paint.Style.FILL_AND_STROKE
            isAntiAlias = true
        }
        
        // Calculate model bounds for centering
        val bounds = model.boundingBox
        val modelWidth = bounds.second.x - bounds.first.x
        val modelHeight = bounds.second.y - bounds.first.y
        
        // Calculate scale to fit model in image
        val scale = minOf(width * 0.8f / modelWidth, height * 0.8f / modelHeight)
        
        // Center offset
        val centerX = (bounds.first.x + bounds.second.x) / 2f
        val centerY = (bounds.first.y + bounds.second.y) / 2f
        val centerZ = (bounds.first.z + bounds.second.z) / 2f
        
        // Convert degrees to radians
        val pitchRad = Math.toRadians(pitchDegrees.toDouble()).toFloat()
        val yawRad = Math.toRadians(yawDegrees.toDouble()).toFloat()
        val rollRad = Math.toRadians(rollDegrees.toDouble()).toFloat()
        
        // Render vertices with rotation
        model.vertices.forEach { vertex ->
            // Translate to origin
            var x = vertex.x - centerX
            var y = vertex.y - centerY
            var z = vertex.z - centerZ
            
            // Apply rotations (yaw, pitch, roll)
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
            val screenX = x3 * scale + width / 2f
            val screenY = y3 * scale + height / 2f
            
            if (screenX >= 0 && screenX < width && screenY >= 0 && screenY < height) {
                canvas.drawCircle(screenX, screenY, 2f, paint)
            }
        }
        
        // Render faces as wireframe with same rotation
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        
        model.faces.forEach { face ->
            val vertices = listOf(
                model.vertices[face.v1],
                model.vertices[face.v2], 
                model.vertices[face.v3]
            )
            
            val transformedVertices = vertices.map { vertex ->
                // Apply same transformation
                var x = vertex.x - centerX
                var y = vertex.y - centerY
                var z = vertex.z - centerZ
                
                // Rotations
                val cosYaw = cos(yawRad)
                val sinYaw = sin(yawRad)
                val cosPitch = cos(pitchRad)
                val sinPitch = sin(pitchRad)
                val cosRoll = cos(rollRad)
                val sinRoll = sin(rollRad)
                
                val x1 = x * cosYaw - z * sinYaw
                val z1 = x * sinYaw + z * cosYaw
                val y2 = y * cosPitch - z1 * sinPitch
                val z2 = y * sinPitch + z1 * cosPitch
                val x3 = x1 * cosRoll - y2 * sinRoll
                val y3 = x1 * sinRoll + y2 * cosRoll
                
                Pair(x3 * scale + width / 2f, y3 * scale + height / 2f)
            }
            
            // Draw triangle edges
            for (i in 0 until 3) {
                val j = (i + 1) % 3
                canvas.drawLine(
                    transformedVertices[i].first, transformedVertices[i].second,
                    transformedVertices[j].first, transformedVertices[j].second,
                    paint
                )
            }
        }
        
        Log.d(TAG, "Rendered 3D model to ${width}x${height} image with rotation (pitch=$pitchDegrees°, yaw=$yawDegrees°, roll=$rollDegrees°)")
        return bitmap
    }

    /**
     * Render 3D model to a 2D image for MediaPipe analysis (original method for backward compatibility)
     */
    private fun render3DModelToImage(model: Model3D, width: Int = 512, height: Int = 512): Bitmap {
        return render3DModelToImageWithRotation(model, 0f, 0f, 0f, width, height)
    }

    /**
     * Create a preview thumbnail of the 3D model for the UI
     */
    fun createModelPreview(model: Model3D, width: Int = 128, height: Int = 128): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        
        // Clear background
        canvas.drawColor(Color.BLACK)
        
        // Set up paint
        val paint = Paint().apply {
            color = Color.WHITE
            strokeWidth = 2f
            style = Paint.Style.FILL_AND_STROKE
            isAntiAlias = true
        }
        
        // Calculate model bounds for centering
        val bounds = model.boundingBox
        val modelWidth = bounds.second.x - bounds.first.x
        val modelHeight = bounds.second.y - bounds.first.y
        val modelDepth = bounds.second.z - bounds.first.z
        
        // Calculate scale to fit model in image
        val scale = minOf(width * 0.8f / modelWidth, height * 0.8f / modelHeight)
        
        // Center offset
        val centerX = (bounds.first.x + bounds.second.x) / 2f
        val centerY = (bounds.first.y + bounds.second.y) / 2f
        val offsetX = width / 2f - (centerX * scale)
        val offsetY = height / 2f - (centerY * scale)
        
        // Render vertices as points (simple orthographic projection)
        model.vertices.forEach { vertex ->
            val x = vertex.x * scale + offsetX
            val y = vertex.y * scale + offsetY
            
            if (x >= 0 && x < width && y >= 0 && y < height) {
                canvas.drawCircle(x, y, 2f, paint)
            }
        }
        
        // Render faces as wireframe
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        
        model.faces.forEach { face ->
            val v1 = model.vertices[face.v1]
            val v2 = model.vertices[face.v2]
            val v3 = model.vertices[face.v3]
            
            val x1 = v1.x * scale + offsetX
            val y1 = v1.y * scale + offsetY
            val x2 = v2.x * scale + offsetX
            val y2 = v2.y * scale + offsetY
            val x3 = v3.x * scale + offsetX
            val y3 = v3.y * scale + offsetY
            
            canvas.drawLine(x1, y1, x2, y2, paint)
            canvas.drawLine(x2, y2, x3, y3, paint)
            canvas.drawLine(x3, y3, x1, y1, paint)
        }
        
        Log.d(TAG, "Created preview of 3D model: ${width}x${height} image")
        return bitmap
    }
    
    /**
     * Detect face landmarks in the rendered image
     */
    private fun detectFaceLandmarks(bitmap: Bitmap): List<NormalizedLandmark> {
        return try {
            val landmarker = faceLandmarker ?: return emptyList()
            
            val mpImage = BitmapImageBuilder(bitmap).build()
            val result = landmarker.detect(mpImage)
            
            if (result.faceLandmarks().isNotEmpty()) {
                val landmarks = result.faceLandmarks()[0]
                Log.d(TAG, "Detected ${landmarks.size} face landmarks in 3D model image")
                landmarks
            } else {
                Log.w(TAG, "No face detected in 3D model image")
                emptyList()
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error detecting face landmarks in 3D model", e)
            emptyList()
        }
    }
    
    /**
     * Map 2D landmarks back to 3D model vertices
     */
    private fun mapLandmarksTo3DVertices(
        landmarks: List<NormalizedLandmark>,
        model: Model3D,
        imageWidth: Int,
        imageHeight: Int
    ): Map<Int, Int> {
        val mapping = mutableMapOf<Int, Int>()
        
        val bounds = model.boundingBox
        val modelWidth = bounds.second.x - bounds.first.x
        val modelHeight = bounds.second.y - bounds.first.y
        val scale = minOf(imageWidth * 0.8f / modelWidth, imageHeight * 0.8f / modelHeight)
        val centerX = (bounds.first.x + bounds.second.x) / 2f
        val centerY = (bounds.first.y + bounds.second.y) / 2f
        val offsetX = imageWidth / 2f - (centerX * scale)
        val offsetY = imageHeight / 2f - (centerY * scale)
        
        landmarks.forEachIndexed { landmarkIndex, landmark ->
            val landmarkX = landmark.x() * imageWidth
            val landmarkY = landmark.y() * imageHeight
            
            // Find closest 3D vertex to this landmark
            var closestVertexIndex = -1
            var minDistance = Float.MAX_VALUE
            
            model.vertices.forEachIndexed { vertexIndex, vertex ->
                val projectedX = vertex.x * scale + offsetX
                val projectedY = vertex.y * scale + offsetY
                
                val distance = sqrt((landmarkX - projectedX).pow(2) + (landmarkY - projectedY).pow(2)).toFloat()
                
                if (distance < minDistance) {
                    minDistance = distance
                    closestVertexIndex = vertexIndex
                }
            }
            
            if (closestVertexIndex != -1 && minDistance < 20f) { // Within 20 pixels
                mapping[landmarkIndex] = closestVertexIndex
            }
        }
        
        Log.d(TAG, "Mapped ${mapping.size} landmarks to 3D vertices")
        return mapping
    }
    
    /**
     * Extract the face region from the full 3D model
     */
    private fun extractFaceRegion(model: Model3D, landmarkMapping: Map<Int, Int>): Model3D {
        // Use mapped vertices and nearby vertices as face region
        val faceVertexIndices = mutableSetOf<Int>()
        
        // Add all mapped vertices
        faceVertexIndices.addAll(landmarkMapping.values)
        
        // Add vertices that are connected to face landmarks through faces
        model.faces.forEach { face ->
            val vertices = listOf(face.v1, face.v2, face.v3)
            if (vertices.any { it in faceVertexIndices }) {
                faceVertexIndices.addAll(vertices)
            }
        }
        
        // Create vertex index mapping
        val oldToNewIndex = mutableMapOf<Int, Int>()
        val faceVertices = mutableListOf<Vertex3D>()
        
        faceVertexIndices.forEachIndexed { newIndex, oldIndex ->
            oldToNewIndex[oldIndex] = newIndex
            faceVertices.add(model.vertices[oldIndex])
        }
        
        // Create faces that use only face vertices
        val faceFaces = mutableListOf<Face3D>()
        model.faces.forEach { face ->
            val v1New = oldToNewIndex[face.v1]
            val v2New = oldToNewIndex[face.v2]
            val v3New = oldToNewIndex[face.v3]
            
            if (v1New != null && v2New != null && v3New != null) {
                faceFaces.add(Face3D(v1New, v2New, v3New))
            }
        }
        
        Log.d(TAG, "Extracted face region: ${faceVertices.size} vertices, ${faceFaces.size} faces")
        
        // Calculate centroid and bounding box for face region
        val centroid = if (faceVertices.isNotEmpty()) {
            val sumX = faceVertices.sumOf { it.x.toDouble() }.toFloat()
            val sumY = faceVertices.sumOf { it.y.toDouble() }.toFloat()
            val sumZ = faceVertices.sumOf { it.z.toDouble() }.toFloat()
            Vertex3D(sumX / faceVertices.size, sumY / faceVertices.size, sumZ / faceVertices.size)
        } else {
            Vertex3D(0f, 0f, 0f)
        }
        
        val boundingBox = if (faceVertices.isNotEmpty()) {
            val minX = faceVertices.minOf { it.x }
            val maxX = faceVertices.maxOf { it.x }
            val minY = faceVertices.minOf { it.y }
            val maxY = faceVertices.maxOf { it.y }
            val minZ = faceVertices.minOf { it.z }
            val maxZ = faceVertices.maxOf { it.z }
            Pair(Vertex3D(minX, minY, minZ), Vertex3D(maxX, maxY, maxZ))
        } else {
            Pair(Vertex3D(0f, 0f, 0f), Vertex3D(0f, 0f, 0f))
        }
        
        return Model3D(faceVertices, faceFaces, centroid, boundingBox)
    }
    
    private fun calculateModelFaceBounds(landmarks: List<NormalizedLandmark>): RectF {
        var minX = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE
        var minY = Float.MAX_VALUE
        var maxY = Float.MIN_VALUE
        
        landmarks.forEach { landmark ->
            minX = minOf(minX, landmark.x())
            maxX = maxOf(maxX, landmark.x())
            minY = minOf(minY, landmark.y())
            maxY = maxOf(maxY, landmark.y())
        }
        
        return RectF(minX, minY, maxX, maxY)
    }
    
    private fun calculateModelFaceCenter(
        landmarks: List<NormalizedLandmark>,
        model: Model3D,
        landmarkMapping: Map<Int, Int>
    ): Vertex3D {
        var sumX = 0f
        var sumY = 0f
        var sumZ = 0f
        var count = 0
        
        // Use key landmarks for center calculation
        KEY_LANDMARKS.forEach { landmarkIndex ->
            val vertexIndex = landmarkMapping[landmarkIndex]
            if (vertexIndex != null && vertexIndex < model.vertices.size) {
                val vertex = model.vertices[vertexIndex]
                sumX += vertex.x
                sumY += vertex.y
                sumZ += vertex.z
                count++
            }
        }
        
        return if (count > 0) {
            Vertex3D(sumX / count, sumY / count, sumZ / count)
        } else {
            Vertex3D(0f, 0f, 0f)
        }
    }
    
    private fun calculateModelFaceScale(faceBounds: RectF): Float {
        return maxOf(faceBounds.width(), faceBounds.height())
    }
    
    fun cleanup() {
        try {
            faceLandmarker?.close()
            faceLandmarker = null
            Log.d(TAG, "Face landmarker cleaned up")
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up face landmarker", e)
        }
    }
}