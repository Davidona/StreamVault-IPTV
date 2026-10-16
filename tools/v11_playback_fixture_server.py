#!/usr/bin/env python3
"""Local Xtream-style auth rollover fixture for V11 playback instrumentation."""

from __future__ import annotations

import argparse
import json
import threading
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


OLD = ("old-user", "old-pass")
NEXT = ("next-user", "next-pass")
THIRD = ("third-user", "third-pass")
LABELS = {OLD: "old", NEXT: "next", THIRD: "third"}
MOVIES = {
    801: ("V11 HLS Adaptive Fixture", "m3u8", "99999801"),
    802: ("V11 Progressive Tracks Fixture", "mp4", "99999802"),
}

lock = threading.Lock()
accepted = {OLD}
events: list[dict] = []
active: dict[int, dict] = {}
sequence = 0
peak_active = 0
hls_root: Path
progressive_file: Path


def label_for(pair: tuple[str, str]) -> str:
    return LABELS.get(pair, "invalid")


def record(event: str, **data: object) -> None:
    row = {"time": time.time(), "event": event, **data}
    with lock:
        events.append(row)


def movie(stream_id: int) -> dict:
    name, extension, tmdb = MOVIES[stream_id]
    return {
        "stream_id": stream_id,
        "name": name,
        "category_id": "1",
        "container_extension": extension,
        "rating": "8",
        "tmdb": tmdb,
    }


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *_args: object) -> None:
        pass

    def reply(self, body: bytes | dict, content_type: str = "application/json", status: int = 200) -> None:
        payload = body if isinstance(body, bytes) else json.dumps(body).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(payload)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        try:
            self.wfile.write(payload)
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
            pass

    @staticmethod
    def credential(query: dict[str, list[str]]) -> tuple[str, str]:
        return query.get("username", [""])[0], query.get("password", [""])[0]

    def do_GET(self) -> None:
        global accepted, sequence, peak_active
        parsed = urllib.parse.urlsplit(self.path)
        query = urllib.parse.parse_qs(parsed.query)

        if parsed.path == "/control":
            selected = set(query.get("accept", ["old"])[0].split(","))
            pairs = {name: pair for pair, name in LABELS.items()}
            with lock:
                accepted = {pairs[name] for name in selected if name in pairs}
                if query.get("reset", ["0"])[0] == "1":
                    events.clear()
                    peak_active = len(active)
            record("control", accepted=sorted(selected))
            return self.reply({"accepted": sorted(selected)})

        if parsed.path == "/events":
            with lock:
                snapshot = {"events": list(events), "active": dict(active), "peakActive": peak_active}
            return self.reply(snapshot)

        if parsed.path == "/player_api.php":
            pair = self.credential(query)
            with lock:
                allowed = pair in accepted
            record("api", credential=label_for(pair), action=query.get("action", [""])[0])
            if not allowed:
                return self.reply({"user_info": {"auth": 0, "status": "Expired"}}, status=401)
            action = query.get("action", [""])[0]
            if not action:
                data = {
                    "user_info": {
                        "username": pair[0], "password": pair[1], "auth": 1,
                        "status": "Active", "max_connections": "5",
                    },
                    "server_info": {
                        "url": "10.0.2.2", "port": "18774", "server_protocol": "http",
                        "timezone": "UTC", "timestamp_now": int(time.time()),
                    },
                }
            elif action == "get_vod_categories":
                data = [{"category_id": "1", "category_name": "V11 Auth Rollover", "parent_id": 0}]
            elif action == "get_vod_streams":
                data = [movie(stream_id) for stream_id in MOVIES]
            elif action == "get_vod_info":
                stream_id = int(query.get("vod_id", ["0"])[0])
                if stream_id not in MOVIES:
                    return self.reply({}, status=404)
                data = {
                    "info": {
                        "name": MOVIES[stream_id][0], "duration_secs": 240,
                        "duration": "00:04:00", "tmdb_id": int(MOVIES[stream_id][2]),
                    },
                    "movie_data": movie(stream_id),
                }
            else:
                data = []
            return self.reply(data)

        path_parts = [urllib.parse.unquote(part) for part in parsed.path.split("/") if part]
        if len(path_parts) < 4 or path_parts[0] != "movie":
            return self.reply({"error": "not found"}, status=404)

        pair = (path_parts[1], path_parts[2])
        credential = label_for(pair)
        suffix = path_parts[3:]
        with lock:
            allowed = pair in accepted
        if not allowed:
            record("media", credential=credential, path="/".join(suffix), status=401)
            return self.reply(b"expired credentials", "text/plain", 401)

        if suffix == ["801.m3u8"]:
            target = hls_root / "master.m3u8"
        elif suffix == ["802.mp4"]:
            target = progressive_file
        else:
            target = (hls_root / Path(*suffix)).resolve()
            if not target.is_relative_to(hls_root.resolve()):
                return self.reply({"error": "not found"}, status=404)
        if not target.is_file():
            record("media", credential=credential, path="/".join(suffix), status=404)
            return self.reply({"error": "not found"}, status=404)

        raw_size = target.stat().st_size
        start = 0
        end = raw_size - 1
        status = 200
        range_value = self.headers.get("Range")
        if range_value:
            import re

            match = re.fullmatch(r"bytes=(\d+)-(\d*)", range_value)
            if not match:
                return self.reply(b"invalid range", "text/plain", 416)
            start = int(match.group(1))
            end = min(int(match.group(2)) if match.group(2) else end, end)
            if start > end:
                return self.reply(b"invalid range", "text/plain", 416)
            status = 206

        with lock:
            sequence += 1
            request_id = sequence
            active[request_id] = {"credential": credential, "path": "/".join(suffix)}
            peak_active = max(peak_active, len(active))
        record("open", id=request_id, credential=credential, path="/".join(suffix), range=range_value, status=status)

        if target.suffix == ".m3u8":
            content_type = "application/vnd.apple.mpegurl"
        elif target.suffix == ".ts":
            content_type = "video/mp2t"
        else:
            content_type = "video/mp4"
        length = end - start + 1
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(length))
        self.send_header("Cache-Control", "no-store")
        self.send_header("Accept-Ranges", "bytes")
        if status == 206:
            self.send_header("Content-Range", f"bytes {start}-{end}/{raw_size}")
        self.end_headers()
        sent = 0
        try:
            with target.open("rb") as media:
                media.seek(start)
                remaining = length
                while remaining:
                    block = media.read(min(64 * 1024, remaining))
                    if not block:
                        break
                    self.wfile.write(block)
                    sent += len(block)
                    remaining -= len(block)
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
            pass
        finally:
            with lock:
                active.pop(request_id, None)
                active_snapshot = dict(active)
            record("close", id=request_id, credential=credential, sent=sent, active=active_snapshot)


def main() -> None:
    global hls_root, progressive_file
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=18774)
    parser.add_argument("--hls-root", type=Path, required=True)
    parser.add_argument("--progressive-file", type=Path, required=True)
    args = parser.parse_args()
    hls_root = args.hls_root.resolve()
    progressive_file = args.progressive_file.resolve()
    if not (hls_root / "master.m3u8").is_file() or not progressive_file.is_file():
        raise SystemExit("fixture media files are missing")
    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    server.daemon_threads = True
    record("started", port=args.port)
    server.serve_forever()


if __name__ == "__main__":
    main()
