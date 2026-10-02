#!/usr/bin/env python3
"""Capture opt-in real-board instrumentation. Stores measurements, never image bytes."""
import argparse
import hashlib
import json
import math
import pathlib
import re
import subprocess
import sys
import threading
import time


def adb(serial, *args):
    return ["adb", "-s", serial, *args]


def frame_rows(output, completed_before_ns=2**63 - 2):
    """Only completed, unflagged frames; caller deduplicates repeated ring-buffer samples."""
    window = "unknown"
    header = None
    for line in output.splitlines():
        line = line.strip()
        if line.startswith("Window: "):
            window = line[8:]
        if line.startswith("Flags,") and "IntendedVsync" in line and "FrameCompleted" in line:
            header = line.rstrip(",").split(",")
        elif header and re.match(r"^\d+,", line):
            values = line.rstrip(",").split(",")
            if len(values) != len(header):
                continue
            row = dict(zip(header, map(int, values)))
            start, end = row["IntendedVsync"], row["FrameCompleted"]
            if row["Flags"] == 0 and 0 < start < end <= completed_before_ns:
                yield window, start, (end - start) / 1e6


def percentile(values, quantile):
    ordered = sorted(values)
    if not ordered:
        return None
    index = (len(ordered) - 1) * quantile
    low, high = math.floor(index), math.ceil(index)
    return ordered[low] + (ordered[high] - ordered[low]) * (index - low)


