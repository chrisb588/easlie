# Issue 29: safe board deletion

Host validation passed: 46 unit tests, `lintDebug`, `assembleDebug`, and
`assembleDebugAndroidTest`. `git diff --check` also passed.
Android integration and UI tests are compiled only. No device test or app launch
was performed for this implementation.

The collection index commits deletion before asset cleanup. A pending deletion
marker lets startup retry incomplete cleanup, after checking that the committed
collection no longer owns the board. Failed index writes preserve the board and
its assets. Cleanup failures report that removal is incomplete rather than claiming
that all images were removed.

An import copies into a temporary file outside the destination board. Its commit
checks the captured destination identity under the store mutex before adopting
that file. Deletion cannot recreate that destination through a late import or save.

## Pending lead smoke checks

Delete the active board while its floating window is open. Verify the window,
foreground service, and notification disappear and board management opens. Create
another board from management. Confirm closing and restarting the app leaves the
last deleted board absent.

Delete an inactive board while another board is floating. Verify the active board,
its images, and both saved viewports remain unchanged. Confirm deleting either of
two independently imported copies leaves the other copy usable.

The floating cleanup checks are pending. They have not been marked verified.
