# Image intake and manipulation

Issue #4 adds image intake and editing to the current in-memory session. Use **Add images** to select several images, or choose easlie from another app's image share menu. New images start near the board center and appear above existing images.

Tap an image to select it before dragging it. The four white corner handles resize it while keeping its proportions. The colored handle above it rotates it. Double-tap an image to open **Delete**. Drag empty space to pan; two fingers always move or zoom the viewport.

JPEG, PNG, WebP, BMP, HEIC/HEIF, and AVIF are accepted when the device decoder supports them. Unsupported MIME types, missing sources, and failed decodes show a dismissible error and leave existing items unchanged. Image loading runs off the main thread. EXIF rotation and reflection are applied before placement.

This implementation retains sampled previews in memory. It does not save the board after process termination. App-owned encoded assets, persistent manifests, and resolution-aware decoding/cache behavior belong to later tickets.

## Automated checks

The host geometry suite covers viewport transforms, diagonal placement and wrapping, rotated hit testing, stacking ties, proportional resizing with a fixed opposite corner, resize limits, and rotation around the item center.

Android tests cover URI share payloads, image subsampling and EXIF orientation, unsupported/missing/corrupt sources, selected versus unselected drags, resize and rotation handles, double-tap deletion, and rejection without changing existing content. They are compiled by the host source check but must be executed on Android to validate runtime behavior.

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:compileDebugAndroidTestKotlin
```

## Device validation still required

After rebasing onto `main` containing PR #13, the debug APK was built, installed, and launched on a Samsung SM-X616B running Android 16. The activity remained foregrounded and the crash log was empty. All 21 host unit tests passed, and instrumentation sources compiled. The instrumentation suite was not executed.

Lint reports an inherited API-level error in `FloatingBoardService.kt`: the four-argument `Rect.inset` overload requires API 31, but its path is guarded for API 30. This error also exists in the updated `main` and is outside issue #4.

For full acceptance testing, import several gallery images and share single and multiple images from a browser and an image-focused app. Check that invalid inputs leave the existing board unchanged. Pan and zoom before batch imports to verify center placement, diagonal spacing, and wrapping.

Select overlapping images and confirm that the top image receives taps. Confirm that dragging an unselected image leaves it in place, then select it and move it. Resize using each corner after rotation, rotate using the upper handle, and delete through the double-tap menu. Pinch while an image is selected and during an item drag; only the viewport should change once two fingers are active. Rotate the device and confirm that the session survives without replaying the previous share batch.
