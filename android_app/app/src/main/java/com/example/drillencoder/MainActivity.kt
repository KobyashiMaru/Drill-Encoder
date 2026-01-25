package com.example.drillencoder

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.MeteringPointFactory
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    private lateinit var overlayView: OverlayView
    private lateinit var focusRing: android.view.View
    private lateinit var previewView: PreviewView
    private lateinit var yoloDetector: YoloDetector
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private lateinit var yuvConverter: YuvToRgbConverter
    private var bitmapBuffer: Bitmap? = null

    // IPS Counter Variables
    private var inferencesProcessed = 0
    private var currentIps = 0
    private var lastIpsTimestamp = System.currentTimeMillis()

    // Adaptive Orientation State
    private var orientationEventListener: android.view.OrientationEventListener? = null
    private var currentDeviceRotation = android.view.Surface.ROTATION_0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Hide system bars
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())

        overlayView = findViewById(R.id.overlay)
        focusRing = findViewById(R.id.focusRing)
        previewView = findViewById(R.id.viewFinder)

        setupOrientationListener()

        if (allPermissionsGranted()) {
            chooseCameraMethod()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }

        try {
            // Determine model file from Intent
            val modelType = intent.getStringExtra("MODEL_TYPE") ?: "Yolo V11N FP32"
            val modelFilename = if (modelType == "Yolo V11N FP16") {
                "best_float16.tflite"
            } else {
                "best_float32.tflite"
            }
            
            // Ensure the model name matches what is in assets
            yoloDetector = YoloDetector(this, modelFilename)
            yuvConverter = YuvToRgbConverter(this)
            Toast.makeText(this, "Model loaded: $modelType", Toast.LENGTH_SHORT).show()
            logToConsole("Model loaded: $modelFilename ($modelType)")
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
                        // Efficient YUV -> RGB Conversion
                        if (bitmapBuffer == null || bitmapBuffer?.width != imageProxy.width || bitmapBuffer?.height != imageProxy.height) {
                            bitmapBuffer = Bitmap.createBitmap(imageProxy.width, imageProxy.height, Bitmap.Config.ARGB_8888)
                        }
                        
                        yuvConverter.yuvToRgb(imageProxy, bitmapBuffer!!)
                        
                        // Adaptive: Use Current Device Rotation Logic
                        // If locked to Portrait, imageProxy.imageInfo.rotationDegrees is always 90 (Back Cam).
                        // We need to override this based on currentDeviceRotation.
                        val deviceRotation = currentDeviceRotation
                        val sensorRotation = imageProxy.imageInfo.rotationDegrees
                        
                        val rotationDegrees = when (deviceRotation) {
                            android.view.Surface.ROTATION_0 -> sensorRotation // Portrait: Pass 90
                            android.view.Surface.ROTATION_90 -> 0 // Landscape Left: Pass 0
                            android.view.Surface.ROTATION_180 -> 270 // Upside Down
                            android.view.Surface.ROTATION_270 -> 180 // Landscape Right
                            else -> 90
                        }
                        
                        val bitmapToProcess = bitmapBuffer!!
                        
                        try {
                            val results = yoloDetector.detect(bitmapToProcess, rotationDegrees)
                            runOnUiThread {
                                overlayView.setResults(results)

                                // IPS Counter Update
                                inferencesProcessed++
                                val currentTime = System.currentTimeMillis()
                                if (currentTime - lastIpsTimestamp >= 1000) {
                                    currentIps = inferencesProcessed
                                    inferencesProcessed = 0
                                    lastIpsTimestamp = currentTime
                                    val tvIps = findViewById<android.widget.TextView>(R.id.tvIps)
                                    tvIps.text = "IPS: $currentIps"
                                }
                                
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
                        } finally {
                            imageProxy.close()
                        }
                    }
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                val camera = cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageAnalyzer
                )
                
                // Set up touch to focus (using ACTION_UP as requested)
                overlayView.setOnTouchListener { _, event ->
                    if (event.action == android.view.MotionEvent.ACTION_UP) {
                        val meteringPointFactory = previewView.meteringPointFactory
                        val point = meteringPointFactory.createPoint(event.x, event.y)
                        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
                            .setAutoCancelDuration(3, TimeUnit.SECONDS)
                            .build()
                        
                        try {
                            camera.cameraControl.startFocusAndMetering(action)
                            // Show focus ring
                             showFocusRing(event.x, event.y)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error adding focus metering", e)
                        }
                    }
                    true // Consume the event
                }
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }
    
    override fun onResume() {
        super.onResume()
        if (session != null) {
            try {
                // Determine if ARCore installation is needed
                val session = session ?: return
                when (com.google.ar.core.ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                    com.google.ar.core.ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                        installRequested = true
                        return
                    }
                    com.google.ar.core.ArCoreApk.InstallStatus.INSTALLED -> {}
                }
                
                // Resume the session
                session.resume()
                displayRotationHelper?.onResume()
                surfaceView?.onResume()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to resume AR session", e)
                logToConsole("Failed to resume AR session: ${e.message}")
            }
        }
    }
    
    private var installRequested = false

    // ARCore Variables
    private var session: com.google.ar.core.Session? = null
    private var displayRotationHelper: DisplayRotationHelper? = null
    private var backgroundRenderer: BackgroundRenderer? = null
    private var surfaceView: android.opengl.GLSurfaceView? = null
    private val bodyMeasureEngine = BodyMeasureEngine()

    private val processingExecutor = Executors.newSingleThreadExecutor()
    @Volatile
    private var isProcessing = false
    private var lastProcessedTimestamp: Long = 0
    @Volatile
    private var pendingDetection: List<Person>? = null
    // Add flag to track if initialization success has been logged
    private var hasLoggedARCoreSuccess = false
    
    // Sensor Warmup & Animation State
    private var isSensorWarmedUp = false
    private var isAnimationFinished = false

    private var warmupStartTime: Long = 0
    private val warmupHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val warmupTimeoutRunnable = Runnable {
        if (!isSensorWarmedUp || findViewById<android.view.View>(R.id.loadingLayout).visibility == android.view.View.VISIBLE) {
             val loadingLayout = findViewById<android.view.View>(R.id.loadingLayout)
             if (loadingLayout.visibility == android.view.View.VISIBLE) {
                 logToConsole("[ERROR] Sensor Warmup Timed Out (Force Quit).")
                 loadingLayout.visibility = android.view.View.GONE
                 isSensorWarmedUp = true // Ensure we don't block subsequent logic
             }
        }
    }

    private fun startARCoreSession() {
        Log.i(TAG, "ANTIGRAVITY: Starting ARCore Session - Code Version 2.0")
        hasLoggedARCoreSuccess = false // Reset on start
        
        // Reset and Start Sensor Warmup & Animation
        isSensorWarmedUp = false
        isAnimationFinished = false
        warmupStartTime = System.currentTimeMillis()
        
        runOnUiThread {
            val loadingLayout = findViewById<android.view.View>(R.id.loadingLayout)
            val ivWormhole = findViewById<android.widget.ImageView>(R.id.ivWormhole)
            loadingLayout.visibility = android.view.View.VISIBLE
            logToConsole("[INFO] Sensor Warmup Started...")
            
            // Start Wormhole Animation (3 seconds)
            ivWormhole.visibility = android.view.View.VISIBLE
            ivWormhole.alpha = 0f
            
            // Custom Wormhole Animation with "Approach" Physics
            val animator = android.animation.ValueAnimator.ofFloat(0f, 1f)
            animator.duration = 3000
            
            animator.addUpdateListener { animation ->
                val progress = animation.animatedValue as Float
                
                // 1. Scale: Exponential growth to simulate approaching speed
                // 0.0 -> 0.7: Slow growth (Distnat)
                // 0.7 -> 1.0: Rapid expansion (Proximity)
                val baseScale = 0.1f
                val maxScale = 50f // Enough to fill screen usually
                
                // Curve: s = base + (max - base) * p^4
                val scaleCurve = Math.pow(progress.toDouble(), 4.0).toFloat()
                val scale = baseScale + (maxScale - baseScale) * scaleCurve
                
                ivWormhole.scaleX = scale
                ivWormhole.scaleY = scale
                
                // 2. Rotation: Removed for modern look
                // ivWormhole.rotation = progress * 720f
                
                // 3. Alpha: Fade in, hold, then flash-die
                // 0.0 -> 0.1: Fade In
                // 0.1 -> 0.9: Full Opacity
                // 0.9 -> 1.0: Fade Out (Pass through)
                if (progress < 0.1f) {
                    ivWormhole.alpha = progress / 0.1f
                } else if (progress > 0.9f) {
                    ivWormhole.alpha = 1f - ((progress - 0.9f) / 0.1f)
                } else {
                    ivWormhole.alpha = 1f
                }
            }

            animator.addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    ivWormhole.visibility = android.view.View.GONE // Ensure it's gone
                    isAnimationFinished = true
                    checkWarmupCompletion()
                }
            })
            animator.start()
            
            // Failsafe: Force hide after 4 seconds (Animation 3s + 1s buffer)
            warmupHandler.removeCallbacks(warmupTimeoutRunnable)
            warmupHandler.postDelayed(warmupTimeoutRunnable, 4000)
        }
        
        if (session == null) {
            try {
                // Check for ARCore installation
                when (com.google.ar.core.ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                    com.google.ar.core.ArCoreApk.InstallStatus.INSTALLED -> {
                        // Creating a new Session
                        session = com.google.ar.core.Session(this)
                        val config = com.google.ar.core.Config(session)
                        config.depthMode = com.google.ar.core.Config.DepthMode.RAW_DEPTH_ONLY
                        config.focusMode = com.google.ar.core.Config.FocusMode.AUTO
                        // Use LATEST_CAMERA_IMAGE to avoid blocking the GL thread.
                        config.updateMode = com.google.ar.core.Config.UpdateMode.LATEST_CAMERA_IMAGE
                        session?.configure(config)
                    }
                    com.google.ar.core.ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                        installRequested = true
                        return
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create AR session", e)
                logToConsole("Failed to create AR session: ${e.message}")
                return
            }
        }

        surfaceView = findViewById(R.id.surfaceView)
        surfaceView?.visibility = android.view.View.VISIBLE
        previewView.visibility = android.view.View.GONE
        overlayView.visibility = android.view.View.VISIBLE // Ensure overlay is visible

        displayRotationHelper = DisplayRotationHelper(this)
        backgroundRenderer = BackgroundRenderer()

        surfaceView?.preserveEGLContextOnPause = true
        surfaceView?.setEGLContextClientVersion(2)
        surfaceView?.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        surfaceView?.setRenderer(object : android.opengl.GLSurfaceView.Renderer {
            override fun onSurfaceCreated(gl: javax.microedition.khronos.opengles.GL10?, config: javax.microedition.khronos.egl.EGLConfig?) {
                android.opengl.GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1.0f)
                backgroundRenderer?.createOnGlThread()
            }

            override fun onSurfaceChanged(gl: javax.microedition.khronos.opengles.GL10?, width: Int, height: Int) {
                displayRotationHelper?.onSurfaceChanged(width, height)
                android.opengl.GLES20.glViewport(0, 0, width, height)
            }

            override fun onDrawFrame(gl: javax.microedition.khronos.opengles.GL10?) {
                android.opengl.GLES20.glClear(android.opengl.GLES20.GL_COLOR_BUFFER_BIT or android.opengl.GLES20.GL_DEPTH_BUFFER_BIT)
                if (session == null) return

                displayRotationHelper?.updateSessionIfNeeded(session!!)

                try {
                    session?.setCameraTextureName(backgroundRenderer?.getTextureId() ?: -1)
                    val frame = session?.update()
                    
                    if (!hasLoggedARCoreSuccess && frame != null) {
                        hasLoggedARCoreSuccess = true
                        runOnUiThread {
                            logToConsole("ARCore session activated successfully")
                        }
                    }

                    backgroundRenderer?.draw(frame!!)
                    
                    // Consume pending detection results on the GL thread (synchronized with Frame)
                    val detectionResults = pendingDetection
                    if (detectionResults != null && frame != null) {
                        pendingDetection = null // Clear pending
                        
                        // Reuse buffers for coordinate transformation
                        val inputCoords = FloatArray(2)
                        val outputCoords = FloatArray(2)
                        val viewWidth = surfaceView?.width ?: 1
                        val viewHeight = surfaceView?.height ?: 1
                        
                        // Transform all persons/keypoints to View Coordinates
                        val transformedPersons = detectionResults.map { person ->
                            val newKeypoints = person.keypoints.map { kpt ->
                                inputCoords[0] = kpt.x
                                inputCoords[1] = kpt.y
                                
                                // Transform IMAGE_NORMALIZED -> VIEW (Screen Pixels)
                                frame.transformCoordinates2d(
                                    com.google.ar.core.Coordinates2d.IMAGE_NORMALIZED, 
                                    inputCoords, 
                                    com.google.ar.core.Coordinates2d.VIEW, 
                                    outputCoords
                                )
                                
                                val screenX = outputCoords[0]
                                val screenY = outputCoords[1]
                                
                                // Normalize for OverlayView (0..1 relative to View)
                                val normX = screenX / viewWidth
                                val normY = screenY / viewHeight
                                
                                val newKpt = Keypoint(normX, normY, kpt.conf)
                                
                                // We will fill x3d/y3d/z3d in the depth block below
                                newKpt 
                            }
                            Person(newKeypoints)
                        }

                        // Check if depth is available
                        // Try to acquire and use depth image in one go
                        try {
                            val depthImage = frame.acquireRawDepthImage16Bits()
                            depthImage.use { _ ->
                                transformedPersons.forEach { person ->
                                    person.keypoints.forEach { kpt ->
                                        if (kpt.conf > 0.3f) {
                                            // access coordinates. Since kpt is now normalized to view,
                                            // we reconvert to screen pixels for BodyMeasureEngine
                                            val screenX = kpt.x * viewWidth
                                            val screenY = kpt.y * viewHeight
                                            
                                            val position3d = bodyMeasureEngine.get3DJointPositionWithProvidedDepth(
                                                frame, depthImage, screenX, screenY
                                            ) { msg ->
                                                // Store critical depth errors for high conf points to display later
                                                if (kpt.conf > 0.3f) {
                                                     kpt.depthError = msg
                                                }
                                            }
                                            if (position3d != null) {
                                                kpt.x3d = position3d[0]
                                                kpt.y3d = position3d[1]
                                                kpt.z3d = position3d[2]
                                            }
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            // Depth might not be available yet or acquisition failed
                            // runOnUiThread { logToConsole("Depth not ready: ${e.message}") }
                        }

                        // Implement Global Depth Check for Sensor Warmup
                        if (!isSensorWarmedUp) {
                             try {
                                val depthImage = frame.acquireRawDepthImage16Bits()
                                depthImage.use { depth ->
                                    // Check center 50x50 pixels for valid depth (>0)
                                    val buffer = depth.planes[0].buffer.order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                                    val width = depth.width
                                    val height = depth.height
                                    val centerX = width / 2
                                    val centerY = height / 2
                                    var validPixels = 0
                                    val scanRadius = 25
                                    
                                    for (y in centerY - scanRadius..centerY + scanRadius) {
                                        for (x in centerX - scanRadius..centerX + scanRadius) {
                                            if (x in 0 until width && y in 0 until height) {
                                                val index = y * width + x
                                                val pixel = buffer.get(index).toInt()
                                                val depthMm = pixel and 0x1FFF
                                                if (depthMm > 0) validPixels++
                                            }
                                        }
                                    }
                                    
                                    val totalPixels = (scanRadius * 2 + 1) * (scanRadius * 2 + 1)
                                    val validRatio = validPixels.toFloat() / totalPixels
                                    
                                    // If >10% of center pixels are valid, consider sensors converged
                                    if (validRatio > 0.1f) {
                                        isSensorWarmedUp = true
                                        runOnUiThread {
                                             logToConsole("[SUCCESS] AE/AF Triggered & Converged (Global Depth).")
                                             checkWarmupCompletion()
                                        }
                                    }
                                }
                             } catch (e: Exception) {
                                 // Depth not ready yet
                             }
                        }

                        runOnUiThread {
                            overlayView.setResults(transformedPersons)
                            
                            // IPS Counter Update (ToF Mode)
                            inferencesProcessed++
                            val currentTime = System.currentTimeMillis()
                            if (currentTime - lastIpsTimestamp >= 1000) {
                                currentIps = inferencesProcessed
                                inferencesProcessed = 0
                                lastIpsTimestamp = currentTime
                                val tvIps = findViewById<android.widget.TextView>(R.id.tvIps)
                                tvIps.text = "IPS: $currentIps"
                            }
                            // Log keypoints if console is visible (Ported from normal camera loop)
                            if (findViewById<android.view.View>(R.id.consoleScrollView).visibility == android.view.View.VISIBLE) {
                                if (transformedPersons.isNotEmpty()) {
                                    val sb = StringBuilder()
                                    sb.append("[INFO] ToF Detected ${transformedPersons.size} person(s):\n")
                                    transformedPersons.forEachIndexed { index, person ->
                                        sb.append("Person $index:\n")
                                        person.keypoints.forEachIndexed { kIndex, kpt ->
                                            if (kpt.conf > 0.3f) {
                                                val depthInfo = if (kpt.z3d != 0f) " D:${String.format("%.2f", kpt.z3d)}m" else ""
                                                sb.append("  Kpt $kIndex: (${String.format("%.2f", kpt.x)}, ${String.format("%.2f", kpt.y)}) Conf:${String.format("%.2f", kpt.conf)}$depthInfo\n")
                                                if (kpt.depthError != null) {
                                                    sb.append("  [WARNING] 3D Err: ${kpt.depthError}\n")
                                                }
                                            }
                                        }
                                    }
                                    logToConsole(sb.toString())
                                }
                            }
                        }
                    }

                    if (frame != null && frame.timestamp != lastProcessedTimestamp && !isProcessing) {
                        isProcessing = true
                        lastProcessedTimestamp = frame.timestamp
                        
                        try {
                            val cameraImage = frame.acquireCameraImage()
                            try {
                                if (bitmapBuffer == null || bitmapBuffer?.width != cameraImage.width || bitmapBuffer?.height != cameraImage.height) {
                                    bitmapBuffer = Bitmap.createBitmap(cameraImage.width, cameraImage.height, Bitmap.Config.ARGB_8888)
                                }
                                // Efficient YUV -> RGB
                                yuvConverter.yuvToRgb(cameraImage, bitmapBuffer!!)
                            } finally {
                                cameraImage.close() // Close IMMEDIATELY on GL thread
                            }

                            processingExecutor.execute {
                                val startTime = System.currentTimeMillis()
                                try {
                                    // Strategy 2: Custom Rotation Engine
                                    val deviceRotation = currentDeviceRotation
                                    val rotationDegrees = when (deviceRotation) {
                                        android.view.Surface.ROTATION_0 -> 90f
                                        android.view.Surface.ROTATION_90 -> 0f
                                        android.view.Surface.ROTATION_180 -> 270f
                                        android.view.Surface.ROTATION_270 -> 180f
                                        else -> 90f
                                    }
                                    
                                    val bitmap = bitmapBuffer!!
                                    // Pass rotation to detector instead of creating rotated bitmap
                                    val persons = yoloDetector.detect(bitmap, rotationDegrees.toInt())
                                    
                                    // Un-rotate keypoints to match Sensor Coordinates (Image Normalized)
                                    val unrotatedPersons = persons.map { person ->
                                        val newKpts = person.keypoints.map { kpt ->
                                            // General Un-rotation Logic
                                            // The detector sees 'rotated' view. kpt.x, kpt.y are normalized 0..1 relative to that.
                                            // We need to map them back to the original 'bitmap' (Sensor image) coordinates 0..1.
                                            
                                            // 1. De-normalize to Pixels in Rotated Space
                                            // Note: If rotation is 90 or 270, dimensions are swapped
                                            val rotW = if (rotationDegrees == 90f || rotationDegrees == 270f) bitmap.height else bitmap.width
                                            val rotH = if (rotationDegrees == 90f || rotationDegrees == 270f) bitmap.width else bitmap.height
                                            
                                            val rx = kpt.x * rotW
                                            val ry = kpt.y * rotH
                                            
                                            // 2. Inverse Transform (Back to Original Bitmap Pixels)
                                            val ox: Float
                                            val oy: Float
                                            
                                            when (rotationDegrees) {
                                                90f -> {
                                                    // CW 90: (x, y) -> (y, H-x)
                                                    // Inverse: ox = ry, oy = H - rx
                                                    ox = ry
                                                    oy = bitmap.height - rx
                                                }
                                                0f -> {
                                                    // No rotation
                                                    ox = rx
                                                    oy = ry
                                                }
                                                180f -> {
                                                    // 180 CW: (x, y) -> (W-x, H-y)
                                                    ox = bitmap.width - rx
                                                    oy = bitmap.height - ry
                                                }
                                                270f -> {
                                                    // 270 CW: (x, y) -> (w-y, x)
                                                    // Inverse: ox = w - ry, oy = rx
                                                    ox = bitmap.width - ry
                                                    oy = rx
                                                }
                                                else -> { ox = rx; oy = ry }
                                            }
                                            
                                            // 3. Normalize back to Original Bitmap Dimensions
                                            // Adaptive strategy: Map to Screen Coordinates (Portrait)
                                            val normX: Float
                                            val normY: Float
                                            
                                            // If we are in Landscape (0 or 180), we need to SWAP axes to match Portrait Overlay
                                            // The Bitmap is Landscape (W > H). The Screen is Portrait (H > W).
                                            if (rotationDegrees == 0f || rotationDegrees == 180f) {
                                                // Map Bitmap Y (Short) -> Screen X (Short)
                                                // Map Bitmap X (Long) -> Screen Y (Long)
                                                normX = oy / bitmap.height
                                                normY = ox / bitmap.width
                                            } else {
                                                // Portrait: Standard mapping
                                                normX = ox / bitmap.width
                                                normY = oy / bitmap.height
                                            }
                                            
                                            Keypoint(normX, normY, kpt.conf)
                                        }
                                        Person(newKpts)
                                    }
                                    
                                    // UI Counter-Rotation (Selective)
                                    runOnUiThread {
                                        val btnBack = findViewById<android.view.View>(R.id.btnBack)
                                        val uiRotation = when (deviceRotation) {
                                            android.view.Surface.ROTATION_0 -> 0f
                                            android.view.Surface.ROTATION_90 -> -90f
                                            android.view.Surface.ROTATION_180 -> -180f
                                            android.view.Surface.ROTATION_270 -> 90f
                                            else -> 0f
                                        }
                                        btnBack.rotation = uiRotation
                                    }
                                    
                                    // Assign to volatile variable for consumption by GL thread
                                    pendingDetection = unrotatedPersons
                                    
                                    // DIAGNOSTIC LOOP: Ensure we see 0 results if that's what we got
                                    if (unrotatedPersons.isEmpty()) {
                                        // runOnUiThread { logToConsole("ToF Detection returned 0 persons") }
                                    }
                                } catch (t: Throwable) {
                                    Log.e(TAG, "Error in background processing", t)
                                    runOnUiThread { logToConsole("Error ToF Processing: ${t.javaClass.simpleName} - ${t.message}") }
                                } finally {
                                    // DIAGNOSTIC: Log total time
                                    val duration = System.currentTimeMillis() - startTime
                                    // Log.d(TAG, "Frame processing took ${duration}ms")
                                    if (duration > 1000) {
                                         runOnUiThread { logToConsole("WARNING: Slow processing detected (${duration}ms)") }
                                    }
                                    isProcessing = false // Ensure lock is ALWAYS released
                                }
                            }
                        } catch (e: com.google.ar.core.exceptions.ResourceExhaustedException) {
                            // Log.e(TAG, "Resource Exhausted (ANTIGRAVITY CHECK): ${e.message}")
                            isProcessing = false
                        } catch (t: Throwable) {
                             // Catch EVERYTHING including OutOfMemoryError, LinkageError, etc.
                             Log.e(TAG, "CRITICAL FAILURE in Frame Processing Loop", t)
                             isProcessing = false
                             runOnUiThread { logToConsole("CRITICAL: ${t.javaClass.simpleName} - ${t.message}") }
                        }
                    } else if (isProcessing) {
                         // DIAGNOSTIC: Detect if we are stuck
                         // Log.d(TAG, "Skipping frame: processing still in progress")
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "Exception on the OpenGL thread", t)
                    if (!hasLoggedARCoreSuccess) {
                        runOnUiThread {
                             logToConsole("Exception on the OpenGL thread: ${t.message}") 
                        }
                    }
                }
            }
        })
        
        surfaceView?.renderMode = android.opengl.GLSurfaceView.RENDERMODE_CONTINUOUSLY
        
        // We decide that we don't add touch listener for ToF mode to show focus ring
        // Note: ARCore handles focus automatically, so we don't need to do anything.

    }
    
    override fun onPause() {
        super.onPause()
        displayRotationHelper?.onPause()
        if (session != null) {
            session?.pause()
        }
    }
    
    // Override startCamera to choose based on intent
    private fun chooseCameraMethod() {
        val inferenceMethod = intent.getStringExtra("INFERENCE_METHOD")
        if (inferenceMethod == "ToF") {
            startARCoreSession()
        } else {
            startCamera()
        }
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
                chooseCameraMethod()
            } else {
                Toast.makeText(this, "Permissions not granted by the user.", Toast.LENGTH_SHORT).show()
                logToConsole("Permissions not granted by the user.")
                finish()
            }
        }
    }

    private fun showFocusRing(x: Float, y: Float) {
        focusRing.apply {
            // Center the ring on the touch point
            translationX = x - (width / 2)
            translationY = y - (height / 2)
            visibility = android.view.View.VISIBLE
            alpha = 1f
            scaleX = 1.5f
            scaleY = 1.5f
            
            // Simple scale animation
            animate()
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(300)
                .withEndAction {
                    // Fade out after a delay
                    animate()
                        .alpha(0f)
                        .setStartDelay(1000)
                        .setDuration(200)
                        .start()
                }
                .start()
        }
    }

    private fun checkWarmupCompletion() {
        if (isAnimationFinished) {
            val loadingLayout = findViewById<android.view.View>(R.id.loadingLayout)
            if (isSensorWarmedUp) {
                 loadingLayout.visibility = android.view.View.GONE
            } else {
                 logToConsole("[INFO] Animation done, waiting for sensors...")
                 // Optional: Keep showing loading or show specific text "Calibrating..."
                 // Check timeout as failsafe (though animation is 3s)
                 val elapsed = System.currentTimeMillis() - warmupStartTime
                 if (elapsed > 4000) { // Give explicit extra 1s grace if needed
                      loadingLayout.visibility = android.view.View.GONE
                      logToConsole("[ERROR] Sensor Timeout after Animation.")
                 }
            }
        }
    }

    private fun setupOrientationListener() {
        orientationEventListener = object : android.view.OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == android.view.OrientationEventListener.ORIENTATION_UNKNOWN) return

                val newRotation = when (orientation) {
                    in 45..134 -> android.view.Surface.ROTATION_270 // Reversed for device rotation
                    in 135..224 -> android.view.Surface.ROTATION_180
                    in 225..314 -> android.view.Surface.ROTATION_90
                    else -> android.view.Surface.ROTATION_0
                }

                if (newRotation != currentDeviceRotation) {
                    currentDeviceRotation = newRotation
                    rotateUI(newRotation)
                }
            }
        }
        if (orientationEventListener?.canDetectOrientation() == true) {
            orientationEventListener?.enable()
        }
    }

    private fun rotateUI(rotation: Int) {
        val angle = when (rotation) {
            android.view.Surface.ROTATION_90 -> -90f
            android.view.Surface.ROTATION_180 -> 180f
            android.view.Surface.ROTATION_270 -> 90f
            else -> 0f
        }
        
        // Rotate UI elements
        val btnBack = findViewById<android.view.View>(R.id.btnBack)
        val tvIps = findViewById<android.widget.TextView>(R.id.tvIps)
        val btnToggleConsole = findViewById<android.widget.Button>(R.id.btnToggleConsole)
        
        // Animate rotation
        btnBack.animate().rotation(angle).setDuration(300).start()
        tvIps.animate().rotation(angle).setDuration(300).start()
        btnToggleConsole.animate().rotation(angle).setDuration(300).start()
        
        // Console needs container rotation or specific handling? 
        // Rotating the scrollview might cause layout issues (width vs height). 
        // For now, let's keep console static or just rotate the text view? 
        // Rotating TextView inside ScrollView is tricky. 
        // Let's just rotate the Toggle Button and IPS for now to prove concept.
    }

    companion object {
        private const val TAG = "DrillEncoder"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }
}
