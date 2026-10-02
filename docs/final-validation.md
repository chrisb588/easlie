# Issue #11: physical tablet validation

This is the procedure and blank record for the lead's final v0.1 smoke test. All observations remain **pending** until run on the target physical tablet. Issue #11 remains blocked until issue #8 has measured profiling results and issue #10 is ready in the stack. Use [the image profiling procedure](image-performance-profile.md) for counters, traces, repeated workloads, and the performance report. Do not substitute an emulator or a build result for device evidence.

## Preparation and evidence

Record the tablet model, Android version, app commit and version, test date, available storage, and any vendor battery restrictions. Use 20–30 real reference images including high-resolution photos; record an anonymized count, formats, dimensions, and total encoded size. Start with an empty board or record its existing contents. Keep the source gallery and shared images available long enough to confirm their import. Record the starting board item count and both viewport positions/zoom levels using screenshots that show recognizable landmarks. Use a browser and a separate image-focused app installed on the tablet; record their names and the exact image/share action used.

For each row below, record **pass**, **fail**, or **pending**, the actual observation, and a screenshot, screen recording, or timestamped log/trace filename. Capture failures before retrying. Do not change an acceptance criterion to pass based only on an automated test, a code inspection, or an intended behavior.

| Issue #11 acceptance criterion | Device procedure and observable pass condition | Status / observation / evidence |
| --- | --- | --- |
| Multiple images import from gallery | Select at least three images in one Android photo-picker operation. Confirm all appear as distinct board items near the viewport center and remain visible after leaving and returning to the app. Record before/after item counts. | Pending — |
| Browser and image-focused app shares | In an actual browser, share a still image to easlie through Android's share sheet. Repeat from an actual image-focused app. Where each app offers multiple-image sharing, exercise that too. Record source app, share action, count, and resulting board count. Each supported shared image appears once, and existing items stay intact. | Pending — |
| 20–30 image manipulation | Build a board with 20–30 real images. Pan empty space; pinch zoom around a visible focal point; select then move an image; resize a corner while preserving its aspect ratio; rotate; double-tap and delete an image. Repeat representative gestures at overview and detail zoom. Record count and before/after views. Gestures respond without freezes, the intended item moves, the resized image retains its width-to-height ratio in the captured views, its angle visibly changes, and deletion persists. | Pending — |
| Board and both viewports survive restart and reboot | Set visibly different full-screen and floating centers/zoom levels. Edit at least one item in each mode. Force-stop and relaunch; inspect item count, transforms, and each mode's viewport. Reboot the tablet, relaunch, and inspect both again. The same app-owned images and edits are restored with each mode's independent viewport. Record screenshots before and after each boundary. | Pending — |
| Floating mode over another app | Grant overlay permission, enter floating mode, open another app, move the floating window with its handle, resize it, and edit an image on the board. The board stays visible and usable above that app; dragging the handle moves the window without panning board content; resizing changes the window bounds; the edit is visible on the board. Record the app and screen capture. | Pending — |
| Return to full screen | In floating mode, make a recognizable item edit and change the floating viewport. Use Return to full screen. The edit appears immediately on the full-screen board, while its earlier center/zoom is retained. Record both viewport landmarks and the edit before and after. | Pending — |
| Repeated floating cycles | Repeat entry, move/resize/edit, return, and close paths at least five times each. After each close or return, inspect the screen and notification shade for a stale overlay or ongoing notification; capture service/window diagnostics if any remain. Relaunch and confirm edits persist. Pass only when there are no stale windows, notifications, services, or lost changes. | Pending — |
| Permission denial then approval | Starting without overlay permission, request floating mode and deny it in system settings. Confirm the full-screen board remains usable and unchanged. Request again, grant permission, enter floating mode, make a recognizable edit, and return. Record screen capture and crash log status. Pass only when the floating window attaches after approval, the edit appears on return, and there is no crash or data loss. | Pending — |
| Final performance and lifecycle results | Run the repeated physical-tablet workloads in [the image profiling procedure](image-performance-profile.md). Attach peak memory samples, slow/frozen frame counts and thresholds, decode times, cache observations, OOM/crash status, and window/service/notification cleanup evidence. Describe any unresolved regressions. | Pending — |

## Result record

- Device / Android / vendor restrictions:
- Build commit / app version / test date:
- Source apps and exact share actions:
- Image inventory and starting/final board count:
- Evidence files and timestamps by criterion:
- Failures, reproduction steps, and issue links:
- Issue #8 measured result and status:
- Issue #10 dependency status:
- Final performance report / traces:
- Lead's overall assessment: Pending

Keep the issue and PR open if any device result is pending or fails. The lead records the physical smoke-test observations; contributors can analyze the resulting evidence and fix confirmed defects in a separate change.
