# Debugging 3D Model Loading Issue

## 🔍 **Issue Description**
User selects a model in the Library tab and navigates to Camera, but the 3D model doesn't appear on their face.

## 🛠️ **Debugging Steps Applied**

### 1. **Added Logging to CameraFragment**
```kotlin
Log.d(TAG, "Setting 3D model with ${model.vertices.size} vertices")
Log.w(TAG, "ViewModel says model should be visible but model is null")
Log.d(TAG, "ViewModel says 3D model should not be visible")
```

### 2. **Added Logging to OverlayView**
```kotlin
Log.d("OverlayView", "Rendering 3D model - show3DModel: $show3DModel, hasModel: ${model3DRenderer.hasModel()}")
Log.d("OverlayView", "Head pose calculated: center=${pose.center}, direction=${pose.direction}")
Log.w("OverlayView", "Could not calculate head pose")
```

### 3. **Added Logging to ModelLibraryFragment**
```kotlin
Log.d(TAG, "Successfully loaded model: ${model.getDisplayName()} with ${model3D.vertices.size} vertices, ${model3D.faces.size} faces")
Log.d(TAG, "Set model in ViewModel. ViewModel visible state: ${viewModel.is3DModelVisible()}")
```

### 4. **Added Logging to Model3DRenderer**
```kotlin
Log.d(TAG, "Rendering model with ${model.vertices.size} vertices, ${model.faces.size} faces")
Log.d(TAG, "Projected ${projectedVertices.size} vertices")
Log.d(TAG, "Wireframe rendering completed")
```

### 5. **Fixed CameraFragment Logic**
**Before** (problematic):
```kotlin
viewModel.get3DModel()?.let { model ->
    if (!fragmentCameraBinding.overlay.is3DModelVisible()) {
        fragmentCameraBinding.overlay.set3DModel(model)
    }
}
```

**After** (corrected):
```kotlin
if (viewModel.is3DModelVisible()) {
    viewModel.get3DModel()?.let { model ->
        fragmentCameraBinding.overlay.set3DModel(model)
    }
}
```

## 🔍 **Troubleshooting Checklist**

### **Step 1: Verify Model Selection**
Check logs when selecting a model in Library:
```
ModelLibraryFragment: Successfully loaded model: [ModelName] with [X] vertices, [Y] faces
ModelLibraryFragment: Set model in ViewModel. ViewModel visible state: true
```

### **Step 2: Verify Camera Integration**
Check logs in Camera view:
```
CameraFragment: Setting 3D model with [X] vertices
```

### **Step 3: Verify Overlay Rendering**
Check logs in OverlayView:
```
OverlayView: Rendering 3D model - show3DModel: true, hasModel: true
OverlayView: Head pose calculated: center=Vertex3D(...), direction=Vertex3D(...)
```

### **Step 4: Verify 3D Renderer**
Check logs in Model3DRenderer:
```
Model3DRenderer: Rendering model with [X] vertices, [Y] faces
Model3DRenderer: Projected [X] vertices
Model3DRenderer: Wireframe rendering completed
```

## 🚨 **Common Issues & Solutions**

### **Issue 1: Model Not Loading from Storage**
**Symptoms**: No logs from ModelLibraryFragment about loading model
**Solution**: Check file paths and model storage integrity

### **Issue 2: ViewModel Not Retaining Model**
**Symptoms**: "ViewModel says model should be visible but model is null"
**Solution**: Verify ViewModel lifecycle and model persistence

### **Issue 3: Head Pose Not Calculated**
**Symptoms**: "Could not calculate head pose"
**Solutions**: 
- Ensure face is properly detected
- Check MediaPipe landmark availability
- Verify HeadDirectionCalculator logic

### **Issue 4: Model Not Visible in Overlay**
**Symptoms**: "show3DModel: false" or "hasModel: false"
**Solutions**:
- Check OverlayView state management
- Verify set3DModel is being called
- Ensure model visibility flags are correct

### **Issue 5: Rendering Pipeline Issues**
**Symptoms**: Logs show model loading but no visual output
**Solutions**:
- Check wireframe rendering logic
- Verify canvas drawing operations
- Check coordinate transformation

## 📱 **How to Use Debugging**

### **1. Enable Debug Logging**
Use Android Studio Logcat with filters:
- `ModelLibraryFragment`
- `CameraFragment` 
- `OverlayView`
- `Model3DRenderer`

### **2. Test Workflow**
1. Select model in Library (check logs)
2. Navigate to Camera (check logs)
3. Point camera at face (check logs)
4. Look for 3D model overlay

### **3. Identify Problem Point**
Follow the log chain to see where the process breaks:
```
Library → ViewModel → Camera → Overlay → Renderer → Canvas
```

## 🎯 **Expected Log Flow**

When working correctly, you should see:
```
1. ModelLibraryFragment: Successfully loaded model...
2. ModelLibraryFragment: Set model in ViewModel...
3. CameraFragment: Setting 3D model with X vertices
4. OverlayView: Rendering 3D model - show3DModel: true, hasModel: true
5. OverlayView: Head pose calculated...
6. Model3DRenderer: Rendering model with X vertices...
7. Model3DRenderer: Wireframe rendering completed
```

## 🔧 **Next Steps**

1. **Run the app with updated logging**
2. **Follow the test workflow**
3. **Check logs to identify where the chain breaks**
4. **Report back with specific log outputs**

This will help pinpoint exactly where the 3D model loading is failing!