# 3D Object Overlay Features Implementation

## Summary of Implementation

This Android application enhances the existing MediaPipe Face Landmarker with 3D object overlay capabilities. Here's what has been implemented:

## ✅ Completed Features

### 1. Enhanced .gitignore File
- Added comprehensive ignore patterns for Android development
- Included 3D model file formats (*.obj, *.fbx, *.ply, etc.)
- Added upload and temporary directories
- Included common development artifacts

### 2. File Upload Utility (`FileUploadHelper.kt`)
- **Storage Integration**: Uses Android Storage Access Framework
- **Supported Formats**: OBJ, FBX, PLY, STL, 3DS, DAE, BLEND, GLTF, GLB
- **File Validation**: Checks file extensions and sizes
- **Internal Storage**: Copies uploaded files to app-internal storage
- **Modern API**: Uses Activity Result API for file picking

### 3. 3D Model Parser (`Model3DParser.kt`)
- **OBJ File Parsing**: Complete implementation for Wavefront OBJ files
- **3D Math Classes**: Vertex3D, Face3D, Model3D with mathematical operations
- **Geometric Calculations**: Centroid, bounding box, scale factor calculation
- **Test Objects**: Built-in test cube generator for testing

### 4. Head Direction Calculator (`HeadDirectionCalculator.kt`)
- **Landmark Processing**: Uses MediaPipe's 468 face landmarks
- **Central Weight Point**: Calculated from key facial features (nose bridge, eyes, mouth, forehead)
- **Direction Vector**: From head center to nose tip landmark
- **Rotation Angles**: Calculates yaw (left/right), pitch (up/down), roll (tilt)
- **3D Pose Estimation**: Converts to transformation matrix for object positioning

### 5. 3D Renderer (`Model3DRenderer.kt`)
- **Wireframe Rendering**: Real-time 3D wireframe overlay on camera feed
- **Perspective Projection**: Proper 3D to 2D projection with depth
- **Head-Aligned Positioning**: Objects positioned and oriented based on head pose
- **Canvas Drawing**: Uses Android Canvas API for efficient rendering
- **Depth Sorting**: Basic painter's algorithm for face rendering order

### 6. Enhanced Overlay View (`OverlayView.kt`)
- **Integrated 3D Rendering**: Seamlessly combines face landmarks with 3D objects
- **Real-time Updates**: Synchronizes with face detection results
- **Toggle Functionality**: Show/hide 3D models dynamically
- **Performance Optimized**: Efficient canvas-based rendering

### 7. New Model3D Fragment (`Model3DFragment.kt`)
- **User Interface**: Dedicated screen for 3D model management
- **File Upload**: Button to select 3D model files from storage
- **Test Mode**: Built-in test cube for immediate functionality testing
- **Model Controls**: Toggle visibility, clear models, navigate to camera
- **Status Display**: Real-time feedback on model loading and processing

### 8. Updated Architecture
- **ViewModel Integration**: `MainViewModel.kt` extended with 3D model state management
- **Navigation**: Added Model3D fragment to navigation graph
- **Menu System**: New bottom navigation item for 3D Models
- **Fragment Communication**: Proper state sharing between camera and model management

### 9. Head Direction Algorithm

The head direction calculation works as follows:

1. **Key Landmark Selection**: 
   - Nose bridge (landmark 6)
   - Eye inner corners (landmarks 133, 362)
   - Mouth corners (landmarks 61, 291)
   - Forehead center (landmark 9)

2. **Central Weight Point Calculation**:
   ```kotlin
   headCenter = (noseBridge + leftEye + rightEye + leftMouth + rightMouth + forehead) / 6
   ```

3. **Direction Vector**:
   ```kotlin
   direction = (noseTip - headCenter).normalize()
   ```

4. **Rotation Angles**:
   - **Yaw**: `atan2(noseToEye.x, noseToEye.z)` (left/right rotation)
   - **Pitch**: `atan2(noseToChin.y, sqrt(noseToChin.x² + noseToChin.z²))` (up/down rotation)
   - **Roll**: `atan2(eyeVector.y, eyeVector.x)` (tilt rotation)

### 10. 3D Object Alignment Process

1. **Transformation Matrix Generation**: 4x4 matrix combining rotation and translation
2. **Object Positioning**: Slightly above and in front of head center
3. **Real-time Updates**: Recalculates transformation on every frame
4. **Coordinate Mapping**: Converts 3D coordinates to screen coordinates

## 🔧 Technical Implementation Details

### Dependencies Added
- `androidx.documentfile:documentfile:1.0.1` - File system access
- `com.google.code.gson:gson:2.10.1` - JSON parsing
- `org.jetbrains.kotlinx:kotlinx-coroutines-android:1.6.4` - Background processing

### Permissions Required
- `READ_EXTERNAL_STORAGE` - Access to device files
- `WRITE_EXTERNAL_STORAGE` - File operations (API ≤ 28)
- `MANAGE_EXTERNAL_STORAGE` - Enhanced file access
- `CAMERA` - Face detection (existing)

### File Structure Added
```
app/src/main/java/com/google/mediapipe/examples/facelandmarker/
├── FileUploadHelper.kt          # File upload and storage management
├── Model3DParser.kt             # 3D model parsing and processing
├── HeadDirectionCalculator.kt   # Head pose estimation
├── Model3DRenderer.kt           # 3D rendering engine
├── fragment/Model3DFragment.kt  # UI for 3D model management
└── [Enhanced existing files]
```

## 🎯 How It Works

1. **User uploads a 3D model** via the Model3D fragment
2. **File is parsed** and converted to internal 3D representation
3. **Camera detects face landmarks** using MediaPipe
4. **Head direction is calculated** from landmark positions
5. **3D object is positioned and oriented** based on head pose
6. **Real-time rendering** overlays the object on the camera feed

## 🚀 Usage Instructions

1. **Launch App** → Grant camera and storage permissions
2. **Navigate to "3D Models" tab** → Upload OBJ file or load test cube
3. **Switch to "Camera" tab** → Point camera at face
4. **3D object automatically appears** aligned with head direction
5. **Move head** → Object follows head orientation in real-time

## 🧪 Testing

- **Test Cube**: Built-in wireframe cube for immediate testing
- **OBJ Files**: Upload any Wavefront OBJ file from device storage
- **Real-time Performance**: Optimized for smooth rendering at camera framerate

This implementation provides a complete solution for 3D object overlay on detected faces with accurate head direction tracking and real-time alignment.