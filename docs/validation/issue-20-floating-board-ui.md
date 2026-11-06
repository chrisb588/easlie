# Issue #20 — Floating-board redesign

## Lead-approved design

On 2026-11-06, the lead supplied the annotated floating-board wireframe and confirmed 32 dp window corners. After reviewing the issue's required close action, the lead placed a circular X Close button at top right and Return to full-screen immediately to its left. Add images sits below Close. The top bar moves the window; the two bottom corners resize it. Window handles are separate from canvas input.

The lead confirmed that the top-left board pill matches the full-screen dropdown, including opening another board and New board. Rename, deletion, theme and About remain available by returning to full-screen. Warnings and errors occupy the lower-left area; selection-only Deselect sits at bottom center. The shared canvas fills the rounded host, with drawing and hit testing using that same coordinate space.

Issue #19 was already merged as PR #50 before this work. This branch starts from its merged main rather than reopening the completed prerequisite branch.

## Implementation

Both hosts reuse the board selector, round controls, notices and canvas. Window corners are 32 dp. Circular actions remain 56 dp; native move and resize targets are at least 48 dp. Controls remain visible, replacing the previous auto-hiding chrome and its reveal-controls hint. Rounded invisible corners do not accept initial window/canvas touches.

The minimum window is 280 × 248 dp. This leaves separate space for the top controls, Add images, a scrollable notice region, Deselect and bottom resize targets. The initial size remains 360 × 260 dp. Notices reserve the right-side Add images column and bottom selection action. Board names truncate within their pill; the dropdown scrolls when needed.

An unexported translucent activity owns the photo picker and New board form. The existing overlay remains visible until activity launch, then hides while system/keyboard input is in use. Completion or cancellation restores the existing overlay. A delayed restoration request with no existing overlay stops its service instead of creating an idle one. Creation and opening use the existing board-store operations. Floating imports use the floating viewport and leave the saved full-screen viewport unchanged.

The foreground notification, permission checks, image cache ownership, shared gestures, saves and cleanup continue through the existing service/store implementation. Configuration changes refit the window to available bounds. No profiling or benchmarking is included.

## Automated validation

Host validation passed `:app:assembleDebug`, `:app:assembleDebugAndroidTest`, `:app:testDebugUnitTest`, `:app:lintDebug` and `git diff --check`. All 55 JVM tests passed, including the added rounded-corner input test. Lint reported zero errors, 11 warnings and one hint. No dependencies were changed.

The first connected SM-X616B batch passed `OK (32 tests)`: FloatingBoardRedesignTest, FullScreenRedesignTest, CanvasGestureOwnershipTest, CanvasResizeContinuationTest and CanvasInterruptionTest. After the final native-handle backplates and notice spacing, all four FloatingBoardRedesignTest cases passed again. These cover shared host bounds, separate controls and callbacks, board switching, notice/Deselect separation at 280 × 248 dp, floating-viewport import placement, and portrait/landscape host constraints with live light/dark recomposition. The orientation checks resize the isolated Compose host; they do not establish physical-device rotation evidence.

The final real-window lifecycle run passed `OK (1 test)` on the same production APK. It verifies duplicate-start protection; both native resize handles; the 280 × 248 dp minimum; right-edge preservation during left resizing; window movement without changing either viewport; New board cancellation; system-picker cancellation; Return and Close cleanup; removal of the foreground notification; and a stale restoration request leaving no idle service. The original active board and board list were restored and checked. Across these batches, all 33 distinct instrumentation cases passed; the complete 33-case set was not rerun as one final batch.

The lifecycle harness required three setup corrections before its successful run: temporary notification permission on a tablet with notifications denied, waiting for the delegated system picker's focused/idle window and using its dismissal control, and finishing the current activities rather than an ActivityScenario instance replaced by repeated Return. Earlier failed harness runs are not counted as passing evidence. No production changes were needed for these harness corrections.

Tests use synthetic images and isolated cache boards, or a disposable empty board for real overlay/action-window lifecycle checks. The lifecycle test restores the original active board and verifies the original board list. APKs are installed with `adb install -r`, retaining app data; the test runner never uninstalls the app. Notification permission is temporarily granted by the host test wrapper and restored in `finally`. No user board image data is inspected.

