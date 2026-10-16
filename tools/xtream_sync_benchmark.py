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
        pipeline = re.search(r"\bXtreamSyncTiming\s*\([^)]*\): phase=live-pipeline rows=(\d+) batches=(\d+) reader_ms=(\d+) drain_ms=(\d+) send_wait_ms=(\d+) outstanding_batch_bytes=(\d+) queue_capacity=(\d+) batch_byte_limit=(\d+)$", line)
        if pipeline:
            records.append(dict(event="live-pipeline", **dict(zip(
                ["rows", "batches", "reader_ms", "drain_ms", "send_wait_ms",
                 "outstanding_batch_bytes", "queue_capacity", "batch_byte_limit"],
                map(int, pipeline.groups())))))
        stage = re.search(r"\bSyncCatalogStore\s*\([^)]*\): catalog timing provider=(\d+) stage=([a-z-]+(?:\(\d+\))?) took=(\d+)ms$", line)
        if stage:
            records.append({"event": "catalog-stage", "provider_id": int(stage[1]),
                            "stage": stage[2], "elapsed_ms": int(stage[3])})
    return records


def summarize(records: list[dict], events: list[dict], metadata: dict) -> dict:
    starts = {event["request_id"]: event for event in events if event["event"] == "request_start"}
    samples = [dict(record) for record in records if record.get("event") == "sample"]
    environment = next((r for r in records if r.get("event") == "environment"), None)
    for sample in samples:
        # The same account is used for refresh: request counts cover the entire
        # account lifecycle, and must not be attributed to one phase alone.
        requests = [event for event in starts.values() if event["run"] == sample["run"]]
        live = [event for event in requests if event["action"] == "get_live_streams"]
        sample["account_live_requests"] = len(live)
        sample["account_bulk_requests"] = sum(event["category_id"] is None for event in live)
        sample["account_category_requests"] = sum(event["category_id"] is not None for event in live)
        if sample.get("settle_seconds", 0) > 0:
            first_category = next((event["request_id"] for event in live if event["category_id"] is not None), None)
            sample["bulk_requests_after_fallback"] = sum(
                event["category_id"] is None and event["request_id"] > first_category
                for event in live
            ) if first_category is not None else 0
        for section, actions in {
            "movie": {"get_vod_streams"},
            "series": {"get_series"},
        }.items():
            section_requests = [event for event in requests if event["action"] in actions]
            sample[f"account_{section}_requests"] = len(section_requests)
            sample[f"account_{section}_full_requests"] = sum(
                event["category_id"] is None for event in section_requests
            )
            sample[f"account_{section}_category_requests"] = sum(
                event["category_id"] is not None for event in section_requests
            )
    groups = []
    for mode, phase in sorted({(s["mode"], s["phase"]) for s in samples}):
        selected = [s for s in samples if s["mode"] == mode and s["phase"] == phase and not s["warmup"]]
        if not selected:
            continue
        elapsed = [s["elapsed_ms"] for s in selected]
        live_ready = [milestone["elapsed_ms"] for s in selected for milestone in s.get("milestones", [])
                      if milestone["phase"] == "live-committed"]
        first_browsable = [s["first_browsable_screen_elapsed_ms"] for s in selected
                           if "first_browsable_screen_elapsed_ms" in s]
        background_elapsed = [s["background_worker_elapsed_ms"] for s in selected
                              if "background_worker_elapsed_ms" in s]
        group = {"mode": mode, "phase": phase, "samples": len(selected),
                 "median_ms": statistics.median(elapsed), "max_ms": max(elapsed),
                 "median_live_committed_ms": statistics.median(live_ready) if live_ready else None,
                 "max_sampled_java_heap_bytes": max(s["peak_java_heap_bytes"] for s in selected),
                 "channel_counts": sorted({s["channels"] for s in selected})}
        if environment and environment.get("verify_channel_fields"):
            checked_samples = [s for s in selected if "fixture_row_fields_verified" in s]
            group["fixture_row_fields_verified"] = (
                len(checked_samples) == len(selected) and
                all(s["fixture_row_fields_verified"] for s in checked_samples)
            )
            group["fixture_rows_checked"] = sorted({
                s.get("fixture_rows_checked", 0) for s in checked_samples
            })
            group["fixture_categories_checked"] = sorted({
                s.get("fixture_categories_checked", 0) for s in checked_samples
            })
            verification_times = [s["fixture_row_field_verification_ms"] for s in checked_samples
                                  if "fixture_row_field_verification_ms" in s]
            if verification_times:
                group["median_fixture_row_field_verification_ms"] = statistics.median(verification_times)
        if first_browsable:
            group["median_first_browsable_screen_ms"] = statistics.median(first_browsable)
            group["max_first_browsable_screen_ms"] = max(first_browsable)
        if background_elapsed:
            group["median_background_worker_ms"] = statistics.median(background_elapsed)
            group["max_background_worker_ms"] = max(background_elapsed)
            group["background_worker_states"] = sorted({
                s.get("background_worker_state", "MISSING") for s in selected
                if "background_worker_elapsed_ms" in s
            })
            group["background_worker_channel_counts"] = sorted({
                s.get("background_worker_channels", 0) for s in selected
                if "background_worker_elapsed_ms" in s
            })
        summary_heap = [s["summary_peak_java_heap_bytes"] for s in selected
                        if "summary_peak_java_heap_bytes" in s]
        if summary_heap:
            group["max_summary_sampled_java_heap_bytes"] = max(summary_heap)
            group["max_summary_gc_count_delta"] = max(
                s.get("summary_gc_count_delta", 0) for s in selected
            )
        for field in ("unchanged_movie_refresh_elapsed_ms", "unchanged_series_refresh_elapsed_ms",
                      "unchanged_vod_refresh_elapsed_ms"):
            values = [s[field] for s in selected if field in s]
            if values:
                group["median_" + field] = statistics.median(values)
                group["max_" + field] = max(values)
        unchanged_summary_heap = [s["unchanged_summary_peak_java_heap_bytes"] for s in selected
                                  if "unchanged_summary_peak_java_heap_bytes" in s]
        if unchanged_summary_heap:
            group["max_unchanged_summary_sampled_java_heap_bytes"] = max(unchanged_summary_heap)
            group["max_unchanged_summary_gc_count_delta"] = max(
                s.get("unchanged_summary_gc_count_delta", 0) for s in selected
            )
        for section in ("movie", "series"):
            for field in (f"{section}_index_elapsed_ms", f"{section}_after_live_elapsed_ms"):
                values = [s[field] for s in selected if field in s]
                if values:
                    group[f"median_{field}"] = statistics.median(values)
                    group[f"max_{field}"] = max(values)
            counts = [s.get("movies" if section == "movie" else "series", 0) for s in selected]
            group[f"{section}_counts"] = sorted(set(counts))
        groups.append(group)
    limits = ["Debug APK, synthetic local server, Java heap sampled every 50 ms.",
              "Native RSS and playback are not measured.",
              "Refresh shares an account with setup; account request counts include both phases.",
              "Warmups excluded; queued real background work may contend with later samples."]
    if environment and environment.get("verify_channel_fields"):
        limits.append("Persisted fixture rows and categories are checked in bounded database pages after setup and after refresh when requested; verification time is excluded from sync timing.")
    if any("first_browsable_screen_elapsed_ms" in sample for sample in samples):
        limits.append("First browsable timing uses the real Welcome-to-Live UI and synthetic channel row, but invokes setup through the production use case rather than form interaction.")
    else:
        limits.append("No UI readiness measurement; pass --first-browsable-screen to measure the first accessible synthetic live row.")
    if any("background_worker_elapsed_ms" in sample for sample in samples):
        limits.append("Background probe enqueues one immediate no-ID ProviderSyncWorker request; WorkManager interval and scheduling constraints are not measured.")
    return {"fixture": metadata, "environment": environment,
            "complete": any(r["event"] == "complete" for r in records), "groups": groups, "samples": samples,
            "timings": [r for r in records if r["event"] in {"live-response", "live-pipeline", "catalog-stage"}],
            "limits": limits}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default=shutil.which("adb"))
    parser.add_argument("--serial", default="emulator-5554")
    parser.add_argument("--port", type=int, default=8766)
    parser.add_argument("--modes", default="AUTO,STREAM_ALL,CATEGORY_BY_CATEGORY")
    parser.add_argument("--iterations", type=int, default=5)
    parser.add_argument("--warmups", type=int, default=1)
    parser.add_argument("--refresh", action="store_true")
    parser.add_argument("--settle-seconds", type=int, default=0,
                        help="Observe terminal setup state/catalog and reject bulk retries after fallback")
    parser.add_argument("--repeat-vod-refresh", action="store_true",
                        help="Force unchanged movie and series summary reindexing after the initial catalog is complete.")
    parser.add_argument("--first-browsable-screen", action="store_true",
                        help="Launch the real app Welcome screen before setup and measure the first accessible Live channel row.")
    parser.add_argument("--verify-channel-fields", action="store_true",
                        help="Compare every persisted live row with deterministic fixture fields after setup, outside import timing.")
    parser.add_argument("--background-worker", action="store_true",
                        help="Run the no-provider-ID ProviderSyncWorker against a deliberately stale synthetic live catalog.")
    parser.add_argument("--runtime-tier", choices=["DEVICE", "LOW", "MID", "HIGH"], default="DEVICE",
                        help="Simulated resource-policy tier; device memory remains the connected emulator's real memory.")
    parser.add_argument("--stage-batch-size", type=int, choices=[100, 128, 256, 300, 500, 512, 1024])
    parser.add_argument("--experimental-auto-bulk", action="store_true",
                        help="Enable bulk-first AUTO in the isolated benchmark app; normal app builds already use it.")
    parser.add_argument("--hidden-category-percent", type=int, default=0,
                        help="Hide this percentage of returned live categories before refresh.")
    parser.add_argument("--remember-sequential-stress", action="store_true",
                        help="Seed the synthetic provider with remembered category stress before refresh.")
    parser.add_argument("--avoid-full-cooldown-seconds", type=int, default=0,
                        help="Seed the full-catalog cooldown before refresh.")
    parser.add_argument("--cleanup-run", help="Exact synthetic run token to remove after an interrupted benchmark")
    parser.add_argument("--output", type=Path)
    for field, default in asdict(Scenario()).items():
        parser.add_argument("--" + field.replace("_", "-"), type=int, default=default)
    args = parser.parse_args()
    if not args.adb or not set(args.modes.split(',')) <= MODES:
        parser.error("adb and valid sync modes are required")
    if not 1 <= args.iterations <= 20 or not 0 <= args.warmups <= 3:
        parser.error("iterations must be 1..20 and warmups 0..3")
    if not 0 <= args.settle_seconds <= 120 or (args.settle_seconds and (args.refresh or args.background_worker)):
        parser.error("settle-seconds must be 0..120 and requires setup-only runs")
    if not 0 <= args.hidden_category_percent <= 100 or not 0 <= args.avoid_full_cooldown_seconds <= 86_400:
        parser.error("hidden-category-percent must be 0..100 and avoid-full-cooldown-seconds 0..86400")
    if (args.hidden_category_percent or args.remember_sequential_stress or args.avoid_full_cooldown_seconds) and not args.refresh:
        parser.error("hidden scopes and remembered-failure inputs require --refresh")
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
                                     "-e", "measureSummaryRefresh", str(args.repeat_vod_refresh).lower(),
                                     "-e", "measureFirstBrowsableScreen", str(args.first_browsable_screen).lower(),
                                     "-e", "verifyChannelFields", str(args.verify_channel_fields).lower(),
                                     "-e", "settleSeconds", str(args.settle_seconds),
                                     "-e", "measureBackgroundWorker", str(args.background_worker).lower(),
                                     *(["-e", "cleanupRun", args.cleanup_run] if args.cleanup_run else []),
                                     "-e", "runtimeTier", args.runtime_tier,
                                     "-e", "experimentalAutoBulk", str(args.experimental_auto_bulk).lower(),
                                     "-e", "hiddenCategoryPercent", str(args.hidden_category_percent),
                                     "-e", "rememberSequentialStress", str(args.remember_sequential_stress).lower(),
                                     "-e", "avoidFullCooldownSeconds", str(args.avoid_full_cooldown_seconds),
                                     *( ["-e", "stageBatchSize", str(args.stage_batch_size)] if args.stage_batch_size is not None else [] ),
                                     PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner"],
                                    capture_output=True, text=True)
            log_process.terminate()
            log_process.communicate(timeout=10)
            instrumentation_output = result.stdout + "\n" + result.stderr
            # Keep only instrumentation status/failure class, never exception text:
            # network errors from the app can contain request identities.
            status = {"exit_code": result.returncode,
                      "ok": bool(re.search(r"OK \(1 test\)", instrumentation_output)),
                      "test_started": "INSTRUMENTATION_STATUS: test=" in instrumentation_output,
                      "status_codes": re.findall(r"INSTRUMENTATION_STATUS_CODE: (-?\d+)", instrumentation_output),
                      "exception_classes": sorted(set(re.findall(r"(?:^|\n|Caused by: )([\w.$]+(?:Exception|Error))(?::|\s|$)", instrumentation_output))),
                      "failure": next(iter(re.findall(r"^INSTRUMENTATION_STATUS: (?:Error|shortMsg)=(\w[\w.$]*)", instrumentation_output, re.M)), None)}
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
        report["background_worker_requested"] = args.background_worker
        report["retry_quiescence_passed"] = all(
            s.get("bulk_requests_after_fallback", 0) == 0 for s in report["samples"]
        ) if args.settle_seconds else None
        (output / "report.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
        print(json.dumps({"report": str(output / "report.json"), "groups": report["groups"],
                          "complete": report["complete"], "instrumentation": status}), flush=True)
        if not status["ok"] or not report["complete"] or not report["all_samples_recorded"] or request_events_truncated or report["retry_quiescence_passed"] is False:
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
