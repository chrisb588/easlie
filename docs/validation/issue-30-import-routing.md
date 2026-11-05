# Issue 30 import routing and retry validation

Imports bind to a stable board identity before copying. Startup intake waits for
storage restore and uses the saved active identity. With no active board, intake
waits for creation or opening; it binds that choice even before canvas layout.
Switching or renaming does not change an existing request or its retry destination.

Each successful image commits independently. Errors identify the source and keep
only eligible failures for retry. Unsupported types, invalid pixel data, animation,
and truncated containers do not receive retry. Source access, copying, and save
failures remain retryable. Retry never includes committed images. Deleted targets
are checked before copy and adoption, and cannot be recreated or redirected.
Staged files are removed after failure and cancellation, including while waiting
for the commit mutex. Existing committed files retain their ownership.

Automated validation: `testDebugUnitTest`, `compileDebugAndroidTestKotlin`, and
`lintDebug` run on the host. `ImportRoutingTest` covers single/multiple startup
share intake, choosing a destination before layout, mixed successful/temporary/
invalid/unsupported sources, retry after rename and switch, deleted-target
retry, and a temporary collection-read failure during copy. It asserts that failed
staging is removed before retry and only committed assets remain afterward. Existing `BoardCollectionStoreTest` covers deletion during a blocked copy,
board isolation, and rename/switch during import; deletion during copy also asserts
that abandoned staging and survivor assets remain clean. `BoardStorePersistenceTest`
covers atomic-save failure cleanup without changing existing content.

At implementation time, Android integration tests were compiled but not executed. No Android device
commands, app installation, launch, or smoke tests are performed for this slice.
The lead's device run must verify the above integration tests and visible error,
destination-choice, and retry controls. No UI redesign is included.

## Review verification — 2026-11-05

The rebased debug build was installed and launched on the SM-X616B tablet.
All 48 host unit tests, lint, and APK builds passed. All 20 targeted device tests
passed: `PartialImportUiTest`, `ImportRoutingTest`, `BoardCollectionStoreTest`,
`BoardStorePersistenceTest`, and `images.ImageIntakeTest`.

AC3: `PartialImportUiTest` verifies visible failure names and the retry control,
retention of a successful image, and retry of a valid image after a temporary
source-access failure. Invalid content is excluded, and the original committed
item and exact persisted item/asset counts are checked. The existing temporary
collection-read failure test additionally verifies retry after a storage problem.

AC7: `python tools/test_stopped_shares.py --serial R52WC032P3A` passed for both
single and multiple shares. The script force-stops the app and confirms no process
exists before an external sender delivers the intent. It checks saved destination
identity, committed assets, another board's isolation, and no replay on launcher
return. Existing app files are temporarily moved aside and restored in `finally`.
The routing integration tests cover destination choice, mixed inputs, retry after
switching and renaming, and destination deletion.

`ImageIntakeTest` now returns `Unit` for JUnit and accesses external fixtures
through the provider instead of the test APK's private directory. All three of
its tests executed successfully. Lead smoke tests for remaining user flows are
still pending; the PR has not been merged.

## AC2 and AC5 UI smoke tests — 2026-11-05

All 16 device tests passed in `ImportDestinationUiTest`,
`BoardCollectionStoreTest`, `ImportRoutingTest`, and `BoardDeletionDialogTest`.
Lint and the Android test APK build passed.

The four new `ImportDestinationUiTest` smoke tests pause source reads while
operating the visible controls. Picker-result and parsed shared-payload cases
rename the destination, open another board, resume the import, and press Retry.
Both assert that successful and retried images remain in the original board,
the original successful item is preserved, and the other board stays empty.
They reopen the renamed destination to confirm its persisted contents.

Deletion is exercised both during copying and before retry. Both cases assert
the visible explanation, absence of the retry control, absence of the deleted
board directory, and no images redirected to the surviving board. A direct retry
call after deletion also cannot start another import. Test boards use isolated
cache directories; the lead's two prepared boards are untouched.

The picker-result and shared-payload tests enter at the import queue. Android's
system picker is not driven; external stopped-app share delivery is covered by
the separate host script above. AC2 and AC5 are verified from these UI and
storage assertions.

An initial broader run exposed a test race in destination choice: active identity
was observed before restore completed. `ImportRoutingTest` now also waits for
`collectionReady` before acting on the active board. The corrected run passed.
