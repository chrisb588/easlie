# easlie

A native Android reference board that stays close while you work. Arrange images on a freeform canvas,
then open that same board in floating mode over another app.

## Goals

- Make collecting reference images from the gallery, browser, Pinterest, and other Android apps quick.
- Make navigating and arranging a visual board feel immediate, even with 20 to 30 large source images.
- Keep the board visible and usable as a movable, resizable floating board while another app is open.
- Prioritize the floating-mode experience, which is the primary way v0.1 is intended to be used.
- Preserve the board locally across app restarts and device reboots.
- Build the smallest sound foundation that can later support multiple boards without redesigning the
  canvas or persistence model.

## Non-goals

- Collaboration, accounts, cloud sync, or a web client.
- Drawing, text, links, video, animation, or other board item types.
- Image editing beyond position, size, and rotation.
- Automatic layout, tagging, search, folders, or mood-board templates.
- Multiple boards in v0.1.
- Desktop, iOS, phone-specific, or foldable-specific interfaces.
- Exporting or sharing a composed board.

## Product model

v0.1 has exactly one board. Full-screen mode and floating mode edit the same board data. Only one mode is
interactive at a time: starting floating mode moves the board out of the full-screen host, and returning
to full-screen mode closes the floating board before opening the activity.

Each mode has its own saved viewport because the full-screen and floating board have different window
sizes. Panning or zooming in one mode does not change the other mode's viewport. Floating-mode UX takes
priority when the needs of the two modes conflict.

The board contains:

- A full-screen viewport and a floating-mode viewport, each with a world-space center and zoom level.
- A list of image items, each with a persistent front-to-back stacking order.
  When images overlap, the item higher in that order appears on top. Newly
  imported items are placed above existing items. `zIndex` values are integers spaced by 10 by default.
  Reordering uses an available integer between neighboring items. If no integer remains in a gap, the app
  rewrites all indexes with intervals of 10. If duplicate values are loaded, later manifest items appear
  above earlier ones until the app normalizes the indexes during the next successful save.
- An app-owned copy of each imported image in the app's private storage. The
  board stores a stable asset ID for each copy, so it can still load the image
  if the original gallery file or shared content URI is moved, deleted, or no
  longer available.

Each image item stores its asset reference, world-space position, displayed width and height, rotation,
and stacking order. Coordinates and dimensions are independent of screen pixels so the board does not
move when the window size changes.

## Experience

### Full-screen board

- Drag empty canvas space to pan.
- Pinch around a focal point to zoom.
- Tap an image to select it.
- Drag a selected image to move it. Dragging an unselected image does not move it; the user must first tap
  to select it, then drag it in a second gesture.
- Drag a corner handle to resize it while preserving its aspect ratio.
- Drag a rotation handle to rotate it.
- Double-tap an image to open a popover menu containing a Delete action.
- Add one or more images through the Android photo picker.
- Accept one or more images shared from another Android app through the system
  share menu, then import them into the board.
- Place newly imported images near the current viewport center and above existing items. For a batch
  import, place image centers along a diagonal from the viewport center, offsetting each subsequent image
  right and down by 10% of the shorter viewport dimension. Wrap to a new diagonal when an item center
  would leave the visible viewport.

One-finger gestures manipulate either the selected item or the empty canvas. Two-finger gestures always
manipulate the viewport. This rule avoids ambiguous nested transforms.

Each mode opens at its own last saved viewport. Selection is temporary UI state and is not persisted.

### Floating mode

The user starts floating mode from the full-screen app. If floating-board permission has not been granted,
the app explains why it is needed and opens the system overlay-permission screen. Once the floating board
is attached, the full-screen activity finishes so that only one mode is interactive.

The floating board:

- Draws and edits the same board using the same canvas state and renderer.
- Has a dedicated drag handle for moving the window without moving the canvas.
  The handle and other floating-mode controls appear when the user taps the floating
  board and hide after a short period of inactivity. The handle retains a
  sufficiently large invisible touch target for accessibility. Controls expose content descriptions,
  support accessibility focus, and remain visible while accessibility focus is within the floating
  board. A first-run hint identifies the move, resize, return, and close controls.
