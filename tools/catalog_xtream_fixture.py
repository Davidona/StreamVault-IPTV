#!/usr/bin/env python3
"""Deterministic local Xtream fixture for Catalog production-activity checks.

Run this process on the host and point the Android debug build at
``http://10.0.2.2:8765``. The fixture intentionally models only the Xtream
player API fields consumed by the app; it never contacts a provider and logs
actions without query credentials.
"""

from __future__ import annotations

import argparse
import copy
import json
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse


DEFAULT_PORT = 8765
ASSET_BASE = f"http://10.0.2.2:{DEFAULT_PORT}/assets"

LIVE_CATEGORIES = [
    {"category_id": "101", "category_name": "Fixture Live", "parent_id": 0, "is_adult": 0},
]
VOD_CATEGORIES = [
    {"category_id": "201", "category_name": "Fixture Movies", "parent_id": 0, "is_adult": 0},
    {"category_id": "202", "category_name": "Pagination Movies", "parent_id": 0, "is_adult": 0},
]
SERIES_CATEGORIES = [
    {"category_id": "301", "category_name": "Fixture Series", "parent_id": 0, "is_adult": 0},
    {"category_id": "302", "category_name": "Pagination Series", "parent_id": 0, "is_adult": 0},
]


def configure_port(port: int) -> None:
    global ASSET_BASE
    ASSET_BASE = f"http://10.0.2.2:{port}/assets"


def live_streams() -> list[dict[str, object]]:
    return [
        {
            "num": 1,
            "name": "Fixture News",
            "stream_type": "live",
            "stream_id": 501,
            "stream_icon": f"{ASSET_BASE}/live.svg",
            "epg_channel_id": "fixture-news",
            "category_id": "101",
            "category_name": "Fixture Live",
            "container_extension": "ts",
            "tv_archive": 0,
            "is_adult": 0,
        },
    ]


def vod_streams() -> list[dict[str, object]]:
    streams = [
        {
            "num": 1,
            "name": "Fixture Movie One",
            "stream_type": "movie",
            "stream_id": 1001,
            "stream_icon": f"{ASSET_BASE}/movie-one.svg",
            "cover_big": f"{ASSET_BASE}/movie-one.svg",
            "added": "1704067200",
            "category_id": "201",
            "category_name": "Fixture Movies",
            "container_extension": "mp4",
            "rating": "8.4",
            "rating_5based": "4.2",
            "tmdb": "9001",
            "is_adult": 0,
        },
        {
            "num": 2,
            "name": "Fixture Movie Two",
            "stream_type": "movie",
            "stream_id": 1002,
            "stream_icon": f"{ASSET_BASE}/movie-two.svg",
            "cover_big": f"{ASSET_BASE}/movie-two.svg",
            "added": "1704153600",
            "category_id": "201",
            "category_name": "Fixture Movies",
            "container_extension": "mp4",
            "rating": "7.6",
            "rating_5based": "3.8",
            "tmdb": "9002",
            "is_adult": 0,
        },
    ]
    # Keep the first two named fixture entries stable for the detail/search
    # journey, then add a second category large enough to cross the Catalog's
    # 60-item selected-category page boundary.  The pagination names/category
    # deliberately omit "Fixture" so the five-result search assertion remains
    # focused on the acceptance entries above.
    for index in range(3, 64):
        streams.append(
            {
                "num": index,
                "name": f"Pagination Movie {index:02d}",
                "stream_type": "movie",
                "stream_id": 1000 + index,
                "stream_icon": f"{ASSET_BASE}/movie-two.svg",
                "cover_big": f"{ASSET_BASE}/movie-two.svg",
                "added": str(1704000000 - index),
                "category_id": "202",
                "category_name": "Pagination Movies",
                "container_extension": "mp4",
                "rating": "5.0",
                "rating_5based": "2.5",
                "tmdb": str(9000 + index),
                "is_adult": 0,
            }
        )
    return streams


