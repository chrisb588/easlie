# Issue 31 canvas gesture checks

Automated coverage: `CanvasGestureOwnershipTest` exercises the shared canvas in
both modes: overlapping unselected images, topmost tap selection, pan ownership,
fresh pinch zoom, release behavior, selected-image controls, and resize/rotation
handles over another image. `CanvasViewportOwnershipTest` adds both-image and
mixed image/empty pinch starts in either pointer order, off-center focal preservation,
twist input without rotation, the remaining-finger fallback, and continuous pan
ownership. `CanvasHostInputTest` injects Android touch events through the real
Activity and floating-service windows. It checks canvas pan, off-center pinch,
release ownership, stable host bounds during canvas input, and floating-window
movement without canvas or image changes.

Device verification on the SM-X616B: all nine tests passed. The host-input test
creates a temporary board, removes only that board, restores the original active
board, and asserts that the original board list, image transforms, and both saved
viewports are unchanged. Overlay permission is required. Compilation alone does
not verify runtime behavior.

Lead smoke checks, numbered to match the issue's ACs. Repeat AC1–AC5 in
full-screen and floating mode:

1. **AC1 — Unselected-image pan:** Import two images, overlap them, and deselect
   by tapping empty space. Drag from an unselected image, then from their overlap.
   The canvas pans; no image is selected or transformed.
2. **AC2 — Tap selection and touch slop:** Tap the overlap without dragging.
   The topmost image is selected. Repeat with movement shorter than platform
   touch slop: it still selects the topmost image instead of panning.
3. **AC3 — Fresh pinch:** Start a fresh pinch with both fingers on images, then
   with one finger on empty space. Zoom around the finger midpoint. Release, then drag an unselected image:
   the canvas still pans. The pinch must not select, move, resize, or rotate images.
   Repeat away from the canvas center to check focal-point preservation.
4. **AC4 — Selected-image controls:** Tap an image, drag its center, resize each
   corner, and drag its rotation control. Repeat with its handles inside another
   unselected image, including an image above it and one below it. Only the
   selected image changes; the viewport stays unchanged. A stationary tap on a
   handle over another image must still select the topmost image at that point.
5. **AC5 — Canvas ownership:** Deselect, then start a canvas drag on an unselected
   image. Continue dragging, change direction, and release. It must remain a canvas
   gesture throughout; no image is selected or transformed during the gesture or
   on release. Repeat with a fresh pinch over images, then start another drag to
   confirm that release did not select an image. Supported canvas gestures work
   over unselected images without introducing canvas rotation. A pan moves images
   with your finger, so this check does not require crossing a different image.
6. **AC6 — Automated coverage and host instructions (contributor check):** Run
   `CanvasGestureOwnershipTest`, `CanvasViewportOwnershipTest`, and
   `CanvasHostInputTest` on the device. All nine tests must pass, including the
   real-host input and original-board restoration assertions. AC1–AC5 above
   provide the lead's checks for both hosts.

Additional ownership checks, outside the AC numbering:

- During an established selected-image drag, add a second finger away from its
   controls. The viewport must remain unchanged. Lift all fingers before starting
   a new pinch. Two-finger image resizing belongs to issue 32.
- Move and resize the floating window using its host controls. Canvas gestures
   must stay distinct from those controls.

Canvas pinch continuation with one remaining finger is currently omitted. The
remaining finger stays inactive until all fingers lift, avoiding a viewport jump
or accidental image selection. Issue 32 will assess continuation and transitions.