- Has a resize affordance with a practical minimum size.
- Has actions to return to full-screen mode and close the floating board. Returning removes the floating
  board and stops its service before opening the full-screen activity.
- Remains visible while another app is in front.
- Shows an ongoing foreground-service notification while active.

Closing the floating board removes its window and stops its foreground service. It does not erase or
reset the board.

## Architecture

```
                  board state
                       |
            +----------+----------+
            |                     |
     full-screen activity   floating-board service
            |                     |
            +----- canvas UI -----+
                       |
              image decode/cache
                       |
          manifest + app-owned assets
```

The app is a Kotlin Android project using Jetpack Compose. Native Android APIs handle media intake,
storage, the floating-board window, and the foreground service.

There is one authoritative in-memory board-content state. The full-screen Android activity and the
floating-board service are separate hosts, but only one is interactive at a time. An activity is the
Android component that owns the normal full-screen easlie screen. Both hosts obtain the same
application-scoped store, which loads once before accepting edits and serializes all board mutations and
persistence snapshots. Each host creates its own Compose UI and lifecycle ownership. The renderer,
gesture logic, and coordinate conversion code must not be duplicated between the hosts. Each host keeps
its own viewport, selection, controls, and window-lifecycle state.

The prototype should use direct, concrete components rather than introduce layers for hypothetical
features. Multi-board support later should add board manifests and a switcher, not replace the canvas or
image pipeline.

## Canvas

The canvas maintains one transform between world coordinates and window coordinates. Panning changes the
world-space viewport center. Zooming changes the scale around the gesture focal point so the content under
the user's fingers remains stationary.

Item bounds remain in world coordinates. Hit testing converts pointer positions into world coordinates,
then tests the transformed item shapes from highest to lowest stacking order.

Rendering must cull items whose rotation-aware transformed bounds do not intersect the visible window,
with a small margin to prevent images flashing in at the edges.

## Image ingestion and storage

The app accepts common still-image MIME types from the photo picker and `ACTION_SEND` or
`ACTION_SEND_MULTIPLE` intents. Unsupported or unreadable inputs are rejected without changing the board.

On import, the app copies each image into app-owned storage and records an asset identifier in the board
manifest. It does not rely on the source content URI remaining readable. This costs additional device
storage, but gives picker and share-intent imports identical lifetime semantics.

Imports are transactional per image:

1. Copy the source into a temporary app-owned file.
2. Read enough metadata to validate the image, apply its EXIF orientation, and determine its displayed
   dimensions and aspect ratio.
3. Move the completed asset into its final location.
4. Add the item to board state and persist the manifest.

A failed import removes its temporary file and leaves existing board content unchanged. If the process
stops after moving an asset but before saving its item, the file may be left unreferenced. After a
successful manifest load and after the board becomes usable, the app schedules low-priority background
reconciliation on an I/O thread rather than blocking startup. Reconciliation lists asset filenames and
compares their IDs with the manifest; it does not decode images or read their contents. It removes stale
temporary files and final assets that no valid manifest references. It never modifies an unsupported
future schema.

Deletion first removes the item from board state and atomically saves the new manifest. Only after that
save succeeds does the app delete the asset when no remaining item references it. If file deletion fails,
later reconciliation retries it. If the manifest save fails, the item and asset remain unchanged and the
user sees an error.

## Image resolution and cache [can u explain each bullet point to me in a less
technical way? but dont modify the bullet points tho. just explain]

Source images remain encoded on disk. The app decodes only the resolution needed for an image's current
on-screen size.

- Choose a decode size from the image's transformed screen bounds and device density.
- Subsample during decode rather than decode the original and scale it afterward.
- Decode away from the main thread and cancel work that is no longer relevant.
- Draw an existing lower-resolution copy until a sharper copy is ready.
- Avoid repeated decode churn by adding hysteresis before changing resolution tiers.
- Keep decoded bitmaps in a byte-bounded LRU cache sized from the device's available memory class.
- Prefer visible items, then items just outside the viewport, when retaining decoded images.
- Release high-resolution copies when items become small or leave the viewport.

The exact resolution tiers and cache budget are profiling results, not constants to guess in advance.

## Persistence

The board is stored locally as a versioned JSON manifest beside its asset directory. A save means writing
the current board state to this manifest on disk, not merely updating memory. The initial schema contains:

