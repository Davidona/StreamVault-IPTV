import importlib.util
import json
import threading
import unittest
from pathlib import Path
from urllib.request import urlopen


MODULE_PATH = Path(__file__).parents[1] / "catalog_xtream_fixture.py"
SPEC = importlib.util.spec_from_file_location("catalog_xtream_fixture", MODULE_PATH)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"Unable to load fixture module from {MODULE_PATH}")
FIXTURE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(FIXTURE)


class CatalogXtreamFixtureTest(unittest.TestCase):
    def test_payload_contains_vod_and_series_acceptance_data(self):
        movies = FIXTURE.payload("get_vod_streams", {})
        series = FIXTURE.payload("get_series_info", {"series_id": ["2001"]})

        self.assertEqual([1001, 1002], [item["stream_id"] for item in movies])
        self.assertEqual("Fixture Series One", series["info"]["name"])
        self.assertEqual(2, len(series["episodes"]["1"]))

    def test_http_handler_serves_player_api_json(self):
        server = FIXTURE.ThreadingHTTPServer(("127.0.0.1", 0), FIXTURE.Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            url = f"http://127.0.0.1:{server.server_address[1]}/player_api.php?action=get_vod_streams"
            with urlopen(url, timeout=2) as response:
                self.assertEqual(200, response.status)
                body = json.load(response)
            self.assertEqual("Fixture Movie One", body[0]["name"])
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=2)


if __name__ == "__main__":
    unittest.main()
