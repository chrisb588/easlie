# Physical tablet profiling procedure

Issue #8 requires a measured run on the target physical tablet. The [high-resolution results](image-performance-results.md) document the measured 30-image board, cache comparison, and retained tier policy. This procedure alone is not evidence that acceptance criteria pass. The lead's manual gesture and image-quality checks remain part of PR review.

## Setup

Record the tablet model, Android version, RAM, display resolution and density, build commit, app version, date, and whether developer options or thermal throttling affected the run. Install a debuggable build. Begin with an empty board. Import 20 to 30 *real* reference images representing the intended mix of source dimensions, formats, and file sizes. Record an anonymized inventory with count, formats, dimensions, and total encoded bytes; avoid collecting private image content in logs or the report.

Force-stop and relaunch the app before each independent run to reset in-process profile counters. Reset frame and log counters immediately before each workload. Capture system traces with Android Studio System Trace or Perfetto if available. Save these command outputs before and after each workload, replacing the serial and output names as needed:

```sh
adb -s DEVICE shell dumpsys meminfo com.chrisb588.easlie > meminfo-before.txt
adb -s DEVICE shell dumpsys gfxinfo com.chrisb588.easlie reset
# Use a package/tag-filtered log capture; preserve the tablet's global logs.
# Perform one workload below on the tablet.
adb -s DEVICE shell dumpsys meminfo com.chrisb588.easlie > meminfo-after.txt
adb -s DEVICE shell dumpsys gfxinfo com.chrisb588.easlie framestats > frames.txt
adb -s DEVICE logcat -d -s EaslieImageProfile:D AndroidRuntime:E > image-profile.txt
```

Use `dumpsys meminfo` total PSS and native/graphics/Java breakdown for memory, and retain the largest observed reading across intermediate samples as peak. A before/after pair alone cannot establish peak. Record frame durations from `framestats` or the system trace, including slow and frozen frame counts and the threshold used. The `EaslieImageProfile` lines report individual completed decode time in milliseconds (excluding queue time), sample factor, bitmap allocation bytes, and cumulative requested-tier observations every 100 refreshes. `refresh_hits` and `refresh_misses` count per-refresh observations, so their ratio depends on refresh frequency; an in-flight decode can cause repeated misses. `decodes_scheduled` counts actual decode jobs. Use deltas between counter lines for each workload, and report the sampling interval. The counters reset when the process restarts. Debug-only `easlie.decode` trace sections identify the sample factor and source edge without image names or content; they wrap only the worker-thread decode, excluding its queue wait. Count `decode_failed` warnings separately. Debug logging and debugger or profiler attachment can affect timing; record the tool configuration for every run.

## Workloads

Run each workload at least three times, with a clean app process and board state recorded for each run. Capture intermediate memory samples while the workload runs. Use the same sequence and approximate pace on every run:

1. Open the 20 to 30 image board, wait for visible images to settle, then pan across the full board and back repeatedly for two minutes.
2. Pinch zoom from overview to detail and back on several different images for two minutes. Include quick reversals that could cause repeated tier changes.
3. Import a batch of additional real images while the board is open. Record import count and any failure; repeat until at least 30 images are present if the starting set had fewer.
4. Enter floating mode, resize between practical minimum and large window sizes repeatedly for two minutes, and pan/zoom while floating. Return to full screen and repeat the transition several times.
5. Repeat the full sequence several times in one process. Observe memory after idle and after leaving the board. Check AndroidRuntime logs and the UI for out-of-memory failures, stale images, freezes, and leaks. Use Android Studio Memory Profiler heap inspection if memory fails to return after idle; note expected retained board/cache memory separately.

Do not substitute synthetic images or emulator readings for the physical tablet result. The lead performs the interactive tablet smoke test and records observed behavior; the contributor can analyze the resulting traces and prepare a measured tuning proposal.

## Report template

- Device/build/date/thermal state:
- Source image inventory and board count:
- Workload and repetitions:
- Peak total PSS and native/graphics/Java breakdown, with sample times:
- Slow/frozen frames: threshold, count, total frames, worst and percentile durations:
- Decode times: count, median, p95, maximum, and sample-factor distribution:
- Requested-tier cache observations: refresh hit/miss deltas and their frequency-weighted ratio, decode jobs scheduled, cache bytes versus budget:
- OOM, visual stalls, and suspected leak observations:
- Trace/log file names and measurement method:
- Candidate resolution-tier or cache-budget change, expected tradeoff, and supporting before/after measurements:
- Remaining uncertainty and acceptance criteria still unverified:

Only propose tier or budget values after comparing repeatable before/after runs on the same tablet and image set. Record whether improvement in one metric worsens memory, frame timing, or image clarity.

