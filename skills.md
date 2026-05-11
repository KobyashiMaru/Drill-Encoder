# Development Skills & Patterns

This document captures the specialized logic, architectural patterns, and domain knowledge used in the Drill Encoder Android app. It covers only what is **currently implemented** in the codebase; planned but unimplemented work lives in [`ROADMAP.md`](./ROADMAP.md).

## Domain Knowledge: 2D-to-3D Pose Reconstruction

The core pipeline fuses a 2D keypoint detector (YOLOv11-Pose) with ARCore's depth sensor to produce 3D joint positions in the camera coordinate system (meters).

- **Pinhole unprojection** (in [`BodyMeasureEngine.get3DJointPosition`](android_app/app/src/main/java/com/example/drillencoder/BodyMeasureEngine.kt)):
  ```
  X = (u - cx) * Z / fx
  Y = (v - cy) * Z / fy
  Z = depth (m)
  ```
  `cx, cy, fx, fy` come from `frame.camera.imageIntrinsics` (`principalPoint`, `focalLength`). `(u, v)` are pixel coords in the **intrinsics image space** (typically 640×480, matching the CPU image), not the YOLO 640×640 input.
- **Coordinate space alignment**: YOLO returns normalized keypoints in the image plane. We pass `normX, normY ∈ [0, 1]` into `BodyMeasureEngine`, then scale separately for (a) intrinsics math and (b) depth sampling — the depth image is a smaller resolution (e.g. 160×120) than the intrinsics image.

## YOLOv11-Pose Inference

### Model
- 17 COCO keypoints, single-class (person) detector.
- Input: 640×640 RGB, normalized `[0, 1]` via `NormalizeOp(0f, 255f)`.
- Output shape: `[1, 56, 8400]` *or* transposed `[1, 8400, 56]`. `YoloDetector.detect` handles both — if `outputShape[1] == 8400`, it transposes to `[56][8400]` for uniform downstream processing.
- Per-anchor layout in the `[56][8400]` form: `0-3` = `(cx, cy, w, h)`, `4` = objectness score, `5..55` = 17 × `(kx, ky, kconf)`.

### Delegate Selection (Fallback Chain)
In [`YoloDetector.init`](android_app/app/src/main/java/com/example/drillencoder/YoloDetector.kt):
1. **NNAPI** — preferred on Android 10+ (`Build.VERSION_CODES.Q`). Best for NPU-equipped SoCs.
2. **GPU Delegate** — fallback if NNAPI throws or device is pre-Q. Gated on `CompatibilityList.isDelegateSupportedOnThisDevice`.
3. **CPU + XNNPACK** — universal fallback, `setNumThreads(4)`.

Each step is wrapped in `try-catch`; a failure cascades to the next delegate, never aborts construction.

### Post-Processing
- **Confidence threshold**: `0.3f`.
- **IoU threshold**: `0.5f` for NMS.
- **Keypoint normalization heuristic**: if `rawX ∈ (0, 1)`, treat as already normalized; otherwise divide by `inputSize` (640). This guards against both export variants.
- **NMS**: standard greedy sort-by-score + IoU suppression; returns `List<Person>`.

### Rotation Handling
`ImageProcessor` chain applies `ResizeOp` → `Rot90Op(-rotation / 90)` → `NormalizeOp` so the input tensor is upright regardless of device orientation. The rotation is passed in from the caller (CameraX `ImageAnalysis` provides `imageInfo.rotationDegrees`).

## ARCore Depth Pipeline

### Configuration
- `Config.DepthMode.RAW_DEPTH_ONLY` — unsmoothed depth from the hardware ToF sensor. Required because we do our own filtering and want to know which pixels are *truly* invalid (zero) rather than interpolated.
- Depth image is `DEPTH16` format: each pixel is a 16-bit little-endian short; the **low 13 bits** are millimeters (`pixel and 0x1FFF`), the upper 3 bits are a confidence/flag field we currently ignore.

### Depth Sampling Strategy
Two-stage in [`BodyMeasureEngine`](android_app/app/src/main/java/com/example/drillencoder/BodyMeasureEngine.kt):

1. **3×3 Median Filter (`getSmoothedDepth`)**: scan radius-1 window around the target pixel, drop zero pixels, take the median in millimeters, convert to meters. Returns `-1f` if every pixel in the window is invalid. Uses a pre-allocated `ShortArray(25)` buffer to stay GC-free on the hot path.

2. **Spiral Search Fallback (`getSpiralDepth`)**: when the 3×3 median fails, walk a pre-computed spiral of up to 225 offsets (max radius ~15 px) around the keypoint and return the **first** non-zero depth (early exit). Offsets are interleaved `[dx1, dy1, dx2, dy2, ...]` in a `companion object` `IntArray(450)` generated once at class load. Reads raw shorts directly (no median) — speed over stability for fallback.

If both stages fail, `get3DJointPosition` returns `null`. Callers (`MainActivity`) skip the keypoint update; `OverlayView` retains the previous `x3d/y3d/z3d` value (default `0f` on first frame).

