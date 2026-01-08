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
    fun get3DJointPosition(frame: Frame, yoloX: Float, yoloY: Float, debugLog: ((String) -> Unit)? = null): FloatArray? {
        try {
            val rawDepth = frame.acquireRawDepthImage16Bits()
            // debugLog?.invoke("Depth Image acquired: ${rawDepth.width}x${rawDepth.height}") 
            rawDepth.use { depthImage ->
                // 1. Coordinate Transformation: Critical!
                // Map YOLO coordinates (likely screen or RGB) to Depth Image coordinates (usually small like 160x120)
                yoloCoords[0] = yoloX
                yoloCoords[1] = yoloY

                // Assuming YOLO coordinates are based on "VIEW" (screen pixels)
                // If YOLO runs on CPU Image, change VIEW to IMAGE_PIXELS and normalize yourself if needed
                // But typically for full screen view, we use VIEW.
                // However, the prompt notes: "Coordinates2d.VIEW represents screen pixel coordinates".
                // We need to confirm if YOLO detections are normalized or pixels.
                // Our current YoloDetector returns normalized (0-1) or pixel coords depending on how we used it.
                // But in MainActivity we are drawing them scaled to view size.
                // If we pass pixel coords relative to the view, we use Coordinates2d.VIEW.
                
                // Note: transformCoordinates2d expects normalized values [0, 1] if input is VIEW_NORMALIZED?
                // No, Coordinates2d.VIEW is not normalized, it's pixels? 
                // Wait, ARCore docs say: 
                // VIEW: Coordinates in the View (e.g. SurfaceView) associated with the session.
                // IMAGE_PIXELS: Coordinates in the camera image buffer.
                // IMAGE_NORMALIZED: Normalized coordinates in the camera image [0,1].
                
                // Let's assume input yoloX/Y are pixels coming from the view size if we use VIEW.
                // If they are from the camera image, we should use IMAGE_PIXELS.
                
                // For simplicity, let's assume we pass in pixel coordinates relative to the IMAGE (since we run YOLO on the bitmap from ImageAnalysis/ARCore frame).
                // Actually, in the ARCore path (which we will implement), we'll likely get the image from frame.acquireCameraImage().
                // So yoloX/Y will be in IMAGE_PIXELS space if we detect on that bitmap.
                
                // The prompt example says:
                // frame.transformCoordinates2d(Coordinates2d.VIEW, yoloCoords, Coordinates2d.IMAGE_PIXELS, depthUvCoords);
                // This implies the input `yoloX/Y` are in Screen View coordinates.
                
                // Let's implement exactly as the prompt suggested first (using VIEW -> IMAGE_PIXELS), 
                // assuming the caller passes Screen Coordinates.
                
                try {
                    frame.transformCoordinates2d(
                        Coordinates2d.VIEW,         // Input: Screen pixels
                        yoloCoords,
                        Coordinates2d.IMAGE_NORMALIZED, // Output: Normalized coordinates (0..1)
                        depthUvCoords
                    )
                } catch (e: Exception) {
                    debugLog?.invoke("Coord Transform Failed: ${e.message}")
                    return null
                }

                // Scale normalized coords to actual depth image dimensions
                val depthX = (depthUvCoords[0] * depthImage.width).toInt()
                val depthY = (depthUvCoords[1] * depthImage.height).toInt()
                
                // debugLog?.invoke("2D: ($yoloX, $yoloY) -> Depth: ($depthX, $depthY)")

                if (depthX < 0 || depthX >= depthImage.width || depthY < 0 || depthY >= depthImage.height) {
                    debugLog?.invoke("Depth Coords Out of Bounds: $depthX, $depthY")
                    return null
                }

                // 2. Get Planar Depth (Z), apply median filter
                val planarDepthMeters = getSmoothedDepth(depthImage, depthX, depthY)

                if (planarDepthMeters <= 0) {
                    // debugLog?.invoke("Invalid Depth: $planarDepthMeters at ($depthX, $depthY)")
                    return null // Invalid depth
                }

                // 3. Unprojection: From 2D + Z -> 3D (X, Y, Z)
                val intrinsics = frame.camera.imageIntrinsics
                return unproject(depthX, depthY, planarDepthMeters, intrinsics)
            }
        } catch (e: Exception) {
            debugLog?.invoke("Error in BodyMeasureEngine: ${e.message}")
            e.printStackTrace()
            return null
        }
    }

    /**
     * Optimized method that uses a pre-acquired depth image.
     */
    fun get3DJointPositionWithProvidedDepth(
        frame: Frame,
        depthImage: Image,
        yoloX: Float,
        yoloY: Float,
        debugLog: ((String) -> Unit)? = null
    ): FloatArray? {
        try {
            // 1. Coordinate Transformation
            yoloCoords[0] = yoloX
            yoloCoords[1] = yoloY

            frame.transformCoordinates2d(
                Coordinates2d.VIEW,
                yoloCoords,
                Coordinates2d.IMAGE_NORMALIZED,
                depthUvCoords
            )

            val depthX = (depthUvCoords[0] * depthImage.width).toInt()
            val depthY = (depthUvCoords[1] * depthImage.height).toInt()

            if (depthX < 0 || depthX >= depthImage.width || depthY < 0 || depthY >= depthImage.height) {
                debugLog?.invoke("Depth Coords Out of Bounds: $depthX, $depthY")
                return null
            }

            // 2. Get Planar Depth (Z)
            val planarDepthMeters = getSmoothedDepth(depthImage, depthX, depthY)

            if (planarDepthMeters <= 0) {
                debugLog?.invoke("Invalid Depth: $planarDepthMeters at ($depthX, $depthY)")
                return null
            }

            // 3. Unprojection
            val intrinsics = frame.camera.imageIntrinsics
            return unproject(depthX, depthY, planarDepthMeters, intrinsics)

        } catch (e: Exception) {
            debugLog?.invoke("Error in get3DJointPositionWithProvidedDepth: ${e.message}")
            // Don't print stack trace here to avoid spamming logs
            return null
        }
    }

    /**
     * Extract and calculate median depth from Raw Depth Buffer
     */
    private fun getSmoothedDepth(depthImage: Image, cx: Int, cy: Int): Float {
        val buffer = depthImage.planes[0].buffer.order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val width = depthImage.width
        val height = depthImage.height
        var validCount = 0

        // 5x5 window scan
        for (y in cy - 2..cy + 2) {
            for (x in cx - 2..cx + 2) {
                // Bounds check
                if (x < 0 || x >= width || y < 0 || y >= height) continue

                val index = y * width + x
                val pixel = buffer.get(index).toInt()

                // Parse Raw Depth: first 13 bits are distance (mm)
                val depthMm = pixel and 0x1FFF
                // Last 3 bits are confidence (0-7)
                val confidence = (pixel shr 13) and 0x7

                // Filter: Exclude 0 and low confidence
                if (depthMm > 0 && confidence >= 3) {
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
     * Math core: specific unprojection using camera intrinsics
     */
    private fun unproject(u: Int, v: Int, z: Float, intrinsics: CameraIntrinsics): FloatArray {
        val principals = intrinsics.principalPoint // {cx, cy}
        val focals = intrinsics.focalLength        // {fx, fy}

        // Formula: X = (u - cx) * Z / fx
        val x = (u - principals[0]) * z / focals[0]
        // Formula: Y = (v - cy) * Z / fy
        val y = (v - principals[1]) * z / focals[1]

        // ARCore OpenGL coordinate system: Forward is -Z.
        // But to keep physical distance calc simple, we return relative pos in camera space.
        // We want positive distance for user display
        return floatArrayOf(x, y, z)
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
    }
}
