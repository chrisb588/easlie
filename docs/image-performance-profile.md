# Physical tablet profiling procedure

Issue #8 requires a measured run on the target physical tablet. The current decode tiers, 7/8 visible and 1/8 nearby split, and memory-class/8 cache budget are provisional. Do not mark the issue's acceptance criteria complete or change those values from this document alone.

## Setup

Record the tablet model, Android version, RAM, display resolution and density, build commit, app version, date, and whether developer options or thermal throttling affected the run. Install a debuggable build. Begin with an empty board. Import 20 to 30 *real* reference images representing the intended mix of source dimensions, formats, and file sizes. Record an anonymized inventory with count, formats, dimensions, and total encoded bytes; avoid collecting private image content in logs or the report.

Force-stop and relaunch the app before each independent run to reset in-process profile counters. Reset frame and log counters immediately before each workload. Capture system traces with Android Studio System Trace or Perfetto if available. Save these command outputs before and after each workload, replacing the serial and output names as needed:

```sh
adb -s DEVICE shell dumpsys meminfo com.chrisb588.easlie > meminfo-before.txt
adb -s DEVICE shell dumpsys gfxinfo com.chrisb588.easlie reset
adb -s DEVICE logcat -c
# Perform one workload below on the tablet.
adb -s DEVICE shell dumpsys meminfo com.chrisb588.easlie > meminfo-after.txt
adb -s DEVICE shell dumpsys gfxinfo com.chrisb588.easlie framestats > frames.txt
adb -s DEVICE logcat -d -s EaslieImageProfile:D AndroidRuntime:E > image-profile.txt
```

Use `dumpsys meminfo` total PSS and native/graphics/Java breakdown for memory, and retain the largest observed reading across intermediate samples as peak. A before/after pair alone cannot establish peak. Record frame durations from `framestats` or the system trace, including slow and frozen frame counts and the threshold used. The `EaslieImageProfile` lines report individual completed decode time in milliseconds (excluding queue time), sample factor, bitmap allocation bytes, and cumulative requested-tier observations every 100 refreshes. `refresh_hits` and `refresh_misses` count per-refresh observations, so their ratio depends on refresh frequency; an in-flight decode can cause repeated misses. `decodes_scheduled` counts actual decode jobs. Use deltas between counter lines for each workload, and report the sampling interval. The counters reset when the process restarts. Count `decode_failed` warnings separately. Debug logging and debugger or profiler attachment can affect timing; record the tool configuration for every run.

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

Android references: [dumpsys memory and frame commands](https://developer.android.com/tools/dumpsys), [inspect system trace frame and memory tracks](https://developer.android.com/studio/profile/inspect-traces), and [UI jank detection](https://developer.android.com/studio/profile/jank-detection).
