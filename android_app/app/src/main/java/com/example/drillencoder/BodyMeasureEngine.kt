package com.example.drillencoder

import android.media.Image
import com.google.ar.core.CameraIntrinsics
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import java.nio.ShortBuffer
import java.util.Arrays
import kotlin.math.pow
import kotlin.math.sqrt

class BodyMeasureEngine {

    // Cache for median filtering to avoid frequent object allocation (GC Friendly)
    private val depthWindowCache = ShortArray(25) // 5x5 window

    // Buffer for transformed depth coordinates
    private val depthUvCoords = FloatArray(2)

    // Buffer for input YOLO coordinates
    private val yoloCoords = FloatArray(2)

    /**
     * Core method: Input YOLO 2D points, return 3D points in camera coordinate system (meters)
     * @param frame       ARCore current frame
     * @param yoloX       YOLO detected X (pixels)
     * @param yoloY       YOLO detected Y (pixels)
     * @param inputWidth  Width of input source for YOLO (e.g., screen width or RGB image width)
     * @param inputHeight Height of input source for YOLO
     * @return FloatArray {x, y, z} Unit: meters. Returns null if measurement fails.
     */
    /**
     * Core method: Input IMAGE_NORMALIZED 2D points, return 3D points in camera coordinate system (meters)
     * Strategy 4: Integrated Normalized Workflow
     * @param frame       ARCore current frame
     * @param depthImage  The acquired depth image
     * @param normX       Normalized X (0.0 - 1.0) relative to Camera Image
     * @param normY       Normalized Y (0.0 - 1.0) relative to Camera Image
     * @return FloatArray {x, y, z} Unit: meters. Returns null if measurement fails.
     */
    fun get3DJointPosition(
        frame: Frame, 
        depthImage: Image, 
        normX: Float, 
        normY: Float, 
        debugLog: ((String) -> Unit)? = null
    ): FloatArray? {
        try {
            // 1. Scale for Intrinsics (Math Alignment)
            // We need the 'pixel' coordinates relative to the Intrinsics resolution for the Pinhole Model
            val intrinsics = frame.camera.imageIntrinsics
            val dim = intrinsics.imageDimensions
            // Important: intrinsics dimensions usually match the CPU image resolution (e.g. 640x480)
            val u_i = normX * dim[0]
            val v_i = normY * dim[1]

            // 2. Scale for Depth Sampling
            // We need the 'pixel' coordinates relative to the Depth Image resolution (e.g. 160x120)
            val dW = depthImage.width
            val dH = depthImage.height
            
            // Clamp to safe bounds to avoid crashes at edges
            val dU = (normX * dW).toInt().coerceIn(0, dW - 1)
            val dV = (normY * dH).toInt().coerceIn(0, dH - 1)
            
            // 3. Get Depth (Z) with Stability
            // Use 3x3 Median Filter to ignore noise (Strategy 4 Recommendation)
            var z = getSmoothedDepth(depthImage, dU, dV) 
            
            // Strategy 1: Spiral Search Fallback
            if (z <= 0) {
                 z = getSpiralDepth(depthImage, dU, dV)
            }
            
            if (z <= 0) {
                // debugLog?.invoke("Invalid Depth: $z at ($dU, $dV)")
                return null
            }

            // 4. Unproject (Pinhole Model)
            // Formula: X = (u - cx) * Z / fx
            val principals = intrinsics.principalPoint // {cx, cy}
            val focals = intrinsics.focalLength        // {fx, fy}
            
            val x = (u_i - principals[0]) * z / focals[0]
            val y = (v_i - principals[1]) * z / focals[1]
            
            return floatArrayOf(x, y, z)

        } catch (e: Exception) {
            debugLog?.invoke("Error in BodyMeasureEngine: ${e.message}")
            // e.printStackTrace() // Keep logs clean
            return null
        }
    }

    /**
     * Backward compatibility wrapper if needed, or alias for the main function.
     * In Strategy 4, we use the provided depth image directly.
     */
    fun get3DJointPositionWithProvidedDepth(
        frame: Frame,
        depthImage: Image,
        normX: Float,
        normY: Float,
        debugLog: ((String) -> Unit)? = null
    ): FloatArray? {
        return get3DJointPosition(frame, depthImage, normX, normY, debugLog)
    }

