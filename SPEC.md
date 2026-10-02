# easlie

A native Android reference board. Arrange images on a freeform canvas, then open
that board in a movable, resizable floating window over another app.

## Specification scope

This specification defines the scope and behavior for v0.2.0 and v0.3.0. The
v0.1.0 feature list records the released foundation. Existing features must
continue to work unless a requirement for the relevant release changes their
behavior. Automatic image snapping is deferred entirely to v0.3.0; it is not a
v0.2.0 implementation or completion requirement.

The lead decides the final design and architecture. The snapping proposal below
provides concrete behavior to review during implementation.

## Released v0.1.0 features

- One persistent freeform reference board.
- Full-screen and floating views of the same board.
- Separate saved canvas viewports for full-screen and floating modes.
- Canvas panning and focal-point pinch zoom.
- Image selection, movement, aspect-preserving resizing, rotation, and deletion.
- Persistent image stacking order, with newly imported images above existing images.
- Import of multiple images through the Android photo picker.
- Single-image and multiple-image imports through Android share intents.
- App-owned image copies that remain available independently of their source files.
- Local atomic save and restore across app restarts and device reboots.
- Recovery messages for images that cannot be restored.
- Resolution-aware background image decoding, visibility culling, and a bounded cache.
- Movable and resizable floating window with return and close actions.
- Overlay-permission handling and an ongoing foreground-service notification.
- Performance and lifecycle validation with 20 to 30 real images on a physical tablet,
  recorded in [the v0.1.0 profiling report](docs/profiling/2026-10-02/report.md).

## v0.2.0 goals

Redesign the full-screen and floating-board experiences. Make their controls and
interactions easier to discover and use.

Allow canvas gestures over unselected images. Support multiple independent boards
with naming and renaming. Add a small About section and light, dark, and system
appearance options.

Preserve existing board data when upgrading from v0.1.0. Maintain responsive image
manipulation and reliable floating-window behavior.

## v0.3.0 goals

Add automatic image snapping during movement, resizing, and rotation in both
full-screen and floating modes. Preserve the board management, gestures, appearance
settings, and reliability introduced in v0.2.0.

## Non-goals

- Collaboration, accounts, cloud sync, or a web client.
- Drawing, text, links, video, animation, or other board item types.
- Image editing beyond position, size, and rotation.
- Automatic layout, tagging, search, folders, or mood-board templates.
- Desktop, iOS, phone-specific, or foldable-specific interfaces.
- Exporting or sharing a composed board, or adding backup functionality.
- Board duplication, image duplication, or manual stacking-order controls.
- Undo and redo.

Profiling, performance measurements, and benchmark comparisons are out of scope
for v0.2.0. Functional responsiveness and lifecycle checks remain required.

## v0.2.0 requirements

### UI/UX redesign

The full-screen and floating-board redesigns are tracked by issues #19 and #20.

The redesign scope, layouts, navigation, and control placement will be decided with
the lead while implementing those issues. Their current issue bodies do not define
the redesign requirements for v0.2.0. This specification does not prescribe a
replacement layout or retain the previous control layout as a requirement.

Both views must support the canvas behavior specified below. Full-screen mode must
provide access to board creation, opening, renaming, and deletion, the About
section, and appearance settings. The entry points and any corresponding
floating-mode controls will be decided during the redesign.

Floating mode must retain window movement, window resizing, return to full-screen,
and close actions. Moving or resizing the floating window must remain distinct
from manipulating its canvas. Returning to full-screen must preserve the active
board. Closing floating mode must preserve its saved contents.

Controls must have accessible names and usable touch targets. Selection feedback
must remain visible in both light and dark themes. Exact feedback styling belongs
to the redesign.

Assess the redesign through concrete user tasks: selecting and deselecting an
image, opening another board, entering floating mode, moving and resizing the
floating window, and manipulating its canvas without activating window controls.
Both views must provide an explicit deselect action that works even when the
selected image fills the visible canvas. Record the lead's assessment of these
tasks alongside the accepted design decisions. These tasks do not prescribe a
particular layout or control placement.

### Canvas gestures and transitions

An unselected image must not block a canvas gesture. This applies in full-screen
and floating modes, including areas containing several overlapping unselected images.