def summarize(directory):
    logs = (directory / "profile.log").read_text()
    timed_lines = [(float(line.split()[0]), line) for line in logs.splitlines() if re.match(r"^\s*\d+\.\d+\s", line)]
    stages = []
    for line in logs.splitlines():
        match = re.search(r"stage=(\S+) repeat=(\d+) event=(start|end)", line)
        if match:
            name, repeat, event = match.groups()
            stamp = float(line.split()[0])
            time_ns = int(re.search(r"time_ns=(\d+)", line).group(1))
            if event == "start":
                stages.append(dict(stage=name, repeat=int(repeat), start=stamp, start_ns=time_ns))
            elif stages and stages[-1]["stage"] == name:
                stages[-1]["end"] = stamp
                stages[-1]["end_ns"] = time_ns
    samples = [json.loads(line) for line in (directory / "samples.jsonl").read_text().splitlines()]
    # Both IntendedVsync and the instrumentation stage markers use device monotonic time.
    frames = {}
    future_frames = set()
    anchor = stages[0] if stages else None
    for sample in samples:
        output = (directory / sample["frames"]).read_text()
        # A completion cannot occur after observation. Legacy samples only record
        # polling start, so allow both adb calls their full 30-second timeout.
        observed = sample.get("finished_timestamp", sample["timestamp"] + 60)
        limit = anchor["start_ns"] + int((observed - anchor["start"] + 1) * 1e9) if anchor else 2**63 - 2
        raw = {(window, intended) for window, intended, _ in frame_rows(output)}
        valid = list(frame_rows(output, limit))
        future_frames.update(raw - {(window, intended) for window, intended, _ in valid})
        for window, intended, duration in valid:
            frames.setdefault((window, intended), duration)
    for stage in stages:
        end = stage.get("end", float("inf"))
        interval = [s for s in samples if stage["start"] <= s["timestamp"] <= end]
        memories = []
        for sample in interval:
            output = (directory / sample["memory"]).read_text()
            values = {key: int(value) for key, value in re.findall(r"(TOTAL PSS|Java Heap|Native Heap|Graphics):\s+(\d+)", output)}
            if "TOTAL PSS" in values:
                memories.append(dict(timestamp=sample["timestamp"], **values))
        durations = [d for (_, intended), d in frames.items() if stage["start_ns"] <= intended <= stage.get("end_ns", 2**63 - 1)]
        lines = [line for stamp, line in timed_lines if stage["start"] <= stamp <= end]
        decodes = [int(m.group(1)) for line in lines if (m := re.search(r"decode_ms=(\d+)", line))]
        factors = [int(m.group(1)) for line in lines if "decode_ms=" in line and (m := re.search(r"sample=(\d+)", line))]
        counters = []
        for line in lines:
            if "refresh_hits=" in line:
                counters.append({k: int(v) for k, v in re.findall(r"(\w+)=(\d+)", line)})
        stage["peak_sample"] = max(memories, key=lambda s: s["TOTAL PSS"]) if memories else None
        stage["last_sample"] = memories[-1] if memories else None
        stage["memory_samples"] = len(memories)
        stage["frames"] = dict(count=len(durations), over_16_67_ms=sum(d > 1000 / 60 for d in durations), frozen_over_700_ms=sum(d > 700 for d in durations), p95_ms=percentile(durations, .95), worst_ms=max(durations, default=None))
        stage["frames"]["invalid_future_completions"] = sum(stage["start_ns"] <= intended <= stage.get("end_ns", 2**63 - 1) for _, intended in future_frames)
        stage["decode"] = dict(count=len(decodes), median_ms=percentile(decodes, .5), p95_ms=percentile(decodes, .95), max_ms=max(decodes, default=None), sample_factors={str(f): factors.count(f) for f in sorted(set(factors))})
        large = [line for line in lines if "decode_ms=" in line and (m := re.search(r"source_edge=(\d+)", line)) and int(m.group(1)) >= 4000]
        large_times = [int(re.search(r"decode_ms=(\d+)", line).group(1)) for line in large]
        large_factors = [int(re.search(r"sample=(\d+)", line).group(1)) for line in large]
        stage["large_source_decode"] = dict(min_source_edge_px=4000, count=len(large_times), median_ms=percentile(large_times, .5), p95_ms=percentile(large_times, .95), max_ms=max(large_times, default=None), sample_factors={str(f): large_factors.count(f) for f in sorted(set(large_factors))}) if any("source_edge=" in line for line in lines) else None
        stage["decode_failures"] = sum("decode_failed" in line for line in lines)
        if len(counters) >= 2:
            first, last = counters[0], counters[-1]
            stage["cache"] = {key: last[key] - first[key] for key in ("refresh_hits", "refresh_misses", "decodes_scheduled")}
            total = stage["cache"]["refresh_hits"] + stage["cache"]["refresh_misses"]
            stage["cache"].update(end_bytes=last["cache_bytes"], max_observed_bytes=max(c["cache_bytes"] for c in counters), budget_bytes=last["budget_bytes"], refresh_hit_ratio=stage["cache"]["refresh_hits"] / total if total else None)
        else:
            stage["cache"] = None
    result = dict(stages=stages, board_restored="board_restored=true" in logs,
                  oom="OutOfMemoryError" in logs,
                  floating_skipped="floating_skipped=true" in logs,
                  limitations="Sampled PSS is a lower bound on peak. gfxinfo ring buffers can omit frames; impossible future completion timestamps are excluded and counted. Programmatic viewport changes bypass gesture recognition. Cache experiments do not draw frames. Decode times omit canceled jobs and queue time. Debug/instrumentation/capture overhead is included.")
    (directory / "summary.json").write_text(json.dumps(result, indent=2) + "\n")
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    parser.add_argument("--seconds", type=int, default=120)
    parser.add_argument("--repetitions", type=int, default=3)
    parser.add_argument("--interval", type=float, default=1)
    parser.add_argument("--summarize", action="store_true")
    modes = parser.add_mutually_exclusive_group()
    modes.add_argument("--skip-floating", action="store_true", help="Record a partial full-screen/cache run when overlay permission is unavailable")
    modes.add_argument("--floating-only", action="store_true", help="Capture floating resize and transitions without repeating the full-screen/cache experiments")
    modes.add_argument("--cache-only", action="store_true", help="Compare renderer budgets without repeating real-host workloads")
    modes.add_argument("--hosts-only", action="store_true", help="Capture real hosts without the renderer-only cache comparison")
    parser.add_argument("--cache-divisor", type=int, choices=(16, 8, 4), help="Limit the cache experiment to memoryClass / this divisor")
    args = parser.parse_args()
    if args.summarize:
        summarize(args.output)
        return
    if not (5 <= args.seconds <= 600 and 1 <= args.repetitions <= 10 and args.interval >= .25):
        parser.error("seconds must be 5..600, repetitions 1..10, interval >= 0.25")
    args.output.mkdir(parents=True, exist_ok=False)
    repo = pathlib.Path(__file__).resolve().parents[1]
    metadata = dict(seconds=args.seconds, repetitions=args.repetitions, interval_seconds=args.interval,
                    floating_requested=not (args.skip_floating or args.cache_only),
                    floating_only=args.floating_only,
                    cache_only=args.cache_only,
                    hosts_only=args.hosts_only,
                    cache_divisor=args.cache_divisor,
                    commit=subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=repo, text=True).strip(),
                    dirty=bool(subprocess.check_output(["git", "status", "--porcelain"], cwd=repo, text=True).strip()),
                    apk_sha256={})
    for relative in ("app/build/outputs/apk/debug/app-debug.apk", "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"):
        metadata["apk_sha256"][relative] = hashlib.sha256((repo / relative).read_bytes()).hexdigest()
    (args.output / "metadata.json").write_text(json.dumps(metadata, indent=2) + "\n")
    stop = threading.Event()
    def run(*command):
        return subprocess.run(adb(args.serial, *command), capture_output=True, text=True, timeout=30)
    clocks = []
    for _ in range(3):
        before = time.time()
        device = int(run("shell", "date", "+%s%N").stdout.strip()) / 1e9
        after = time.time()
        clocks.append(dict(offset_seconds=device - (before + after) / 2, uncertainty_seconds=(after - before) / 2))
    clock = min(clocks, key=lambda c: c["uncertainty_seconds"])
    (args.output / "clock.json").write_text(json.dumps(clock, indent=2) + "\n")
    for name, command in {
        "device.txt": ("shell", "getprop"),
        "thermal-before.txt": ("shell", "dumpsys", "thermalservice"),
        "display.txt": ("shell", "dumpsys", "display"),
        "ram.txt": ("shell", "cat", "/proc/meminfo"),
        "developer-options.txt": ("shell", "settings", "get", "global", "development_settings_enabled"),
    }.items():
        (args.output / name).write_text(run(*command).stdout)
    # Package-scoped reset; do not clear the tablet's global log buffer.
    run("shell", "am", "force-stop", "com.chrisb588.easlie")
    run("shell", "dumpsys", "gfxinfo", "com.chrisb588.easlie", "reset")
    with (args.output / "profile.log").open("w") as log, (args.output / "instrumentation.txt").open("w") as test:
        logger = subprocess.Popen(adb(args.serial, "logcat", "-v", "epoch", "-T", "1", "EaslieBoardProfile:I", "EaslieImageProfile:D", "AndroidRuntime:E", "*:S"), stdout=log, stderr=subprocess.STDOUT)
        command = ["shell", "am", "instrument", "-w", "-r", "-e", "class", "com.chrisb588.easlie.RealBoardProfileTest", "-e", "profileRealBoard", "true", "-e", "profileSeconds", str(args.seconds), "-e", "profileRepetitions", str(args.repetitions), "-e", "profileFloating", str(not args.skip_floating).lower(), "-e", "profileFloatingOnly", str(args.floating_only).lower(), "-e", "profileCacheOnly", str(args.cache_only).lower(), "-e", "profileHostsOnly", str(args.hosts_only).lower()]
        if args.cache_divisor:
            command.extend(["-e", "profileCacheDivisor", str(args.cache_divisor)])
        command.append("com.chrisb588.easlie.test/androidx.test.runner.AndroidJUnitRunner")
        runner = subprocess.Popen(adb(args.serial, *command), stdout=test, stderr=subprocess.STDOUT)
        try:
            with (args.output / "samples.jsonl").open("w") as samples:
                index = 0
                while runner.poll() is None:
                    start = time.time()
                    stamp = start + clock["offset_seconds"]
                    memory = f"memory-{index:05}.txt"
                    frames = f"frames-{index:05}.txt"
                    (args.output / memory).write_text(run("shell", "dumpsys", "meminfo", "com.chrisb588.easlie").stdout)
                    (args.output / frames).write_text(run("shell", "dumpsys", "gfxinfo", "com.chrisb588.easlie", "framestats").stdout)
                    samples.write(json.dumps(dict(timestamp=stamp, finished_timestamp=time.time() + clock["offset_seconds"], memory=memory, frames=frames)) + "\n")
                    samples.flush()
                    index += 1
                    stop.wait(max(0, args.interval - (time.time() - start)))
        finally:
            if runner.poll() is None:
                # Terminating the adb client alone leaves instrumentation mutating
                # the real board. Stop the target too; verify the external backup
                # afterward because process death cannot run test teardown.
                try:
                    stopped = run("shell", "am", "force-stop", "com.chrisb588.easlie")
                    if stopped.returncode != 0:
                        print("Target stop failed; stop easlie and verify the board backup before retrying.", file=sys.stderr)
                except (OSError, subprocess.TimeoutExpired):
                    print("Target stop could not be verified; stop easlie and verify the board backup before retrying.", file=sys.stderr)
                finally:
                    runner.terminate()
            logger.terminate()
            for process in (runner, logger):
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=10)
    (args.output / "thermal-after.txt").write_text(run("shell", "dumpsys", "thermalservice").stdout)
    summarize(args.output)
    output = (args.output / "instrumentation.txt").read_text()
    if runner.returncode != 0 or "OK (1 test)" not in output:
        raise SystemExit(f"Profiling failed. Read {args.output / 'instrumentation.txt'} and verify the board backup before retrying.")
    print(f"Measurements: {args.output / 'summary.json'}", flush=True)


if __name__ == "__main__":
    main()
