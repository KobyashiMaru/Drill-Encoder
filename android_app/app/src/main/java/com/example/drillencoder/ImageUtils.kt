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

        val yPixelStride = yPlane.pixelStride
        val uPixelStride = uPlane.pixelStride
        val vPixelStride = vPlane.pixelStride

        val ySize = yBuffer.remaining()

        // Full size of NV21 buffer
        val nv21 = ByteArray(width * height * 3 / 2)
        
        var pos = 0
        // Copy Y plane
        if (yRowStride == width) {
            yBuffer.get(nv21, pos, ySize)
            pos += ySize
        } else {
            var yBufferPos = 0
            for (row in 0 until height) {
                yBuffer.position(yBufferPos)
                yBuffer.get(nv21, pos, width)
                pos += width
                yBufferPos += yRowStride
            }
        }

        // Copy Interleaved U and V planes
        val uRow = ByteArray(uRowStride)
        val vRow = ByteArray(vRowStride)
        
        for (row in 0 until height / 2) {
            uBuffer.position(row * uRowStride)
            uBuffer.get(uRow, 0, uRowStride)
            
            vBuffer.position(row * vRowStride)
            vBuffer.get(vRow, 0, vRowStride)
            
            for (col in 0 until width / 2) {
                nv21[pos++] = vRow[col * vPixelStride]
                nv21[pos++] = uRow[col * uPixelStride]
            }
        }
        
        return nv21
    }
}
