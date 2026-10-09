#!/usr/bin/env python3
"""Run the isolated Android sync benchmark against a local synthetic fixture."""
from __future__ import annotations

import argparse
import json
import re
import shutil
import statistics
import subprocess
import threading
from dataclasses import asdict
from datetime import datetime, timezone
from pathlib import Path

from xtream_sync_perf_fixture import FixtureServer, Scenario


PACKAGE = "com.streamvault.app.syncbenchmark"
TEST_CLASS = "com.streamvault.app.benchmark.XtreamLiveSyncBenchmarkTest"
MODES = {"AUTO", "STREAM_ALL", "CATEGORY_BY_CATEGORY"}


def parse_records(log: str) -> list[dict]:
    records = []
    for line in log.splitlines():
        # Only our JSON tag is retained; never copy unrelated provider logs.
        match = re.search(r"\bXtreamSyncBenchmark\s*\([^)]*\):\s*(\{.*\})$", line)
        if match:
            value = json.loads(match[1])
            if value.get("event") in {"environment", "sample", "complete"}:
                if value["event"] == "environment":
                    records.clear()  # Discard the previous run's last buffered line.
                records.append(value)
            continue
        timing = re.search(r"\bXtreamSyncTiming\s*\([^)]*\): phase=live-response elapsed_ms=(\d+) callback_ms=(\d+) rows=(\d+) completed=(true|false)$", line)
        if timing:
            records.append({"event": "live-response", "elapsed_ms": int(timing[1]),
                            "callback_ms": int(timing[2]), "rows": int(timing[3]),
                            "completed": timing[4] == "true"})
        stage = re.search(r"\bSyncCatalogStore\s*\([^)]*\): catalog timing provider=(\d+) stage=([a-z-]+(?:\(\d+\))?) took=(\d+)ms$", line)
        if stage:
            records.append({"event": "catalog-stage", "provider_id": int(stage[1]),
                            "stage": stage[2], "elapsed_ms": int(stage[3])})
    return records