    /**
     * Extract and calculate median depth from Raw Depth Buffer
     * Implements 3x3 Median Filter for Stability
     */
    private fun getSmoothedDepth(depthImage: Image, cx: Int, cy: Int): Float {
        val buffer = depthImage.planes[0].buffer.order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val width = depthImage.width
        val height = depthImage.height
        var validCount = 0

        // 3x3 window scan (Radius = 1) - Safer for edges than 5x5, sufficient for stability
        val scanRadius = 1 
        
        for (y in cy - scanRadius..cy + scanRadius) {
            for (x in cx - scanRadius..cx + scanRadius) {
                // Bounds check
                if (x < 0 || x >= width || y < 0 || y >= height) continue

                val index = y * width + x
                val pixel = buffer.get(index).toInt()

                // Parse Raw Depth: first 13 bits are distance (mm)
                val depthMm = pixel and 0x1FFF
                
                // Filter: Exclude 0 (Invalid)
                if (depthMm > 0) {
                    depthWindowCache[validCount++] = depthMm.toShort()
                }
            }
        }

        if (validCount == 0) return -1f

        // Calculate median
        Arrays.sort(depthWindowCache, 0, validCount)
        val medianMm: Float = if (validCount % 2 == 1) {
            depthWindowCache[validCount / 2].toFloat()
        } else {
            (depthWindowCache[validCount / 2 - 1] + depthWindowCache[validCount / 2]) / 2.0f
        }

        return medianMm / 1000.0f // Convert to meters
    }

    /**
     * Strategy 1: Smart Spiral Search
     * Searches for a valid depth value in a spiral pattern around the given center.
     * Uses a pre-computed efficient lookup table to minimize CPU usage.
     */
    private fun getSpiralDepth(
        depthImage: Image, 
        centerX: Int, 
        centerY: Int
    ): Float {
        // 1. Check center again (redundant but safe)
        val centerDepth = getSmoothedDepth(depthImage, centerX, centerY)
        if (centerDepth > 0) return centerDepth

        val buffer = depthImage.planes[0].buffer.order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val width = depthImage.width
        val height = depthImage.height

        // 2. Iterate through pre-computed spiral offsets
        // format: [dx1, dy1, dx2, dy2, ...]
        for (i in 0 until spiralOffsets.size step 2) {
            val dx = spiralOffsets[i]
            val dy = spiralOffsets[i+1]
            
            val tx = centerX + dx
            val ty = centerY + dy
            
            // Bounds check
            if (tx < 0 || tx >= width || ty < 0 || ty >= height) continue
            
            // Direct Raw Read (Faster than median filter for search)
            val index = ty * width + tx
            val pixel = buffer.get(index).toInt()
            val depthMm = pixel and 0x1FFF
            
            if (depthMm > 0) {
                // Found valid pixel! Return immediately (Early Exit)
                return depthMm / 1000.0f
            }
        }
        
        return -1f // Truly invalid
    }

    companion object {
        /**
         * Helper: Calculate Euclidean distance between two 3D points
         */
        fun calculateDistance(p1: FloatArray?, p2: FloatArray?): Float {
            if (p1 == null || p2 == null) return -1f
            return sqrt(
                (p1[0] - p2[0]).pow(2) +
                (p1[1] - p2[1]).pow(2) +
                (p1[2] - p2[2]).pow(2)
            )
        }
        
        /**
         * Pre-computed Spiral Offsets
         * Generated for Radius 15 (Max)
         * Interleaved dx, dy
         */
        private val spiralOffsets = IntArray(450).apply {
             // Simple generator embedded in static block to keep code clean but offsets static
             // In a real optimized build we might hardcode the array, but this runs once at class load.
             var idx = 0
             var x = 0
             var y = 0
             var dx = 0
             var dy = -1
             val maxRadius = 15
             // Max iterations to cover roughly 15px radius box (~225 points * 2)
             for (j in 0 until 900) { 
                 if (-maxRadius <= x && x <= maxRadius && -maxRadius <= y && y <= maxRadius) {
                     if (x != 0 || y != 0) { // Skip center (already checked)
                         if (idx < size - 1) {
                             this[idx++] = x
                             this[idx++] = y
                         } else {
                            break
                         }
                     }
                 }
                 if (x == y || (x < 0 && x == -y) || (x > 0 && x == 1 - y)) {
                     val t = dx
                     dx = -dy
                     dy = t
                 }
                 x += dx
                 y += dy
             }
        }
    }
}