A one-finger drag that begins on an unselected image pans the canvas, just like a
drag on empty space. The drag must not select or move that image. A tap without a
drag still selects the topmost image under the touch point.

Dragging an already selected image moves that image. Its resize and rotation
controls continue to manipulate the selected image. These operations must not
accidentally pan the canvas.

A fresh two-finger canvas gesture manipulates the viewport, including when either
or both fingers begin on images. Pinch zoom keeps its focal-point behavior. All
other supported canvas gestures must also work over unselected images. This
requirement does not introduce a new canvas gesture such as canvas rotation.

Adding a second finger to an ongoing image manipulation must not change that
gesture into canvas manipulation. If the second finger touches a resize corner
of the selected image during an image drag, permit a transition to two-finger
image resizing. Both fingers contribute to resizing; neither is ignored. Resize
according to the change in distance between the fingers, preserving the image's
aspect ratio and keeping its center fixed at the transition. Moving the fingers'
midpoint must not translate the image, and this gesture must not rotate it or
manipulate the canvas. Existing one-finger corner resizing keeps its opposite
corner fixed.

A second finger elsewhere must not start canvas manipulation. To manipulate the
canvas after image manipulation, the user must lift all fingers and start a fresh
canvas gesture. Lifting a finger after two-finger image resizing must not transfer
the remaining finger to canvas panning.

Continuing to pan with the remaining finger after a two-finger canvas pinch is a
good-to-have v0.2.0 behavior. Attempt to implement it without a viewport jump or
accidental image selection or transformation. If it causes buggy canvas controls,
it may be omitted without blocking v0.2.0 completion. In that case, the remaining
finger stays inactive until all fingers lift and the user starts a fresh gesture.
Record whether this continuation was implemented or omitted, and the reason for
any omission.

When an image manipulation is canceled or interrupted, retain and save the last
displayed position, size, and rotation rather than reverting to the pre-gesture
transform. End the interrupted gesture so later touches start a fresh gesture.
The explicit deselect action clears selection without changing the image's
transform.

Gesture recognition must distinguish a tap from a drag using the platform's touch
slop. Once a gesture becomes a canvas gesture, it must not select or transform an
image when the fingers lift. Crossing another image during a canvas drag must not
change the gesture's target.

### Multiple independent boards

#### Board lifecycle

The user can create an empty board, open an existing board, rename a board, and
delete a board. Creation asks for a name with a default such as `Board 1`. Every
board has a visible, non-empty name and a stable identity independent of its name.
Renaming changes only the name; it must not change identity, content, viewports,
active-board status, or the destination of an ongoing import.

Board names must be unique. When another board already has the requested name,
append a number
suffix, starting at `(2)` and increasing until the result is unused. For example,
creating or renaming a board to `Board 1` when that name exists produces
`Board 1 (2)`, or `Board 1 (3)` if the former also exists. Display the resulting
name to the user. Persist names across restarts and reboots.

List boards by most recently opened first. Persist that ordering across launches.
Creating and opening a board makes it the most recently opened board; renaming
alone does not change the ordering.

Creating a board must not copy the current board's images or canvas state into
the new board.

The app displays one active board at a time. Opening another board saves pending
changes to the current board before switching. If that save fails, the current
board remains open and the app reports the failure.

The active board identity is saved and remains active even when the app is not
running. Closing the app does not clear it. The active board is restored on the
next launch when it still exists. If the saved active board is missing while
other boards exist, return to board management with a message explaining that
the previous board is unavailable. Do not silently select another board or
recreate the missing board. If no boards exist, the app presents an empty
board-management state with a way to create a board. It must not recreate a
deleted board silently.

Board deletion requires confirmation that identifies the board and explains that
its images will be removed from easlie. Deleting the active board closes its canvas
and returns to board management. If it is floating, the floating window and its
service must close cleanly. Deleting another board must not change the active board.

#### State isolation

Each board owns its image items, image assets, stacking order, saved full-screen
viewport, and saved floating viewport. Selection is temporary and clears when
switching boards. Editing, importing into, or deleting one board must not change
another board's content or viewports.

Importing the same source image into two boards creates independent board-owned
copies and independent image items. Deleting either copy or its board must leave
the other board intact. Boards must not share mutable content or asset ownership.

