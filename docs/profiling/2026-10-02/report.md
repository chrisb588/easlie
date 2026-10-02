# Reference-board profiling — 2026-10-02

## Scope and evidence

Profiling used a physical Samsung Galaxy Tab S9 FE+ 5G (SM-X616B) running Android
16, in landscape, with a debug build and no attached debugger. The preliminary
30-JPEG board lacked high-resolution photos. The representative follow-up
replaced five images with original-resolution photos and supersedes that baseline
for the performance conclusions below.

The follow-up ran three sustained pan, zoom, import, and floating-resize cycles.
Separate renderer-only cache comparisons ran in fresh processes with varied
budget order. A floating trace checked decode threads and frame presentation.
Source details and measurements remain in [the baseline inventory](source-inventory.json),
[full-screen baseline](full-screen-summary.json), [floating baseline](floating-summary.json),
[representative inventory](high-resolution-inventory.json),
[build metadata](high-resolution-metadata.json), [host results](high-resolution-host-summary.json),
[cache comparisons](high-resolution-cache-comparison.json), and
[trace summary](high-resolution-trace-summary.json).

## Key findings and implications

The representative workload completed without observed out-of-memory errors,
completed-decode failures, or frozen frames. This supports the tested board on
this tablet; it does not establish a maximum supported board size.

Floating resizing produced more slow frames than full-screen pan or zoom. The
preliminary baseline also identified floating work as the main frame-time concern,
but the different source sets and workloads prevent a controlled before/after
comparison. The trace showed frame-production and presentation delays despite
completed image decodes running on background workers. No unique bottleneck was
established, and increasing cache size has no demonstrated resize benefit.

The smaller cache admitted coarser image copies and reduced memory use. The larger
cache admitted more detail but increased memory use and completed decode time.
The existing memory-class/8 budget was retained as the measured middle choice,
without establishing a universal optimum. Refresh-hit ratios are observations of
different requested resolution tiers, so they cannot independently rank budgets.

The existing visible/nearby cache allocation, power-of-two sampling, hysteresis,
and single concurrent decode were retained. Alternative hysteresis settings were
not compared. Large photos were subsampled rather than routinely decoded at their
original resolution.

After activity and renderer release, the image cache emptied and sampled memory
fell substantially. Preliminary teardown diagnostics found no remaining easlie
service or window. Board manifests and original assets were externally verified
against backups after captures. These finite observations support cleanup and
source preservation during the tests, rather than proving every possible leak
absent.

Profiling exposed a cancellation crash: synchronous coroutine cleanup mutated the
pending-job map during iteration. The fix detaches the jobs before canceling them.
A targeted regression reproduced the crash before the fix and passed afterward.
The harness was also corrected to observe floating-window accessibility events.
An interrupted USB-disconnect comparison was excluded and rerun from a fresh
process.

## Lead observations

The lead reported responsive pan and zoom in both modes and generally responsive
image operations and floating-board use. Slight image-movement and offscreen-image
reappearance delays were noticeable but acceptable in the tested use. Their causes
and durations were not separately established.

The later final smoke test on build `299f542` confirmed the manual criteria,
including persistence across restart and reboot, independent viewports, repeated
Return and Close cycles, notification actions, and permission denial then approval.
Image detail remained usable, with slight softness noted in the specification.
These are lead-reported observations, not new profiling measurements of that build.

## Limits and future comparisons

Memory peaks are sampled. Frame history can omit rows; the slow-frame threshold is
a fixed 60 Hz comparison even though the tablet can run at 90 Hz. Decode durations
exclude queue wait and canceled work. Programmatic viewport changes bypass finger
gesture recognition. Renderer-only comparisons draw no frames and cannot measure
visual quality or GPU upload cost.

The first trace overwrote part of its buffer; a separate larger-buffer trace
provided the decode-thread evidence. Trace and instrumentation overhead prevent
using its presentation counts as replacements for the sustained frame captures.

Performance alongside another drawing app, larger persistent boards, other tablets,
portrait layouts, other image formats, and longer sessions need separate evidence.
A future comparison of floating-resize frame production and matched-scale image
sharpness would address the remaining measured concerns. [OPINION]

Use [the profiling procedure](../../image-performance-profile.md) for future runs.
Keep each attempt's report beside its anonymized JSON results, recording conclusions
and measurement limits without duplicating the raw statistics.
