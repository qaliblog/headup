# MediaPipe Face Landmark Detection with 3D Object Overlay Android Demo

### Overview

This is an enhanced camera app that detects face landmarks and overlays 3D objects aligned with head direction. The app can detect face landmarks from continuous camera frames, images, or videos from the device's gallery using MediaPipe **task** files, and then renders uploaded 3D models positioned and oriented based on the detected head pose.

### Key Features

- **Face Landmark Detection**: Real-time detection of 468 face landmarks using MediaPipe
- **3D Object Upload**: Support for uploading 3D model files from device storage
- **Head Direction Calculation**: Calculates head orientation using central weight point and nose landmark
- **3D Object Alignment**: Automatically aligns and positions 3D objects based on head direction
- **Real-time Rendering**: Live overlay of 3D objects on camera feed

### Supported 3D File Formats

- OBJ (Wavefront OBJ) - Currently implemented
- FBX, PLY, STL, 3DS, DAE, BLEND, GLTF, GLB - Supported formats (future implementation)

The task file is downloaded by a Gradle script when you build and run the app. You don't need to do any additional steps to download task files into the project explicitly unless you wish to use your own landmark detection task.

This application should be run on a physical Android device to take advantage of the camera and file system access.

## Build the demo using Android Studio

### Prerequisites

*   The **[Android Studio](https://developer.android.com/studio/index.html)** IDE. This sample has been tested on Android Studio Dolphin.

*   A physical Android device with a minimum OS version of SDK 24 (Android 7.0 -
    Nougat) with developer mode enabled. The process of enabling developer mode
    may vary by device.
    
*   **Storage Access**: The app requires access to device storage for uploading 3D model files.

*   **Camera Permission**: Required for real-time face detection and 3D object overlay.

### Building

*   Open Android Studio. From the Welcome screen, select Open an existing
    Android Studio project.

*   From the Open File or Project window that appears, navigate to and select
    the mediapipe/examples/face_landmarker/android directory. Click OK. You may
    be asked if you trust the project. Select Trust.

*   If it asks you to do a Gradle Sync, click OK.

*   With your Android device connected to your computer and developer mode
    enabled, click on the green Run arrow in Android Studio.

## How to Use the 3D Object Overlay Feature

1. **Launch the App**: Open the app and grant camera and storage permissions when prompted.

2. **Upload 3D Model**:
   - Navigate to the "3D Models" tab in the bottom navigation
   - Tap "Select 3D Model File" to upload a 3D object file from your device
   - Alternatively, tap "Load Test Cube" to use a built-in test object

3. **Camera View**:
   - Switch to the "Camera" tab to see the live camera feed
   - Point the camera at a face - the app will detect face landmarks
   - The 3D object will automatically appear and align with the detected head direction

4. **Controls**:
   - Use the toggle button in the 3D Models tab to show/hide the 3D object
   - The object automatically rotates and moves based on head pose changes

## Technical Implementation

### Head Direction Calculation

The app calculates head direction using:
- **Central Weight Point**: Calculated from key facial landmarks (nose bridge, eye corners, mouth corners, forehead center)
- **Direction Vector**: From head center to nose tip landmark
- **Rotation Angles**: Yaw, pitch, and roll calculated from facial geometry

### 3D Object Alignment

- Objects are positioned relative to the head center point
- Rotation matrix applied based on calculated head pose
- Real-time updates maintain alignment as head moves

### File Format Support

Currently supports OBJ (Wavefront) format with plans to expand to other common 3D formats.

### Models used

Downloading, extraction, and placing the models into the *assets* folder is
managed automatically by the **download.gradle** file.

## Dependencies Added

- **Document File API**: For file system access and 3D model uploads
- **Activity Result API**: For modern file picker integration (already included in fragment-ktx)
- **Gson**: For JSON parsing of certain 3D formats
- **Kotlinx Coroutines**: For background processing of 3D model files
- **Custom 3D Rendering**: Simple canvas-based 3D projection and wireframe rendering