Full-screen and floating modes edit the same active board. Only one mode is
interactive at a time. Each board retains separate viewports for the two modes.
Changing one viewport must not change the other mode's viewport or another board's
viewports.

An import targets the currently active board before copying begins. If the app
was not running, use its saved active board identity. An asynchronous import or
save must retain its destination board identity even if the user switches boards. It must
not apply its result to whichever board happens to be active when it finishes.
An import must not recreate a board that was deleted while the import was running.

Shared images must have a destination board before import. Use the active board
when one exists. If none exists, let the user create or open a board first. The
choice's presentation will be decided during the redesign.

For a partial import, retain successfully imported images and report which images
failed. Offer retry for valid images that could not be imported because of a
temporary app, source-access, or storage problem. Retry only the failed images,
retain the original destination board identity, and do not duplicate successful
imports. Invalid or unsupported images receive the usual import error rather
than a retry offer for that same invalid or unsupported content. If the destination
board was deleted, explain that the import cannot continue; retry must not recreate
it or redirect the images to another board.

#### Persistence and upgrade

Persist the board collection and each board's content locally. Board identities
must remain stable across app restarts and device reboots. Appearance preferences
are app settings, separate from board content.

On upgrade from v0.1.0, preserve the existing single board as one board in the
collection. Preserve its images, transforms, stacking order, and both viewports.
Name the migrated board `Board 1` and make it the initial active board. Migration
must be safe to retry and must not produce duplicate boards or discard the
original data after a failure.
If migration cannot complete, preserve the original board and assets, show an
error with a retry action, and prevent board editing until migration succeeds.
Do not present an empty replacement board or treat the incomplete migration as
successful.

Continue atomic saves and recovery reporting. A failed save or deletion must not
be reported as successful. Asset cleanup must respect board ownership and must
never remove another board's assets. An unsupported future storage format must
not be overwritten.

The storage schema and component changes will be decided during implementation.
The canvas and image pipeline must remain usable by both hosts without duplicating
their gesture or rendering behavior.

### About section

Provide a small About section showing the app name, `easlie`, and the installed
app's current version. Obtain the displayed version from the build's version
metadata so future releases do not require a separate hardcoded About version.

Its location and presentation will be decided during the full-screen redesign.

### Appearance settings

Provide three appearance choices: Light, Dark, and Use system default. Use system
default is the initial choice when the user has not set a preference.

Light and Dark remain in effect independently of the system theme. Use system
default follows the system's current light or dark appearance and updates when
that appearance changes.

Persist the preference across app restarts. Apply it consistently to full-screen
and floating-board app surfaces, controls, selection feedback, and dialogs.
Changing the preference must not require reopening the app or board.

The setting is global. Switching boards must not change it. Image content retains
its original colors; changing the theme must not modify imported images.

### Reliability and responsiveness

The new features must preserve usable pan, zoom, image manipulation, and floating
window resizing with a representative board of 20 to 30 real reference images.
Image decoding must remain off the main thread. Validate responsiveness through
functional checks; v0.2.0 does not require profiling or performance measurements.

Switching or deleting boards must release resources that are no longer needed.
Repeated board switches and floating-mode transitions must not leave stale board
content, duplicate windows, stale notifications, or ongoing services after closure.

Permission denial must leave full-screen mode usable. Returning from floating mode
must preserve the active board and its separate viewports. Recovery errors must
identify the affected board without changing unaffected boards.

### v0.2.0 completion criteria

1. The lead accepts the full-screen and floating-board redesigns implemented through
   issues #19 and #20. Record design decisions and assess selecting and deselecting
   images, opening another board, entering floating mode, and distinguishing window
   movement and resizing from canvas manipulation.
2. In both modes, a drag over an unselected image or overlapping unselected images
   pans the canvas. A tap still selects the topmost image. Selected-image movement,
   resizing, and rotation continue to work. Explicit deselection works when the
   selected image fills the visible canvas.
3. Fresh canvas pinch gestures work over images without accidentally selecting or
   transforming them. A second finger during image manipulation does not start
   canvas manipulation. A second finger on a resize corner during an image drag
   permits resizing with both fingers, preserving aspect ratio and a fixed center.
   One-finger corner resizing still preserves the opposite corner. Verify that
   interruption retains the last displayed transform and ends the gesture.