```
Board
  schemaVersion
  viewports
    fullScreen { centerX, centerY, zoom }
    floating { centerX, centerY, zoom }
  items[]
    id
    assetId
    x
    y
    width
    height
    rotationDegrees
    zIndex
```

Manifest writes are atomic: write a complete temporary file, then replace the previous manifest. Save
immediately after imports and deletions. Changes may be debounced during continuous gestures, but save
periodically during a long gesture, when a gesture ends, and when either mode loses visibility. A
visibility callback is an extra opportunity to save, not a required final callback, because Android may
stop the process without delivering it.

On startup, malformed items or missing assets are skipped without changing the original manifest. After
the board opens, a persistent, dismissible message states how many items could not be restored and why;
diagnostic details are also logged. An unsupported future schema version must not be overwritten.

## Android platform behavior

Floating mode uses `TYPE_APPLICATION_OVERLAY` through `WindowManager` and requires
`SYSTEM_ALERT_WINDOW`. Permission denial leaves full-screen mode fully usable.

The floating-board service is declared with the foreground-service permissions and type required by the target
Android version. If no standard type fits, it uses `specialUse` with a precise manifest explanation. This
declaration is subject to Google Play review.

The normal launch path starts the floating board while the activity is visible. No background path may assume
that holding `SYSTEM_ALERT_WINDOW` alone permits a foreground-service start. On Android 15 and newer, a
background start using that exemption requires the floating board to already be visible before the
foreground service starts.

The notification returns to full-screen mode and offers a stop action. If the service or process is stopped, the
window is removed cleanly and the last board state remains on disk.

## Performance requirements

Performance is evaluated on the development Android tablet with a board of 20 to 30 real reference images,
including several high-resolution photos.

- Pan, zoom, move, resize, and rotate must remain responsive without main-thread image decoding.
- The app must not run out of memory during repeated zooming, panning, importing, and floating-board
  resizing.
- A newly needed higher-resolution image may appear progressively, but interaction must not block while it
  decodes.
- Opening and closing floating mode repeatedly must not leak windows, services, or decoded bitmaps.
- Profiling results must record peak memory, slow or frozen frames, decode time, and cache hit behavior.

The prototype is not complete until it has been profiled with real images. Emulator-only testing does not
satisfy these requirements.

## v0.1 scope

In scope:

- One persistent freeform board
- Canvas pan and zoom
- Image selection, move, aspect-preserving resize, rotation, and deletion
- Multi-image import from the Android photo picker
- Single and multiple image share intents
- Resolution-aware image decoding and a bounded LRU cache
- Visibility culling and background decoding
- Movable and resizable floating board
- Foreground-service lifecycle and notification
- Local, atomic save and restore
- Profiling with 20 to 30 real images on a physical Android tablet

Deferred, in likely order: multiple boards, board naming and switching, item reordering
(manually changing an item's front-to-back stacking position, such as bringing it to the front or
sending it to the back), duplicate item, undo and redo, board export, richer item types, and backup
or sync.

## Build order

1. Set up the Kotlin and Jetpack Compose Android project, including the required Android SDK,
   build tooling, and Gradle dependencies, then verify it runs on the target tablet.
2. Build a single in-memory canvas with stable pan and focal-point zoom.
3. Prove floating-mode feasibility on the target tablet with a minimal draggable, resizable board,
   including permission, foreground-service, notification, vendor lifecycle, and intended Google Play
   declaration behavior.
4. Add picker import, share intents, and item manipulation.
5. Add the versioned manifest, app-owned asset storage, atomic writes, reconciliation, and restore
   behavior.
6. Build resolution-aware background decoding, visibility culling, and the LRU cache.
7. Profile a 20 to 30 image board and tune resolution tiers and cache limits from measured results.
8. Host the shared canvas in the proven floating-mode host and complete its lifecycle and accessibility
   behavior.
9. Repeat the performance and lifecycle tests with both full-screen and floating-mode hosts.

Do not start multi-board work during v0.1. The single-board manifest must first prove that the persisted
unit is complete and portable within the app.

## Completion criteria

v0.1 is complete when all of the following work on the target Android tablet:

1. Import multiple gallery images and receive images shared from at least a browser and one image-focused
   app.
