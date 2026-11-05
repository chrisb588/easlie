# Issue 27 validation

The create and open controls provide temporary entry points. Their final layout belongs to the full-screen redesign.

Validation passed: 39 host tests, debug APK build, debug lint, and Android instrumentation Kotlin compilation. The instrumentation tests were compiled but were not run.

Host tests cover migration preservation, unique names, stable identities, empty creation, persisted recent ordering, missing/empty active-board management, failed pre-switch save callbacks, independent board-owned asset copies and viewports, restart restoration, and destination-bound completion after switching.

The added BoardCollectionStoreTest exercises actual store switching, save failure, and an import paused during copying while another board opens. It also imports the same source into both boards and checks independent owned copies. It remains unrun because it requires an Android runtime.

Device smoke checks remain pending with the project lead. No device command, installation, launch, or device test was performed for this slice. Check create/open in full-screen mode, tap selection clearing after switching, imports completing after switching, relaunch/reboot restoration, and switching repeatedly between full-screen and floating mode. Check failure messages with unavailable storage. Verify only one host accepts canvas input and both modes preserve each board's separate viewport.