4. Attempt one-finger canvas panning after lifting one finger from a canvas pinch.
   Record whether it works without jumps or unintended image manipulation. If it
   causes buggy controls, document its omission and verify that a fresh gesture
   after lifting all fingers works. Omission does not block release.
5. Create and rename boards with unique visible names. Request an existing name
   and verify numbered suffixes, including an already-used `(2)` suffix. Relaunch
   to verify names and ordering by most recently opened. Renaming preserves board
   identity, content, viewports, and ongoing import destinations.
6. Create at least two boards and import the same source image into each. Edit images
   and viewports independently. Switching, relaunching, and rebooting preserve each
   board's own state.
7. Delete an image or an entire board without changing another board's images or
   viewports. Canceling board deletion leaves its contents intact. Deleting the last
   board leaves a usable board-management state. A missing saved active board returns
   to board management with a message instead of selecting or recreating a board.
8. Close the app with an active board, then share images into easlie. The import
   targets the saved active board. Start another import, then switch boards. Its
   result belongs only to its original destination. Deleting that destination during
   import does not recreate it.
9. Exercise partial imports with successful images, retryable failures, and invalid
   or unsupported images. Successful imports remain; retry affects only eligible
   failures in the original board without duplicates. Invalid or unsupported images
   receive the usual error. A deleted destination cannot be retried or recreated.
10. Upgrade an existing v0.1.0 installation without losing its board, images,
    transforms, stacking order, or viewports. The migrated board is named `Board 1`
    and is initially active. A failed migration preserves the original data, reports
    the error, and offers retry before editing. Retrying does not duplicate the board.
11. About displays `easlie` and the installed build's version.
12. Light, Dark, and Use system default work in both modes. The preference survives
    relaunch, follows system changes when appropriate, and leaves image colors intact.
13. Check functional responsiveness with 20 to 30 representative images and repeated
    board switches and floating-mode transitions. Verify resource cleanup, permission
    denial, independent viewports, and clean service, window, and notification closure.
    Document automated validation separately from the lead's device smoke tests.
    Profiling, performance measurements, and benchmark comparisons are not required.

## v0.3.0 requirements

### Automatic image snapping

#### Required behavior

Moving or resizing an image close to another image's edge or corner automatically
snaps it as soon as the snapping conditions are met, even while the user is still
dragging the image or its resize handle. Rotating an image near 0°, 90°, 180°, or
270° automatically snaps its angle during the rotation gesture.

Snapping must not wait for the image to stop moving or for the user to lift their
finger. No button, modifier, or separate mode is required. Snapping must work in
full-screen and floating modes. Image-to-image snapping only considers images in
the active board.

Image-to-image snapping during movement or resizing is eligible only when both
images are at one of 0°, 90°, 180°, or 270°, with equivalent full-turn angles
normalized. Their rotations do not need to match: an image at 0° can snap to one
at 90°. Arbitrary rotations remain allowed, but those images do not participate
in image-to-image snapping. Moving or resizing must not automatically rotate an
image to make it eligible.

Move snapping changes only the selected image's position. Resize snapping changes
its dimensions while preserving its aspect ratio and the gesture's anchor. Keep
the opposite corner fixed for one-finger corner resizing and the center fixed
for two-finger resizing. Rotation snapping changes only its angle, keeping its
center and size unchanged. No snapping operation changes the target image,
stacking order, or creates a lasting link between images. Save the resulting transform like any
other image manipulation.

The user must remain able to overlap images. Continuing to drag must release a
snap and allow the image to move through or over its target. Continuing a resize
or rotation gesture must likewise release the snap and permit free manipulation.
Snapping must not act as collision detection or prohibit overlap.

Show temporary visual feedback identifying the active alignment. Snapping
feedback must remain visible in both light and dark themes and follow the global
appearance preference. Remove that feedback when the snap releases or the gesture
ends. Panning or zooming the canvas must never snap or move images.

#### Proposed snapping rules

The following rules are a proposed starting point for implementation, subject to
the lead's final design decision. [OPINION]