def series_streams() -> list[dict[str, object]]:
    streams = [
        {
            "series_id": 2001,
            "name": "Fixture Series One",
            "cover": f"{ASSET_BASE}/series-one.svg",
            "cover_big": f"{ASSET_BASE}/series-one.svg",
            "movie_image": f"{ASSET_BASE}/series-one.svg",
            "plot": "A deterministic series fixture for Catalog acceptance journeys.",
            "description": "A deterministic series fixture for Catalog acceptance journeys.",
            "cast": "Fixture Actor",
            "director": "Fixture Director",
            "genre": "Drama",
            "releaseDate": "2024-01-01",
            "rating": "8.1",
            "rating_5based": "4.0",
            "category_id": "301",
            "category_name": "Fixture Series",
            "last_modified": "1704067200",
            "is_adult": 0,
        },
        {
            "series_id": 2002,
            "name": "Fixture Series Two",
            "cover": f"{ASSET_BASE}/series-two.svg",
            "cover_big": f"{ASSET_BASE}/series-two.svg",
            "movie_image": f"{ASSET_BASE}/series-two.svg",
            "plot": "A second deterministic series fixture.",
            "description": "A second deterministic series fixture.",
            "genre": "Comedy",
            "releaseDate": "2024-02-01",
            "rating": "7.4",
            "rating_5based": "3.7",
            "category_id": "301",
            "category_name": "Fixture Series",
            "last_modified": "1706745600",
            "is_adult": 0,
        },
    ]
    for index in range(3, 64):
        streams.append(
            {
                "series_id": 2000 + index,
                "name": f"Pagination Series {index:02d}",
                "cover": f"{ASSET_BASE}/series-two.svg",
                "cover_big": f"{ASSET_BASE}/series-two.svg",
                "movie_image": f"{ASSET_BASE}/series-two.svg",
                "plot": "A deterministic series pagination fixture.",
                "description": "A deterministic series pagination fixture.",
                "genre": "Drama",
                "releaseDate": "2023-01-01",
                "rating": "5.0",
                "rating_5based": "2.5",
                "category_id": "302",
                "category_name": "Pagination Series",
                "last_modified": str(1704000000 - index),
                "is_adult": 0,
            }
        )
    return streams


def movie_info(stream_id: int) -> dict[str, object]:
    stream = next(item for item in vod_streams() if item["stream_id"] == stream_id)
    return {
        "info": {
            "movie_image": stream["stream_icon"],
            "tmdb_id": int(stream["tmdb"]),
            "plot": "A deterministic movie fixture for Catalog acceptance journeys.",
            "cast": "Fixture Actor, Fixture Actor Two",
            "director": "Fixture Director",
            "genre": "Adventure",
            "releasedate": "2024-01-01",
            "rating": stream["rating"],
            "rating_5based": stream["rating_5based"],
            "duration_secs": 5400,
            "duration": "1h 30m",
            "backdrop_path": [stream["cover_big"]],
            "youtube_trailer": "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
        },
        "movie_data": {
            "stream_id": stream_id,
            "name": stream["name"],
            "added": stream["added"],
            "category_id": stream["category_id"],
            "container_extension": "mp4",
            "direct_source": "",
            "tmdb": stream["tmdb"],
            "is_adult": 0,
        },
    }


