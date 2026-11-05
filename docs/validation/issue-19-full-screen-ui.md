# Issue #19: full-screen UI

## Accepted design

The lead supplied three wireframes and approved the following decisions in the implementation conversation on 2026-11-05. These decisions replace the issue's former layout. Android dimensions use density-independent pixels: 16 dp for rounded cards/dialogs and 4 dp for sharper containers/menus. The board selector remains a pill, and the Home, Actions and Add images buttons are circular with 48 dp touch targets.

The canvas fills the Activity's available content area inside system-bar and cutout insets. Controls overlay that canvas. Home sits to the left of the board selector at the upper left. Actions sits at the upper right, with Add images immediately below. The board selector lists saved boards and ends with New board +. Actions offers Switch to floating mode, Deselect, Change theme, and About, in that order. Theme and About open dialogs. Overlay-permission guidance appears when floating entry is requested without permission. Cancelling or denying permission retains the full-screen board.

Home opens a main menu without changing the active board or either saved viewport. Startup reopens the saved active board; when no board is active, it shows the main menu. The main menu shows easlie and the installed build version, followed by Your boards with a + button on the same row. Board rows have bounded visual previews and overflow menus for Rename and Delete. Its Actions menu offers Change theme above About. Rename opens a text-entry dialog; deletion retains the existing named confirmation and data-safety behavior.

Warnings and errors stack at the lower left. Ordinary notices remain for five seconds and then fade. Recovery errors and import failures offering Retry remain until resolved or dismissed. Dismissal retains an independent recovery/import retry entry when necessary. Import progress and waiting-for-destination guidance remain visible while relevant. Message kinds, rather than displayed English text, determine persistence and severity. Board previews decode off the main thread and do not open boards or change viewports.

The floating UI is outside this redesign. Its existing controls, separate viewport and mode-entry implementation remain in use. Full-screen Deselect is in Actions; it clears canvas selection and the image context menu without requiring exposed empty canvas.

## Automated validation

Base: main `078505832a7086fa9fe0be213f2a555a32b4e4b0`, which includes merged prerequisite PR #49 / issue #35. A live fetch confirmed origin/main matched that base during implementation. The implementation branch is `feature/issue-19-fullscreen-ui`; the PR targets main because the prerequisite is merged.

Host command:

```sh
ANDROID_HOME=/home/chrisb/Android/Sdk ./gradlew \
  :app:testDebugUnitTest :app:assembleDebug \
  :app:assembleDebugAndroidTest :app:lintDebug --offline --console=plain
```

Final host result: BUILD SUCCESSFUL. All 51 unit tests passed, with zero failures, errors or skips. The debug app APK and instrumentation APK built. Lint completed with zero errors, six warnings and one hint. `git diff --check` passed. Instrumentation compilation is not device execution.

The implement workflow's independent acceptance review and the code-review skill's standards/spec reviews found no remaining concrete findings after fixes. The reviews caught and prompted fixes for duplicate import Retry controls, hidden migration retry after dismissal, repeated equal-message events, stale rename success, and recovery notices surviving same-board reloads. Eight new isolated UI cases compile; their device execution remains pending. The installed create-pr template was used from `/home/chrisb/.codex/skills/create-pr/pull_request_template.md` because the skill's legacy `.claude` template path is unavailable.

New isolated-cache instrumentation cases cover empty startup and creation, active-board startup, Home restoration without viewport changes, full-host canvas bounds, board selection/rename navigation, contextual permission denial/cancellation, existing floating entry callbacks, theme/About access, independent timed messages and repeated errors, recovery retry after dismissal, and menu deselection with an image larger than the canvas. A preview assertion checks accessibility/presence and preserved board identity; it does not inspect user image data.

Inherited UI tests were updated for Home/library board management, Actions/About access, icon-based controls and retry actions. The named deletion/cancellation, save-failure rename, startup readiness and destination-bound import assertions remain in place.

No app was launched on the connected Android device during implementation. Instrumentation tests, screenshots, both tablet orientations, physical input, rendered light/dark selection feedback, system-bar/cutout reachability and actual floating transitions remain unrun for this branch. Emulator acceleration was unavailable in the execution environment. The existing pinch-continuation fallback is unchanged. Profiling and benchmarks were not run.

## Lead task assessment

Pending lead-performed smoke testing. No task results or design acceptance beyond the supplied wireframes and explicit conversation decisions are inferred.

