package com.example.drillencoder

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.Image
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicYuvToRGB
import android.renderscript.Type
import java.nio.ByteBuffer

object ImageUtils {

    private var rs: RenderScript? = null

    /**
     * Converts an NV21 byte array to a Bitmap using RenderScript.
     */
    fun nv21ToBitmap(nv21: ByteArray, width: Int, height: Int, context: Context): Bitmap {
        if (rs == null) {
            rs = RenderScript.create(context)
        }

        val yuvToRgbIntrinsic = ScriptIntrinsicYuvToRGB.create(rs, Element.U8_4(rs))

        val yuvType = Type.Builder(rs, Element.U8(rs)).setX(width).setY(height).setYuvFormat(ImageFormat.NV21)
        val yuvAllocation = Allocation.createTyped(rs, yuvType.create(), Allocation.USAGE_SCRIPT)
        yuvAllocation.copyFrom(nv21)

        val outBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val outAllocation = Allocation.createFromBitmap(rs, outBitmap, Allocation.MipmapControl.MIPMAP_NONE, Allocation.USAGE_SCRIPT)

        yuvToRgbIntrinsic.setInput(yuvAllocation)
        yuvToRgbIntrinsic.forEach(outAllocation)
        outAllocation.copyTo(outBitmap)
        
        // Clean up
        yuvAllocation.destroy()
        outAllocation.destroy()
        yuvToRgbIntrinsic.destroy()

        return outBitmap
    }

    /**
     * Converts a YUV_420_888 image to a Bitmap using RenderScript.
     * This is faster than the compress-to-JPEG method.
     */
    fun yuv420ToBitmap(image: Image, context: Context): Bitmap {
        return nv21ToBitmap(imageToNv21ByteArray(image), image.width, image.height, context)
    }

    /**
     * Converts YUV_420_888 Image to NV21 byte array.
     * This is a best-effort conversion. It assumes semi-planar (Y-plane, and interleaved UV plane)
     * which is common for camera2.
     */
    fun imageToNv21ByteArray(image: Image): ByteArray {
        val width = image.width
        val height = image.height
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        val yRowStride = yPlane.rowStride
        val uRowStride = uPlane.rowStride
        val vRowStride = vPlane.rowStride

        val uPixelStride = uPlane.pixelStride
        val vPixelStride = vPlane.pixelStride

        val nv21 = ByteArray(width * height + width * height / 2)
        var pos = 0

        // Copy Y plane
        if (yRowStride == width) {
            val ySize = yBuffer.remaining()
            yBuffer.get(nv21, 0, ySize)
            pos += ySize
        } else {
            // If rowStride > width, copy row by row
            val yRowBytes = ByteArray(width)
            for (row in 0 until height) {
                yBuffer.position(row * yRowStride)
                yBuffer.get(yRowBytes, 0, width)
                System.arraycopy(yRowBytes, 0, nv21, pos, width)
                pos += width
            }
        }

        // Copy U/V planes (interleaved for NV21: V, U, V, U...)
        // NV21 expects V first, then U
        val uvHeight = height / 2
        val uvWidth = width / 2
        val uRow = ByteArray(uRowStride)
        val vRow = ByteArray(vRowStride)

        for (row in 0 until uvHeight) {
            // Read U row safely
            uBuffer.position(row * uRowStride)
            val uRemaining = uBuffer.remaining()
            val uToRead = if (uRowStride <= uRemaining) uRowStride else uRemaining
            uBuffer.get(uRow, 0, uToRead)

            // Read V row safely
            vBuffer.position(row * vRowStride)
            val vRemaining = vBuffer.remaining()
            val vToRead = if (vRowStride <= vRemaining) vRowStride else vRemaining
            vBuffer.get(vRow, 0, vToRead)

            for (col in 0 until uvWidth) {
                if (pos >= nv21.size - 1) break 
                
                // NV21 format: V first, then U
                val vVal = vRow[col * vPixelStride]
                val uVal = uRow[col * uPixelStride]
                
                nv21[pos++] = vVal
                nv21[pos++] = uVal
            }
        }

        return nv21
    }
}
