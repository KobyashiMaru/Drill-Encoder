package com.example.drillencoder

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import kotlin.math.max
import kotlin.math.min

class YoloDetector(context: Context, modelPath: String) {
    private var interpreter: Interpreter
    private val inputSize = 640
    private val confThreshold = 0.3f
    private val iouThreshold = 0.5f

    init {
        val model = FileUtil.loadMappedFile(context, modelPath)
        val options = Interpreter.Options()
        interpreter = Interpreter(model, options)
    }

    fun detect(bitmap: Bitmap): List<Person> {
        val imageProcessor = ImageProcessor.Builder()
            .add(ResizeOp(inputSize, inputSize, ResizeOp.ResizeMethod.BILINEAR))
            .add(org.tensorflow.lite.support.common.ops.NormalizeOp(0f, 255f))
            .build()
        var tensorImage = TensorImage(org.tensorflow.lite.DataType.FLOAT32)
        tensorImage.load(bitmap)
        tensorImage = imageProcessor.process(tensorImage)

        val outputTensor = interpreter.getOutputTensor(0)
        val outputShape = outputTensor.shape()
        android.util.Log.d("YoloDetector", "Output shape: ${outputShape.contentToString()}")

        // Handle different shapes
        val output = if (outputShape[1] == 56 && outputShape[2] == 8400) {
             val out = Array(1) { Array(56) { FloatArray(8400) } }
             interpreter.run(tensorImage.buffer, out)
             out[0]
        } else if (outputShape[1] == 8400 && outputShape[2] == 56) {
            // Transposed shape [1, 8400, 56]
            val out = Array(1) { Array(8400) { FloatArray(56) } }
            interpreter.run(tensorImage.buffer, out)
            // Transpose to [56][8400] for easier processing
            val transposed = Array(56) { FloatArray(8400) }
            for (i in 0 until 8400) {
                for (j in 0 until 56) {
                    transposed[j][i] = out[0][i][j]
                }
            }
            transposed
        } else {
            throw RuntimeException("Unexpected output shape: ${outputShape.contentToString()}")
        }

        // Debug raw values for the first few anchors
        for (i in 0 until 5) {
             android.util.Log.d("YoloDetector", "Anchor $i: Box[${output[0][i]}, ${output[1][i]}, ${output[2][i]}, ${output[3][i]}] Score[${output[4][i]}] Kpt0[${output[5][i]}, ${output[6][i]}, ${output[7][i]}]")
        }

        return processOutput(output)
    }

    private fun processOutput(output: Array<FloatArray>): List<Person> {
        val candidates = mutableListOf<Candidate>()
        val numAnchors = 8400
        
        // output is [56][8400]
        // 0-3: box, 4: score, 5-55: kpts
        
        for (i in 0 until numAnchors) {
            val score = output[4][i]
            if (score > confThreshold) {
                val cx = output[0][i]
                val cy = output[1][i]
                val w = output[2][i]
                val h = output[3][i]
                val x1 = cx - w / 2
                val y1 = cy - h / 2
                val x2 = cx + w / 2
                val y2 = cy + h / 2
                
                val keypoints = mutableListOf<Keypoint>()
                for (k in 0 until 17) {
                    val rawX = output[5 + k * 3][i]
                    val rawY = output[5 + k * 3 + 1][i]
                    
                    // Check if already normalized
                    val kx = if (rawX < 1.0f && rawX > 0.0f) rawX else rawX / inputSize
                    val ky = if (rawY < 1.0f && rawY > 0.0f) rawY else rawY / inputSize
                    
                    val kconf = output[5 + k * 3 + 2][i]
                    keypoints.add(Keypoint(kx, ky, kconf))
                }
                
                candidates.add(Candidate(x1, y1, x2, y2, score, keypoints))
            }
        }

        return nms(candidates)
    }

    private fun nms(candidates: List<Candidate>): List<Person> {
        val sorted = candidates.sortedByDescending { it.score }
        val selected = mutableListOf<Candidate>()
        val active = BooleanArray(sorted.size) { true }

        for (i in sorted.indices) {
            if (active[i]) {
                selected.add(sorted[i])
                for (j in i + 1 until sorted.size) {
                    if (active[j]) {
                        val iou = calculateIoU(sorted[i], sorted[j])
                        if (iou > iouThreshold) {
                            active[j] = false
                        }
                    }
                }
            }
        }
        
        return selected.map { Person(it.keypoints) }
    }

    private fun calculateIoU(a: Candidate, b: Candidate): Float {
        val x1 = max(a.x1, b.x1)
        val y1 = max(a.y1, b.y1)
        val x2 = min(a.x2, b.x2)
        val y2 = min(a.y2, b.y2)

        if (x2 < x1 || y2 < y1) return 0f

        val intersection = (x2 - x1) * (y2 - y1)
        val areaA = (a.x2 - a.x1) * (a.y2 - a.y1)
        val areaB = (b.x2 - b.x1) * (b.y2 - b.y1)
        
        return intersection / (areaA + areaB - intersection)
    }

    data class Candidate(
        val x1: Float, val y1: Float, val x2: Float, val y2: Float,
        val score: Float, val keypoints: List<Keypoint>
    )
}
