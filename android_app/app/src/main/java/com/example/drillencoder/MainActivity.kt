package com.example.drillencoder

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var overlayView: OverlayView
    private lateinit var previewView: PreviewView
    private lateinit var yoloDetector: YoloDetector
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Hide system bars
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())

        overlayView = findViewById(R.id.overlay)
        previewView = findViewById(R.id.viewFinder)

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }

        try {
            // Ensure the model name matches what is in assets
            yoloDetector = YoloDetector(this, "best_float32.tflite")
            Toast.makeText(this, "Model loaded successfully", Toast.LENGTH_SHORT).show()
            logToConsole("Model loaded successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing detector", e)
            Toast.makeText(this, "Error initializing detector: ${e.message}", Toast.LENGTH_LONG).show()
            logToConsole("Error initializing detector: ${e.message}")
        }
        
        val btnToggleConsole = findViewById<android.widget.Button>(R.id.btnToggleConsole)
        val consoleScrollView = findViewById<android.widget.ScrollView>(R.id.consoleScrollView)
        
        btnToggleConsole.setOnClickListener {
            if (consoleScrollView.visibility == android.view.View.VISIBLE) {
                consoleScrollView.visibility = android.view.View.GONE
            } else {
                consoleScrollView.visibility = android.view.View.VISIBLE
            }
        }
        
        findViewById<android.view.View>(R.id.btnBack).setOnClickListener {
            finish()
        }
    }

    private fun logToConsole(message: String) {
        val tvConsole = findViewById<android.widget.TextView>(R.id.tvConsole)
        val consoleScrollView = findViewById<android.widget.ScrollView>(R.id.consoleScrollView)
        tvConsole.append("$message\n")
        consoleScrollView.post {
            consoleScrollView.fullScroll(android.view.View.FOCUS_DOWN)
        }
    }


    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                        // Note: This is a simplified way to get bitmap. 
                        // For better performance, use YUV to RGB conversion or TensorImage directly from ImageProxy if supported.
                        // But PreviewView.bitmap is easiest for now, though it might be null or slow.
                        // A better way is to use the bitmap from the preview view or convert the imageProxy.
                        // Since we are running on a separate thread, we can use the bitmap from the view (UI thread access required?)
                        // Actually, previewView.bitmap must be called on UI thread? No, but it captures the current view content.
                        // Better: use imageProxy.toBitmap() if available (CameraX 1.1+) or conversion.
                        
                        // For this example, we'll try to get the bitmap from the view on the UI thread or use a converter.
                        // Using previewView.bitmap is safe? It returns a copy.
                        
                        // Let's use a safe approach: run on UI thread to get bitmap? No, that blocks UI.
                        // Let's use the imageProxy.
                        
                        // Since we don't have a robust YUV converter handy in this snippet, 
                        // and we want to keep it simple, we will try to use the previewView bitmap 
                        // but we need to be careful about threading.
                        
                        // Actually, let's just use the imageProxy if possible.
                        // But TFLite Support TensorImage can load from Bitmap.
                        
                        // Let's stick to the plan:
                        runOnUiThread {
                            val bitmap = previewView.bitmap
                            if (bitmap != null) {
                                cameraExecutor.execute {
                                    try {
                                        val results = yoloDetector.detect(bitmap)
                                        runOnUiThread {
                                            overlayView.setResults(results)
                                            
                                            // Log keypoints if console is visible
                                            if (findViewById<android.view.View>(R.id.consoleScrollView).visibility == android.view.View.VISIBLE) {
                                                if (results.isNotEmpty()) {
                                                    val sb = StringBuilder()
                                                    sb.append("Detected ${results.size} person(s):\n")
                                                    results.forEachIndexed { index, person ->
                                                        sb.append("Person $index:\n")
                                                        person.keypoints.forEachIndexed { kIndex, kpt ->
                                                            if (kpt.conf > 0.3f) {
                                                                sb.append("  Kpt $kIndex: (${String.format("%.2f", kpt.x)}, ${String.format("%.2f", kpt.y)}) Conf: ${String.format("%.2f", kpt.conf)}\n")
                                                            }
                                                        }
                                                    }
                                                    logToConsole(sb.toString())
                                                }
                                            }
                                        }
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Error detecting", e)
                                        runOnUiThread { logToConsole("Error detecting: ${e.message}") }
                                    }
                                }
                            }
                        }
                        imageProxy.close()
                    }
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageAnalyzer
                )
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(this, "Permissions not granted by the user.", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    companion object {
        private const val TAG = "DrillEncoder"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }
}
