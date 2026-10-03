# Issue 31 canvas gesture checks

Automated coverage: `CanvasGestureOwnershipTest` exercises the shared canvas with
both full-screen and floating input tags. It covers overlapping unselected images,
topmost tap selection, pan ownership, fresh pinch zoom, and release without image
selection or transformation. Instrumentation requires a device; compilation alone
does not verify runtime behavior.

Lead smoke checks, repeated in full-screen and floating mode:

1. Import two images, overlap them, and deselect by tapping empty space. Drag from
   their overlap across another image. The canvas pans; every image transform stays
   unchanged. Repeat with a drag shorter than platform touch slop: it selects the
   topmost image instead of panning.
2. Start a fresh pinch with both fingers on images, then with one finger on empty
   space. Zoom around the finger midpoint. Release, then drag an unselected image:
   the canvas still pans. The pinch must not select, move, resize, or rotate images.
3. Tap the topmost image, drag its center, resize each corner, and drag its rotation
   control. Only the selected image changes; the viewport stays unchanged.
4. During an established selected-image drag, add a second finger away from its
   controls. The viewport must remain unchanged. Lift all fingers before starting
   a new pinch. Two-finger image resizing belongs to issue 32.
5. Move and resize the floating window using its host controls. Canvas gestures
   must stay distinct from those controls.

Canvas pinch continuation with one remaining finger is currently omitted. The
remaining finger stays inactive until all fingers lift, avoiding a viewport jump
or accidental image selection. Issue 32 will assess continuation and transitions.
