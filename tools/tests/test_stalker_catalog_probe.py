import importlib.util
import sys
import threading
import unittest
from pathlib import Path


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


TOOLS = Path(__file__).parents[1]
FIXTURE = load("stalker_catalog_fixture", TOOLS / "stalker_catalog_fixture.py")
PROBE = load("stalker_catalog_probe", TOOLS / "stalker_catalog_probe.py")


class StalkerCatalogProbeTest(unittest.TestCase):
    def run_probe(self, **scenario_options):
        server = FIXTURE.FixtureServer(("127.0.0.1", 0), FIXTURE.Scenario(**scenario_options))
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()

        def close():
            server.shutdown()
            server.server_close()
            thread.join(timeout=3)

        self.addCleanup(close)
        base = "http://127.0.0.1:" + str(server.server_address[1])
        options = PROBE.ProbeOptions(max_pages=100, request_interval_ms=0)
        client = PROBE.StalkerClient(base, "02:00:00:00:00:01", options)
        return PROBE.probe(client, options), server

    def test_one_based_fixture_wildcard_matches_per_category(self):
        report, _ = self.run_probe(movies=100, categories=5)
        self.assertEqual("ONE_BASED_CLAMPED", report["page_base_kind"])
        self.assertEqual("complete", report["coverage"])
        self.assertEqual(0, report["missing_rows"])
        self.assertEqual(0, report["extra_rows"])
        self.assertEqual(100, report["per_category"]["rows"])
        self.assertEqual(100, report["wildcard"]["rows"])
        self.assertEqual(8, report["wildcard"]["requests"])
        self.assertTrue(report["totals_stable"])
        self.assertEqual([], report["warnings"])

    def test_zero_based_fixture_detects_base_and_crawls_completely(self):
        report, _ = self.run_probe(movies=60, categories=3, p_base=0)
        self.assertEqual("ZERO_BASED", report["page_base_kind"])
        self.assertEqual(0, report["page_base"])
        self.assertEqual("complete", report["coverage"])
        self.assertEqual(60, report["wildcard"]["rows"])

    def test_stock_fixture_flags_page_zero_last_page_warning(self):
        report, _ = self.run_probe(movies=60, categories=3, p_base=2)
        self.assertEqual("STOCK_LAST_PAGE_ON_ZERO", report["page_base_kind"])
        self.assertTrue(report["stock_page_zero_is_last_page"])
        self.assertEqual("complete", report["coverage"])
        self.assertTrue(any("last page" in warning for warning in report["warnings"]))

    def test_wildcard_unsupported_is_reported(self):
        report, _ = self.run_probe(movies=60, categories=3, wildcard_supported=0)
        self.assertEqual("wildcard_unsupported", report["coverage"])
        self.assertEqual(0, report["wildcard"]["rows"])
        self.assertEqual(60, report["per_category"]["rows"])

    def test_adult_exclusion_is_explained_not_incomplete(self):
        report, _ = self.run_probe(movies=100, categories=4, adult_category=9, adult_stride=10,
                                    wildcard_excludes_adult=1)
        self.assertEqual("complete_with_excluded_categories", report["coverage"])
        self.assertEqual(["9"], report["excluded_categories"])
        self.assertEqual({"9": 10}, report["missing_by_category"])
        self.assertEqual(90, report["wildcard"]["rows"])
        self.assertEqual(100, report["per_category"]["rows"])

    def test_unstable_totals_are_reported(self):
        report, _ = self.run_probe(movies=60, categories=3, unstable_totals_every=2)
        self.assertFalse(report["totals_stable"])
        self.assertTrue(any("total_items" in warning for warning in report["warnings"]))

    def test_repeated_pages_produce_duplicates_and_incomplete_coverage(self):
        report, _ = self.run_probe(movies=60, categories=2, repeat_page_every=1)
        self.assertGreater(report["per_category"]["duplicates"], 0)
        self.assertEqual("incomplete", report["coverage"])
        self.assertTrue(any("repeated" in warning for warning in report["warnings"]))

    def test_throttle_events_are_counted_without_failing(self):
        report, server = self.run_probe(movies=60, categories=3, throttle_every=4)
        self.assertGreaterEqual(report["counters"]["throttled"], 1)
        self.assertGreater(report["counters"]["retries"], 0)
        self.assertEqual("complete", report["coverage"])
        with server.lock:
            self.assertGreaterEqual(server.counters.throttled, 1)

    def test_report_never_contains_mac_or_full_url(self):
        report, _ = self.run_probe(movies=30, categories=3)
        serialized = repr(report)
        self.assertNotIn("02:00:00:00:00:01", serialized)
        self.assertNotIn("http://", serialized)
        self.assertIn("127.0.0.1", report["portal_host"])


if __name__ == "__main__":
    unittest.main()
