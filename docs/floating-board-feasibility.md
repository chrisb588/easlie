# Floating-board feasibility proof

Issue #3 is intentionally a platform proof, not the final reference-board
implementation. The current proof has one overlay window, a placeholder
board surface, move and resize gestures, and notification actions for return
and stop.

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
SM-X616B running Android 16. During automated verification, the tablet was
locked and in Dozing. Samsung vendor lifecycle behavior, overlay visibility,
and notification presentation therefore remain unverified; no observed
behavior is being attributed to Samsung or One UI.

After unlocking the tablet and recording its One UI version, perform these
manual checks and record the exact settings labels shown by the device:

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
   repeatedly and confirm that only one window and one notification exist.
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
requires a physical or emulator Android device with overlay permission; final
confidence for this issue still requires manual testing on the Samsung
SM-X616B after it is unlocked and no longer Dozing.
