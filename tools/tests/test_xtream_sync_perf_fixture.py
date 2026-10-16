import importlib.util
import json
import sys
import threading
import unittest
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import urlopen


SPEC = importlib.util.spec_from_file_location(
    "xtream_sync_perf_fixture", Path(__file__).parents[1] / "xtream_sync_perf_fixture.py"
)
FIXTURE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = FIXTURE
SPEC.loader.exec_module(FIXTURE)


class XtreamSyncPerfFixtureTest(unittest.TestCase):
    def test_category_partition_matches_bulk_without_losing_rows(self):
        scenario = FIXTURE.Scenario(channels=103, categories=12)
        bulk = list(scenario.rows())
        partition = [row for category in range(1, 13) for row in scenario.rows(category)]
        self.assertEqual(bulk, sorted(partition, key=lambda row: row["num"]))
        self.assertEqual([], list(scenario.rows(999)))

    def test_hash_is_deterministic_and_fault_admission_is_explicit(self):
        scenario = FIXTURE.Scenario(channels=103, categories=12, malformed_every=10)
        metadata = scenario.metadata()
        self.assertEqual(93, metadata["expected_channels"])
        self.assertEqual(metadata, scenario.metadata())
        self.assertNotEqual(metadata["payload_sha256"],
                            FIXTURE.Scenario(channels=103, categories=12, seed=2).metadata()["payload_sha256"])
        self.assertEqual(103, len(json.loads(b"".join(scenario.chunks()))))

    def test_vod_and_series_rows_partition_and_hash_deterministically(self):
        scenario = FIXTURE.Scenario(
            channels=12, categories=3, movies=31, movie_categories=4,
            series_items=19, series_categories=3
        )
        movie_bulk = list(scenario.movie_rows())
        movie_partition = [row for category in range(1, 5) for row in scenario.movie_rows(category)]
        series_bulk = list(scenario.series_rows())
        series_partition = [row for category in range(1, 4) for row in scenario.series_rows(category)]
        self.assertEqual(movie_bulk, sorted(movie_partition, key=lambda row: row["num"]))
        self.assertEqual(series_bulk, sorted(series_partition, key=lambda row: row["series_id"]))
        metadata = scenario.metadata()
        self.assertEqual(31, metadata["expected_movies"])
        self.assertEqual(19, metadata["expected_series"])
        self.assertEqual(metadata["movie_payload_sha256"], scenario.metadata()["movie_payload_sha256"])
        self.assertEqual(metadata["series_payload_sha256"], scenario.metadata()["series_payload_sha256"])
        with self.assertRaises(ValueError):
            FIXTURE.Scenario(movies=1)

    def start_server(self, **options):
        server = FIXTURE.FixtureServer(("127.0.0.1", 0), FIXTURE.Scenario(**options))
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()

        def close():
            server.shutdown()
            server.server_close()
            thread.join(timeout=3)

        self.addCleanup(close)
        return server, "http://127.0.0.1:" + str(server.server_address[1])

    def get(self, base, path):
        with urlopen(base + path, timeout=3) as response:
            return json.load(response)

    def test_http_stream_and_events_never_expose_credentials(self):
        server, base = self.start_server(channels=103, categories=12)
        body = self.get(base, "/player_api.php?action=get_live_streams&category_id=2"
                        "&username=private-user&password=do-not-log")
        self.assertEqual(9, len(body))
        events = self.get(base, "/events")
        serialized = json.dumps(events)
        self.assertNotIn("private-user", serialized)
        self.assertNotIn("do-not-log", serialized)
        self.assertEqual(2, events["events"][0]["category_id"])
        self.assertEqual(len(body), events["events"][1]["rows"])
        self.assertTrue(events["events"][1]["completed"])
        self.assertEqual(103, self.get(base, "/fixture")["expected_channels"])
        self.assertEqual(0, server.active)

    def test_bulk_rejection_does_not_reject_categories(self):
        _, base = self.start_server(channels=30, categories=3, bulk_status=503)
        with self.assertRaises(HTTPError) as error:
            self.get(base, "/player_api.php?action=get_live_streams")
        self.assertEqual(503, error.exception.code)
        error.exception.close()
        self.assertEqual(10, len(self.get(base, "/player_api.php?action=get_live_streams&category_id=1")))

    def test_vod_and_series_streams_support_full_rejection_and_category_fallback(self):
        _, base = self.start_server(
            channels=12, categories=3, movies=31, movie_categories=4,
            series_items=19, series_categories=3,
            bulk_vod_status=503, bulk_series_status=503
        )
        for action in ("get_vod_streams", "get_series"):
            with self.assertRaises(HTTPError) as error:
                self.get(base, "/player_api.php?action=" + action)
            self.assertEqual(503, error.exception.code)
            error.exception.close()
        movies = [row for category in range(1, 5) for row in self.get(
            base, f"/player_api.php?action=get_vod_streams&category_id={category}")]
        series = [row for category in range(1, 4) for row in self.get(
            base, f"/player_api.php?action=get_series&category_id={category}")]
        self.assertEqual(31, len(movies))
        self.assertEqual(19, len(series))
        self.assertEqual(4, len(self.get(base, "/player_api.php?action=get_vod_categories")))
        self.assertEqual(3, len(self.get(base, "/player_api.php?action=get_series_categories")))

    def test_disconnect_is_a_truncated_document_and_incomplete_event(self):
        _, base = self.start_server(channels=20, categories=2, disconnect_after_rows=5)
        with self.assertRaises(json.JSONDecodeError):
            self.get(base, "/player_api.php?action=get_live_streams")
        events = self.get(base, "/events")["events"]
        self.assertEqual(5, events[-1]["rows"])
        self.assertFalse(events[-1]["completed"])

    def test_slow_response_allows_overlapping_http_requests(self):
        _, base = self.start_server(channels=20, categories=2, header_delay_ms=50)
        barrier = threading.Barrier(3)
        errors = []

        def fetch(action):
            try:
                barrier.wait(timeout=2)
                self.get(base, "/player_api.php?action=" + action)
            except Exception as error:
                errors.append(error)

        threads = [threading.Thread(target=fetch, args=(action,))
                   for action in ("get_live_categories", "get_live_streams")]
        for thread in threads:
            thread.start()
        barrier.wait(timeout=2)
        for thread in threads:
            thread.join(timeout=3)
        self.assertFalse(errors)
        self.assertTrue(all(not thread.is_alive() for thread in threads))
        self.assertEqual(2, self.get(base, "/events")["high_water"])


if __name__ == "__main__":
    unittest.main()
