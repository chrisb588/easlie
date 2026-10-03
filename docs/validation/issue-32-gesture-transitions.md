# Issue 32 gesture transitions

A second pointer newly placed on a selected image's current resize corner during
an established image move starts proportional resizing. The transition captures
its displayed geometry and the pointers' distance. Subsequent distance changes
scale that geometry around its captured center. Translation of the midpoint does
not translate or rotate the image. Once either resizing pointer lifts, remaining
pointers stay inactive until all lift. The existing corner resize retains its
opposite anchor.

The shared canvas supplies an accessible Deselect image button for both hosts.
Its final placement remains part of issues #19 and #20.

Canvas pinch continuation was considered and omitted. Reusing the original
one-finger pan baseline after a pinch would jump back to the pre-pinch viewport.
Keeping the remaining finger inactive also prevents accidental image ownership.
A fresh gesture after every pointer lifts resumes normal panning. This is the
specification's permitted safe fallback.

Pointer coroutine cancellation and host disposal retain the latest board item
values and invoke the existing save path. No rollback is applied.

Host validation: debug unit tests and lint passed. Android instrumentation sources
compiled. Tests cover both hosts, second-pointer placement, fixed-center distance
resizing, aspect ratio and rotation, midpoint movement, pointer lift ownership,
corner anchoring, explicit deselection, cancellation persistence, fresh input after
cancellation, and canvas-pinch remaining-pointer inactivity.

Android instrumentation execution and the lead's device smoke tests are pending.
No device command, app install, launch, or device test was performed.
