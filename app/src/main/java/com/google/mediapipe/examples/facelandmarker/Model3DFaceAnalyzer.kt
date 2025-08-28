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
import java.io.File
import java.io.FileOutputStream
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
                .setMinFaceDetectionConfidence(0.1f)  // Much lower threshold
                .setMinFacePresenceConfidence(0.1f)   // Much lower threshold
                .setMinTrackingConfidence(0.1f)       // Much lower threshold
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
            
            // Try comprehensive angles to catch faces in any orientation
            val angles = mutableListOf<Triple<Float, Float, Float>>()
            
            // Primary orientations (faces might be facing different directions)
            angles.addAll(listOf(
                Triple(0f, 0f, 0f),       // Front view
                Triple(0f, 90f, 0f),      // Right side
                Triple(0f, -90f, 0f),     // Left side  
                Triple(0f, 180f, 0f),     // Back view
                Triple(90f, 0f, 0f),      // Looking up (face on top)
                Triple(-90f, 0f, 0f),     // Looking down (face on bottom)
                Triple(0f, 0f, 90f),      // Rolled right
                Triple(0f, 0f, -90f),     // Rolled left
                Triple(0f, 0f, 180f)      // Upside down
            ))
            
            // Diagonal orientations (common for imported models)
            angles.addAll(listOf(
                Triple(45f, 45f, 0f),     // Up-right diagonal
                Triple(45f, -45f, 0f),    // Up-left diagonal
                Triple(-45f, 45f, 0f),    // Down-right diagonal
                Triple(-45f, -45f, 0f),   // Down-left diagonal
                Triple(45f, 0f, 45f),     // Tilted up-rolled
                Triple(-45f, 0f, -45f)    // Tilted down-rolled
            ))
            
            // Fine-tuned angles around front view (common slight misalignments)
            angles.addAll(listOf(
                Triple(0f, 15f, 0f),      // Slightly right
                Triple(0f, -15f, 0f),     // Slightly left
                Triple(15f, 0f, 0f),      // Slightly up
                Triple(-15f, 0f, 0f),     // Slightly down
                Triple(0f, 30f, 0f),      // More right
                Triple(0f, -30f, 0f),     // More left
                Triple(30f, 0f, 0f),      // More up
                Triple(-30f, 0f, 0f),     // More down
                Triple(0f, 0f, 15f),      // Slightly rolled right
                Triple(0f, 0f, -15f)      // Slightly rolled left
            ))
            
            // Add extreme angles for models that might be completely flipped
            angles.addAll(listOf(
                Triple(180f, 0f, 0f),     // Completely flipped vertically
                Triple(0f, 135f, 0f),     // 3/4 turn right
                Triple(0f, -135f, 0f),    // 3/4 turn left
                Triple(135f, 0f, 0f),     // Severely tilted up
                Triple(-135f, 0f, 0f),    // Severely tilted down
                Triple(90f, 90f, 0f),     // Top-right corner
                Triple(-90f, 90f, 0f),    // Bottom-right corner
                Triple(90f, -90f, 0f),    // Top-left corner
                Triple(-90f, -90f, 0f)    // Bottom-left corner
            ))
            
            Log.d(TAG, "Testing ${angles.size} different angles for comprehensive face detection")
            
            var bestResult: Model3DFaceData? = null
            var maxLandmarks = 0
            var bestAngle: Triple<Float, Float, Float>? = null
            
            for ((index, angle) in angles.withIndex()) {
                Log.d(TAG, "Trying angle ${index + 1}/${angles.size}: pitch=${angle.first}°, yaw=${angle.second}°, roll=${angle.third}°")
                
                // Step 1: Render 3D model from this angle
                val renderedImage = render3DModelToImageWithRotation(model, angle.first, angle.second, angle.third)
                
                // DEBUGGING: Save rendered image for inspection
                saveDebugImage(renderedImage, "model_angle_${index + 1}", index)
                
                // Step 2: Detect face landmarks in the rendered image
                val landmarks = detectFaceLandmarks(renderedImage)
                
                Log.d(TAG, "Angle ${index + 1}: detected ${landmarks.size} landmarks")
                Log.d(TAG, "Rendered image size: ${renderedImage.width}x${renderedImage.height}")
                Log.d(TAG, "Image format: ${renderedImage.config}")
                
                // Log image characteristics
                logImageCharacteristics(renderedImage, "Angle ${index + 1}")
                
                if (landmarks.size > maxLandmarks) {
                    Log.d(TAG, "🎯 NEW BEST RESULT with ${landmarks.size} landmarks at angle: pitch=${angle.first}°, yaw=${angle.second}°, roll=${angle.third}°")
                    
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
                    bestAngle = angle
                }
                
                // If we found a good amount of landmarks, we can stop early
                if (landmarks.size >= 450) { // MediaPipe typically detects ~468 landmarks
                    Log.d(TAG, "Found sufficient landmarks (${landmarks.size}), stopping scan")
                    break
                }
            }
            
            if (bestResult != null && bestAngle != null) {
                Log.d(TAG, "✅ Multi-angle analysis SUCCESS: ${maxLandmarks} landmarks detected!")
                Log.d(TAG, "🎯 Best angle found: pitch=${bestAngle.first}°, yaw=${bestAngle.second}°, roll=${bestAngle.third}°")
                Log.d(TAG, "💡 IMPORTANT: Your model face is oriented at this angle - this might explain why face detection failed before!")
            } else {
                Log.w(TAG, "❌ No face landmarks detected in ANY of the ${angles.size} angles tested")
                Log.w(TAG, "🔍 This suggests the model either:")
                Log.w(TAG, "   1. Doesn't have clear facial features in the wireframe")
                Log.w(TAG, "   2. Is too small/dark in the rendered images")
                Log.w(TAG, "   3. Has proportions too different from human faces")
                Log.w(TAG, "   4. Check the saved debug images for visual inspection")
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
        
        // Clear background with medium gray for better MediaPipe detection
        canvas.drawColor(Color.rgb(128, 128, 128))
        
        // Set up high-contrast paints optimized for face detection
        val faceFillPaint = Paint().apply {
            color = Color.WHITE
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        
        val faceStrokePaint = Paint().apply {
            color = Color.rgb(220, 220, 220) // Light gray for edges
            strokeWidth = 1f
            style = Paint.Style.STROKE
            isAntiAlias = true
        }
        
        val vertexPaint = Paint().apply {
            color = Color.WHITE
            strokeWidth = 3f
            style = Paint.Style.FILL
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
                canvas.drawCircle(screenX, screenY, 3f, vertexPaint)
            }
        }
        
        // First pass: Render faces as FILLED triangles for better face detection
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
            
            // Draw filled triangle (face-like surface)
            val path = Path()
            path.moveTo(transformedVertices[0].first, transformedVertices[0].second)
            path.lineTo(transformedVertices[1].first, transformedVertices[1].second)
            path.lineTo(transformedVertices[2].first, transformedVertices[2].second)
            path.close()
            canvas.drawPath(path, faceFillPaint)
        }
        
        // Second pass: Add wireframe edges for definition
        model.faces.forEach { face ->
            val vertices = listOf(
                model.vertices[face.v1],
                model.vertices[face.v2], 
                model.vertices[face.v3]
            )
            
            val transformedVertices = vertices.map { vertex ->
                // Apply same transformation (repeated for clarity)
                var x = vertex.x - centerX
                var y = vertex.y - centerY
                var z = vertex.z - centerZ
                
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
            
            // Draw triangle edges for definition
            for (i in 0 until 3) {
                val j = (i + 1) % 3
                canvas.drawLine(
                    transformedVertices[i].first, transformedVertices[i].second,
                    transformedVertices[j].first, transformedVertices[j].second,
                    faceStrokePaint
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
     * Save debug image to storage for inspection
     */
    private fun saveDebugImage(bitmap: Bitmap, prefix: String, index: Int) {
        try {
            val debugDir = File(context.filesDir, "debug_images")
            if (!debugDir.exists()) debugDir.mkdirs()
            
            val fileName = "${prefix}_${System.currentTimeMillis()}.png"
            val file = File(debugDir, fileName)
            
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            
            Log.d(TAG, "Saved debug image: ${file.absolutePath}")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save debug image", e)
        }
    }
    
    /**
     * Log detailed characteristics of the rendered image
     */
    private fun logImageCharacteristics(bitmap: Bitmap, label: String) {
        try {
            // Analyze pixel distribution
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            
            var whitePixels = 0
            var blackPixels = 0
            var grayPixels = 0
            var otherPixels = 0
            
            pixels.forEach { pixel ->
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                val brightness = (r + g + b) / 3
                
                when {
                    brightness > 200 -> whitePixels++
                    brightness < 50 -> blackPixels++
                    brightness in 50..200 -> grayPixels++
                    else -> otherPixels++
                }
            }
            
            val totalPixels = pixels.size
            Log.d(TAG, "$label Image Analysis:")
            Log.d(TAG, "  Total pixels: $totalPixels")
            Log.d(TAG, "  White pixels: $whitePixels (${(whitePixels * 100f / totalPixels).toInt()}%)")
            Log.d(TAG, "  Black pixels: $blackPixels (${(blackPixels * 100f / totalPixels).toInt()}%)")
            Log.d(TAG, "  Gray pixels: $grayPixels (${(grayPixels * 100f / totalPixels).toInt()}%)")
            Log.d(TAG, "  Other pixels: $otherPixels (${(otherPixels * 100f / totalPixels).toInt()}%)")
            
            // Check if image is mostly black (empty render)
            if (blackPixels > totalPixels * 0.9) {
                Log.w(TAG, "$label WARNING: Image is mostly black - model may not be rendering properly!")
            }
            
            // Check if there's enough contrast for face detection
            if (whitePixels < totalPixels * 0.05) {
                Log.w(TAG, "$label WARNING: Very few white pixels - model may be too dark for face detection!")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error analyzing image characteristics", e)
        }
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