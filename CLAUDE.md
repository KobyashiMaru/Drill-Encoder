# Drill Encoder

## Project Overview

An Android application that improves the posture of athletic drill movements using **3D pose estimation**. A YOLOv11-Pose TFLite model detects 2D keypoints on the live camera feed; ARCore's RAW_DEPTH (ToF) provides per-pixel depth; the two are fused via a pinhole-camera unprojection to recover 3D joint positions in meters. The model/inference stack is the pivot of the repo — higher-level business logic (video playback, skeleton renderer, segment analysis, drill scoring) will be layered on top later.

## AI Interaction Workflow

- **Always generate an execution plan in markdown format before any code changes**
- **Maintain Progress Snapshot**:
  - At the end of every session or after reaching a major milestone, update the `Current Focus` section in `ROADMAP.md`.
  - The snapshot must include: (1) Completed changes, (2) Current system state/blockers, and (3) The specific starting point for the next session.
- **Context Handover**: If the conversation becomes too long, the AI must proactively summarize the progress into `ROADMAP.md` to prepare for a fresh session.
- Do not proceed with building until the user explicitly agrees with the plan
- After plan approval, follow the plan phase by phase, checking in at milestones
- If the plan needs to change during implementation, update the execution plan first and get user approval
- Before implementing, check `skills.md` for existing patterns to ensure consistency
- **Preserve downstream contracts**: When proposing improvements, preserve the original functionality and contracts exposed to downstream components (the `Person` / `Keypoint` data classes from `YoloDetector`, the `FloatArray {x, y, z}` shape returned by `BodyMeasureEngine.get3DJointPosition`, the `OverlayView` field names `x3d`/`y3d`/`z3d`, the activity intent contract between `StartActivity` and `MainActivity`) unless a change is strictly necessary. If a proposed change would affect downstream behavior or shape, flag it explicitly to the developer and seek approval before proceeding.

## Tech Stack

- **Language**: Kotlin (JVM target 1.8)
- **Min / Target SDK**: 24 (Android 7.0) / 34 (Android 14); recommended Android 10+ for stable NNAPI
- **Build**: Gradle Kotlin DSL (`build.gradle.kts`), AGP via Android Studio
- **Camera (2D path)**: CameraX 1.4.0 (`camera-core`, `camera-camera2`, `camera-lifecycle`, `camera-view`)
- **Camera + Depth (ToF path)**: ARCore 1.52.0 (`com.google.ar:core`) with `Config.DepthMode.RAW_DEPTH_ONLY`
- **Inference**: LiteRT 1.4.1 (`com.google.ai.edge.litert`, `litert-support`, `litert-gpu`) — formerly TensorFlow Lite
- **Delegate priority**: NNAPI (Android 10+) → GPU → CPU (XNNPACK, 4 threads)
- **YUV → RGB**: `YuvToRgbConverter` (RenderScript intrinsic, `renderscriptTargetApi = 30`, `renderscriptSupportModeEnabled = true`)
- **UI**: AndroidX AppCompat, ConstraintLayout, Material 1.11.0; ViewBinding enabled
- **Model**: YOLOv11-Pose, exported to TFLite. Bundled as `app/src/main/assets/best_float32.tflite` (FP32, default) and `best_float16.tflite` (FP16, available for IPS work). 17 COCO keypoints, input 640×640, output `[1, 56, 8400]` (or transposed `[1, 8400, 56]`).

## Project Structure

