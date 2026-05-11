# Project Roadmap & Progress

## 📍 Current Focus (Last Update: 2026-05-11)

- **Status**: 🚧 In Progress — **v0.1.1 IPS Optimization**. Goal: raise inferences-per-second across all three inference paths so the realtime pipeline holds up on mid-tier devices. The IPS counter (already shipped in v0.1.1) is the benchmark instrument; current observation per `android_app/README.md`: "IPS is improved but not much as expected." Three sub-tracks:
    1. **YOLO model** — switch from `best_float32.tflite` to `best_float16.tflite` (already bundled in `app/src/main/assets/`). Verify accuracy parity on the existing skeleton; measure IPS delta across NNAPI / GPU / CPU delegates.
    2. **ToF mode YUV path** — replace the current `YUV → NV21 → Bitmap` conversion with direct `YUV → RGB Bitmap`; **do not pre-rotate the bitmap**, instead push `ResizeOp` / `Rot90Op` into the TFLite `ImageProcessor` chain in `YoloDetector` (already partially in place — needs the rotate stage migrated and the upstream conversion shortened).
    3. **MiDaS mode** — stop using `previewView.bitmap` (forces a UI-thread blit). Consume the `ImageProxy` directly from `ImageAnalysis` and reuse `YuvToRgbConverter` (or pass YUV straight to the model if its input layer accepts it). **Note**: MiDaS mode is referenced in the v0.1.1 To-Do but the implementation is not yet in the codebase — this sub-track is partly scaffolding.
- **Current system state / blockers**: FP16 model file is present but not yet wired as the default. No A/B benchmark numbers recorded. The skeleton-rendering mismatch (commit `194ed1f`) and AE/AF init issue (commit `002fa89`) are both resolved as of `main` HEAD; adaptive device position (commits `70b98ee`, `c9db00a`) is also in.
- **Starting point for next session**: pick sub-track (1) — add a runtime model-path switch in `YoloDetector` (or a build flag in `MainActivity`) so we can flip between FP32 and FP16 without recompiling, then capture IPS on a reference device for both. Confirm accuracy parity by eyeballing skeleton stability on a 30-second drill clip before declaring FP16 the default.

- **Previous**: ✅ Completed — Skeleton rendering mismatch fix (commit `194ed1f`). `OverlayView` was using a different rotation/aspect than the camera surface, causing keypoint positions to drift relative to the rendered image; now reconciled.
- **Previous**: ✅ Completed — Most AE/AF initialization issues (commit `002fa89`). Spiral depth search (`BodyMeasureEngine.getSpiralDepth`) + robust 5-zone warmup detection + `NotYetAvailableException` reclassification from CRITICAL to WARN. Animation logic respects `isSensorWarmedUp` with a 4000ms failsafe. See the v0.1.1 entry in `android_app/README.md` for the full design notes.
- **Previous**: ✅ Completed — 3D camera intrinsic fixes related to new adaptive camera position methods (commit `70b98ee`). Intrinsics are read per-frame rather than cached across orientation changes; the intrinsics' own `imageDimensions` (not the screen size) drive the YOLO-coord-to-pixel scaling.
- **Earlier**: ✅ Completed — Adaptive method on device position (commit `c9db00a`).

## Version History

| Version | Status | Highlights |
| :--- | :--- | :--- |
| **v0.1.0** | ✅ Shipped | Initial YOLOv11-Pose + ARCore ToF pipeline; spec docs; startup animation; in-menu version display; touch-to-focus circle removed (ARCore limitation). |
| **v0.1.1** | 🚧 In progress | AE/AF init hardened (spiral depth + robust warmup); adaptive device position; 3D intrinsics fix; skeleton render fix; IPS counter for benchmarking; **IPS optimization across inference modes (current focus)**; pure-visual solution (not yet started). |

## Implemented Features

| Feature | Module | Status |
| :--- | :--- | :--- |
| **YOLOv11-Pose inference** | `YoloDetector.kt` | ✅ Completed (FP32 default; FP16 bundled, not yet default) |
| **NNAPI / GPU / CPU delegate fallback** | `YoloDetector.kt` | ✅ Completed |
| **ARCore RAW_DEPTH ToF mode** | `MainActivity.kt` | ✅ Completed |
| **CameraX 2D-only mode** | `MainActivity.kt` | ✅ Completed |
| **Pinhole 3D unprojection** | `BodyMeasureEngine.get3DJointPosition` | ✅ Completed |
| **3×3 median depth filter** | `BodyMeasureEngine.getSmoothedDepth` | ✅ Completed |
| **Spiral-search depth fallback** | `BodyMeasureEngine.getSpiralDepth` | ✅ Completed |
| **Robust 5-zone warmup detection** | `MainActivity.checkRobustWarmup` | ✅ Completed |
| **YUV → RGB conversion** | `YuvToRgbConverter.kt` | ✅ Completed (RenderScript; optimization pending) |
| **Skeleton overlay rendering** | `OverlayView.kt` | ✅ Completed |
| **Startup wormhole animation** | `ParticleView.kt`, `StartActivity.kt` | ✅ Completed |
| **IPS counter** | `MainActivity.kt` | ✅ Completed |
| **Adaptive device-position intrinsics** | `MainActivity.kt`, `BodyMeasureEngine.kt` | ✅ Completed |
| **Version display in menu** | `StartActivity.kt` | ✅ Completed |

## Technical Debt & Future Tasks

### Short-term (v0.1.1)
- [ ] **Improve IPS across inference methods** — see Current Focus. Sub-tracks: YOLO FP16, ToF YUV-direct path, MiDaS `ImageProxy` refactor.
- [ ] **Pure-visual (RGB-only 3D) solution** — for devices without ToF/LiDAR. Likely candidates: MiDaS monocular depth, or a learned 2D→3D lift on top of the existing 17 keypoints. Not yet scoped.
- [ ] **AE/AF further testing** — the v0.1.1 fix landed but the To-Do flags "test more" on real-world variation (low light, fast motion, off-axis subjects).

### Long-term
- [ ] **Video playback module** — load a recorded drill clip and run the same pipeline offline for review.
- [ ] **Segment analysis** — slice a drill into phases (setup / execution / follow-through) and compute per-segment metrics from the 3D keypoint stream.
- [ ] **Drill scoring / feedback UI** — turn 3D pose deltas into actionable posture corrections (the project's stated end-goal).
- [ ] **Unit + instrumented tests** — `./gradlew test` and `connectedAndroidTest` currently have only scaffold; cover `YoloDetector.processOutput` / `nms`, `BodyMeasureEngine.getSmoothedDepth` / spiral search math, and the warmup-zone logic.
- [ ] **CI build** — no GitHub Actions yet. Add at minimum `assembleDebug` + `lint` on PR.
- [ ] **Replace RenderScript** — deprecated upstream. Migration target likely `androidx.camera:camera-effects` or a CameraX-native ImageAnalysis pipeline that produces RGBA directly.
- [ ] **Model retraining / domain adaptation** — current YOLOv11-Pose is COCO-trained; drill-specific poses (extreme angles, occluded joints behind sports equipment) may need fine-tuning.
- [ ] **File-based log dumping** — currently logs only to logcat. A session log written to app storage would help when debugging on user devices.
