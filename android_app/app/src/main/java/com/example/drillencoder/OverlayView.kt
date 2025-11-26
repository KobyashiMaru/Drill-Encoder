package com.example.drillencoder

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

data class Person(val keypoints: List<Keypoint>)
data class Keypoint(val x: Float, val y: Float, val conf: Float)

class OverlayView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    private var results: List<Person> = emptyList()
    private val pointPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.FILL
        strokeWidth = 15f
    }
    private val linePaint = Paint().apply {
        color = Color.CYAN
        style = Paint.Style.STROKE
        strokeWidth = 8f
    }

    // Skeleton connections (indices based on COCO keypoints)
    private val skeletonConnections = listOf(
        Pair(5, 6), Pair(5, 7), Pair(7, 9), Pair(6, 8), Pair(8, 10),
        Pair(11, 12), Pair(5, 11), Pair(6, 12), Pair(11, 13), Pair(13, 15),
        Pair(12, 14), Pair(14, 16), Pair(0, 1), Pair(0, 2), Pair(1, 3),
        Pair(2, 4), Pair(0, 5), Pair(0, 6)
    )

    fun setResults(newResults: List<Person>) {
        results = newResults
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (person in results) {
            val keypoints = person.keypoints
            // Draw connections
            for ((start, end) in skeletonConnections) {
                if (start < keypoints.size && end < keypoints.size) {
                    val p1 = keypoints[start]
                    val p2 = keypoints[end]
                    // Use normalized coordinates (0..1) * view size
                    if (p1.conf > 0.5f && p2.conf > 0.5f) {
                        canvas.drawLine(p1.x * width, p1.y * height, p2.x * width, p2.y * height, linePaint)
                    }
                }
            }
            // Draw points
            for (point in keypoints) {
                if (point.conf > 0.5f) {
                    canvas.drawCircle(point.x * width, point.y * height, 10f, pointPaint)
                }
            }
        }
    }
}
