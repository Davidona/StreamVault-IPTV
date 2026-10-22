#!/usr/bin/env python3
"""Deterministic Stalker portal fixture for catalog-crawl measurements.

No provider account is required. The fixture emulates the legacy STB API
(`server/load.php`) for the catalog surface used by the app: handshake,
profile, VOD categories, and paged `get_ordered_list`. It models stock
Ministra behavior that the speed plan depends on:

- fixed page size (a server constant, not a request parameter),
- page-numbering variants (zero-based, one-based, stock `p=0` = last page),
- category-less (wildcard) whole-catalog listings,
- wildcard adult exclusion,
- 429/503 injection, unstable totals, repeated pages, and latency.

Events contain action/page/category/count/timing only, never credentials.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import re
import threading
import time
from collections import deque
from dataclasses import asdict, dataclass, field
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Iterator
from urllib.parse import parse_qs, urlparse

PAGE_BASE_NAMES = {0: "ZERO_BASED", 1: "ONE_BASED", 2: "STOCK_LAST_PAGE_ON_ZERO"}
SYNTHETIC_MAC = re.compile(r"02:00:00:[0-9a-fA-F:]{8}")
TOKEN = "fixture-token"


@dataclass(frozen=True)
class Scenario:
    movies: int = 10_000
    categories: int = 100
    channels: int = 50
    live_genres: int = 3
    page_size: int = 14
    p_base: int = 1
    advertised_page_size: int = 0
    wildcard_supported: int = 1
    adult_category: int = 0
    adult_stride: int = 10
    wildcard_excludes_adult: int = 0
    seed: int = 1
    header_delay_ms: int = 0
    page_delay_ms: int = 0
    throttle_every: int = 0
    server_error_every: int = 0
    unstable_totals_every: int = 0
    repeat_page_every: int = 0

    def __post_init__(self):
        if not 1 <= self.movies <= 500_000:
            raise ValueError("movies is outside fixture limits")
        if not 1 <= self.categories <= 10_000:
            raise ValueError("categories is outside fixture limits")
        if not 0 <= self.channels <= 100_000:
            raise ValueError("channels is outside fixture limits")
        if not 1 <= self.live_genres <= 100:
            raise ValueError("live_genres is outside fixture limits")
        if not 1 <= self.page_size <= 500:
            raise ValueError("page_size is outside fixture limits")
        if self.p_base not in PAGE_BASE_NAMES:
            raise ValueError("p_base must be 0, 1, or 2")
        if self.advertised_page_size and not 1 <= self.advertised_page_size <= 500:
            raise ValueError("advertised_page_size is outside fixture limits")
        if self.wildcard_supported not in (0, 1) or self.wildcard_excludes_adult not in (0, 1):
            raise ValueError("wildcard flags must be 0 or 1")
        if self.adult_category:
            if not self.categories < self.adult_category <= 10_000:
                raise ValueError("adult_category must be greater than categories")
            if not 2 <= self.adult_stride <= 1_000:
                raise ValueError("adult_stride is outside fixture limits")
            if self.wildcard_excludes_adult and self.movies // self.adult_stride == 0:
                raise ValueError("adult exclusion requires at least one adult row")
        for name, value in asdict(self).items():
            if value < 0:
                raise ValueError(name + " must be nonnegative")

    @property
    def page_base_name(self) -> str:
        return PAGE_BASE_NAMES[self.p_base]

    def category_of(self, number: int) -> int:
        if self.adult_category and number % self.adult_stride == 0:
            return self.adult_category
        return (number - 1) % self.categories + 1

    def row(self, number: int) -> dict:
        added = 1_700_000_000 + number
        return {
            "id": str(100_000 + number),
            "name": f"Benchmark Movie {number:06d}",
            "o_name": f"Benchmark Movie {number:06d} Original",
            "category_id": str(self.category_of(number)),
            "added": str(added),
            "cmd": f"/media/{100_000 + number}.mpg",
            "screenshot_uri": f"/misc/screenshots/{self.seed}/{100_000 + number}.jpg",
            "genres_str": "Benchmark",
            "is_movie": 1,
            "is_series": 0,
            "hd": 0,
            "lock": 0,
            "fav": 0,
            "has_files": 1,
        }

    def all_rows(self) -> Iterator[dict]:
        for number in range(1, self.movies + 1):
            yield self.row(number)

    def category_rows(self, category_id: int) -> Iterator[dict]:
        if not 1 <= category_id <= self.categories and category_id != self.adult_category:
            return
        for number in range(1, self.movies + 1):
            if self.category_of(number) == category_id:
                yield self.row(number)

    def wildcard_rows(self) -> Iterator[dict]:
        for number in range(1, self.movies + 1):
            category = self.category_of(number)
            if self.wildcard_excludes_adult and category == self.adult_category:
                continue
            yield self.row(number)

    def channel_row(self, number: int) -> dict:
        return {
            "id": str(400_000 + number),
            "name": f"Benchmark Channel {number:04d}",
            "number": str(number),
            "tv_genre_id": str((number - 1) % self.live_genres + 1),
            "logo": "",
            "cmd": f"ffmpeg http://127.0.0.1:1/iptv/{400_000 + number}",
            "use_http_tmp_link": 0,
            "xmltv_id": f"benchmark-{self.seed}-{number}",
        }

    def channel_rows(self, genre_id: int | None = None) -> Iterator[dict]:
        if genre_id is not None and not 1 <= genre_id <= self.live_genres:
            return
        for number in range(1, self.channels + 1):
            if genre_id is None or (number - 1) % self.live_genres + 1 == genre_id:
                yield self.channel_row(number)

    def advertised_size(self) -> int:
        return self.advertised_page_size or self.page_size

    @staticmethod
    def page_count(rows: int, page_size: int) -> int:
        return math.ceil(rows / page_size) if rows else 0

    def metadata(self) -> dict:
        digest = hashlib.sha256()
        size = 0
        visible = 0
        for row in self.all_rows():
            chunk = encode(row)
            digest.update(chunk)
            size += len(chunk)
            if not (self.wildcard_excludes_adult and row["category_id"] == str(self.adult_category)):
                visible += 1
        pages_by_category = {
            str(category): self.page_count(sum(1 for _ in self.category_rows(category)), self.page_size)
            for category in range(1, self.categories + 1)
        }
        if self.adult_category:
            pages_by_category[str(self.adult_category)] = self.page_count(
                sum(1 for _ in self.category_rows(self.adult_category)), self.page_size
            )
        return {
            "fixture": "streamvault-stalker-catalog-v1",
            "scenario": asdict(self),
            "page_base": self.page_base_name,
            "expected_movies": self.movies,
            "expected_channels": self.channels,
            "live_genres": self.live_genres,
            "expected_wildcard_rows": visible,
            "expected_adult_rows": self.movies // self.adult_stride if self.adult_category else 0,
            "pages_wildcard": self.page_count(visible, self.page_size),
            "pages_by_category": pages_by_category,
            "payload_sha256": digest.hexdigest(),
            "payload_bytes": size,
            "playback_media": False,
        }


def encode(value) -> bytes:
    return json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8")


@dataclass
class Counters:
    ordered_list_requests: int = 0
    throttled: int = 0
    server_errors: int = 0
    page_walks: int = 0
    last_rows_by_scope: dict = field(default_factory=dict)


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
        self.counters = Counters()
        self.epoch = time.monotonic()
        if event_log:
            event_log.parent.mkdir(parents=True, exist_ok=True)

    def record(self, event: dict) -> None:
        # Caller holds lock.
        self.events.append(event)
        if self.event_log:
            with self.event_log.open("a", encoding="utf-8") as output:
                output.write(json.dumps(event, separators=(",", ":")) + "\n")

    def begin(self, action: str, page: int | None, category_id: int | None, wildcard: bool, run: str):
        with self.lock:
            self.sequence += 1
            self.active += 1
            self.high_water = max(self.high_water, self.active)
            if action == "get_ordered_list":
                self.counters.ordered_list_requests += 1
                if page is not None and self.scenario.p_base == 1 and page == 1:
                    self.counters.page_walks += 1
            self.record({
                "event": "request_start", "request_id": self.sequence,
                "at_ms": round((time.monotonic() - self.epoch) * 1000, 3),
                "run": run, "action": action, "page": page,
                "category_id": category_id, "wildcard": wildcard,
                "active": self.active,
            })
            return self.sequence

    def finish(self, request_id: int, rows: int, status: int, started: float, completed: bool, total_items: int | None):
        with self.lock:
            self.active -= 1
            self.record({
                "event": "request_end", "request_id": request_id,
                "at_ms": round((time.monotonic() - self.epoch) * 1000, 3),
                "elapsed_ms": round((time.monotonic() - started) * 1000, 3),
                "rows": rows, "status": status, "completed": completed,
                "total_items": total_items,
            })

    def ordered_list(self, category_raw: str | None, page_raw: str | None, sortby: str | None):
        """Returns (status, payload, rows, total_items). Never called with the lock held."""
        scenario = self.scenario
        wildcard = not category_raw or category_raw == "*"
        category_id = None
        if not wildcard:
            try:
                category_id = int(category_raw)
            except ValueError:
                return 200, empty_page(scenario), 0, 0
        try:
            page = int(page_raw) if page_raw is not None else 1
        except ValueError:
            page = 1

        with self.lock:
            counters = self.counters
            request_number = counters.ordered_list_requests
            throttle = scenario.throttle_every and request_number % scenario.throttle_every == 0
            server_error = scenario.server_error_every and request_number % scenario.server_error_every == 0
            if throttle:
                counters.throttled += 1
            if server_error:
                counters.server_errors += 1
        if throttle:
            return 429, {"error": "configured_throttle"}, 0, None
        if server_error:
            return 503, {"error": "configured_failure"}, 0, None

        if wildcard and not scenario.wildcard_supported:
            return 200, empty_page(scenario), 0, 0

        if wildcard:
            rows = list(scenario.wildcard_rows())
        else:
            rows = list(scenario.category_rows(category_id))
        if sortby == "added":
            rows.sort(key=lambda row: (row["added"], row["id"]), reverse=True)

        total_items = len(rows)
        if scenario.unstable_totals_every and request_number % scenario.unstable_totals_every == 0:
            total_items += 7

        pages = Scenario.page_count(len(rows), scenario.page_size)
        index = page_index(scenario.p_base, page, pages)
        scope = "*" if wildcard else str(category_id)
        with self.lock:
            previous = self.counters.last_rows_by_scope.get(scope)
            if (
                scenario.repeat_page_every
                and index > 0
                and request_number % scenario.repeat_page_every == 0
                and previous
            ):
                slice_rows = previous
            else:
                start = index * scenario.page_size
                slice_rows = rows[start:start + scenario.page_size]
                self.counters.last_rows_by_scope[scope] = slice_rows

        if scenario.page_delay_ms:
            time.sleep(scenario.page_delay_ms / 1000)
        payload = {
            "js": {
                "total_items": total_items,
                "max_page_items": scenario.advertised_size(),
                "cur_page": index + 1,
                "data": slice_rows,
                "selected_item": 0,
            }
        }
        return 200, payload, len(slice_rows), total_items

    def ordered_list_live(self, genre_raw: str | None, page_raw: str | None):
        """Live fallback paging (`type=itv`, `genre` filter). No failure injection."""
        scenario = self.scenario
        genre_id = None
        if genre_raw and genre_raw != "*":
            try:
                genre_id = int(genre_raw)
            except ValueError:
                return 200, empty_page(scenario), 0, 0
        try:
            page = int(page_raw) if page_raw is not None else 1
        except ValueError:
            page = 1
        rows = list(scenario.channel_rows(genre_id))
        pages = Scenario.page_count(len(rows), scenario.page_size)
        index = page_index(scenario.p_base, page, pages)
        start = index * scenario.page_size
        slice_rows = rows[start:start + scenario.page_size]
        payload = {
            "js": {
                "total_items": len(rows),
                "max_page_items": scenario.advertised_size(),
                "cur_page": index + 1,
                "data": slice_rows,
                "selected_item": 0,
            }
        }
        return 200, payload, len(slice_rows), len(rows)


def page_index(p_base: int, page: int, pages: int) -> int:
    if p_base == 0:
        return max(page, 0)
    if p_base == 2 and page == 0:
        return max(pages - 1, 0)
    return max(page - 1, 0)


def empty_page(scenario: Scenario) -> dict:
    return {
        "js": {
            "total_items": 0,
            "max_page_items": scenario.advertised_size(),
            "cur_page": 1,
            "data": [],
            "selected_item": 0,
        }
    }


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_args):
        pass

    def json_response(self, body, status=200, headers: dict | None = None):
        raw = encode(body)
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(raw)))
        if headers:
            for name, value in headers.items():
                self.send_header(name, value)
        self.end_headers()
        self.wfile.write(raw)
        return len(raw)

    def do_GET(self):
        parsed = urlparse(self.path)
        self.dispatch(parsed.path, parse_qs(parsed.query))

    def do_POST(self):
        parsed = urlparse(self.path)
        length = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(length).decode("utf-8") if length else ""
        query = parse_qs(parsed.query)
        for name, values in parse_qs(body).items():
            query.setdefault(name, values)
        self.dispatch(parsed.path, query)

    def dispatch(self, path: str, query: dict):
        server: FixtureServer = self.server
        if path == "/fixture":
            self.json_response(server.metadata)
            return
        if path == "/events":
            with server.lock:
                snapshot = {
                    "events": list(server.events),
                    "high_water": server.high_water,
                    "counters": asdict(server.counters),
                }
            self.json_response(snapshot)
            return
        if path not in ("/server/load.php", "/portal.php"):
            self.json_response({"error": "not_found"}, 404)
            return

        action = query.get("action", [""])[0]
        if not action:
            self.json_response({"error": "missing_action"}, 400)
            return
        page = query.get("p", [None])[0]
        category_raw = query.get("category", [None])[0]
        wildcard = not category_raw or category_raw == "*"
        category_id = None if wildcard else _safe_int(category_raw)
        mac = query.get("mac", [""])[0]
        run = mac if SYNTHETIC_MAC.fullmatch(mac or "") else "unlabelled"
        started = time.monotonic()
        request_id = server.begin(action, _safe_int(page), category_id, wildcard, run)
        status = 200
        rows = 0
        total_items: int | None = None
        completed = False
        try:
            time.sleep(server.scenario.header_delay_ms / 1000)
            if action == "handshake":
                status, size = 200, self.json_response({
                    "js": {"token": f"{TOKEN}-{server.scenario.seed}", "random": f"{server.scenario.seed:08x}"}
                })
            elif action in ("get_profile", "get_account_info", "get_localization"):
                status, size = 200, self.json_response(self.profile_payload(action, server.scenario))
            elif action == "get_genres":
                status, size = 200, self.json_response({"js": self.live_genres_payload(server.scenario)})
            elif action == "get_all_channels":
                channel_rows = list(server.scenario.channel_rows())
                status, size = 200, self.json_response({"js": {"data": channel_rows, "total_items": len(channel_rows)}})
            elif action == "get_categories" and query.get("type", ["vod"])[0] == "vod":
                status, size = 200, self.json_response({"js": self.vod_categories(server.scenario)})
            elif action == "get_categories":
                status, size = 200, self.json_response({"js": []})
            elif action == "get_ordered_list" and query.get("type", ["vod"])[0] == "itv":
                status, payload, rows, total_items = server.ordered_list_live(
                    query.get("genre", [None])[0], page
                )
                size = self.json_response(payload, status)
            elif action == "get_ordered_list" and query.get("type", ["vod"])[0] == "vod":
                status, payload, rows, total_items = server.ordered_list(category_raw, page, query.get("sortby", [None])[0])
                headers = {"Retry-After": "1"} if status == 429 else None
                size = self.json_response(payload, status, headers)
            else:
                status, size = 200, self.json_response({"js": []})
            completed = True
        except (BrokenPipeError, ConnectionResetError):
            self.close_connection = True
        finally:
            server.finish(request_id, rows, status, started, completed, total_items)

    @staticmethod
    def profile_payload(action: str, scenario: Scenario) -> dict:
        if action == "get_localization":
            return {"js": {"locale": "en", "timezone": "UTC"}}
        if action == "get_account_info":
            return {"js": {"id": "1", "login": "fixture", "mac": "00:1A:79:12:34:56",
                           "phone": "", "status": 0, "tariff_plan": "fixture",
                           "end_date": "2030-01-01"}}
        return {"js": {"id": "1", "name": "Fixture", "mac": "00:1A:79:12:34:56",
                       "token": f"{TOKEN}-{scenario.seed}", "hd": 1, "status": 0,
                       "stb_type": "MAG254", "timezone": "UTC", "locale": "en"}}

    @staticmethod
    def live_genres_payload(scenario: Scenario) -> list[dict]:
        return [
            {"id": str(genre), "title": f"Genre {genre:02d}", "alias": f"genre-{genre:02d}"}
            for genre in range(1, scenario.live_genres + 1)
        ]

    @staticmethod
    def vod_categories(scenario: Scenario) -> list[dict]:
        categories = [{"id": "*", "title": "All", "alias": "*", "censored": 0}]
        for category in range(1, scenario.categories + 1):
            categories.append({
                "id": str(category), "title": f"Category {category:04d}",
                "alias": f"cat-{category:04d}", "censored": 0,
            })
        if scenario.adult_category:
            categories.append({
                "id": str(scenario.adult_category), "title": "Adult",
                "alias": "adult", "censored": 0,
            })
        return categories


def _safe_int(value: str | None) -> int | None:
    try:
        return int(value) if value is not None else None
    except ValueError:
        return None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bind", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8767)
    parser.add_argument("--events", type=Path)
    for field_name, default in asdict(Scenario()).items():
        parser.add_argument("--" + field_name.replace("_", "-"), type=int, default=default)
    args = parser.parse_args()
    scenario = Scenario(**{field_name: getattr(args, field_name) for field_name in asdict(Scenario())})
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
