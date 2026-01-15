# Drill Encoder Android App

This is an Android application that uses a YOLOv11 Pose model to detect human poses and draw skeleton joints on the camera feed.

## Prerequisites

- Android Studio
- Android Device (Developer mode enabled)

## Setup

1.  Open this folder (`android_app`) in Android Studio.
2.  Sync Gradle project.
3.  Connect your Android device.
4.  Run the app.

## Model

The model is located in `app/src/main/assets/best_float32.tflite`.
It was exported from the custom YOLOv11 model.

## Troubleshooting

- If the app crashes on launch, check the Logcat for errors.
- Ensure Camera permissions are granted.
- If the model fails to load, verify the file name in `MainActivity.kt` matches the asset file.




## System Requirements

To ensure optimal performance and functionality, particularly for the **ToF (Time-of-Flight) Mode**, the device must meet the following specifications:

### Operating System
*   **Minimum**: Android 7.0 (Nougat) - API Level 24.
*   **Recommended**: **Android 10.0 (Q) - API Level 29** or higher.
    *   *Reason*: The app utilizes Android's **NNAPI** (Neural Network API) for hardware acceleration of the YOLO model, which is stable and preferred on Android 10+. Lower versions may fallback to slower GPU or CPU inference.

### Hardware Dependencies
*   **AR Capabilities**: Device **MUST** support **Google Play Services for AR (ARCore)**.
    *   *Check compatibility*: [ARCore Supported Devices](https://developers.google.com/ar/devices)
*   **Sensors (Critical for ToF Mode)**: A hardware **Time-of-Flight (ToF)** sensor or **LiDAR** scanner is valid depth data.
    *   The app uses `Config.DepthMode.RAW_DEPTH_ONLY`, which relies on hardware sensors to provide raw, unsmoothed depth maps. Devices without physical depth sensors may fail to initialize ToF mode or provide inaccurate 0-filled depth values.
*   **Processor (SoC)**: High-performance chipset with NPU/GPU support.
    *   *Examples*: Qualcomm Snapdragon 865+, Google Tensor G2 or newer.
    *   *Workload*: Real-time inference of YOLO-Pose (640x640 resolution) + ARCore depth processing requires significant compute power.
*   **RAM**: **6GB** system RAM or higher recommended.

### Supported Devices 
*   **Google**: Pixel 4, Pixel 6 Pro, Pixel 7 Pro, Pixel 8 Pro (and newer).
*   **Samsung**: Galaxy S20+/Ultra, S21+/Ultra, S22 Ultra, S23 Ultra, Note 10+/20 Ultra.
*   **Other**: Devices explicitly listed as supporting the **ARCore Depth API** with hardware sensors.





## Version 0.1.0

### To-Do
- [ ] AE/AF calibration has initialized but sometimes it doesn't work. Test more.
- [&check;] Add spec requirement for the app
- [&check;] Startup animation is not exactly correct, need fix. 
- [&check;] Need version print in menu
    * Final Decision: Added version print in the menu
- [&cross;] Touch-to-focus is not working in ToF mode
    * Standard ARCore `Session` API does not expose a method to set a specific focus point (metering area).
    * `FocusMode.AUTO` in ARCore delegates control to the device's default continuous auto-focus algorithm, which is completely unaware of your touch inputs.
    * The `setOnTouchListener` validation in `MainActivity.kt` (lines 625-630) explicitly notes this limitation: 
        ```kotlin
        // Note: ARCore handles focus automatically, so we only show the visual feedback
        ```
    * Final Decision: Remove focus circle
