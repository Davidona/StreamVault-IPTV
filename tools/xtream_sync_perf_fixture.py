#!/usr/bin/env python3
"""Streaming, deterministic Xtream fixture for sync measurements.

No provider account is required. Payloads are generated a row at a time; events
contain action/scope/count/timing only, never URLs or query credentials.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import threading
import time
from collections import deque
from dataclasses import asdict, dataclass
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Iterator
from urllib.parse import parse_qs, urlparse


@dataclass(frozen=True)
class Scenario:
    channels: int = 10_000
    categories: int = 100
    movies: int = 0
    movie_categories: int = 0
    series_items: int = 0
    series_categories: int = 0
    seed: int = 1
    header_delay_ms: int = 0
    category_delay_ms: int = 0
    bytes_per_second: int = 0
    bulk_status: int = 200
    bulk_vod_status: int = 200
    bulk_series_status: int = 200
    disconnect_after_rows: int = 0
    malformed_every: int = 0
    throttle_every: int = 0

    def __post_init__(self):
        if not 1 <= self.channels <= 500_000 or not 1 <= self.categories <= 10_000:
            raise ValueError("channels/categories are outside fixture limits")
        if not 0 <= self.movies <= 500_000 or not 0 <= self.series_items <= 500_000:
            raise ValueError("movies/series_items are outside fixture limits")
        if not 0 <= self.movie_categories <= 10_000 or not 0 <= self.series_categories <= 10_000:
            raise ValueError("movie/series categories are outside fixture limits")
        if self.movies and not self.movie_categories:
            raise ValueError("movie_categories is required when movies are configured")
        if self.series_items and not self.series_categories:
            raise ValueError("series_categories is required when series items are configured")
        for name, value in asdict(self).items():
            if value < 0:
                raise ValueError(name + " must be nonnegative")
        if any(not 100 <= status <= 599 for status in
               (self.bulk_status, self.bulk_vod_status, self.bulk_series_status)):
            raise ValueError("bulk statuses must be HTTP statuses")

    def rows(self, category_id: int | None = None) -> Iterator[dict]:
        # Jump directly to the selected category; no full-catalog scan per request.
        start = 1 if category_id is None else category_id
        step = 1 if category_id is None else self.categories
        if category_id is not None and not 1 <= category_id <= self.categories:
            return
        for number in range(start, self.channels + 1, step):
            category = (number - 1) % self.categories + 1
            yield {
                "num": number,
                "name": f"Benchmark Channel {number:06d} HD",
                "stream_id": None if self.malformed_every and number % self.malformed_every == 0 else 100_000 + number,
                "stream_type": "live",
                "stream_icon": "",
                "epg_channel_id": f"benchmark-{self.seed}-{number}",
                "category_id": str(category),
                "container_extension": "m3u8",
                "tv_archive": int(number % 7 == 0),
                "tv_archive_duration": 3 if number % 7 == 0 else 0,
                "is_adult": 0,
            }

    def chunks(self, category_id: int | None = None) -> Iterator[bytes]:
        yield b"["
        for index, row in enumerate(self.rows(category_id)):
            if index:
                yield b","
            yield encode(row)
        yield b"]"

    def movie_rows(self, category_id: int | None = None) -> Iterator[dict]:
        if not self.movie_categories:
            return
        start = 1 if category_id is None else category_id
        step = 1 if category_id is None else self.movie_categories
        if category_id is not None and not 1 <= category_id <= self.movie_categories:
            return
        for number in range(start, self.movies + 1, step):
            category = (number - 1) % self.movie_categories + 1
            yield {
                "num": number,
                "name": f"Benchmark Movie {number:06d}",
                "stream_type": "movie",
                "stream_id": 200_000 + number,
                "stream_icon": "",
                "category_id": str(category),
                "container_extension": "mp4",
                "added": str(1_700_000_000 + number),
                "rating": "7.5",
                "rating_5based": "3.75",
                "tmdb": str(500_000 + number),
                "youtube_trailer": "",
                "is_adult": 0,
            }

    def series_rows(self, category_id: int | None = None) -> Iterator[dict]:
        if not self.series_categories:
            return
        start = 1 if category_id is None else category_id
        step = 1 if category_id is None else self.series_categories
        if category_id is not None and not 1 <= category_id <= self.series_categories:
            return
        for number in range(start, self.series_items + 1, step):
            category = (number - 1) % self.series_categories + 1
            yield {
                "series_id": 300_000 + number,
                "name": f"Benchmark Series {number:06d}",
                "cover": "",
                "cover_big": "",
                "category_id": str(category),
                "genre": "Benchmark",
                "rating": "7.0",
                "episode_run_time": "45",
                "last_modified": str(1_700_000_000 + number),
                "is_adult": 0,
            }

    @staticmethod
    def payload_info(rows: Iterator[dict]) -> tuple[str, int]:
        digest = hashlib.sha256()
        digest.update(b"[")
        size = 1
        first = True
        for row in rows:
            chunk = (b"" if first else b",") + encode(row)
            first = False
            digest.update(chunk)
            size += len(chunk)
        digest.update(b"]")
        size += 1
        return digest.hexdigest(), size

    def metadata(self) -> dict:
        digest = hashlib.sha256()
        size = 0
        for chunk in self.chunks():
            digest.update(chunk)
            size += len(chunk)
        movie_hash, movie_size = self.payload_info(self.movie_rows())
        series_hash, series_size = self.payload_info(self.series_rows())
        return {
            "fixture": "streamvault-xtream-sync-performance-v1",
            "scenario": asdict(self),
            "expected_channels": self.channels - (self.channels // self.malformed_every if self.malformed_every else 0),
            "expected_movies": self.movies,
            "expected_series": self.series_items,
            "payload_sha256": digest.hexdigest(),
            "payload_bytes": size,
            "movie_payload_sha256": movie_hash,
            "movie_payload_bytes": movie_size,
            "series_payload_sha256": series_hash,
            "series_payload_bytes": series_size,
            "playback_media": False,
        }


def encode(value) -> bytes:
    return json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8")


class FixtureServer(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, address, scenario: Scenario, event_log: Path | None = None):
        super().__init__(address, Handler)
        self.scenario = scenario
        self.metadata = scenario.metadata()
        self.events = deque(maxlen=20_000)
        self.event_log = event_log
        self.lock = threading.Lock()
        self.sequence = 0
        self.active = 0
        self.high_water = 0
        self.live_requests = 0
        self.epoch = time.monotonic()
        if event_log:
            event_log.parent.mkdir(parents=True, exist_ok=True)

    def record(self, event: dict) -> None:
        # Caller holds lock.
        self.events.append(event)
        if self.event_log:
            with self.event_log.open("a", encoding="utf-8") as output:
                output.write(json.dumps(event, separators=(",", ":")) + "\n")

    def begin(self, action: str, category_id: int | None, run: str) -> tuple[int, int]:
        with self.lock:
            self.sequence += 1
            self.active += 1
            self.high_water = max(self.high_water, self.active)
            if action == "get_live_streams":
                self.live_requests += 1
            self.record({
                "event": "request_start", "request_id": self.sequence,
                "at_ms": round((time.monotonic() - self.epoch) * 1000, 3),
                "run": run, "action": action, "category_id": category_id,
                "active": self.active,
            })
            return self.sequence, self.live_requests

    def finish(self, request_id: int, rows: int, size: int, status: int, started: float, completed: bool):
        with self.lock:
            self.active -= 1
            self.record({
                "event": "request_end", "request_id": request_id,
                "at_ms": round((time.monotonic() - self.epoch) * 1000, 3),
                "elapsed_ms": round((time.monotonic() - started) * 1000, 3),
                "rows": rows, "bytes": size, "status": status,
                "completed": completed,
            })


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_args):
        pass

    def json_response(self, body, status=200):
        raw = encode(body)
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)
        return len(raw)

    def do_GET(self):
        parsed = urlparse(self.path)
        query = parse_qs(parsed.query)
        server: FixtureServer = self.server
        if parsed.path == "/fixture":
            self.json_response(server.metadata)
            return
        if parsed.path == "/events":
            with server.lock:
                snapshot = {"events": list(server.events), "high_water": server.high_water}
            self.json_response(snapshot)
            return
        if parsed.path != "/player_api.php":
            self.json_response({"error": "not_found"}, 404)
            return
        action = query.get("action", ["authenticate"])[0]
        if action not in {"authenticate", "get_live_categories", "get_live_streams",
                          "get_vod_categories", "get_vod_streams", "get_series_categories", "get_series"}:
            self.json_response({"error": "unsupported_action"}, 400)
            return
        try:
            category_id = int(query["category_id"][0]) if "category_id" in query else None
        except ValueError:
            self.json_response({"error": "invalid_category"}, 400)
            return
        username = query.get("username", [""])[0]
        # Only synthetic benchmark tokens can appear in artifacts.
        run = username if re.fullmatch(r"bench_[a-zA-Z0-9_]{1,100}", username) else "unlabelled"
        started = time.monotonic()
        request_id, request_number = server.begin(action, category_id, run)
        size = rows = 0
        status = 200
        completed = False
        try:
            scenario = server.scenario
            time.sleep(scenario.header_delay_ms / 1000)
            if action == "authenticate":
                size = self.json_response({
                    "user_info": {"auth": 1, "status": "Active", "max_connections": "4",
                                  "allowed_output_formats": ["m3u8", "ts"]},
                    "server_info": {"version": "fixture-v1", "server_protocol": "http"},
                })
            elif action == "get_live_categories":
                time.sleep(scenario.category_delay_ms / 1000)
                size = self.json_response([
                    {"category_id": str(index), "category_name": f"Benchmark Group {index:04d}",
                     "parent_id": 0, "is_adult": 0}
                    for index in range(1, scenario.categories + 1)
                ])
            elif action == "get_live_streams":
                status = scenario.bulk_status if category_id is None else 200
                if scenario.throttle_every and request_number % scenario.throttle_every == 0:
                    status = 429
                if status != 200:
                    size = self.json_response({"error": "configured_failure"}, status)
                else:
                    self.send_response(200)
                    self.send_header("Content-Type", "application/json; charset=utf-8")
                    # HTTP/1.0 close-delimited response supports incremental generation.
                    self.end_headers()
                    delivery_started = time.monotonic()
                    self.wfile.write(b"[")
                    size = 1
                    for row in scenario.rows(category_id):
                        chunk = (b"," if rows else b"") + encode(row)
                        self.wfile.write(chunk)
                        size += len(chunk)
                        rows += 1
                        if rows % 128 == 0:
                            self.wfile.flush()
                        if scenario.bytes_per_second:
                            wait = size / scenario.bytes_per_second - (time.monotonic() - delivery_started)
                            if wait > 0:
                                time.sleep(wait)
                        if scenario.disconnect_after_rows and rows == scenario.disconnect_after_rows:
                            self.wfile.flush()
                            self.close_connection = True
                            return
                    self.wfile.write(b"]")
                    self.wfile.flush()
                    size += 1
            elif action in {"get_vod_categories", "get_series_categories"}:
                time.sleep(scenario.category_delay_ms / 1000)
                category_count = (scenario.movie_categories if action == "get_vod_categories"
                                  else scenario.series_categories)
                size = self.json_response([
                    {"category_id": str(index),
                     "category_name": f"Benchmark {('Movie' if action == 'get_vod_categories' else 'Series')} Group {index:04d}",
                     "parent_id": 0, "is_adult": 0}
                    for index in range(1, category_count + 1)
                ])
            elif action in {"get_vod_streams", "get_series"}:
                is_movie = action == "get_vod_streams"
                status = (scenario.bulk_vod_status if is_movie else scenario.bulk_series_status) \
                    if category_id is None else 200
                if status != 200:
                    size = self.json_response({"error": "configured_failure"}, status)
                else:
                    source = scenario.movie_rows(category_id) if is_movie else scenario.series_rows(category_id)
                    self.send_response(200)
                    self.send_header("Content-Type", "application/json; charset=utf-8")
                    self.end_headers()
                    self.wfile.write(b"[")
                    size = 1
                    for row in source:
                        chunk = (b"," if rows else b"") + encode(row)
                        self.wfile.write(chunk)
                        size += len(chunk)
                        rows += 1
                        if rows % 128 == 0:
                            self.wfile.flush()
                    self.wfile.write(b"]")
                    self.wfile.flush()
                    size += 1
            else:
                # EPG remains outside the catalog benchmark.
                size = self.json_response([])
            completed = True
        except (BrokenPipeError, ConnectionResetError):
            self.close_connection = True
        finally:
            server.finish(request_id, rows, size, status, started, completed)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bind", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8766)
    parser.add_argument("--events", type=Path)
    for field, default in asdict(Scenario()).items():
        parser.add_argument("--" + field.replace("_", "-"), type=int, default=default)
    args = parser.parse_args()
    scenario = Scenario(**{field: getattr(args, field) for field in asdict(Scenario())})
    server = FixtureServer((args.bind, args.port), scenario, args.events)
    print(json.dumps({"listening_port": server.server_address[1], **server.metadata}), flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
