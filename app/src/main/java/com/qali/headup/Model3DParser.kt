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

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

/**
 * Data class representing a 3D vertex
 */
data class Vertex3D(val x: Float, val y: Float, val z: Float) {
    operator fun plus(other: Vertex3D) = Vertex3D(x + other.x, y + other.y, z + other.z)
    operator fun minus(other: Vertex3D) = Vertex3D(x - other.x, y - other.y, z - other.z)
    operator fun times(scalar: Float) = Vertex3D(x * scalar, y * scalar, z * scalar)
    operator fun div(scalar: Float) = Vertex3D(x / scalar, y / scalar, z / scalar)
    
    fun magnitude() = sqrt(x * x + y * y + z * z)
    fun normalize() = this / magnitude()
    
    fun dot(other: Vertex3D) = x * other.x + y * other.y + z * other.z
    fun cross(other: Vertex3D) = Vertex3D(
        y * other.z - z * other.y,
        z * other.x - x * other.z,
        x * other.y - y * other.x
    )
}

/**
 * Data class representing a 3D face/triangle
 */
data class Face3D(val v1: Int, val v2: Int, val v3: Int)

/**
 * Data class for face analysis data on 3D models
 */
data class Model3DFaceData(
    val landmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>? = null,
    val boundingBox: android.graphics.RectF? = null,
    val confidence: Float = 0f,
    val rotationStatistics: Map<String, Any>? = null
)

/**
 * Data class representing a complete 3D model
 */
data class Model3D(
    val vertices: List<Vertex3D>,
    val faces: List<Face3D>,
    val centroid: Vertex3D,
    val boundingBox: Pair<Vertex3D, Vertex3D>, // min, max
    val faceData: Model3DFaceData? = null // Face analysis data if available
) {
    fun getScaleFactor(targetSize: Float = 1.0f): Float {
        val size = maxOf(
            boundingBox.second.x - boundingBox.first.x,
            boundingBox.second.y - boundingBox.first.y,
            boundingBox.second.z - boundingBox.first.z
        )
        return targetSize / size
    }
    
    val hasFaceData: Boolean
        get() = faceData != null
}

/**
 * Data classes for GLB/GLTF structure
 */
data class GLTFAsset(
    val version: String? = null,
    val generator: String? = null
)

data class GLTFBuffer(
    val byteLength: Int,
    val uri: String? = null
)

data class GLTFBufferView(
    val buffer: Int,
    val byteOffset: Int = 0,
    val byteLength: Int,
    val target: Int? = null
)

data class GLTFAccessor(
    val bufferView: Int,
    val byteOffset: Int = 0,
    val componentType: Int,
    val count: Int,
    val type: String,
    val min: List<Float>? = null,
    val max: List<Float>? = null
)

data class GLTFMesh(
    val primitives: List<GLTFPrimitive>
)

data class GLTFPrimitive(
    val attributes: Map<String, Int>,
    val indices: Int? = null,
    val mode: Int = 4 // TRIANGLES
)

data class GLTFNode(
    val mesh: Int? = null,
    val children: List<Int>? = null
)

data class GLTFScene(
    val nodes: List<Int>
)

data class GLTF(
    val asset: GLTFAsset,
    val buffers: List<GLTFBuffer>? = null,
    val bufferViews: List<GLTFBufferView>? = null,
    val accessors: List<GLTFAccessor>? = null,
    val meshes: List<GLTFMesh>? = null,
    val nodes: List<GLTFNode>? = null,
    val scenes: List<GLTFScene>? = null,
    val scene: Int? = null
)

/**
 * Parser for 3D model files
 */
class Model3DParser(private val context: android.content.Context) {
    
    companion object {
        private const val TAG = "Model3DParser"
    }
    
    private val faceAnalyzer = Model3DFaceAnalyzer(context)
    