The lead's assessment still needs to record selecting and deselecting an image, opening another board, entering floating mode, distinguishing floating-window movement/resizing from canvas manipulation, and using the controls in both tablet orientations and themes. Include image pan/pinch/move/one- and two-finger resize/rotation/deletion and interruption recovery in the smoke checks. Record actual results and any limitations here during review.

## Review refinements — 2026-11-05

The lead requested a longer board pill and matching dropdown, right-aligned chevron and separate New board + icon, more space between Actions and Add images, an Actions menu beside its button, removal of the Deselect/Change theme divider, a wider main-menu list, a lower app title, and a plain main-menu New board icon button.

The selector now requests 256 dp while remaining constrained by available width, and its dropdown matches the measured available selector width. Its label expands before the trailing chevron. New board uses the menu's trailing icon slot. The Actions/Add images gap is 16 dp. A focusable, dismissible popup opens 8 dp to the left of Actions, aligned with the button top and clamped inside the window. Its maximum width respects the available space beside the controls. The library width limit increases from 640 to 960 dp. Title top spacing follows 20% of available height, bounded to 112–192 dp. The main-menu + is a plain IconButton with its existing accessible name and 48 dp touch target.

The initial device run passed 14 of 16 isolated UI tests. It exposed an assertion before a notice's enter animation completed, and duplicated reports of the same import-cancellation error. The notice test now advances through the enter animation. Active notices with identical text are coalesced; a later identical event still restarts the lifetime or appears again after expiry. Independent acceptance and standards/spec reviews found no remaining concrete findings in the refinements.

Final refinement host checks passed: 54 unit tests, debug app and instrumentation builds, lint (zero errors, six warnings, one hint), and diff checks. Three added host tests exercise side-menu placement and window clamping. On the connected SM-X616B, 15 of the 16 affected instrumentation cases passed against the final production APK. The remaining clock-controlled notice test then passed in a targeted rerun after advancing a frame following dismissal. Thus all 16 distinct affected cases passed on this production build; the final full batch itself was not rerun after that test-only correction. These automated checks used isolated cache boards and synthetic provider images. No user board image data was viewed.

The updated APK was installed with `adb install -r`, preserving app data. Its installed checksum and foreground Activity were checked when leaving this revision open for the lead. Device execution above covers the affected menu, Home, bounds, theme/About, permission, deselection, recovery, rename/deletion, and import tests. It does not replace the lead's task assessment or establish both-orientation and full-gesture smoke results.

## Additional polish — 2026-11-06

The lead approved smaller New board plus icons, with the Add images icon excluded from that reduction; a label-fitting Actions popup; a compact selection-only Deselect button at the canvas bottom center; more board-row padding; a lighter Actions shadow; dialog headers approximately 2 sp smaller and Your boards 1 sp smaller; larger edge insets; centered About content matching the wireframe; and larger circular buttons.

The main-menu and dropdown plus sizes follow their corresponding text sizes while preserving the plain button's 48 dp touch target. The circular Home, Actions and Add images controls increase to 56 dp, with their icons unchanged. Canvas control insets increase to 24 dp. Board rows use 24 dp horizontal and 16 dp vertical padding. Actions uses intrinsic width, bounded by the available window space, and shadow elevation decreases from 8 to 2 dp. Dialog headings use 22 sp and Your boards uses 21 sp at default theme sizing, with Android font scaling retained.

Deselect is removed from Actions. The canvas owns the selected-only bottom-center button and clears selection, the image menu and double-tap history when it is pressed. Notices reserve the bottom button area so the action remains reachable. The retired menu-driven deselection parameters were removed; floating mode retains its existing top-right deselection control. The About dialog now centers its icon, easlie name, installed version and Close action in one column, following the wireframe rather than the standard end-aligned dialog action arrangement.

Host checks passed: 54 unit tests, both debug APK builds, lint with zero errors (seven warnings and one hint), and diff checks. The updated UI regression asserts that Deselect is absent without a selection, appears at bottom center after selecting, remains usable with a notice present, disappears after use, and permits canvas panning without moving the deselected image. About assertions follow the centered card and retain installed-version checks. Independent acceptance and standards/spec reviews found no remaining concrete findings.

The connected SM-X616B completed the final targeted instrumentation batch with `OK (30 tests)`, zero failures: FullScreenRedesignTest, AboutSectionTest, CanvasGestureOwnershipTest, CanvasResizeContinuationTest and CanvasInterruptionTest. Canvas checks used isolated cache boards and synthetic images. The About appearance test restored the original global appearance setting. The updated app is installed with `adb install -r` and left open for the lead; this automated evidence does not substitute for the lead's visual/task assessment or verify both tablet orientations.
