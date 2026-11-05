---
name: camerax
description: Implement camera features with CameraX - Compose preview (controller or CameraXViewfinder), photo capture with rotation fix, video recording, ML Kit analysis, camera switching, hardware rules, and camera testing with fakes. Use when the user asks to add a camera, take photos, record video, scan barcodes/faces, or build any camera screen.
metadata:
  argument-hint: "<feature> [photo|video|analysis]"
---

Build the camera feature per this project's conventions. Adapted from Google's official camerax skill (github.com/android/skills) and Philipp Lackner's CameraXGuide, with APIs verified current. Permissions come FIRST: run `/add-permission` for `CAMERA` (+ `RECORD_AUDIO` only if recording with audio — check it separately when enabling audio).

## Setup

Version catalog: one `camerax` version for all artifacts — check the current stable at developer.android.com/jetpack/androidx/releases/camera (1.6.x era as of 2026-08; never pin from memory). Artifacts: `camera-core`, `camera-camera2`, `camera-lifecycle`, `camera-view`; add `camera-video` (recording), `camera-compose` (Compose-native viewfinder — stable), `camera-mlkit-vision` (ML Kit), `camera-extensions` (night/bokeh).

## The one architectural fork: controller vs provider

| | `LifecycleCameraController` (default) | `ProcessCameraProvider` + `CameraXViewfinder` |
|---|---|---|
| When | Common cases — photo/video/analysis screens | Custom use-case configs, pure-Compose rendering, feature groups |
| Preview | `PreviewView` in `AndroidView` (the controller API is built around PreviewView) | `CameraXViewfinder(surfaceRequest)` from camera-compose — no AndroidView |
| Extras | Tap-to-focus + pinch-zoom built in; ML Kit results in VIEW coordinates | Full control; you do coordinate transforms yourself |

Pick the controller unless a requirement forces the provider path. Never mix both for one screen.

### Controller + preview (default path)

```kotlin
// In the Screen (Compose side — the controller is a View-world object; it does NOT go in the ViewModel)
val controller = remember {
    LifecycleCameraController(context.applicationContext).apply {
        setEnabledUseCases(CameraController.IMAGE_CAPTURE or CameraController.VIDEO_CAPTURE)
        // Preview is always enabled by default; setEnabledUseCases ADDS on top of it
    }
}
val lifecycleOwner = LocalLifecycleOwner.current
AndroidView(
    factory = { PreviewView(it).apply { this.controller = controller; controller.bindToLifecycle(lifecycleOwner) } },
    modifier = Modifier.fillMaxSize()
)
```

### Provider + CameraXViewfinder (Compose-native path)

ViewModel owns a `MutableStateFlow<SurfaceRequest?>`; `Preview` use case's `setSurfaceProvider { request -> _surfaceRequest.value = request }`; bind via `ProcessCameraProvider.awaitInstance(context)` + `bindToLifecycle`; the composable collects the request and renders `CameraXViewfinder(surfaceRequest)`.

## Photo capture

`controller.takePicture` with `OnImageCapturedCallback` (in-memory) — two mandatory fixes over the naive version:

```kotlin
controller.takePicture(ContextCompat.getMainExecutor(context), object : OnImageCapturedCallback() {
    override fun onCaptureSuccess(image: ImageProxy) {
        try {
            val matrix = Matrix().apply { postRotate(image.imageInfo.rotationDegrees.toFloat()) }
            val bitmap = Bitmap.createBitmap(image.toBitmap(), 0, 0, image.width, image.height, matrix, true)
            onAction(CameraAction.PhotoCaptured(bitmap))
        } finally {
            image.close()   // ImageProxy MUST be closed — leaking it stalls the pipeline
        }
    }
    override fun onError(e: ImageCaptureException) { onAction(CameraAction.CaptureFailed) }
})
```

1. The captured image is NOT rotated — apply `imageInfo.rotationDegrees` yourself. 2. Always `image.close()` (the popular tutorials skip this). Results flow through the MVI contract (`Action` in, state/`Event` out) like any other input.

## Video recording

