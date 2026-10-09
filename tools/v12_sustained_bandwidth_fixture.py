"""Private Xtream-style V12 bandwidth fixture.

The media files are supplied outside the repository.  This server only exposes
the controlled catalogue/media routes needed by the opt-in normal UI test,
applies one aggregate delivery rate to every response, and records request
bytes/timing so the measured delivery can be compared with player telemetry.
"""

from __future__ import annotations

import argparse
import json
import pathlib
import re
import threading
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


parser = argparse.ArgumentParser()
parser.add_argument("--hls-root", type=pathlib.Path, required=True)
parser.add_argument("--progressive", type=pathlib.Path, required=True)
parser.add_argument("--state-dir", type=pathlib.Path, required=True)
parser.add_argument("--port", type=int, default=18786)
args = parser.parse_args()

HLS_ROOT = args.hls_root.resolve()
PROGRESSIVE = args.progressive.resolve()
STATE = args.state_dir.resolve()
STATE.mkdir(parents=True, exist_ok=True)
if not (HLS_ROOT / "long-master.m3u8").is_file():
    parser.error("--hls-root must contain long-master.m3u8")
if not PROGRESSIVE.is_file():
    parser.error("--progressive file is missing")


def extend_variant(name: str, copies: int = 480) -> None:
    """Create a long finite VOD playlist by repeating real fixture segments."""
    source = HLS_ROOT / name / "long.m3u8"
    target = HLS_ROOT / name / "v12.m3u8"
    lines = source.read_text(encoding="utf-8").splitlines()
    segment_names = [line for line in lines if line.endswith(".ts")]
    if not segment_names:
        raise SystemExit(f"no segments in {source}")
    header = [line for line in lines if not line.endswith(".ts") and line != "#EXT-X-ENDLIST"]
    output = header[:]
    for index in range(copies):
        if index and index % len(segment_names) == 0:
            output.append("#EXT-X-DISCONTINUITY")
        output.extend(["#EXTINF:2.000000,", segment_names[index % len(segment_names)]])
    output.append("#EXT-X-ENDLIST")
    target.write_text("\n".join(output) + "\n", encoding="utf-8")


for _variant in ("v0", "v1", "v2"):
    extend_variant(_variant)
(HLS_ROOT / "v12-master.m3u8").write_text(
    "\n".join([
        "#EXTM3U", "#EXT-X-VERSION:3", "#EXT-X-INDEPENDENT-SEGMENTS",
        '#EXT-X-STREAM-INF:BANDWIDTH=15075344,AVERAGE-BANDWIDTH=14439089,RESOLUTION=3840x2160,CODECS="avc1.42c033"',
        "v0/v12.m3u8",
        '#EXT-X-STREAM-INF:BANDWIDTH=5441472,AVERAGE-BANDWIDTH=5177833,RESOLUTION=1920x1080,CODECS="avc1.42c028"',
        "v1/v12.m3u8",
        '#EXT-X-STREAM-INF:BANDWIDTH=2675616,AVERAGE-BANDWIDTH=2614515,RESOLUTION=1280x720,CODECS="avc1.42c01f"',
        "v2/v12.m3u8", "",
    ]), encoding="utf-8")

MOVIES = {
    1201: ("V12 Adaptive Sustainable", "v12-master.m3u8", "m3u8"),
    1202: ("V12 Adaptive Unsustainable", "v12-master.m3u8", "m3u8"),
    1203: ("V12 Progressive Control", "progressive.mp4", "mp4"),
}

lock = threading.Lock()
rate_bps = 120_000_000
next_slot = 0.0
sequence = 0
events: list[dict[str, object]] = []


def record(event: str, **data: object) -> None:
    row = {"time": time.time(), "event": event, **data}
    with lock:
        events.append(row)
        (STATE / "events.jsonl").open("a", encoding="utf-8").write(json.dumps(row) + "\n")


def status() -> dict[str, object]:
    with lock:
        return {"rateBps": rate_bps, "nextSlot": next_slot, "eventCount": len(events)}


def shape(byte_count: int) -> None:
    global next_slot
    with lock:
        now = time.monotonic()
        due = max(now, next_slot) + byte_count * 8 / max(1, rate_bps)
        next_slot = due
    delay = due - time.monotonic()
    if delay > 0:
        time.sleep(delay)