def summarize(records: list[dict], events: list[dict], metadata: dict) -> dict:
    starts = {event["request_id"]: event for event in events if event["event"] == "request_start"}
    samples = [dict(record) for record in records if record.get("event") == "sample"]
    for sample in samples:
        # The same account is used for refresh: request counts cover the entire
        # account lifecycle, and must not be attributed to one phase alone.
        requests = [event for event in starts.values() if event["run"] == sample["run"]]
        live = [event for event in requests if event["action"] == "get_live_streams"]
        sample["account_live_requests"] = len(live)
        sample["account_bulk_requests"] = sum(event["category_id"] is None for event in live)
        sample["account_category_requests"] = sum(event["category_id"] is not None for event in live)
    groups = []
    for mode, phase in sorted({(s["mode"], s["phase"]) for s in samples}):
        selected = [s for s in samples if s["mode"] == mode and s["phase"] == phase and not s["warmup"]]
        if not selected:
            continue
        elapsed = [s["elapsed_ms"] for s in selected]
        live_ready = [milestone["elapsed_ms"] for s in selected for milestone in s.get("milestones", [])
                      if milestone["phase"] == "live-committed"]
        groups.append({"mode": mode, "phase": phase, "samples": len(selected),
                       "median_ms": statistics.median(elapsed), "max_ms": max(elapsed),
                       "median_live_committed_ms": statistics.median(live_ready) if live_ready else None,
                       "max_sampled_java_heap_bytes": max(s["peak_java_heap_bytes"] for s in selected),
                       "channel_counts": sorted({s["channels"] for s in selected})})
    return {"fixture": metadata, "environment": next((r for r in records if r["event"] == "environment"), None),
            "complete": any(r["event"] == "complete" for r in records), "groups": groups, "samples": samples,
            "timings": [r for r in records if r["event"] in {"live-response", "catalog-stage"}],
            "limits": ["Debug APK, synthetic local server, Java heap sampled every 50 ms.",
                       "Backend completion only; no UI readiness, native RSS, or playback measurement.",
                       "Refresh shares an account with setup; account request counts include both phases.",
                       "Warmups excluded; queued real background work may contend with later samples."]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default=shutil.which("adb"))
    parser.add_argument("--serial", default="emulator-5554")
    parser.add_argument("--port", type=int, default=8766)
    parser.add_argument("--modes", default="AUTO,STREAM_ALL,CATEGORY_BY_CATEGORY")
    parser.add_argument("--iterations", type=int, default=5)
    parser.add_argument("--warmups", type=int, default=1)
    parser.add_argument("--refresh", action="store_true")
    parser.add_argument("--cleanup-run", help="Exact synthetic run token to remove after an interrupted benchmark")
    parser.add_argument("--output", type=Path)
    for field, default in asdict(Scenario()).items():
        parser.add_argument("--" + field.replace("_", "-"), type=int, default=default)
    args = parser.parse_args()
    if not args.adb or not set(args.modes.split(',')) <= MODES:
        parser.error("adb and valid sync modes are required")
    if not 1 <= args.iterations <= 20 or not 0 <= args.warmups <= 3:
        parser.error("iterations must be 1..20 and warmups 0..3")
    if args.cleanup_run and not re.fullmatch(r"bench_[a-zA-Z0-9_]{1,100}", args.cleanup_run):
        parser.error("cleanup-run must be an exact synthetic benchmark token")
    scenario = Scenario(**{field: getattr(args, field) for field in asdict(Scenario())})
    output = args.output or Path(__file__).resolve().parents[1] / "build" / "xtream-sync-performance" / datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    output.mkdir(parents=True, exist_ok=False)
    server = FixtureServer(("127.0.0.1", args.port), scenario, output / "requests.jsonl")
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    adb = [args.adb, "-s", args.serial]

    def command(*parts):
        return subprocess.run([*adb, *parts], capture_output=True, text=True, check=True).stdout

    # Do not steal an existing reverse mapping or erase the device log buffer.
    mapping = "tcp:" + str(args.port)
    reversed_port = False
    log_process = None
    raw_log = output / "benchmark-log.txt"
    try:
        existing = command("reverse", "--list")
        if any(mapping in line.split()[1:] for line in existing.splitlines()):
            raise RuntimeError("Benchmark port already has an adb reverse mapping; use another --port")
        check_package = command("shell", "pm", "path", PACKAGE).strip()
        if not check_package.startswith("package:"):
            raise RuntimeError("Install the isolated benchmark APK first")
        command("reverse", mapping, mapping)
        reversed_port = True
        with raw_log.open("w", encoding="utf-8") as log:
            log_process = subprocess.Popen([*adb, "logcat", "-T", "1", "-v", "brief", "-s",
                                            "XtreamSyncBenchmark:I", "XtreamSyncTiming:I", "SyncCatalogStore:I"],
                                           stdout=log, stderr=subprocess.PIPE, text=True)
            result = subprocess.run([*adb, "shell", "am", "instrument", "-w", "-r",
                                     "-e", "class", TEST_CLASS, "-e", "xtreamSyncBenchmark", "true",
                                     "-e", "fixtureUrl", "http://127.0.0.1:" + str(args.port),
                                     "-e", "syncModes", args.modes, "-e", "iterations", str(args.iterations),
                                     "-e", "warmups", str(args.warmups), "-e", "measureRefresh", str(args.refresh).lower(),
                                     *(["-e", "cleanupRun", args.cleanup_run] if args.cleanup_run else []),
                                     PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner"],
                                    capture_output=True, text=True)
            log_process.terminate()
            log_process.communicate(timeout=10)
            # Keep only instrumentation status/failure class, never exception text:
            # network errors from the app can contain request identities.
            status = {"exit_code": result.returncode,
                      "ok": bool(re.search(r"OK \(1 test\)", result.stdout)),
                      "status_codes": re.findall(r"INSTRUMENTATION_STATUS_CODE: (-?\d+)", result.stdout),
                      "exception_classes": sorted(set(re.findall(r"(?:^|\n|Caused by: )([\w.$]+(?:Exception|Error))(?::|\s|$)", result.stdout))),
                      "failure": next(iter(re.findall(r"^INSTRUMENTATION_STATUS: (?:Error|shortMsg)=(\w[\w.$]*)", result.stdout, re.M)), None)}
        records = parse_records(raw_log.read_text(encoding="utf-8"))
        # Replace raw tag output with structured benchmark records only.
        raw_log.write_text("".join(json.dumps(r) + "\n" for r in records), encoding="utf-8")
        with server.lock:
            events = list(server.events)
            request_events_truncated = server.sequence * 2 - server.active > len(events)
            max_active_requests = server.high_water
        report = summarize(records, events, server.metadata)
        report["request_events_truncated"] = request_events_truncated
        report["max_active_requests"] = max_active_requests
        expected_samples = len(args.modes.split(',')) * (args.iterations + args.warmups) * (2 if args.refresh else 1)
        report["expected_samples"] = expected_samples
        report["all_samples_recorded"] = len(report["samples"]) == expected_samples
        report["instrumentation"] = status
        (output / "report.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
        print(json.dumps({"report": str(output / "report.json"), "groups": report["groups"],
                          "complete": report["complete"], "instrumentation": status}), flush=True)
        if not status["ok"] or not report["complete"] or not report["all_samples_recorded"] or request_events_truncated:
            raise SystemExit("Benchmark failed; no successful performance result was recorded")
    finally:
        if log_process and log_process.poll() is None:
            log_process.terminate()
            log_process.communicate(timeout=10)
        if reversed_port:
            command("reverse", "--remove", mapping)
        server.shutdown()
        server.server_close()
        thread.join(timeout=3)


if __name__ == "__main__":
    main()
