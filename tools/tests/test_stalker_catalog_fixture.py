import importlib.util
import json
import sys
import threading
import unittest
from pathlib import Path
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import urlopen


SPEC = importlib.util.spec_from_file_location(
    "stalker_catalog_fixture", Path(__file__).parents[1] / "stalker_catalog_fixture.py"
)
FIXTURE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = FIXTURE
SPEC.loader.exec_module(FIXTURE)


class StalkerCatalogFixtureTest(unittest.TestCase):
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

    def get(self, base, path, **params):
        query = urlencode({key: value for key, value in params.items() if value is not None})
        url = base + path + ("?" + query if query else "")
        with urlopen(url, timeout=5) as response:
            return json.load(response)

    def ordered_list(self, base, **params):
        params.setdefault("type", "vod")
        params.setdefault("action", "get_ordered_list")
        return self.get(base, "/server/load.php", **params)["js"]

    # -- pure scenario behavior -------------------------------------------------

    def test_category_partition_matches_wildcard_without_losing_rows(self):
        scenario = FIXTURE.Scenario(movies=103, categories=12)
        wildcard = list(scenario.wildcard_rows())
        partition = [row for category in range(1, 13) for row in scenario.category_rows(category)]
        self.assertEqual(wildcard, sorted(partition, key=lambda row: int(row["id"])))
        self.assertEqual([], list(scenario.category_rows(999)))
        self.assertEqual(103, len(wildcard))

    def test_adult_rows_partition_into_adult_category_and_wildcard_excludes_them(self):
        scenario = FIXTURE.Scenario(movies=100, categories=4, adult_category=9, adult_stride=10,
                                    wildcard_excludes_adult=1)
        adult = list(scenario.category_rows(9))
        self.assertEqual(10, len(adult))
        wildcard = list(scenario.wildcard_rows())
        self.assertEqual(90, len(wildcard))
        self.assertFalse(set(row["id"] for row in adult) & set(row["id"] for row in wildcard))
        metadata = scenario.metadata()
        self.assertEqual(10, metadata["expected_adult_rows"])
        self.assertEqual(90, metadata["expected_wildcard_rows"])
        self.assertEqual(7, metadata["pages_wildcard"])

    def test_page_index_variants(self):
        self.assertEqual(0, FIXTURE.page_index(0, 0, 5))
        self.assertEqual(2, FIXTURE.page_index(0, 2, 5))
        self.assertEqual(0, FIXTURE.page_index(1, 1, 5))
        self.assertEqual(3, FIXTURE.page_index(1, 4, 5))
        self.assertEqual(4, FIXTURE.page_index(2, 0, 5))
        self.assertEqual(0, FIXTURE.page_index(2, 1, 5))

    def test_hash_is_deterministic_and_seed_sensitive(self):
        scenario = FIXTURE.Scenario(movies=103, categories=12)
        self.assertEqual(scenario.metadata(), scenario.metadata())
        self.assertNotEqual(scenario.metadata()["payload_sha256"],
                            FIXTURE.Scenario(movies=103, categories=12, seed=2).metadata()["payload_sha256"])

    def test_invalid_scenarios_are_rejected(self):
        with self.assertRaises(ValueError):
            FIXTURE.Scenario(movies=0)
        with self.assertRaises(ValueError):
            FIXTURE.Scenario(p_base=3)
        with self.assertRaises(ValueError):
            FIXTURE.Scenario(categories=10, adult_category=5)
        with self.assertRaises(ValueError):
            FIXTURE.Scenario(wildcard_supported=2)

    # -- HTTP behavior ----------------------------------------------------------

    def test_handshake_profile_and_categories(self):
        _, base = self.start_server(movies=30, categories=3)
        handshake = self.get(base, "/server/load.php", type="stb", action="handshake")
        self.assertTrue(handshake["js"]["token"].startswith(FIXTURE.TOKEN))
        profile = self.get(base, "/server/load.php", type="stb", action="get_profile")
        self.assertEqual("Fixture", profile["js"]["name"])
        categories = self.get(base, "/server/load.php", type="vod", action="get_categories")["js"]
        self.assertEqual("*", categories[0]["id"])
        self.assertEqual(["1", "2", "3"], [category["id"] for category in categories[1:]])

    def test_one_based_paging_walks_every_row_once(self):
        _, base = self.start_server(movies=100, categories=2)
        page = self.ordered_list(base, category="1", p="1")
        self.assertEqual(50, page["total_items"])
        self.assertEqual(14, page["max_page_items"])
        self.assertEqual(4, FIXTURE.Scenario.page_count(50, 14))
        collected = []
        for number in range(1, 5):
            collected += self.ordered_list(base, category="1", p=str(number))["data"]
        self.assertEqual(50, len(collected))
        self.assertEqual(50, len({row["id"] for row in collected}))
        wildcard = []
        for number in range(1, 9):
            wildcard += self.ordered_list(base, p=str(number))["data"]
        self.assertEqual(100, len(wildcard))

    def test_stock_portal_treats_page_zero_as_last_page(self):
        _, base = self.start_server(movies=60, categories=3, p_base=2)
        last = self.ordered_list(base, category="1", p="0")
        expected_last = self.ordered_list(base, category="1", p="2")
        self.assertEqual(expected_last["data"], last["data"])
        self.assertEqual(20, last["total_items"])
        self.assertEqual(6, len(last["data"]))

    def test_zero_based_portal_uses_page_zero_for_first_page(self):
        _, base = self.start_server(movies=30, categories=3, p_base=0)
        first = self.ordered_list(base, category="1", p="0")
        self.assertEqual("100001", first["data"][0]["id"])
        self.assertEqual(10, first["total_items"])

    def test_wildcard_unsupported_returns_empty_but_categories_work(self):
        _, base = self.start_server(movies=30, categories=3, wildcard_supported=0)
        wildcard = self.ordered_list(base, p="1")
        self.assertEqual(0, wildcard["total_items"])
        self.assertEqual([], wildcard["data"])
        category = self.ordered_list(base, category="2", p="1")
        self.assertEqual(10, category["total_items"])

    def test_advertised_page_size_can_lie_about_real_page_size(self):
        _, base = self.start_server(movies=30, categories=3, advertised_page_size=100)
        page = self.ordered_list(base, category="1", p="1")
        self.assertEqual(100, page["max_page_items"])
        self.assertEqual(10, len(page["data"]))

    def test_sort_added_orders_newest_first(self):
        _, base = self.start_server(movies=30, categories=3)
        page = self.ordered_list(base, p="1", sortby="added")
        self.assertEqual("100030", page["data"][0]["id"])
        self.assertEqual("100029", page["data"][1]["id"])

    def test_throttle_injection_returns_429_with_retry_after(self):
        _, base = self.start_server(movies=30, categories=3, throttle_every=2)
        self.ordered_list(base, category="1", p="1")
        with self.assertRaises(HTTPError) as error:
            self.ordered_list(base, category="1", p="2")
        self.assertEqual(429, error.exception.code)
        self.assertEqual("1", error.exception.headers["Retry-After"])
        error.exception.close()
        self.assertEqual(1, self.get(base, "/events")["counters"]["throttled"])

    def test_server_error_injection_returns_503(self):
        _, base = self.start_server(movies=30, categories=3, server_error_every=1)
        with self.assertRaises(HTTPError) as error:
            self.ordered_list(base, category="1", p="1")
        self.assertEqual(503, error.exception.code)
        error.exception.close()

    def test_unstable_totals_change_advertised_count(self):
        _, base = self.start_server(movies=30, categories=3, unstable_totals_every=2)
        first = self.ordered_list(base, category="1", p="1")
        second = self.ordered_list(base, category="1", p="1")
        self.assertEqual(10, first["total_items"])
        self.assertEqual(17, second["total_items"])

    def test_repeated_page_mode_does_not_advance(self):
        _, base = self.start_server(movies=60, categories=2, repeat_page_every=2)
        page_two = self.ordered_list(base, category="1", p="2")
        page_three = self.ordered_list(base, category="1", p="3")
        self.assertEqual(page_two["data"], page_three["data"])

    def test_events_never_expose_credentials_and_report_rows(self):
        _, base = self.start_server(movies=30, categories=3)
        self.get(base, "/server/load.php", type="vod", action="get_ordered_list",
                 category="1", p="1", mac="00:1A:79:AA:BB:CC", password="do-not-log")
        events = self.get(base, "/events")
        serialized = json.dumps(events)
        self.assertNotIn("00:1A:79:AA:BB:CC", serialized)
        self.assertNotIn("do-not-log", serialized)
        self.assertEqual("unlabelled", events["events"][0]["run"])
        self.assertEqual(10, events["events"][1]["rows"])
        self.assertEqual(10, events["events"][1]["total_items"])
        self.assertTrue(events["events"][1]["completed"])
        self.assertEqual(1, events["counters"]["ordered_list_requests"])

    def test_synthetic_run_token_is_recorded(self):
        _, base = self.start_server(movies=30, categories=3)
        self.get(base, "/server/load.php", type="vod", action="get_ordered_list",
                 category="1", p="1", mac="02:00:00:00:00:01")
        events = self.get(base, "/events")
        self.assertEqual("02:00:00:00:00:01", events["events"][0]["run"])

    def test_slow_response_allows_overlapping_http_requests(self):
        _, base = self.start_server(movies=30, categories=3, header_delay_ms=50)
        barrier = threading.Barrier(3)
        errors = []

        def fetch(page):
            try:
                barrier.wait(timeout=2)
                self.ordered_list(base, category="1", p=str(page))
            except Exception as error:  # pragma: no cover - diagnostic capture
                errors.append(error)

        threads = [threading.Thread(target=fetch, args=(page,)) for page in (1, 2)]
        for thread in threads:
            thread.start()
        barrier.wait(timeout=2)
        for thread in threads:
            thread.join(timeout=3)
        self.assertFalse(errors)
        self.assertEqual(2, self.get(base, "/events")["high_water"])

    def test_live_genres_and_bulk_channels(self):
        _, base = self.start_server(movies=30, categories=3, channels=25, live_genres=3)
        genres = self.get(base, "/server/load.php", type="itv", action="get_genres")["js"]
        self.assertEqual(["1", "2", "3"], [genre["id"] for genre in genres])
        payload = self.get(base, "/server/load.php", type="itv", action="get_all_channels")["js"]
        self.assertEqual(25, payload["total_items"])
        self.assertEqual(25, len(payload["data"]))
        self.assertEqual("400001", payload["data"][0]["id"])
        self.assertEqual("1", payload["data"][0]["tv_genre_id"])
        metadata = self.get(base, "/fixture")
        self.assertEqual(25, metadata["expected_channels"])
        self.assertEqual(3, metadata["live_genres"])

    def test_live_paged_fallback_filters_by_genre(self):
        _, base = self.start_server(movies=30, categories=3, channels=25, live_genres=3)
        page = self.get(base, "/server/load.php", type="itv", action="get_ordered_list",
                        genre="2", p="1")["js"]
        self.assertEqual(8, page["total_items"])
        self.assertEqual(8, len(page["data"]))
        self.assertTrue(all(row["tv_genre_id"] == "2" for row in page["data"]))

    def test_fixture_metadata_is_exposed(self):
        _, base = self.start_server(movies=30, categories=3)
        metadata = self.get(base, "/fixture")
        self.assertEqual("streamvault-stalker-catalog-v1", metadata["fixture"])
        self.assertEqual("ONE_BASED", metadata["page_base"])
        self.assertEqual(30, metadata["expected_movies"])
        self.assertEqual(3, metadata["pages_wildcard"])
        self.assertIn("1", metadata["pages_by_category"])


if __name__ == "__main__":
    unittest.main()
