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
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer

/**
 * Helper class used to efficiently convert a [Media.Image] object from
 * [ImageAnalysis] into an RGB [Bitmap] instead of an RGBA one.
 *
 * This implementation uses RenderScript to perform the conversion.
 */
class YuvToRgbConverter(context: Context) {
    private val rs = RenderScript.create(context)
    private val scriptYuvToRgb = ScriptIntrinsicYuvToRGB.create(rs, Element.U8_4(rs))
    
    // Reuse allocations to avoid reallocation on every frame
    private var pixelCount: Int = -1
    private var yuvBuffer: ByteArray? = null
    private var inputAllocation: Allocation? = null
    private var outputAllocation: Allocation? = null

    /**
     * Converts an ImageProxy (YUV) to a Bitmap (ARGB_8888).
     * Reuse the buffer if possible.
     */
    @Synchronized
    fun yuvToRgb(image: ImageProxy, outputBitmap: Bitmap) {
        val yuvBuffer = getYuvBuffer(image)

        // Ensure allocations match the image dimensions
        if (pixelCount != image.width * image.height) {
            pixelCount = image.width * image.height
            val yuvType = Type.Builder(rs, Element.U8(rs)).setX(image.width).setY(image.height).setYuvFormat(ImageFormat.NV21)
            inputAllocation = Allocation.createTyped(rs, yuvType.create(), Allocation.USAGE_SCRIPT)
            outputAllocation = Allocation.createFromBitmap(rs, outputBitmap, Allocation.MipmapControl.MIPMAP_NONE, Allocation.USAGE_SCRIPT)
        }
        
        // Reset output allocation if the bitmap changed (e.g. resolution change) - though usually we reuse same bitmap
        outputAllocation?.copyFrom(outputBitmap) 

        inputAllocation?.copyFrom(yuvBuffer)
        scriptYuvToRgb.setInput(inputAllocation)
        scriptYuvToRgb.forEach(outputAllocation)
        outputAllocation?.copyTo(outputBitmap)
    }

    /**
     * Converts a Standard Android Image (YUV) to a Bitmap (ARGB_8888).
     * This is useful for ARCore which returns android.media.Image.
     */
    @Synchronized
    fun yuvToRgb(image: Image, outputBitmap: Bitmap) {
         val yuvBuffer = getYuvBuffer(image)

        // Ensure allocations match the image dimensions
        if (pixelCount != image.width * image.height) {
            pixelCount = image.width * image.height
            val yuvType = Type.Builder(rs, Element.U8(rs)).setX(image.width).setY(image.height).setYuvFormat(ImageFormat.NV21)
            inputAllocation = Allocation.createTyped(rs, yuvType.create(), Allocation.USAGE_SCRIPT)
            outputAllocation = Allocation.createFromBitmap(rs, outputBitmap, Allocation.MipmapControl.MIPMAP_NONE, Allocation.USAGE_SCRIPT)
        }
        
        inputAllocation?.copyFrom(yuvBuffer)
        scriptYuvToRgb.setInput(inputAllocation)
        scriptYuvToRgb.forEach(outputAllocation)
        outputAllocation?.copyTo(outputBitmap)
    }

    /**
     * Optimized buffer extraction for YUV_420_888 to NV21 byte array.
     */
    private fun getYuvBuffer(image: ImageProxy): ByteArray {
        val width = image.width
        val height = image.height
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        val numPixels = (width * height * 1.5f).toInt()
        
        if (yuvBuffer == null || yuvBuffer?.size != numPixels) {
            yuvBuffer = ByteArray(numPixels)
        }
        val nv21 = yuvBuffer!!

        // Full copy of Y plane
        yBuffer.rewind()
        yBuffer.get(nv21, 0, width * height)

        // Interleave U and V
        val uRowStride = uPlane.rowStride
        val vRowStride = vPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vPixelStride = vPlane.pixelStride
        
        var pos = width * height
        
        // Fast path for when strides are standard and pixel stride is 2 (common on many devices)
        // Ideally we'd have a native JNI method here, but we'll do best-effort in Kotlin
        
        val uvWidth = width / 2
        val uvHeight = height / 2

        for (row in 0 until uvHeight) {
            for (col in 0 until uvWidth) {
                val vIndex = row * vRowStride + col * vPixelStride
                val uIndex = row * uRowStride + col * uPixelStride
                
                nv21[pos++] = vBuffer.get(vIndex)
                nv21[pos++] = uBuffer.get(uIndex)
            }
        }
        
        return nv21
    }

    // Overload for android.media.Image (ARCore)
    private fun getYuvBuffer(image: Image): ByteArray {
        val width = image.width
        val height = image.height
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        val numPixels = (width * height * 1.5f).toInt()
         if (yuvBuffer == null || yuvBuffer?.size != numPixels) {
            yuvBuffer = ByteArray(numPixels)
        }
        val nv21 = yuvBuffer!!

        // Full copy of Y plane
        yBuffer.rewind()
        yBuffer.get(nv21, 0, width * height)

        val uRowStride = uPlane.rowStride
        val vRowStride = vPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vPixelStride = vPlane.pixelStride
        
        var pos = width * height
        val uvWidth = width / 2
        val uvHeight = height / 2

        for (row in 0 until uvHeight) {
            for (col in 0 until uvWidth) {
                val vIndex = row * vRowStride + col * vPixelStride
                val uIndex = row * uRowStride + col * uPixelStride
                
                // NV21 = Y... V U V U
                nv21[pos++] = vBuffer.get(vIndex)
                nv21[pos++] = uBuffer.get(uIndex)
            }
        }
        return nv21
    }
}