    /**
     * Parse OBJ file content
     */
    fun parseOBJ(content: String): Model3D? {
        return try {
            val vertices = mutableListOf<Vertex3D>()
            val faces = mutableListOf<Face3D>()
            
            content.lines().forEach { line ->
                val trimmedLine = line.trim()
                when {
                    trimmedLine.startsWith("v ") -> {
                        parseVertex(trimmedLine)?.let { vertices.add(it) }
                    }
                    trimmedLine.startsWith("f ") -> {
                        parseFace(trimmedLine)?.let { faces.add(it) }
                    }
                }
            }
            
            if (vertices.isEmpty()) {
                Log.w(TAG, "No vertices found in OBJ file")
                return null
            }
            
            val centroid = calculateCentroid(vertices)
            val boundingBox = calculateBoundingBox(vertices)
            
            Log.d(TAG, "Parsed OBJ: ${vertices.size} vertices, ${faces.size} faces")
            
            // Create initial model without face data
            val initialModel = Model3D(vertices, faces, centroid, boundingBox)
            
            // Analyze for facial landmarks
            Log.d(TAG, "Analyzing OBJ model for facial features...")
            val faceData = faceAnalyzer.analyzeModel3DFace(initialModel)
            
            if (faceData != null) {
                Log.d(TAG, "Face detected in OBJ model: ${faceData.landmarks.size} landmarks")
                Model3D(vertices, faces, centroid, boundingBox, faceData)
            } else {
                Log.w(TAG, "No face detected in OBJ model")
                initialModel
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing OBJ file", e)
            null
        }
    }
    
    private fun parseVertex(line: String): Vertex3D? {
        return try {
            val parts = line.split("\\s+".toRegex())
            if (parts.size >= 4) {
                Vertex3D(
                    parts[1].toFloat(),
                    parts[2].toFloat(),
                    parts[3].toFloat()
                )
            } else null
        } catch (e: Exception) {
            Log.w(TAG, "Error parsing vertex: $line", e)
            null
        }
    }
    
    private fun parseFace(line: String): Face3D? {
        return try {
            val parts = line.split("\\s+".toRegex())
            if (parts.size >= 4) {
                // Handle faces with texture/normal indices (e.g., "f 1/1/1 2/2/2 3/3/3")
                val v1 = parts[1].split("/")[0].toInt() - 1 // OBJ indices are 1-based
                val v2 = parts[2].split("/")[0].toInt() - 1
                val v3 = parts[3].split("/")[0].toInt() - 1
                Face3D(v1, v2, v3)
            } else null
        } catch (e: Exception) {
            Log.w(TAG, "Error parsing face: $line", e)
            null
        }
    }
    
    private fun calculateCentroid(vertices: List<Vertex3D>): Vertex3D {
        if (vertices.isEmpty()) return Vertex3D(0f, 0f, 0f)
        
        val sum = vertices.reduce { acc, vertex -> acc + vertex }
        return sum / vertices.size.toFloat()
    }
    
    private fun calculateBoundingBox(vertices: List<Vertex3D>): Pair<Vertex3D, Vertex3D> {
        if (vertices.isEmpty()) {
            return Pair(Vertex3D(0f, 0f, 0f), Vertex3D(0f, 0f, 0f))
        }
        
        var minX = vertices[0].x
        var maxX = vertices[0].x
        var minY = vertices[0].y
        var maxY = vertices[0].y
        var minZ = vertices[0].z
        var maxZ = vertices[0].z
        
        vertices.forEach { vertex ->
            minX = min(minX, vertex.x)
            maxX = max(maxX, vertex.x)
            minY = min(minY, vertex.y)
            maxY = max(maxY, vertex.y)
            minZ = min(minZ, vertex.z)
            maxZ = max(maxZ, vertex.z)
        }
        
        return Pair(
            Vertex3D(minX, minY, minZ),
            Vertex3D(maxX, maxY, maxZ)
        )
    }
    
    /**
     * Parse GLB file content
     */
    fun parseGLB(data: ByteArray): Model3D? {
        return try {
            Log.d(TAG, "Starting GLB parsing, file size: ${data.size} bytes")
            
            val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            
            // Read GLB header
            val magic = buffer.int
            if (magic != 0x46546C67) { // "glTF" in little-endian
                Log.e(TAG, "Invalid GLB magic number: ${magic.toString(16)}")
                return null
            }
            
            val version = buffer.int
            val length = buffer.int
            
            Log.d(TAG, "GLB version: $version, length: $length")
            
            // Read first chunk (JSON)
            val jsonChunkLength = buffer.int
            val jsonChunkType = buffer.int
            
            if (jsonChunkType != 0x4E4F534A) { // "JSON" in little-endian
                Log.e(TAG, "Expected JSON chunk, got: ${jsonChunkType.toString(16)}")
                return null
            }
            
            val jsonBytes = ByteArray(jsonChunkLength)
            buffer.get(jsonBytes)
            val jsonString = String(jsonBytes, Charsets.UTF_8)
            
            Log.d(TAG, "JSON length: $jsonChunkLength")
            
            // Parse GLTF JSON
            val gltf = Gson().fromJson(jsonString, GLTF::class.java)
            
            // Read binary chunk if present
            var binaryData: ByteArray? = null
            if (buffer.remaining() >= 8) {
                val binChunkLength = buffer.int
                val binChunkType = buffer.int
                
                if (binChunkType == 0x004E4942) { // "BIN\0" in little-endian
                    binaryData = ByteArray(binChunkLength)
                    buffer.get(binaryData)
                    Log.d(TAG, "Binary chunk length: $binChunkLength")
                }
            }
            
            // Extract mesh data
            extractMeshFromGLTF(gltf, binaryData)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing GLB file", e)
            null
        }
    }
    
    private fun extractMeshFromGLTF(gltf: GLTF, binaryData: ByteArray?): Model3D? {
        try {
            val meshes = gltf.meshes ?: return null
            val accessors = gltf.accessors ?: return null
            val bufferViews = gltf.bufferViews ?: return null
            
            if (meshes.isEmpty()) {
                Log.w(TAG, "No meshes found in GLTF")
                return null
            }
            
            val allVertices = mutableListOf<Vertex3D>()
            val allFaces = mutableListOf<Face3D>()
            
            // Process first mesh
            val mesh = meshes[0]
            for (primitive in mesh.primitives) {
                val positionAccessorIndex = primitive.attributes["POSITION"]
                if (positionAccessorIndex == null) {
                    Log.w(TAG, "No POSITION attribute found in primitive")
                    continue
                }
                
                // Get vertices
                val vertices = extractVerticesFromAccessor(
                    accessors[positionAccessorIndex],
                    bufferViews,
                    binaryData
                )
                
                if (vertices.isEmpty()) {
                    continue
                }
                
                val vertexOffset = allVertices.size
                allVertices.addAll(vertices)
                
                // Get indices if present
                val indicesAccessorIndex = primitive.indices
                if (indicesAccessorIndex != null) {
                    val indices = extractIndicesFromAccessor(
                        accessors[indicesAccessorIndex],
                        bufferViews,
                        binaryData
                    )
                    
                    // Create faces from indices
                    for (i in indices.indices step 3) {
                        if (i + 2 < indices.size) {
                            allFaces.add(
                                Face3D(
                                    vertexOffset + indices[i],
                                    vertexOffset + indices[i + 1],
                                    vertexOffset + indices[i + 2]
                                )
                            )
                        }
                    }
                } else {
                    // Create faces from sequential vertices (assuming triangles)
                    for (i in vertices.indices step 3) {
                        if (i + 2 < vertices.size) {
                            allFaces.add(
                                Face3D(
                                    vertexOffset + i,
                                    vertexOffset + i + 1,
                                    vertexOffset + i + 2
                                )
                            )
                        }
                    }
                }
            }
            
            if (allVertices.isEmpty()) {
                Log.w(TAG, "No vertices extracted from GLB")
                return null
            }
            
            val centroid = calculateCentroid(allVertices)
            val boundingBox = calculateBoundingBox(allVertices)
            
            Log.d(TAG, "GLB parsed: ${allVertices.size} vertices, ${allFaces.size} faces")
            
            // Create initial model without face data
            val initialModel = Model3D(allVertices, allFaces, centroid, boundingBox)
            
            // Analyze for facial landmarks
            Log.d(TAG, "Analyzing GLB model for facial features...")
            val faceData = faceAnalyzer.analyzeModel3DFace(initialModel)
            
            if (faceData != null) {
                Log.d(TAG, "Face detected in GLB model: ${faceData.landmarks.size} landmarks")
                return Model3D(allVertices, allFaces, centroid, boundingBox, faceData)
            } else {
                Log.w(TAG, "No face detected in GLB model")
                return initialModel
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting mesh from GLTF", e)
            return null
        }
    }
    
    private fun extractVerticesFromAccessor(
        accessor: GLTFAccessor,
        bufferViews: List<GLTFBufferView>,
        binaryData: ByteArray?
    ): List<Vertex3D> {
        try {
            if (accessor.type != "VEC3") {
                Log.w(TAG, "Expected VEC3 for positions, got: ${accessor.type}")
                return emptyList()
            }
            
            val bufferView = bufferViews[accessor.bufferView]
            val data = binaryData ?: return emptyList()
            
            val offset = bufferView.byteOffset + accessor.byteOffset
            val buffer = ByteBuffer.wrap(data, offset, bufferView.byteLength)
                .order(ByteOrder.LITTLE_ENDIAN)
            
            val vertices = mutableListOf<Vertex3D>()
            
            when (accessor.componentType) {
                5126 -> { // FLOAT
                    repeat(accessor.count) {
                        val x = buffer.float
                        val y = buffer.float
                        val z = buffer.float
                        vertices.add(Vertex3D(x, y, z))
                    }
                }
                else -> {
                    Log.w(TAG, "Unsupported component type: ${accessor.componentType}")
                    return emptyList()
                }
            }
            
            return vertices
            
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting vertices", e)
            return emptyList()
        }
    }
    
    private fun extractIndicesFromAccessor(
        accessor: GLTFAccessor,
        bufferViews: List<GLTFBufferView>,
        binaryData: ByteArray?
    ): List<Int> {
        try {
            if (accessor.type != "SCALAR") {
                Log.w(TAG, "Expected SCALAR for indices, got: ${accessor.type}")
                return emptyList()
            }
            
            val bufferView = bufferViews[accessor.bufferView]
            val data = binaryData ?: return emptyList()
            
            val offset = bufferView.byteOffset + accessor.byteOffset
            val buffer = ByteBuffer.wrap(data, offset, bufferView.byteLength)
                .order(ByteOrder.LITTLE_ENDIAN)
            
            val indices = mutableListOf<Int>()
            
            when (accessor.componentType) {
                5121 -> { // UNSIGNED_BYTE
                    repeat(accessor.count) {
                        indices.add(buffer.get().toInt() and 0xFF)
                    }
                }
                5123 -> { // UNSIGNED_SHORT
                    repeat(accessor.count) {
                        indices.add(buffer.short.toInt() and 0xFFFF)
                    }
                }
                5125 -> { // UNSIGNED_INT
                    repeat(accessor.count) {
                        indices.add(buffer.int)
                    }
                }
                else -> {
                    Log.w(TAG, "Unsupported index component type: ${accessor.componentType}")
                    return emptyList()
                }
            }
            
            return indices
            
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting indices", e)
            return emptyList()
        }
    }

    /**
     * Create a simple 3D object (cube) for testing purposes
     */
    fun createTestCube(size: Float = 1.0f): Model3D {
        val half = size / 2
        val vertices = listOf(
            // Front face
            Vertex3D(-half, -half, half),
            Vertex3D(half, -half, half),
            Vertex3D(half, half, half),
            Vertex3D(-half, half, half),
            // Back face
            Vertex3D(-half, -half, -half),
            Vertex3D(half, -half, -half),
            Vertex3D(half, half, -half),
            Vertex3D(-half, half, -half)
        )
        
        val faces = listOf(
            // Front face
            Face3D(0, 1, 2), Face3D(2, 3, 0),
            // Back face
            Face3D(4, 6, 5), Face3D(6, 4, 7),
            // Left face
            Face3D(4, 0, 3), Face3D(3, 7, 4),
            // Right face
            Face3D(1, 5, 6), Face3D(6, 2, 1),
            // Top face
            Face3D(3, 2, 6), Face3D(6, 7, 3),
            // Bottom face
            Face3D(4, 5, 1), Face3D(1, 0, 4)
        )
        
        val centroid = Vertex3D(0f, 0f, 0f)
        val boundingBox = Pair(Vertex3D(-half, -half, -half), Vertex3D(half, half, half))
        
        return Model3D(vertices, faces, centroid, boundingBox)
    }
}