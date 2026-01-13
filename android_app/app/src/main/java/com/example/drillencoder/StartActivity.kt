package com.example.drillencoder

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputLayout

class StartActivity : AppCompatActivity() {

    private lateinit var actvModel: AutoCompleteTextView
    private lateinit var actvInference: AutoCompleteTextView
    private lateinit var btnOpenCamera: Button
    private lateinit var ivWormhole: ImageView
    private lateinit var tilModel: TextInputLayout
    private lateinit var tilInference: TextInputLayout
    private lateinit var tvTitle: android.widget.TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_start)

        actvModel = findViewById(R.id.actvModel)
        actvInference = findViewById(R.id.actvInference)
        btnOpenCamera = findViewById(R.id.btnOpenCamera)
        ivWormhole = findViewById(R.id.ivWormhole)
        tilModel = findViewById(R.id.tilModel)
        tilInference = findViewById(R.id.tilInference)
        tvTitle = findViewById(R.id.tvTitle)

        setupDropdowns()
        setupButton()
    }

    override fun onResume() {
        super.onResume()
        resetUI()
    }

    private fun resetUI() {
        // Reset UI elements visibility and alpha
        tvTitle.alpha = 1f
        tilModel.alpha = 1f
        tilInference.alpha = 1f
        btnOpenCamera.alpha = 1f
        
        // Hide wormhole and reset its properties
        ivWormhole.visibility = View.INVISIBLE
        ivWormhole.scaleX = 1f
        ivWormhole.scaleY = 1f
        ivWormhole.rotation = 0f
        ivWormhole.alpha = 0f
    }

    private fun setupDropdowns() {
        val models = listOf("Yolo V11N")
        val inferenceMethods = listOf("ToF", "MiDaS")

        val modelAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, models)
        actvModel.setAdapter(modelAdapter)

        val inferenceAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, inferenceMethods)
        actvInference.setAdapter(inferenceAdapter)

        actvModel.setOnItemClickListener { _, _, _, _ -> validateSelection() }
        actvInference.setOnItemClickListener { _, _, _, _ -> validateSelection() }
    }

    private fun validateSelection() {
        val isModelSelected = actvModel.text.isNotEmpty()
        val isInferenceSelected = actvInference.text.isNotEmpty()
        btnOpenCamera.isEnabled = isModelSelected && isInferenceSelected
    }

    private fun setupButton() {
        btnOpenCamera.setOnClickListener {
            startWormholeAnimation()
        }
    }

    private fun startWormholeAnimation() {
        // Launch Camera Immediately
        val intent = Intent(this@StartActivity, MainActivity::class.java)
        intent.putExtra("INFERENCE_METHOD", actvInference.text.toString())
        startActivity(intent)
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }
}
