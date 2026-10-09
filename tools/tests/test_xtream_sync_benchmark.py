import importlib.util
import json
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parents[1]))
SPEC = importlib.util.spec_from_file_location("xtream_sync_benchmark", Path(__file__).parents[1] / "xtream_sync_benchmark.py")
BENCHMARK = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(BENCHMARK)


class BenchmarkReportTest(unittest.TestCase):
    def test_only_benchmark_records_are_retained(self):
        log = '\n'.join([
            'I/OtherTag( 123): password=private',
            'I/XtreamSyncBenchmark( 123): {"event":"complete"}',
            'I/XtreamSyncBenchmark( 123): {"event":"unrelated","secret":"private"}',
        ])
        self.assertEqual([{"event": "complete"}], BENCHMARK.parse_records(log))
        self.assertNotIn("private", json.dumps(BENCHMARK.parse_records(log)))

    def test_warmups_are_excluded_and_request_scopes_are_distinguished(self):
        records = [{"event": "sample", "run": "bench_test", "mode": "AUTO", "phase": "initial",
                    "warmup": warmup, "elapsed_ms": elapsed, "channels": 100,
                    "peak_java_heap_bytes": 1024, "milestones": [{"phase": "live-committed", "elapsed_ms": elapsed - 1}]}
                   for warmup, elapsed in [(True, 9000), (False, 100), (False, 200), (False, 300)]]
        events = [{"event": "request_start", "request_id": index, "run": run,
                   "action": action, "category_id": category}
                  for index, (run, action, category) in enumerate([
                      ("bench_test", "get_live_streams", None),
                      ("bench_test", "get_live_streams", 1),
                      ("bench_test", "authenticate", None),
                      ("bench_other", "get_live_streams", 2)])]
        report = BENCHMARK.summarize(records, events, {})
        self.assertFalse(report["complete"])
        self.assertEqual(3, report["groups"][0]["samples"])
        self.assertEqual(200, report["groups"][0]["median_ms"])
        self.assertEqual(199, report["groups"][0]["median_live_committed_ms"])
        self.assertEqual(300, report["groups"][0]["max_ms"])
        self.assertEqual(1, report["samples"][0]["account_bulk_requests"])
        self.assertEqual(1, report["samples"][0]["account_category_requests"])

    def test_warmups_alone_cannot_produce_a_result(self):
        report = BENCHMARK.summarize([{"event": "sample", "run": "bench_test", "mode": "AUTO",
                                       "phase": "initial", "warmup": True}], [], {})
        self.assertEqual([], report["groups"])

    def test_timings_are_numeric_and_prior_run_is_discarded(self):
        log = '\n'.join([
            'I/XtreamSyncBenchmark( 123): {"event":"complete"}',
            'I/XtreamSyncBenchmark( 123): {"event":"environment"}',
            'I/XtreamSyncTiming( 123): phase=live-response elapsed_ms=100 callback_ms=60 rows=500 completed=true',
            'I/SyncCatalogStore( 123): catalog timing provider=5 stage=channel-staging-batch(300) took=5ms',
            'I/SyncCatalogStore( 123): unexpected credential=private',
        ])
        records = BENCHMARK.parse_records(log)
        self.assertEqual(3, len(records))
        self.assertEqual(60, records[1]["callback_ms"])
        self.assertEqual(5, records[2]["provider_id"])
        self.assertFalse(BENCHMARK.summarize(records, [], {})["complete"])
        self.assertNotIn("private", json.dumps(records))


if __name__ == "__main__":
    unittest.main()