```text
Drill-Encoder/
├── CLAUDE.md
├── skills.md
├── ROADMAP.md
├── README.md
└── android_app/                                    # Android Studio project root
    ├── README.md                                   # Version-by-version To-Do list (v0.1.0, v0.1.1, ...)
    ├── build.gradle.kts                            # Root Gradle config
    ├── settings.gradle.kts
    ├── gradle.properties
    ├── local.properties
    ├── gradlew / gradlew.bat
    └── app/
        ├── build.gradle.kts                        # App module: SDK levels, dependencies, RenderScript
        └── src/main/
            ├── AndroidManifest.xml
            ├── assets/
            │   ├── best_float32.tflite             # YOLOv11-Pose FP32 (active model)
            │   └── best_float16.tflite             # YOLOv11-Pose FP16 (for IPS optimization work)
            ├── res/
            │   ├── layout/activity_start.xml       # Mode-select / menu screen
            │   ├── layout/activity_main.xml        # Live camera + overlay
            │   ├── drawable/                       # wormhole_circle, focus_ring, ic_wormhole_flare
            │   └── color/button_state_selector.xml
            └── java/com/example/drillencoder/
                ├── StartActivity.kt                # Entry screen: pick mode (ToF / 2D-only / MiDaS), show version
                ├── MainActivity.kt                 # Live pipeline: camera → YOLO → depth fuse → overlay (~907 LOC)
                ├── YoloDetector.kt                 # TFLite interpreter + delegate selection + NMS post-processing
                ├── BodyMeasureEngine.kt            # 2D keypoint → 3D unprojection via ARCore intrinsics + depth
                ├── BackgroundRenderer.kt           # GL background renderer for ARCore camera texture
                ├── DisplayRotationHelper.kt        # Display rotation tracking for ARCore session
                ├── ImageUtils.kt                   # YUV / bitmap helpers
                ├── YuvToRgbConverter.kt            # RenderScript YUV → RGB Bitmap converter
                ├── OverlayView.kt                  # Custom View drawing skeleton + 3D coord readouts
                └── ParticleView.kt                 # Startup wormhole/particle animation
```

## Coding Style

- **Language**: Kotlin idiomatic style. Prefer `val` over `var`; use scoped functions (`apply`, `let`, `also`) where they improve clarity.
- **Naming**: `camelCase` for functions/properties, `PascalCase` for classes/objects, `UPPER_SNAKE` for `const val`. Package: `com.example.drillencoder`.
- **Nullability**: Return `null` for measurement failure (e.g. `get3DJointPosition` when no valid depth pixel is found); upstream callers must null-check before consuming. Do not throw across the realtime loop.
- **Error handling**: `try-catch` at every realtime boundary (`frame.acquireCameraImage`, `frame.acquireDepthImage16Bits`, TFLite `interpreter.run`). `NotYetAvailableException` during warmup is expected — log as `WARN`, not `CRITICAL`. Catch broad `Exception` only at the outermost frame-processing boundary; prefer specific exceptions deeper in the stack.
- **Logging**: `android.util.Log` with the class name as tag. Levels: `D` for normal flow, `W` for transient/expected failures, `E` for genuine errors.
- **Allocations on hot path**: Avoid in `MainActivity` frame loop and `BodyMeasureEngine`. Reuse pre-allocated buffers (`depthWindowCache`, `depthUvCoords`, `yoloCoords`, `spiralOffsets`). Frequent GC pauses break the realtime budget.
- **Comments**: Doc-comment public functions in `YoloDetector` and `BodyMeasureEngine`; inline comments only for non-obvious math (pinhole unprojection, raw-depth 13-bit unpacking).
- **No formatter enforced yet** — match the surrounding file's indentation (4 spaces) and brace style.

## Key Commands

All commands run from `android_app/`.

```bash
# Build (debug APK)
./gradlew assembleDebug

# Install on a connected device
./gradlew installDebug

# Clean
./gradlew clean

# Lint
./gradlew lint

# Unit tests (currently scaffold only — no tests written)
./gradlew test

# Instrumented tests (requires connected device/emulator)
./gradlew connectedAndroidTest
```

For day-to-day work, open `android_app/` in Android Studio, sync Gradle, connect a device with developer mode + ARCore + ToF support (see `android_app/README.md` for the supported-device list), and Run.

## Architecture & Site Logic

Detailed implementation notes — YOLOv11-Pose tensor handling, ARCore RAW_DEPTH unpacking, the 3×3 median + spiral-search depth strategy, robust warmup detection, the adaptive device-position camera-intrinsics adjustment, and skeleton rendering — are maintained in [**`skills.md`**](./skills.md).

## Project Progress

Current focus, version-by-version To-Do, and the longer-term roadmap are maintained in [**`ROADMAP.md`**](./ROADMAP.md). Per-version release notes also live in [`android_app/README.md`](./android_app/README.md).