```kotlin
// isRecording lives in the ViewModel State; the Recording HANDLE stays screen-side
var recording by remember { mutableStateOf<Recording?>(null) }

// inside the record button's onClick — toggle: second tap stops
val active = recording
if (active != null) {
    active.stop()
    recording = null
} else {
    recording = controller.startRecording(
        FileOutputOptions.Builder(outputFile).build(),
        AudioConfig.create(true),                    // requires RECORD_AUDIO — check before enabling
        ContextCompat.getMainExecutor(context),
    ) { event ->
        when (event) {
            is VideoRecordEvent.Start -> onAction(CameraAction.RecordingStarted)   // drive state from EVENTS,
            is VideoRecordEvent.Finalize -> {                                      // never from having called start
                // Finalize can arrive WITHOUT a tap (lifecycle stop, max duration) — always clear the handle
                if (event.hasError()) recording?.close()
                recording = null
                if (event.hasError()) onAction(CameraAction.RecordingFailed)
                else onAction(CameraAction.RecordingSaved(outputFile))
            }
        }
    }
}
```

**Immutability trap** (Recorder use-case path): these fluent APIs return a NEW instance — chain or reassign, or the setting is silently ignored: `PendingRecording.withAudioEnabled()/asPersistentRecording()`, `ImageCapture.Builder.setTargetRotation/Resolution`, `Recorder.Builder.setQualitySelector/Executor`, `ViewPort.Builder.setScaleType/LayoutDirection`.

## Switching cameras & hardware rules

- Switch: `controller.cameraSelector = DEFAULT_FRONT_CAMERA / DEFAULT_BACK_CAMERA` — a property swap, no rebinding.
- Never hardcode camera IDs — `CameraSelector.DEFAULT_*` or a capability `filter`.
- Extensions (night, bokeh): query `ExtensionsManager` for device support BEFORE enabling.
- Flash: back = physical, front = screen-based — implement both paths.
- Dual-stream → `ConcurrentCamera` (1.3+); effects → `Media3Effect`/`SurfaceProcessor` over hand-rolled OpenGL; HDR → `DynamicRange.HLG_10_BIT` with SDR fallback; zoom/focus → `CameraControl.setZoomRatio`/`setLinearZoom`, not Camera2Interop.
- Camera callbacks run on background executors — UI updates go through the main executor / Action dispatch.

## Analysis (barcode/face/OCR)

`MlKitAnalyzer` over a hand-written `ImageAnalysis.Analyzer` — it handles resolution override and coordinate transforms:

```kotlin
controller.setImageAnalysisAnalyzer(
    ContextCompat.getMainExecutor(context),
    MlKitAnalyzer(listOf(barcodeScanner), COORDINATE_SYSTEM_VIEW_REFERENCED, ContextCompat.getMainExecutor(context)) { result ->
        result.getResult(barcodeScanner)?.let { onAction(CameraAction.BarcodeDetected(it)) }
    }
)
```

`COORDINATE_SYSTEM_VIEW_REFERENCED` (results pre-mapped to view coordinates for overlays) is controller-only; the provider path gives `COORDINATE_SYSTEM_ORIGINAL`. Manual analyzers must `imageProxy.close()` every frame.

## Testing

- Fakes over mocks — `ImageProxy`/`CameraInfo` mocks are brittle; hand-write `FakeImageProxy(width, height)` and feed the analyzer. Simulate capabilities (no-flash device) with `androidx.camera:camera-testing`'s fake config.
- `cameraProvider.unbindAll()` in `@After` — prevents "Camera in Use" flakes.
- `GrantPermissionRule` in instrumented tests; generous timeout on first init (CI virtual cameras warm up slowly); emulators default to 640x480 — `ResolutionSelector` must tolerate the low-res fallback.
- The ViewModel is tested normally (fake repository/`Action` driving) — camera hardware never enters JVM tests.

## Checklist
- [ ] `/add-permission` ran for CAMERA (+ RECORD_AUDIO if audio); audio checked separately when enabling
- [ ] Controller-vs-provider decision recorded; not mixed
- [ ] Photo path applies rotation AND closes the ImageProxy
- [ ] Recording state driven by `VideoRecordEvent`, not by call-site assumptions
- [ ] No hardcoded camera IDs; extensions capability-checked
- [ ] Analyzer closes frames / uses MlKitAnalyzer
