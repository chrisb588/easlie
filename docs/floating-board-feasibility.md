# Floating-board feasibility proof

Issue #3 is intentionally a platform proof, not the final reference-board
implementation. The current proof has one overlay window, a placeholder
board surface, move and resize gestures, and notification actions for return
and stop.

Both bottom corners have resize handles. The bottom-right handle keeps the
top-left corner fixed; the bottom-left handle keeps the top-right corner fixed.
Both respect the minimum size and remaining usable display space. Tablet checks
on October 1, 2026 verified bottom-left shrinking and expansion to the left
display boundary without moving the right edge.

Bottom-left resizing uses native right gravity with a fixed right offset so
Android anchors the window while resizing its surface. Four repeated tablet
resize gestures kept the right edge at the display boundary in all 90 sampled
window frames. Moving and bottom-right resizing restore left gravity using the
window's actual on-screen position.

## Platform behavior

The full-screen activity explains why `SYSTEM_ALERT_WINDOW` is needed and
opens the Android overlay-permission settings screen. If the user denies the
permission, the activity remains usable and no service is started. After the
permission is granted, the user starts the service while the activity is
visible. The service then attaches a `TYPE_APPLICATION_OVERLAY` window and
finishes the activity so the overlay is the active host.

Starting the board again while the service is already running does not add a
second window. The service returns `START_NOT_STICKY`; it is only started from
the explicit full-screen action or notification actions. Closing the board,
using the notification stop action, or returning to easlie removes the window,
stops the foreground service, and removes its ongoing notification. Cleanup is
also repeated from `onDestroy` so normal service teardown is idempotent.

The service uses the `specialUse` foreground-service type because this
feasibility proof does not fit one of the standard media, location, camera, or
microphone types. The manifest subtype explains that the service keeps the
user-selected reference board visible as a movable, resizable overlay. This is
the intended declaration to review in Google Play's foreground-service
declaration flow; it is not a guarantee of Play approval.

## Development tablet and vendor follow-up

Connected-device tooling identified the development tablet as a Samsung
SM-X616B running Android 16 and One UI 8.5 (One UI version reported by the lead).
The lead reported these manual observations on
October 1, 2026; they have not been independently reproduced by automation:

- The board remained visible and interactive over another app and after
  locking and unlocking the tablet.
- The board disappeared while Settings was open and reappeared upon leaving
  Settings without restarting it. Android allows sensitive windows
  to suppress application overlays; the device's exact cause is unverified.
- easlie was absent from sleeping and deep-sleeping app lists. Its battery
  setting was Optimized, with 0% reported usage since the last full charge.
  During the screen-off check from 21:24 to 21:45 (21 minutes), the board
  remained visible, movable, and resizable after waking the tablet. This does
  not establish prolonged idle or forced-Doze behavior.
- With notification permission allowed, one easlie notification and a separate
  Android system overlay notice appeared. With permission disabled, only the
  system notice appeared. After closing and reopening, the board still worked
  with notification permission disabled.
- Move, resize, return, stop, and repeated start/stop checks were reported as
  completed during smoke testing. The two notices above are from different
  publishers, rather than two easlie notifications.
- Swiping easlie's card away from Recents left the board active, movable, and
  resizable. Return to easlie opened the activity again. The lead also reported
  that stopping removed the board.
- After restarting the tablet, no board appeared automatically. Opening easlie
  and tapping Start floating board created exactly one board.

The location of Samsung's foreground-service task-management control remains
unidentified by the lead. The lifecycle results above document the tested
configuration; they do not establish behavior under every vendor power policy.

The following procedure defines the manual checks reported above and can be
used to repeat them. Record the exact settings labels shown by the device:

1. Grant overlay permission through Settings > Apps > easlie > Appear on top
   (or the equivalent Special access entry). Start the board, switch to
   another app, lock and unlock the tablet, and confirm whether the overlay
   remains visible and interactive.
2. Check Settings > Battery and device care > Battery > Background usage
   limits. Confirm whether easlie is listed under sleeping or deep-sleeping
   apps, record the per-app battery setting, and repeat the overlay test after
   the screen has been off.
3. Allow notification permission, then verify the ongoing notification after
   starting the board. Repeat with notification permission denied and record
   where Samsung exposes the foreground-service status, if anywhere.
4. Use the overlay move, resize, return, and stop controls. Confirm that
   return and stop remove both the window and notification. Start and stop
   repeatedly and confirm that only one window and one easlie notification
   exist. Android's separate overlay notice is not a duplicate app notification.
5. Swipe easlie away from Recents and restart the tablet. Confirm that the
   `START_NOT_STICKY` service does not recreate the board without an explicit
   user start, then test an explicit start again.

These checks cover Samsung's overlay-permission path, battery/lifecycle
controls, lock-screen behavior, notification handling, and service restart
behavior without assuming that any of them match the Android defaults.

On Android 13 and newer, allow notification permission when prompted if the
notification drawer presentation is part of the validation. If notification
permission is denied, Android can still treat the service as foreground while
presenting its foreground-service entry only through system task-management
UI.

The foreground-service notification supplied by easlie is required by Android
when calling `startForeground`, even if notification permission is denied.
Android's separate overlay notice cannot replace that app-supplied notification.
See https://developer.android.com/develop/ui/compose/notifications/notification-permission.

Disabling notification permission while a board is running can terminate the
app process and remove its overlay. The development tablet recorded process
exit reason `PERMISSION CHANGE` after this action on October 1, 2026. This is
Android permission revocation behavior, not a board close action. Reopen easlie
and explicitly start the board again. The prototype does not automatically
restart after process termination. Android's separate display-over-other-apps
notice is controlled by the system, not by easlie's notification permission.

## Validation limits

The automated JVM tests cover resize math, minimum sizes, and maximum sizes
within the remaining viewport space. Device checks on October 1, 2026 verified
that expansion stops at the display boundary and that shrinking at the right
edge keeps the left anchor fixed. A
real overlay, notification, permission grant, vendor lifecycle policy, and
Google Play declaration cannot be proven by local JVM tests. Manual validation
requires a physical or emulator Android device with overlay permission. The
lead's Samsung SM-X616B results are recorded above; the location of the vendor's
active-apps control was not identified and was deferred by the lead.

Sampled window coordinates do not prove that rendered edges remain stationary.
The visible bottom-left resize drift remains a known defect, deferred to
issue #14: https://github.com/chrisb588/easlie/issues/14.
