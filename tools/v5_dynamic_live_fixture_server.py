#!/usr/bin/env python3
"""Controlled dynamic HLS live-window fixture for V5 playback validation.

The fixture deliberately serves a non-ENDLIST sliding playlist from existing
two-second H.264/AAC transport-stream segments.  ``jump=1`` advances the
playlist media sequence beyond the old window without returning a transport
error; this is the controlled behind-live-window stimulus.  ``reset=1``
starts a new run.  The private segment directory is supplied at runtime and
is never copied into the repository.
"""

from __future__ import annotations

import argparse
import json
import pathlib
import threading
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


SEGMENT_SECONDS = 2.0
WINDOW_SIZE = 6
JUMP_SEGMENTS = 40
MEDIA_ASSET_COUNT = 300
# Keep media sequence numbers advancing beyond the private asset set.  The
# fixture reuses those finite segments modulo their count so a two-minute,
# two-channel validation never presents a stuck playlist at an artificial cap.
MAX_SEQUENCE = 10_000
CHANNELS = {
    "1001": "V5 Dynamic Live Alpha",
    "1002": "V5 Dynamic Live Beta",
}

lock = threading.Lock()
events: list[dict] = []
origins: dict[str, float] = {}
wall_origins: dict[str, float] = {}
jump_offsets: dict[str, int] = {key: 0 for key in CHANNELS}
sequence = 0


def record(event: str, **values: object) -> None:
    row = {"time": time.time(), "event": event, **values}
    with lock:
        events.append(row)


def json_reply(handler: BaseHTTPRequestHandler, body: object, status: int = 200) -> None:
    payload = json.dumps(body).encode("utf-8")
    handler.send_response(status)
    handler.send_header("Content-Type", "application/json")
    handler.send_header("Content-Length", str(len(payload)))
    handler.send_header("Cache-Control", "no-store")
    handler.end_headers()
    try:
        handler.wfile.write(payload)
    except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
        pass


def text_reply(handler: BaseHTTPRequestHandler, body: str, content_type: str) -> None:
    payload = body.encode("utf-8")
    handler.send_response(200)
    handler.send_header("Content-Type", content_type)
    handler.send_header("Content-Length", str(len(payload)))
    handler.send_header("Cache-Control", "no-store")
    handler.end_headers()
    try:
        handler.wfile.write(payload)
    except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
        pass


def current_sequence(channel: str) -> int:
    with lock:
        # Start with a complete six-segment live window; the server has no
        # future media and therefore never advertises segments ahead of wall
        # clock time.
        origin = origins.setdefault(channel, time.monotonic() - SEGMENT_SECONDS * WINDOW_SIZE)
        wall_origins.setdefault(channel, time.time() - SEGMENT_SECONDS * WINDOW_SIZE)
        offset = jump_offsets[channel]
    return max(0, int((time.monotonic() - origin) / SEGMENT_SECONDS) + offset)


