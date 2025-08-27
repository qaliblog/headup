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

import android.util.Log
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
 * Data class representing a complete 3D model
 */
data class Model3D(
    val vertices: List<Vertex3D>,
    val faces: List<Face3D>,
    val centroid: Vertex3D,
    val boundingBox: Pair<Vertex3D, Vertex3D> // min, max
) {
    fun getScaleFactor(targetSize: Float = 1.0f): Float {
        val size = maxOf(
            boundingBox.second.x - boundingBox.first.x,
            boundingBox.second.y - boundingBox.first.y,
            boundingBox.second.z - boundingBox.first.z
        )
        return targetSize / size
    }
}

/**
 * Parser for 3D model files
 */
class Model3DParser {
    
    companion object {
        private const val TAG = "Model3DParser"
    }
    
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
            Model3D(vertices, faces, centroid, boundingBox)
            
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