### Sensor Warmup Detection
ToF sensors take a few hundred ms to produce valid depth on startup. The warmup check (`checkRobustWarmup` in `MainActivity`) scans **5 zones** of the depth image — Center, Top-Left, Top-Right, Bottom-Left, Bottom-Right — each a 51×51 pixel region, and counts pixels with valid (>0) depth:
- **Per-zone threshold**: >40% valid pixels.
- **Pass criteria**: `Center valid AND (≥2 of the 4 corners valid)`.

Until warmup passes, the startup wormhole animation (`ivWormhole` / `ParticleView`) stays visible; on pass, it hides. A 4000ms failsafe (`warmupTimeoutRunnable`) hides it regardless to prevent a deadlock when the user is in a poor lighting / featureless scene.

### NotYetAvailableException Handling
`frame.acquireCameraImage()` and `frame.acquireDepthImage16Bits()` throw `NotYetAvailableException` during the first few frames after `Session.resume()`. **This is expected** — log as `WARN` ("Camera not ready"), not `CRITICAL`. Wrapping the realtime loop in a broad `catch (e: Exception)` would also swallow it, but explicit handling produces cleaner logs.

## Camera / Intrinsics: Adaptive Device Position

The pinhole model assumes the principal point matches the image center, but ARCore's `imageIntrinsics` already encodes the actual `principalPoint` for the device's sensor mount. The recent fix on the `main` branch (commits `70b98ee`, `c9db00a`) corrects how the app handles devices where the camera is mounted off-center or where the preview surface is letterboxed — we read intrinsics every frame rather than caching them across orientation changes, and we apply the intrinsics' own `imageDimensions` (not the screen size) when scaling normalized YOLO coordinates back into pixel space.

## YUV → RGB Conversion

`YuvToRgbConverter` uses **RenderScript ScriptIntrinsic** (`ScriptIntrinsicYuvToRGB`) to convert `ImageProxy` YUV_420_888 frames into an `argb_8888` Bitmap suitable for the TFLite `ImageProcessor`. RenderScript is deprecated upstream but remains the fastest path that works across the supported SDK range — `renderscriptTargetApi = 30` + `renderscriptSupportModeEnabled = true` in `build.gradle.kts` keeps the support library active down to API 24.

> **Note**: the v0.1.1 To-Do flags this as a bottleneck. The current code path is `YUV → NV21 → Bitmap` in some modes; a direct YUV-Bitmap path (and pushing rotate/resize into the TFLite `ImageProcessor`) is planned. See `ROADMAP.md`.

## Skeleton Rendering

`OverlayView` is a custom `View` (not GLSurface) that draws on top of the camera preview / GL background:
- Receives `List<Person>` plus the per-keypoint `x3d, y3d, z3d` from `MainActivity` after depth fusion.
- Connects the 17 COCO keypoints with line segments using the standard COCO skeleton edges.
- Renders the 3D coordinate readouts as a text overlay for the active drill-analysis keypoints.
- Default field values are `0f` so a frame with a failed depth lookup degrades to "last valid value" without crashing.

The recent fix on `main` (commit `194ed1f`) addressed a skeleton/preview rendering mismatch caused by the overlay using a different rotation/aspect than the underlying camera surface.

## Two Camera Modes

The app exposes two pipelines selected from `StartActivity`:
- **ToF Mode** — ARCore session, RAW_DEPTH, full 3D pose. Requires hardware depth sensor.
- **2D Mode** — CameraX `ImageAnalysis` only, YOLO detection, no depth fusion. Works on any ARCore-supported device.

A MiDaS-based monocular-depth mode is planned (see ROADMAP) but **not yet present** in the codebase.

## Performance Patterns

- **GC discipline on hot paths**: every per-frame allocation has been hunted out of `BodyMeasureEngine` (pre-allocated buffers, primitive arrays, no boxed `Float`).
- **Early exit**: spiral search returns the first valid depth rather than averaging — speed beats accuracy in the fallback path because the median already failed.
- **Delegate fallback is logged**: each delegate transition emits a `Log.w` with the failure reason so device-specific incompatibilities are visible in logcat without a debugger.
- **Touch-to-focus**: removed in v0.1.0 — ARCore's `Session` API does not expose metering-area focus, so a focus circle was misleading. `FocusMode.AUTO` is the only available mode.

## Observability

- All logs use `android.util.Log` with class name as tag (`YoloDetector`, `MainActivity`, etc.).
- Delegate selection, warmup pass/fail, and per-frame fatal errors all log to logcat. No file-based log dumping yet.
- `IPS counter` (added in v0.1.1) measures inferences-per-second for benchmark comparisons across delegate/model combinations — visible in the on-screen overlay during a session.

## Module Layout & Threading

Two activities, single process:
- **`StartActivity`** — main launcher (`MAIN` / `LAUNCHER` intent filter), mode picker.
- **`MainActivity`** — owns the ARCore `Session` or the CameraX bindings depending on mode. Frame callbacks fire on the GL / ImageAnalysis executor thread; UI updates (`OverlayView.invalidate`, particle animation) post back to the main thread via `runOnUiThread` / `View.post`.

`BodyMeasureEngine` and `YoloDetector` are **not** thread-safe by themselves — each is instantiated once and called from the single frame-processing thread. Don't share them across threads without adding synchronization.
