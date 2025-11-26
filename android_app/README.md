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