Apply image-to-image snapping during selected-image move and resize gestures.
Apply quarter-turn angle snapping during rotation gestures, independently of
whether another image is nearby.

Use the images' actual transformed corners and finite edge segments. Do not use
invisible extensions of an edge or the axis-aligned bounding box of a rotated image.

Support corner-to-corner snapping, corner-to-edge snapping in either direction,
and edge-to-edge alignment. Corner-to-edge snapping uses the nearest point on the
finite edge. Edge-to-edge snapping applies only to parallel edges whose projected
lengths overlap. It permits both adjacent placement and aligned overlapping edges.
No angle adjustment is applied to make nonparallel edges eligible.

For movement, acquire a snap when the required positional correction is at most
8 dp in screen space. Convert that distance through the current viewport zoom so
the visible activation distance stays consistent at different zoom levels and
display densities.

For one-finger corner resizing, consider only alignments achievable by scaling
the image with its aspect ratio and opposite corner preserved. Acquire a snap
when the correction to the dragged corner is at most 8 dp in screen space. Skip alignments that would
require stretching the image or moving its fixed corner. Release when the freely
resized corner would require more than 12 dp of correction.

Choose the eligible alignment requiring the smallest positional correction. For
equal corrections, prefer corner-to-corner, then edge-to-edge, then corner-to-edge.
Resolve remaining ties by stable image and feature identities. Apply one alignment
at a time so competing targets do not pull the image in different directions.

Retain the current alignment until the unsnapped position would require more than
12 dp of correction. For movement, measure this from the position implied by the
original drag and total finger movement, rather than accumulating movement from
snapped positions. For one-finger corner resizing, use the corner position implied
by the original resize gesture without snapping. This separates acquisition and
release distances and avoids repeated switching at the threshold.

After release, suppress that same alignment until the unsnapped position has left
its 12 dp release region. It may be acquired again only after re-entry within 8 dp.
Continuing through the target therefore releases the snap without another button.
Other eligible alignments may still snap under the same rules.

For rotation, acquire the nearest quarter-turn angle when the freely rotated
angle is within 3° of it. Retain that angle until the free angle is more than 5°
away. Use the shortest angular distance, including across the 360°/0° boundary.
Track the free angle from the original gesture, rather than from the snapped angle.
After release, reacquire that angle only after re-entry within 3°.

Use the unsnapped transform to evaluate new candidates throughout each gesture.
Apply an eligible snap immediately and show the snapped transform while the finger
is still down. On finger release, keep and save the displayed position, dimensions,
and angle, whether snapped or free. Finger release ends the gesture; it does not
trigger snapping.
Remove the temporary snap relationship so later movement of either image does not
affect the other.

Before implementing v0.3.0, decide whether fully hidden images participate as
snapping targets and how to permit small intentional offsets within the activation
distance. Also define candidate corrections and screen-space acquisition and
release distances for centered two-finger resizing. The one-finger resize rules
above must not move the fixed center of a two-finger resize. These choices remain
open for the lead; the current proposal does not settle them.

These thresholds and tie-breaking rules are proposals, not measurements of user
preference. Any adjustment must retain automatic activation during gestures,
consistent screen-space distances for movement and resizing, deterministic target
selection, quarter-turn rotation snapping, and free overlap.

### v0.3.0 completion criteria

1. Move and resize images near eligible corners and edges in both modes. Snapping
   activates during the gesture, shows feedback visible in light and dark themes,
   and selects targets deterministically. Resizing preserves aspect ratio and the
   relevant anchor: the opposite corner for one-finger resizing or the center for
   two-finger resizing. Verify the chosen thresholds at different zoom levels.
   Image-to-image snapping works only when both images have quarter-turn rotations,
   including when their rotations differ.
2. Rotate images near 0°, 90°, 180°, and 270° in both modes. Angle snapping activates
   during rotation, including across 360°/0°. Continue movement, resizing, and rotation
   past their release thresholds to manipulate images freely and preserve overlap.
   Ending a gesture saves its displayed transform. Later edits to either image do
   not transform the other.
3. Snapping does not introduce interaction-blocking work during a drag, affect another
   board, or interfere with canvas gestures. Existing v0.2.0 behavior continues to work.
