# Issue 32 gesture transitions

A second pointer newly placed on a selected image's current resize corner while
the first finger touches its body starts proportional resizing. No preceding
drag or minimum first-finger movement is required. A second finger elsewhere
cannot claim the canvas or start resizing. The transition captures
its displayed geometry and the pointers' distance. Subsequent distance changes
scale that geometry around its captured center. Translation of the midpoint does
not translate or rotate the image. When either resizing pointer lifts, the
remaining finger resumes dragging the image from its displayed position and
size. Lifting a finger causes no jump, and a later second finger on a current
corner can start resizing again. The gesture never transfers to canvas control;
all fingers must lift before a fresh canvas gesture. The existing corner resize
retains its opposite anchor.

The shared canvas supplies an accessible Deselect image button for both hosts.
Its final placement remains part of issues #19 and #20.

Canvas pinch continuation was considered and omitted. Reusing the original
one-finger pan baseline after a pinch would jump back to the pre-pinch viewport.
Keeping the remaining finger inactive also prevents accidental image ownership.
A fresh gesture after every pointer lifts resumes normal panning. This is the
specification's permitted safe fallback.

Pointer coroutine cancellation and host disposal retain the latest board item
values and invoke the existing save path. No rollback is applied. Double-tap
history survives selection-triggered pointer-handler restarts so selecting an
image on the first tap does not prevent the second tap from opening Delete.

Validation on 2026-11-05: 49 debug unit tests, lint, and diff checks passed. The
updated app was installed and launched on the connected Samsung SM-X616B before
AC verification. All 22 targeted Android tests passed: CanvasGestureOwnershipTest
(6), CanvasResizeContinuationTest (8), CanvasInterruptionTest (6), and
CanvasViewportOwnershipTest (2).

Coverage includes both canvas modes, a stationary first finger followed by a
second finger on a corner, and a second finger away from all corners leaving
both the image and viewport unchanged. The stationary-corner regression tests
failed in both modes before the fix and passed afterward. Tests also cover each
resize finger lifting first, repeated
resize-to-drag transitions, unchanged geometry on pointer lift, and double taps
with recomposition between taps. Cancellation tests wait for the displayed move,
rotation, or resize before cancellation, then compare that exact transform with
the saved snapshot. Canvas-removal tests remove the composable while pointers
are down, verify its displayed transform on disk, remount it, and verify a fresh
drag and subsequent save. These use temporary boards.

AC5 is verified for injected touch cancellation and canvas removal in both modes.
Activity recreation, service restart, and abrupt process death were not tested.
If additional fingers cause the resizing pair to lose ownership, the remaining
touches stay inactive until all fingers lift. The lead's updated AC1 and AC4
user-flow checks remain pending.
