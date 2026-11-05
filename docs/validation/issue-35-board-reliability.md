# Issue 35: shared board reliability base

## Scope and stacking

This is the shared implementation base for full-screen redesign #19 and move
snapping #36. Both future branches must stack directly on this PR. The snapping
branch must not inherit #19 or floating redesign #20. Repeat affected integration
checks after both stacks land. No redesign or snapping is implemented here.

The PR was rebased onto main `8e34812`. The review and fixes below were performed
on a Samsung SM-X616B tablet running Android 16 on 2026-11-05. These results
supersede the original compilation-only validation record.

## Host automation

Final host validation passed: 51 unit tests across nine suites, debug app and
Android test APK builds, lint, and `git diff --check`.

Run `ANDROID_HOME=/home/chrisb/Android/Sdk ./gradlew :app:testDebugUnitTest
:app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug`, followed by
`git diff --check`.

`BoardCollectionStorageTest` covers populated migration, preservation of original
bytes, copy/index failure and safe retry, future formats, independent board
storage, save-before-switch failure, names/order, rename failure, deletion failure,
missing active identity, and empty management. Its thirty-item regression checks
both viewports, independent transforms/stacking/assets, repeated reopening, and
surviving asset bytes after deletion. These fixtures are storage evidence rather
than representative image-decoding or reboot evidence.

## Device automation

The app and test APKs were installed and the PR app was launched before device
verification. Existing board files were moved aside while the app was stopped;
fixtures exercised isolated data. Original board files and preferences were
restored after each run.

Final full instrumentation passed: 87 tests passed, zero failed, and the opt-in
profiling test was skipped. The runner reports `OK (88 tests)`.

Run `adb shell am instrument -w
com.chrisb588.easlie.test/androidx.test.runner.AndroidJUnitRunner` after isolating
existing app data and creating an empty active board for the singleton/UI tests. The targeted `BoardInteractionTest` run passed all seven tests.

`BoardCollectionStoreTest`, `BoardStorePersistenceTest`, `BoardStorageTest`,
`BoardStartupTest`, `BoardRenameFeedbackTest`, and `BoardDeletionDialogTest` cover
migration blocking/retry, storage failure, independent copies/viewports, active
identity recovery, accurate failure feedback, and last-board management.
`ImportRoutingTest`, `ImportDestinationUiTest`, and `PartialImportUiTest` cover
captured destinations, switching/rename/deletion races, partial retry without
successful duplicates, and recovery controls.

`CanvasGestureOwnershipTest`, `CanvasViewportOwnershipTest`,
`CanvasResizeContinuationTest`, `CanvasInterruptionTest`, and `BoardInteractionTest`
exercise ownership, selected-image manipulation, two-pointer transitions,
interruption persistence, and explicit deselection. `CanvasHostInputTest` injects
input through the real Activity and overlay. `AppearanceHostTest` checks real-host
appearance and system changes; `AppearanceSettingsTest` and `AboutSectionTest`
check settings and installed metadata. `ImageRendererTest` and rendering
persistence tests cover cancellation, release, and host ownership.

A temporary external instrumentation harness additionally passed functional
checks with thirty existing JPEG, PNG, and WebP reference images in both real
hosts: pan, zoom, move, proportional resize, rotation, floating movement/resizing,
and independent copies in two boards. It checked transforms, names, order, both
viewports, repeated switches/transitions, floating closure, missing-asset recovery,
and deletion of the floating active board with survivor integrity. Permission
denial left full-screen editing available. Source inspection confirms decoding
runs in `Dispatchers.IO`.

Populated legacy migration, blocked editing, safe retry, original-byte
preservation, and future-format protection passed device fixtures. After a real
Android reboot, a separate instrumentation run passed comparison of both boards'
manifest/asset hashes, names, ordering, transforms and independent viewports, then
verified survivor integrity after deletion. The original boards/preferences were
restored and 25 original files were checked byte-for-byte against their archive.

`python tools/test_stopped_shares.py --serial R52WC032P3A` passed stopped-app single
and multiple external shares, saved destination ownership, and no launcher replay.
The script restored the original files in its cleanup path.

The temporary review harness and its raw logs are under
`/tmp/easlie-issue35-review` on the review workstation; they are not repository
regression tests. The permanent suite and stopped-share script remain the
reproducible repository checks. A new device still needs the representative-image
and reboot procedures repeated; the temporary files are not a portable artifact.

## Regression fixes

The first full device run had seven `BoardInteractionTest` fixture failures before
its assertions. The fixture now writes through its content-provider URI rather
than the instrumentation package's private files path, and recycles the bitmap
in `finally`. Setup clears prior error feedback and waits for deletion/viewport
reset before importing.

The running tests also exposed stale screen coordinates after panning, rotation
input outside the available canvas, and an obsolete import-error assertion. The
fixes recalculate the selected image's screen position, fit the rotation gesture
inside the canvas while retaining the proportional-resize and 90-degree rotation
assertions, and verify current per-source failure feedback and retry eligibility.
No production behavior changed.

## Lead checks and unrun work

No lead-performed smoke checks were recorded in this review. The device checks
above were agent-run automation; they are separate from the lead's smoke testing.
Only the Android 16 tablet was exercised. Other device/API configurations and the
future redesign/snapping integration rerun remain unrun.

Canvas pinch continuation with one remaining pointer remains omitted: that
pointer stays inactive until all fingers lift. This is the documented permitted
fallback. Selected-image two-finger resize continuation is separately implemented
and tested. Profiling, benchmarks, and performance measurements are out of scope;
the opt-in `RealBoardProfileTest` was skipped.