def series_info(series_id: int) -> dict[str, object]:
    series = next(item for item in series_streams() if item["series_id"] == series_id)
    return {
        "info": series,
        "seasons": [
            {
                "season_number": 1,
                "name": "Season 1",
                "cover": series["cover"],
                "air_date": "2024-01-01",
                "episode_count": 2,
            },
        ],
        "episodes": {
            "1": [
                {
                    "id": str(series_id * 10 + 1),
                    "episode_num": 1,
                    "title": f"{series['name']} - Pilot",
                    "container_extension": "mp4",
                    "added": "1704067200",
                    "season": 1,
                    "direct_source": "",
                    "info": {
                        "movie_image": series["cover"],
                        "plot": "The first fixture episode.",
                        "releasedate": "2024-01-01",
                        "duration_secs": 1800,
                        "duration": "30m",
                        "name": f"{series['name']} - Pilot",
                    },
                },
                {
                    "id": str(series_id * 10 + 2),
                    "episode_num": 2,
                    "title": f"{series['name']} - Second Signal",
                    "container_extension": "mp4",
                    "added": "1704153600",
                    "season": 1,
                    "direct_source": "",
                    "info": {
                        "movie_image": series["cover"],
                        "plot": "The second fixture episode.",
                        "releasedate": "2024-01-02",
                        "duration_secs": 1800,
                        "duration": "30m",
                        "name": f"{series['name']} - Second Signal",
                    },
                },
            ],
        },
    }


def payload(action: str | None, query: dict[str, list[str]]) -> object:
    if action is None:
        return {
            "user_info": {
                "username": "fixture",
                "password": "fixture",
                "auth": 1,
                "status": "Active",
                "exp_date": "4102444800",
                "is_trial": "0",
                "active_cons": "0",
                "created_at": "1704067200",
                "max_connections": "1",
                "allowed_output_formats": ["mp4", "ts"],
            },
            "server_info": {
                "url": "10.0.2.2",
                "port": str(DEFAULT_PORT),
                "server_protocol": "http",
                "api_version": "1.0",
                "timezone": "UTC",
                "timestamp_now": int(time.time()),
                "time_now": "2024-01-01 00:00:00",
            },
        }
    if action == "get_live_categories":
        return copy.deepcopy(LIVE_CATEGORIES)
    if action == "get_live_streams":
        return live_streams()
    if action == "get_vod_categories":
        return copy.deepcopy(VOD_CATEGORIES)
    if action == "get_vod_streams":
        return vod_streams()
    if action == "get_vod_info":
        return movie_info(int(query.get("vod_id", ["1001"])[0]))
    if action == "get_series_categories":
        return copy.deepcopy(SERIES_CATEGORIES)
    if action == "get_series":
        return series_streams()
    if action == "get_series_info":
        return series_info(int(query.get("series_id", ["2001"])[0]))
    if action in {"get_short_epg", "get_simple_data_table", "get_simple_data_table_by_stream_id"}:
        return {"epg_listings": []}
    return []


class Handler(BaseHTTPRequestHandler):
    server_version = "StreamVaultCatalogFixture/1.0"

    def log_message(self, fmt: str, *args: object) -> None:
        sys.stderr.write("[catalog-fixture] " + (fmt % args) + "\n")

    def do_GET(self) -> None:  # noqa: N802 (BaseHTTPRequestHandler API)
        parsed = urlparse(self.path)
        if parsed.path == "/player_api.php":
            query = parse_qs(parsed.query)
            action = query.get("action", [None])[0]
            action_name = action or "authenticate"
            self.log_message("action=%s", action_name)
            body = json.dumps(payload(action, query), separators=(",", ":")).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        if parsed.path == "/xmltv.php":
            self.log_message("xmltv")
            body = b"<?xml version=\"1.0\" encoding=\"UTF-8\"?><tv></tv>"
            self.send_response(200)
            self.send_header("Content-Type", "application/xml")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        if parsed.path.startswith("/assets/"):
            label = parsed.path.rsplit("/", 1)[-1].replace(".svg", "").replace("-", " ").title()
            body = (
                "<svg xmlns='http://www.w3.org/2000/svg' width='320' height='480'>"
                "<rect width='100%' height='100%' fill='#283044'/>"
                f"<text x='20' y='240' fill='white' font-size='24'>{label}</text></svg>"
            ).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "image/svg+xml")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        self.send_response(404)
        self.end_headers()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=DEFAULT_PORT)
    args = parser.parse_args()
    configure_port(args.port)
    server = ThreadingHTTPServer(("0.0.0.0", args.port), Handler)
    print(f"Catalog Xtream fixture listening on 0.0.0.0:{args.port}", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