## Automated baseline capture

`RealBoardProfileTest` is opt-in and skips itself in the ordinary connected-test suite. It uses the existing physical-tablet board. Back up the board before running it, grant easlie's floating-board permission through Android Settings, and leave the tablet available to the workload. Do not use the tablet during capture. Normal teardown removes only the temporary imported items and restores the original items and both saved viewports. An interrupted capture force-stops the target so instrumentation cannot keep changing the board. Process death can prevent teardown; retain the external backup until restoration is verified. If restoration is not reported, stop the app and recover the backup before continuing.

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb -s DEVICE exec-out run-as com.chrisb588.easlie tar -cf - files/board > /tmp/easlie-board-backup.tar
adb -s DEVICE install -r app/build/outputs/apk/debug/app-debug.apk
adb -s DEVICE install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
python tools/profile_board.py --serial DEVICE --output /tmp/easlie-profile --seconds 120 --repetitions 3 --interval 0.5
```

The output directory must be new. Debug decode summaries also separate sources with edges of at least 4000 pixels when that size metadata is present; this threshold is a report grouping, not a production tier. It contains local raw memory/frame captures, filtered logs, build/APK metadata, thermal state, clock calibration, and `summary.json`. These captures contain device identifiers; publish only the anonymized inventory and measurement summary. The script does not copy image content. `--skip-floating` explicitly records a partial full-screen/cache run when overlay permission is unavailable. `--floating-only` records the floating workload separately. `--hosts-only` omits the separate cache comparison. `--cache-only --cache-divisor 8` starts no activity and isolates the 32 MiB renderer experiment on this tablet; divisors 16 and 4 select 16 and 64 MiB. Use separate invocations for each budget, then reverse the order to check order effects. `--seconds 5 --repetitions 1` checks the harness; it is not the sustained profiling run. `--summarize` regenerates a summary from an existing capture without touching the tablet.

The harness configures interactive-window accessibility observation before activity launch so overlay attachment and control changes update its node queries. The automated workload changes the real board's viewport on the main thread, imports batches of three actual source images, cycling through the five largest sources, through the normal importer, and injects touch drags into the actual floating window's resize handle. It bypasses canvas gesture recognition. Each floating round returns to the full-screen host. Repetitions within one invocation share a process; run independent invocations as well when comparing retained memory or alternative production policies. After closing the activity and clearing renderer ownership, separate ten-second idle and ten-second explicit-GC diagnostic stages record retained memory. The GC request is outside interaction measurements and does not guarantee every allocation is reclaimed. Human interaction and visual-quality checks remain part of the lead's smoke test.

After the host workloads, a separate renderer-only experiment compares memory-class/16, /8, and /4 cache budgets on the same real sources and a fixed 2560 × 1444 pixel viewport. Each four-second block pans for one second and focuses on its next image for three seconds. Sources are ordered largest first; a 120-second pass focuses on all 30. It does not draw frames. Its fixed viewport must be recorded separately from the measured host window sizes. Run order, heap retention, canceled decodes, and warm file caches can affect results; this experiment alone cannot select a production budget.

Memory is sampled at the configured interval, so reported peak PSS is the largest observed sample, not an exact allocation peak. Frame duration is `FrameCompleted - IntendedVsync` for completed rows with zero flags. Completion timestamps beyond their capture time are invalid: exclude and count these records, retaining a later valid observation of the same frame if available. New captures record polling completion time; older captures allow both adb calls their documented 30-second timeouts plus one second of clock tolerance. This checks impossible future timestamps without discarding valid frozen frames. Frame rows are deduplicated by window and intended-vsync timestamp and attributed using device monotonic stage markers. The bounded `gfxinfo` history can still omit frames between polls. The summary reports durations above 16.67 ms as a fixed 60 Hz comparison threshold and above 700 ms as frozen frames; the tablet may have a higher refresh rate. Host/tablet epoch clock calibration aligns memory samples with log markers and records its round-trip uncertainty. Decode durations exclude queue time and canceled jobs. Cache hit ratios count refresh observations, not unique decode requests. Cache occupancy is also sampled from log snapshots.

The initial measured continuation is documented in [the supplied-board baseline](image-performance-baseline.md). Its source limitations and remaining acceptance criteria must stay visible until the representative high-resolution run and measured policy choice are complete.

Android references: [dumpsys memory and frame commands](https://developer.android.com/tools/dumpsys), [inspect system trace frame and memory tracks](https://developer.android.com/studio/profile/inspect-traces), and [UI jank detection](https://developer.android.com/studio/profile/jank-detection).
