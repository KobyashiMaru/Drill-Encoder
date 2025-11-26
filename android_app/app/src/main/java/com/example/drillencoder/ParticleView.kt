package com.example.drillencoder

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import java.util.Random

class ParticleView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private data class Particle(
        var x: Float,
        var y: Float,
        var radius: Float,
        var speedX: Float,
        var speedY: Float,
        var alpha: Int
    )

    private val particles = mutableListOf<Particle>()
    private val random = Random()
    private val paint = Paint().apply {
        style = Paint.Style.FILL
        color = Color.CYAN
    }
    private val particleCount = 50

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        createParticles(w, h)
    }

    private fun createParticles(w: Int, h: Int) {
        particles.clear()
        for (i in 0 until particleCount) {
            particles.add(
                Particle(
                    x = random.nextFloat() * w,
                    y = random.nextFloat() * h,
                    radius = random.nextFloat() * 10f + 2f,
                    speedX = (random.nextFloat() - 0.5f) * 2f,
                    speedY = (random.nextFloat() - 0.5f) * 2f,
                    alpha = random.nextInt(150) + 50
                )
            )
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        for (particle in particles) {
            paint.alpha = particle.alpha
            canvas.drawCircle(particle.x, particle.y, particle.radius, paint)

            // Update position
            particle.x += particle.speedX
            particle.y += particle.speedY

            // Bounce off edges
            if (particle.x < 0 || particle.x > width) particle.speedX *= -1
            if (particle.y < 0 || particle.y > height) particle.speedY *= -1
        }

        invalidate() // Trigger redraw for animation
    }
}