2. Arrange 20 to 30 real images using pan, zoom, move, resize, rotate, and delete without an out-of-memory
   failure or interaction-blocking decode.
3. Leave and reopen the app, then reboot the tablet, with the board and viewports restored and every imported
   image still readable.
4. Open the board in floating mode over another app, move and resize it, edit the board there, and see the
   same board changes after returning to full-screen mode without replacing either mode's saved viewport.
5. Deny and later grant floating-board permission without a crash or loss of board data.
6. Close and reopen floating mode repeatedly without a leaked window, stale notification, duplicate service,
   or lost changes.
7. Capture a profiling report for the representative board and document the chosen cache budget and decode
   tiers.

## Known constraints

- Floating-board permission is a high-friction system setting and some Android vendors impose additional
  background-process restrictions.
- Foreground services require a persistent user-visible notification and their declarations may be
  reviewed by Google Play.
- Large decoded bitmaps consume width times height times bytes-per-pixel, regardless of compressed source
  file size. Disk size is not a useful memory estimate.
- Compose hosted in floating mode still depends on Android view and service lifecycles. The floating board must have
  explicit saved-state, lifecycle, and cleanup ownership.
- App-owned image copies make persistence reliable but duplicate source storage. Storage management can be
  revisited after the prototype proves the interaction and performance model.

### Image-detail validation note — 2026-10-02

On build `299f542`, the lead reported that a high-resolution photo looked slightly softer in easlie than
in Samsung Gallery on the Samsung SM-X616B. The detail remained usable and zoom gestures stayed
responsive. The comparison screenshots used different crops and displayed subject sizes, so they do
not establish a matched-scale sharpness comparison. Automatic resolution upgrading for that image
remains unverified by this visual comparison. [UNVERIFIED] The lead subsequently confirmed all manual
acceptance criteria for issue #11, including board manipulation and zoom.

The contributor considers slight softness acceptable for the prototype when reference detail remains
usable and gestures stay responsive, subject to the lead's final acceptance decision. [OPINION]

### Final performance and lifecycle record — issue #11

The physical-tablet profiling used a 30-JPEG board, including five 16.4–30.1 MP photos, on the
Samsung SM-X616B running Android 16. Three sustained pan, zoom, import, and floating-resize cycles
completed without observed out-of-memory failures, completed-decode failures, or usable frames above
700 ms. Peak sampled PSS was 302.30 MiB. Frames above 16.67 ms numbered 27/20,409 during pan,
130/23,644 during zoom, and 1,589/22,217 during floating resizing. The longest completed decode was
237 ms. The measured policy retains a 32 MiB image cache on this tablet. Independent 16/32/64 MiB
cache comparisons recorded refresh-observation hit ratios of 75.01%/66.93%/55.65%; these use different
requested resolution tiers and are not comparisons of identical cache keys.

After activity and renderer release, diagnostic cache occupancy reached zero and idle PSS fell to
119.55 MiB. An earlier floating lifecycle capture found no remaining easlie service or window after
teardown. These finite measurements do not prove the absence of every possible leak. Detailed methods,
build identities, limitations, and anonymized measurements are recorded in
[the performance report](docs/image-performance-results.md),
[the earlier lifecycle baseline](docs/image-performance-baseline.md), and their linked JSON summaries.
These measurements preceded the final manual run; they are not new measurements of build `299f542`.

For the final manual run on build `299f542`, the lead confirmed all issue #11 manual criteria. This
includes shared-board edits and independent viewports across mode changes, force-stop/relaunch and
tablet reboot, repeated Return and Close cycles, notification Return and Stop actions, and permission
denial followed by approval. The manual checklist records at least five Return cycles and five Close
cycles with edits preserved and no visible stale floating window or ongoing notification. These are
lead-reported observations, not additional service diagnostics or memory measurements. Image detail
remained usable with responsive gestures; the slight-softness observation is recorded above.

## Technical references

- [Android photo picker and persistent media access](https://developer.android.com/training/data-storage/shared/photo-picker)
- [Efficient loading of large bitmaps](https://developer.android.com/topic/performance/graphics/load-bitmap)
- [Foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types)
- [Android 15 foreground-service changes for overlay apps](https://developer.android.com/about/versions/15/behavior-changes-15)
