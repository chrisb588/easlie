# Issue 35: shared board reliability base

## Scope and evidence boundary

This is the shared implementation base for full-screen redesign #19 and move
snapping #36. Both future branches must stack directly on this issue's PR.
The snapping branch must not inherit #19 or floating redesign #20. Repeat the
integration checks below after both stacks land. No redesign or snapping is
implemented here. This ticket does not complete the redesign release criteria.

The base is PR #48, commit `04c2c4fea25a2b31a9ea5b477058c4338b532b73`.
Host validation uses the complete inherited implementation. Instrumentation
compilation proves source compatibility only; its assertions have not executed.
No device was installed, launched, tested, or rebooted for this ticket. Every
lead device check below is pending. Historical v0.1.0 profiling is not v0.2.0
validation; profiling, timing, measurements and benchmarks are out of scope.

## Automated coverage

`BoardCollectionStorageTest` covers populated legacy migration, original-byte
preservation, copy and index failure retry, no duplicate migrated identity,
future collection/legacy format rejection, incomplete legacy assets, empty
management, name collisions and numbered suffixes, rename failure, missing active
identity, save-before-switch failure, deletion commit and cleanup failure.

The new thirty-image regression reloads two independently owned boards repeatedly.
It checks every item's geometry, rotation, stacking and asset identity, both
viewports, recent ordering, collision-resolved names and surviving asset bytes
after deletion. The identical source fixture bytes and asset names deliberately
exercise board directory ownership. These are storage fixtures, not representative
image decoding or functional responsiveness evidence, and process recreation is
not an Android reboot test.

`BoardCollectionStoreTest` covers independent viewports, blocked switching after
save failure, imported copies after a switch, rename during import, deletion
during copy and queued save, and missing saved identity. `ImportRoutingTest`
covers stopped-store saved identity, pending destination choice, partial retry
without successful duplicates, storage failure staging cleanup, and deleted
retry destinations. These instrumentation tests remain unrun.

`CanvasGestureOwnershipTest` covers the shared canvas with each host flag;
`BoardInteractionTest` covers item manipulation.
`BoardStorePersistenceTest` and `BoardRenderingPersistenceTest` cover persistence.
`CenteredResizeTest` checks fixed-center proportional geometry on the host.
The issue 32 report records two-pointer transition, interruption, deselection,
corner anchoring and fresh input assertions. Canvas pinch continuation is omitted:
the remaining pointer stays inactive until all fingers lift, avoiding the original
pan-baseline jump and unintended image ownership. This is the permitted fallback.
Shared canvas host flags do not prove actual overlay window lifecycle behavior.

`AppearanceTest` checks preference resolution. `AppearanceSettingsTest` and
`AboutSectionTest` cover preference persistence, live shared-host theme changes,
overlay choice controls and installed build metadata; their device runs are pending.
`ImageRendererTest` covers cache release/cancellation in instrumentation. Source
inspection confirms decoding in `ImageRenderer` runs within `Dispatchers.IO`,
board switch/delete clears the renderer, and floating close removes the view,
stops foreground notification and service. Inspection is not lifecycle execution.

## Lead device checks — all pending

Preserve existing device board data before any destructive test or fixture setup.
Use an isolated test device or backed-up installation for upgrade/failure tests.
Record installed commit, Android version, screenshots, result and affected board
for each check. Run instrumentation separately and attach the actual result;
never infer execution from compilation.

1. Upgrade a populated v0.1.0 installation. Compare image content, transforms,
   stacking and both viewports. Confirm one active `Board 1`. Inject a copy/index
   failure in a test fixture, confirm editing is blocked and original bytes remain,
   then retry without duplication. Open a future-format fixture and confirm neither
   its index nor manifest is overwritten. Automated fault fixtures already cover
   disk behavior; the recovery UI still requires device observation.
2. Create two boards, request duplicate names including an existing `(2)` suffix,
   and import the same real images into each. Edit transforms, stacking and both
   viewports independently. Rename without changing recent order. Switch repeatedly,
   relaunch and reboot; compare names, ordering, identity and both boards' state.
   Delete one image and one board; the survivor's copies must remain intact.
3. Run the store failure fixtures for save, rename and deletion. Confirm switching
   remains blocked on save failure and messages accurately identify failures.
   Cancel a named deletion, then delete the active and last board. Confirm usable
   management and creation. Remove a saved active identity in a fixture; confirm
   unavailable-board recovery rather than silent selection or recreation.
4. Share one and multiple images while stopped. Confirm saved active destination.
   Hold provider reads, start an import, switch and rename, then release reads.
   Results must land only in the original identity. Exercise successful, temporary
   failure and invalid sources together; retry only eligible failures without
   duplicates. Delete the destination while held; release and retry must neither
   recreate it nor redirect to the active survivor.
5. In full-screen and the actual floating window, tap overlapping images, drag
   unselected images to pan, drag selected images to move, and start fresh pinches
   over images. Add a second pointer on a selected resize corner during movement:
   distance changes size, center and aspect stay fixed, midpoint motion does not
   translate or rotate. A second pointer elsewhere never starts canvas input.
   One-pointer corner resize retains its opposite corner. Lift one pinch pointer:
   remaining input must stay inactive; all-up then fresh input must work. Interrupt
   move, resize and rotation; relaunch and compare the last displayed transform.
   Deselect an image filling the canvas without changing its transform.
6. Select Light, Dark and Use system default with both hosts visible in turn.
   Confirm live app surfaces, dialogs and selection feedback, original image colors,
   system-change following and persistence across relaunch/board switching. About
   must show `easlie` and the installed build version. Enter/return floating mode:
   active identity and independent mode viewports must remain intact.
7. Use 20–30 representative real reference images, including mixed dimensions and
   formats. Pan, zoom, move, resize and rotate images in both modes, then move and
   resize the floating window. Record whether these tasks remain usable; no timing,
   profiling or benchmark data is required. The host thirty-item storage regression
   does not satisfy this functional check.
8. Repeat board switching, deletion and floating entry/return/close. Confirm no stale
   images or duplicate windows. Close and delete a floating active board; confirm
   window, foreground notification and ongoing service disappear. Inspect cache
   release with the renderer instrumentation and observe no obsolete content.
   Deny overlay permission and continue editing full-screen. Restore a board with
   a missing asset and confirm the recovery message identifies that board while
   another board remains unchanged.

## Validation result

Passed on this branch: `:app:testDebugUnitTest` (49 tests across nine suites),
`:app:assembleDebug`, `:app:lintDebug`, and `:app:compileDebugAndroidTestKotlin`.
Command: `ANDROID_HOME=/home/chrisb/Android/Sdk ./gradlew :app:testDebugUnitTest
:app:assembleDebug :app:lintDebug :app:compileDebugAndroidTestKotlin`.
Device and instrumentation execution remain pending. No production regression
was reproduced by these host checks; no production behavior was changed.