def media_path(route: str) -> pathlib.Path | None:
    if route == "/asset/progressive.mp4":
        return PROGRESSIVE
    movie_match = re.fullmatch(r"/movie/validation/validation/(1201|1202|1203)\.(?:m3u8|mp4)", route)
    if movie_match:
        stream_id = int(movie_match.group(1))
        return HLS_ROOT / "v12-master.m3u8" if stream_id in (1201, 1202) else PROGRESSIVE
    if route.startswith("/asset/"):
        rel = pathlib.PurePosixPath(urllib.parse.unquote(route.removeprefix("/asset/")))
        target = (HLS_ROOT / pathlib.Path(*rel.parts)).resolve()
        if target.is_relative_to(HLS_ROOT) and target.is_file():
            return target
    return None


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *_args: object) -> None:
        pass

    def reply(self, body: bytes | dict[str, object], content_type: str = "application/json", status: int = 200) -> None:
        payload = body if isinstance(body, bytes) else json.dumps(body).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(payload)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        try:
            self.wfile.write(payload)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def do_GET(self) -> None:
        global rate_bps, next_slot, sequence
        parsed = urllib.parse.urlsplit(self.path)
        query = urllib.parse.parse_qs(parsed.query)
        route = parsed.path

        if route == "/control":
            if "rate" in query:
                rate_bps = max(1, int(query["rate"][0]))
                with lock:
                    next_slot = time.monotonic()
            if query.get("reset", ["0"])[0] == "1":
                with lock:
                    events.clear()
                (STATE / "events.jsonl").write_text("", encoding="utf-8")
            row = {"event": "control", "rateBps": rate_bps, "label": query.get("label", [""])[0]}
            record(**row)
            return self.reply({"rateBps": rate_bps})

        if route == "/status":
            return self.reply(status())
        if route == "/events":
            with lock:
                snapshot = {
                    "events": list(events),
                    "status": {"rateBps": rate_bps, "nextSlot": next_slot, "eventCount": len(events)},
                }
            return self.reply(snapshot)

        if route == "/player_api.php":
            action = query.get("action", [""])[0]
            if not action:
                body = {"user_info": {"username": "validation", "password": "validation", "auth": 1,
                                      "status": "Active", "max_connections": "5"},
                        "server_info": {"url": "10.0.2.2", "port": str(args.port),
                                        "server_protocol": "http", "timezone": "UTC",
                                        "timestamp_now": int(time.time())}}
            elif action == "get_vod_categories":
                body = [{"category_id": "1", "category_name": "V12 Bandwidth", "parent_id": 0}]
            elif action == "get_vod_streams":
                body = [self.movie(stream_id) for stream_id in MOVIES]
            elif action == "get_vod_info":
                stream_id = int(query.get("vod_id", ["0"])[0])
                body = {"info": {"name": MOVIES[stream_id][0], "duration_secs": 384,
                                  "duration": "00:06:24", "tmdb_id": 99999000 + stream_id},
                        "movie_data": self.movie(stream_id)}
            else:
                body = []
            record("api", action=action)
            return self.reply(body)

        target = media_path(route)
        if target is None:
            return self.reply(b"not found", "text/plain", 404)
        return self.stream(target, route)

    @staticmethod
    def movie(stream_id: int) -> dict[str, object]:
        name, _asset, extension = MOVIES[stream_id]
        direct = f"http://10.0.2.2:{args.port}/asset/{_asset}?expires={int(time.time()) + 7200}"
        return {"stream_id": stream_id, "name": name, "category_id": "1", "container_extension": extension,
                "rating": "8", "tmdb": str(99999000 + stream_id), "direct_source": direct}

    def stream(self, target: pathlib.Path, route: str) -> None:
        global sequence
        size = target.stat().st_size
        start, end, response_status = 0, size - 1, 200
        range_value = self.headers.get("Range")
        if range_value:
            match = re.fullmatch(r"bytes=(\d+)-(\d*)", range_value)
            if not match:
                return self.reply(b"invalid range", "text/plain", 416)
            start = int(match.group(1))
            end = min(int(match.group(2)) if match.group(2) else end, end)
            if start > end or start >= size:
                return self.reply(b"invalid range", "text/plain", 416)
            response_status = 206
        content_type = "application/vnd.apple.mpegurl" if target.suffix == ".m3u8" else (
            "video/mp4" if target.suffix == ".mp4" else "video/mp2t")
        self.send_response(response_status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(end - start + 1))
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Cache-Control", "no-store")
        if response_status == 206:
            self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        self.end_headers()
        began = time.monotonic()
        sent = 0
        try:
            with target.open("rb") as stream:
                stream.seek(start)
                remaining = end - start + 1
                while remaining:
                    block = stream.read(min(64 * 1024, remaining))
                    if not block:
                        break
                    shape(len(block))
                    self.wfile.write(block)
                    sent += len(block)
                    remaining -= len(block)
        except (BrokenPipeError, ConnectionResetError, OSError):
            pass
        with lock:
            sequence += 1
            request_id = sequence
        elapsed = max(0.001, time.monotonic() - began)
        record("media", id=request_id, path=route, status=response_status, range=range_value,
               bytes=sent, elapsedMs=round(elapsed * 1000, 1), measuredBps=round(sent * 8 / elapsed))


if __name__ == "__main__":
    record("started", port=args.port, hlsRoot=str(HLS_ROOT), progressive=str(PROGRESSIVE))
    ThreadingHTTPServer(("0.0.0.0", args.port), Handler).serve_forever()