def manifest(channel: str, host: str, port: int) -> str:
    end = min(MAX_SEQUENCE - 1, current_sequence(channel))
    start = max(0, end - WINDOW_SIZE + 1)
    with lock:
        wall_origin = wall_origins[channel]
    rows = [
        "#EXTM3U",
        "#EXT-X-VERSION:3",
        "#EXT-X-TARGETDURATION:2",
        f"#EXT-X-MEDIA-SEQUENCE:{start}",
    ]
    for number in range(start, end + 1):
        program_date_time = time.strftime(
            "%Y-%m-%dT%H:%M:%S",
            time.gmtime(wall_origin + number * SEGMENT_SECONDS),
        ) + ".%03dZ" % int((wall_origin + number * SEGMENT_SECONDS) % 1 * 1000)
        rows.extend((
            f"#EXT-X-PROGRAM-DATE-TIME:{program_date_time}",
            "#EXTINF:2.000000,",
            f"http://{host}:{port}/asset/{channel}/{number:03d}.ts",
        ))
    return "\n".join(rows) + "\n"


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *_args: object) -> None:
        pass

    def do_GET(self) -> None:  # noqa: N802 - stdlib handler API
        global sequence
        parsed = urllib.parse.urlsplit(self.path)
        query = urllib.parse.parse_qs(parsed.query)

        if parsed.path == "/health":
            return json_reply(self, {"ok": True, "port": self.server.server_port})

        if parsed.path == "/control":
            jump_event: tuple[str, int] | None = None
            with lock:
                if query.get("reset", ["0"])[0] == "1":
                    events.clear()
                    origins.clear()
                    wall_origins.clear()
                    jump_offsets.update({key: 0 for key in CHANNELS})
                selected = query.get("channel", list(CHANNELS))[0]
                if query.get("jump", ["0"])[0] == "1":
                    if selected not in CHANNELS:
                        selected = list(CHANNELS)[0]
                    jump_offsets[selected] += JUMP_SEGMENTS
                    origin = origins.setdefault(selected, time.monotonic() - SEGMENT_SECONDS * WINDOW_SIZE)
                    jump_event = (selected, max(0, int((time.monotonic() - origin) / SEGMENT_SECONDS) + jump_offsets[selected]))
                    wall_origins[selected] = time.time() - jump_event[1] * SEGMENT_SECONDS
                state = {"origins": dict(origins), "jumpOffsets": dict(jump_offsets)}
            if jump_event is not None:
                record("window-jump", channel=jump_event[0], jumpSegments=JUMP_SEGMENTS,
                       sequence=jump_event[1])
            return json_reply(self, state)

        if parsed.path == "/events":
            with lock:
                return json_reply(self, {"events": list(events), "origins": dict(origins),
                                         "jumpOffsets": dict(jump_offsets)})

        if parsed.path == "/player_api.php":
            action = query.get("action", [""])[0]
            if not action:
                body = {
                    "user_info": {"username": "v5-fixture", "password": "v5-fixture", "auth": 1,
                                   "status": "Active", "max_connections": "5",
                                   "allowed_output_formats": ["m3u8"]},
                    "server_info": {"url": "10.0.2.2", "port": str(self.server.server_port),
                                     "server_protocol": "http", "timezone": "UTC",
                                     "timestamp_now": int(time.time())},
                }
            elif action == "get_live_categories":
                body = [{"category_id": "501", "category_name": "V5 Dynamic Live", "parent_id": 0}]
            elif action == "get_live_streams":
                body = [{"stream_id": int(channel), "name": name, "category_id": "501",
                         "epg_channel_id": f"V5_{channel}", "tv_archive": 0,
                         "container_extension": "m3u8",
                         "direct_source": f"http://10.0.2.2:{self.server.server_port}/live/v5/v5/{channel}.m3u8"}
                        for channel, name in CHANNELS.items()]
            elif action in ("get_short_epg", "get_simple_data_table"):
                body = {"epg_listings": []}
            else:
                body = []
            record("api", action=action or "account")
            return json_reply(self, body)

        parts = [urllib.parse.unquote(part) for part in parsed.path.split("/") if part]
        # Xtream URL normalization may rebuild the direct source as
        # /live/{username}/{password}/{stream_id}.m3u8, so accept the
        # credential path while identifying the channel only by its id.
        if len(parts) >= 2 and parts[0] == "live" and parts[-1].endswith(".m3u8"):
            channel = parts[-1].removesuffix(".m3u8")
            if channel not in CHANNELS:
                return json_reply(self, {"error": "unknown channel"}, status=404)
            record("manifest", channel=channel, sequence=current_sequence(channel))
            return text_reply(self, manifest(channel, "10.0.2.2", self.server.server_port),
                              "application/vnd.apple.mpegurl")

        if len(parts) == 3 and parts[0] == "asset" and parts[1] in CHANNELS:
            channel, name = parts[1], parts[2]
            if not name.endswith(".ts"):
                return json_reply(self, {"error": "not found"}, status=404)
            try:
                number = int(name[:-3])
            except ValueError:
                return json_reply(self, {"error": "not found"}, status=404)
            asset_number = number % MEDIA_ASSET_COUNT
            target = self.server.segment_root / f"segment_{asset_number:03d}.ts"
            if number < 0 or number >= MAX_SEQUENCE or not target.is_file():
                record("segment-missing", channel=channel, sequence=number)
                return json_reply(self, {"error": "not found"}, status=404)
            with lock:
                sequence += 1
                request_id = sequence
            record("segment", id=request_id, channel=channel, sequence=number)
            payload = target.read_bytes()
            self.send_response(200)
            self.send_header("Content-Type", "video/mp2t")
            self.send_header("Content-Length", str(len(payload)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            try:
                self.wfile.write(payload)
            except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
                pass
            return

        return json_reply(self, {"error": "not found"}, status=404)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=18785)
    parser.add_argument("--segment-root", required=True, type=pathlib.Path)
    args = parser.parse_args()
    if not all((args.segment_root / f"segment_{number:03d}.ts").is_file() for number in range(MEDIA_ASSET_COUNT)):
        raise SystemExit(f"expected {MEDIA_ASSET_COUNT} two-second segments in {args.segment_root}")
    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    server.segment_root = args.segment_root.resolve()
    print(f"V5 dynamic-live fixture listening on 0.0.0.0:{args.port}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
