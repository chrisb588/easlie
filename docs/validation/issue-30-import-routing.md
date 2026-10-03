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

Android integration tests are compiled but not executed. No Android device
commands, app installation, launch, or smoke tests are performed for this slice.
The lead's device run must verify the above integration tests and visible error,
destination-choice, and retry controls. No UI redesign is included.