The configured PR template was unavailable; the PR uses an equivalent concise change/validation description.

Independent acceptance and standards/spec reviews found a notice/control overlap at the original smaller minimum and low-contrast native handles over images. The larger minimum and reserved notice area fix the overlap; opaque theme-aware backplates fix handle contrast. Follow-up reviews reported no remaining concrete implementation findings.

## Lead smoke-test evidence

Pending. Design approval above is not task-assessment evidence. The lead still needs to assess selecting/deselecting, opening another board, entering floating mode, moving/resizing the window, manipulating the canvas without activating controls, the Return/Close layout, and readable selection feedback in both tablet orientations. Automated results are reported separately and do not mark these lead-owned checks complete.

## Lead-requested anchor and visibility refinements — 2026-11-06

The lead requested removal of the anchor backplates, a longer drag mark, larger resize marks that follow the rounded corners, and restoration of the four-second inactivity timeout. The lead confirmed that touching anywhere on the floating board reveals the controls. Deselect and notices are excluded from auto-hide; notices retain their existing expiry and recovery-persistence rules.

The drag mark is now a 176 dp stroke within a 200 × 48 dp touch target. Each resize mark occupies a 64 × 64 dp target, with straight edge segments joined by a 24 dp arc inset 8 dp from the window's 32 dp corner. These marks have no filled backplates. A thin contrasting outline keeps their strokes distinguishable over images. Window movement, left/right resize anchoring and input boundaries retain their existing behavior.

Only the board pill, Return, Close and Add images fade after four seconds without interaction. Touching the host or activating an anchor reveals them. An ongoing touch, an open board dropdown or accessibility focus suspends the timer. Release/dismissal restarts the delay. Action activity hide/show and window teardown cancel pending timeout callbacks. Clearing accessibility focus reschedules the timer without revealing controls that just hid. The shared NoticeStack and canvas-owned Deselect remain outside this visibility state.

Host builds, all 55 JVM tests, lint and diff checks passed after the production refinements. Lint completed with zero errors, 13 warnings and one hint. The final connected-tablet batch passed `OK (7 tests)`: FloatingBoardLifecycleTest, all five FloatingBoardRedesignTest cases, and CanvasHostInputTest. It covers the four-second timeout, touch-to-reveal, held-touch and open-menu protection, persistent Retry with hidden controls, independent transient-notice expiry and Deselect, both native resize anchors, and canvas-versus-window input. The original active board and board list were restored and checked; the existing notification permission setting was preserved. Earlier harness failures are not counted as passing evidence. The native harness refreshes Android's accessibility cache before checking hidden controls and scopes their labels to the overlay, avoiding stale nodes or the matching fullscreen controls beneath its fixture. The persistent import notice test resumes the animation clock before scrolling to Retry in the narrow notice region.

A screenshot captured only the disposable empty fixture after its controls hid. Visual inspection confirmed the longer unboxed drag bar and mirrored curved corner strokes. No user board image data was inspected. The updated native test also checks that anchors remain accessible, canvas touch reveals the controls, held touch and board-menu interaction prevent premature hiding, and the larger targets preserve move/resize behavior.

## Anchor spacing refinement — 2026-11-06

The lead requested the drag mark closer to the top edge, matching the resize marks' inset. The lead confirmed that smaller resize marks should shorten only their straight ends. The drag stroke now sits 8 dp from the top instead of 24 dp. After a further request for a smaller mark, each resize straight end decreases from 20 to 4 dp; the 24 dp corner arcs, stroke widths, contrast outlines and resize touch targets remain unchanged.

The lead also requested less space between the drag mark and top buttons. The top button row moves from 48 to 40 dp, and Add images moves up by the same 8 dp. The wide drag target becomes 200 × 40 dp so its input area ends before the buttons begin; resize targets remain 64 × 64 dp. No additional reviews are requested for these refinements.

Debug and instrumentation APK builds, lint, all 55 JVM tests and the final seven-case affected tablet batch passed. The empty-fixture screenshot was inspected to confirm the 8 dp drag inset and smaller resize ends. The native regression verifies the adjusted drag bounds; UI and input tests cover the tighter button row. No new test cases were added, and further reviews were skipped as requested. The original board/list and notification setting were preserved.
