# Issue 31 canvas gesture checks

Automated coverage: `CanvasGestureOwnershipTest` exercises the shared canvas with
both full-screen and floating input tags. It covers overlapping unselected images,
topmost tap selection, pan ownership, fresh pinch zoom, and release without image
selection or transformation. It also checks selected resize and rotation handles
over another image, with unchanged viewport and stationary-tap selection preserved.
Instrumentation requires a device; compilation alone does not verify runtime behavior.

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
5. **AC5 — Canvas ownership:** Deselect, then start a canvas drag and cross a
   separately positioned image. No image is selected or transformed during the
   drag or on release. Repeat supported canvas gestures over unselected images;
   the canvas must not rotate.
6. **AC6 — Automated coverage and host instructions (contributor check):** Run
   `CanvasGestureOwnershipTest` on the device. Its full-screen and floating-mode
   cases exercise the shared composable, not input through the actual floating
   service. Mixed image/empty pinch starts, off-center pinch focus, crossing a
   separate image, and actual floating-window input still lack automated coverage.
   Keep this AC unchecked until that coverage is supplied. AC1–AC5 above provide
   the lead's checks for both hosts.

Additional ownership checks, outside the AC numbering:

- During an established selected-image drag, add a second finger away from its
   controls. The viewport must remain unchanged. Lift all fingers before starting
   a new pinch. Two-finger image resizing belongs to issue 32.
- Move and resize the floating window using its host controls. Canvas gestures
   must stay distinct from those controls.

Canvas pinch continuation with one remaining finger is currently omitted. The
remaining finger stays inactive until all fingers lift, avoiding a viewport jump
or accidental image selection. Issue 32 will assess continuation and transitions.
