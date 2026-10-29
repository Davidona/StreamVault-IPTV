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
import math
import pathlib
import threading
import time
from collections import defaultdict
from collections.abc import Callable
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
SEGMENT_CHUNK_BYTES = 64 * 1024
REFUSAL_MODES = {
    "off",
    "segment-404-once",
    "segment-403-rotate",
    "segment-403-permanent-after-start",
    "playlist-403-after-start",
}
REFUSAL_STATUS_CODES = {403, 404, 410}


def segment_delay_seconds(elapsed: float, period: float, duration: float, start: float) -> float:
    """Repeatable delivery gaps; default zero leaves the healthy fixture unchanged."""
    if period <= 0 or duration <= 0 or elapsed < start:
        return 0.0
    phase = (elapsed - start) % period
    return max(0.0, duration - phase)


def validated_hold_back_seconds(value: float | None) -> float | None:
    """Return a playlist hold-back that meets HLS's 3x target duration floor."""
    if value is None:
        return None
    if not math.isfinite(value):
        raise ValueError("hold-back must be finite")
    minimum = 3 * SEGMENT_SECONDS
    if value < minimum:
        raise ValueError(f"hold-back must be at least {minimum:g} seconds")
    return value


def paced_chunk_schedule(payload_bytes: int, cap_bits_per_second: int | None,
                         chunk_bytes: int = SEGMENT_CHUNK_BYTES) -> list[tuple[int, int, float]]:
    """Chunk offsets/sizes and elapsed deadlines for capped delivery.

    The first bounded chunk is due immediately. Each following deadline is
    based on total bytes sent, so scheduler delays do not accumulate as drift.
    """
    if payload_bytes < 0 or chunk_bytes <= 0:
        raise ValueError("payload size must be nonnegative and chunk size positive")
    if cap_bits_per_second is None:
        return [(offset, min(chunk_bytes, payload_bytes - offset), 0.0)
                for offset in range(0, payload_bytes, chunk_bytes)]
    if cap_bits_per_second <= 0:
        raise ValueError("delivery cap must be positive")
    chunks = []
    for offset in range(0, payload_bytes, chunk_bytes):
        size = min(chunk_bytes, payload_bytes - offset)
        deadline = 0.0 if offset == 0 else (offset + size) * 8 / cap_bits_per_second
        chunks.append((offset, size, deadline))
    return chunks


class RefusalInjector:
    """Deterministic role-scoped HTTP refusals for local HLS playback probes."""

    def __init__(self, mode: str = "off", after_seconds: float = 20.0,
                 period_seconds: float = 0.0, status_code: int | None = None) -> None:
        if mode not in REFUSAL_MODES:
            raise ValueError(f"unsupported refusal mode: {mode}")
        if not math.isfinite(after_seconds) or after_seconds < 0:
            raise ValueError("refusal start time must be finite and nonnegative")
        if not math.isfinite(period_seconds) or period_seconds < 0:
            raise ValueError("refusal period must be finite and nonnegative")
        if period_seconds and mode != "segment-404-once":
            raise ValueError("periodic refusals are supported only for segment-404-once")
        if status_code is not None and status_code not in REFUSAL_STATUS_CODES:
            raise ValueError("refusal status code must be one of 403, 404, or 410")
        if mode == "off" and status_code is not None:
            raise ValueError("status code cannot be set when refusal mode is off")
        self.mode = mode
        self.after_seconds = after_seconds
        self.period_seconds = period_seconds
        self.status_code = status_code
        self._lock = threading.Lock()
        self._attempts: dict[tuple[str, str, int, int], int] = defaultdict(int)
        self._failed_once: set[str] = set()
        self._failed_periods: set[tuple[str, int]] = set()
        self._periodically_refused_uris: set[tuple[str, int]] = set()
        self._token_generations: dict[tuple[str, int], int] = defaultdict(int)
        self._selected_sequences: dict[str, int] = {}

    def generation_for(self, channel: str, sequence_number: int) -> int:
        with self._lock:
            return self._token_generations[(channel, sequence_number)]

    def decide(self, role: str, channel: str, sequence_number: int,
               token_generation: int | None, elapsed_seconds: float) -> dict[str, int | str | None | bool]:
        """Return HTTP outcome and URI-attempt count without doing any I/O or sleeping."""
        generation = 0 if token_generation is None else token_generation
        key = (role, channel, sequence_number, generation)
        with self._lock:
            self._attempts[key] += 1
            attempts = self._attempts[key]
            status_code = 200
            refused = False
            selected_sequence = self._selected_sequences.get(channel)
            current_generation = self._token_generations[(channel, sequence_number)]

            if self.mode == "segment-404-once" and role == "media-segment" and \
                    elapsed_seconds >= self.after_seconds:
                if self.period_seconds:
                    period_index = int((elapsed_seconds - self.after_seconds) // self.period_seconds)
                    period_key = (channel, period_index)
                    uri_key = (channel, sequence_number)
                    if period_key not in self._failed_periods and uri_key not in self._periodically_refused_uris:
                        self._failed_periods.add(period_key)
                        self._periodically_refused_uris.add(uri_key)
                        refused = True
                else:
                    if channel not in self._failed_once:
                        self._failed_once.add(channel)
                        refused = True
                if refused:
                    status_code = self.status_code or 404

            elif self.mode == "segment-403-rotate" and role == "media-segment":
                if selected_sequence is None and elapsed_seconds >= self.after_seconds:
                    self._selected_sequences[channel] = sequence_number
                    selected_sequence = sequence_number
                    current_generation = 1
                    self._token_generations[(channel, sequence_number)] = current_generation
                    refused = True
                elif generation != current_generation:
                    refused = True
                if refused:
                    status_code = self.status_code or 403

            elif self.mode == "segment-403-permanent-after-start" and role == "media-segment" and \
                    elapsed_seconds >= self.after_seconds:
                refused = True
                status_code = self.status_code or 403

            elif self.mode == "playlist-403-after-start" and role == "playlist" and \
                    elapsed_seconds >= self.after_seconds:
                refused = True
                status_code = self.status_code or 403

            return {
                "statusCode": status_code,
                "attempts": attempts,
                "tokenGeneration": generation,
                "currentTokenGeneration": current_generation,
                "refused": refused,
                "selectedSequence": self._selected_sequences.get(channel),
            }

    def reset(self) -> None:
        with self._lock:
            self._attempts.clear()
            self._failed_once.clear()
            self._failed_periods.clear()
            self._periodically_refused_uris.clear()
            self._token_generations.clear()
            self._selected_sequences.clear()

    def snapshot(self) -> dict[str, object]:
        with self._lock:
            generations = [
                {"channel": channel, "sequence": sequence_number, "generation": generation}
                for (channel, sequence_number), generation in sorted(self._token_generations.items())
            ]
            selected = dict(self._selected_sequences)
        return {"selectedSequences": selected, "tokenGenerations": generations}


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
completed_runs: list[dict] = []
run_number = 1


def record(event: str, **values: object) -> None:
    row = {"time": time.time(), "event": event, **values}
    with lock:
        events.append(row)


def archive_current_run() -> None:
    """Move current events to the run archive before resetting its clock."""
    global run_number
    if events:
        completed_runs.append({"run": run_number, "events": list(events)})
    run_number += 1
    events.clear()


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


def empty_reply(handler: BaseHTTPRequestHandler, status_code: int) -> None:
    handler.send_response(status_code)
    handler.send_header("Content-Length", "0")
    handler.send_header("Cache-Control", "no-store")
    handler.end_headers()


def current_sequence(channel: str, window_size: int = WINDOW_SIZE) -> int:
    with lock:
        # Start with a complete six-segment live window; the server has no
        # future media and therefore never advertises segments ahead of wall
        # clock time.
        origin = origins.setdefault(channel, time.monotonic() - SEGMENT_SECONDS * window_size)
        wall_origins.setdefault(channel, time.time() - SEGMENT_SECONDS * window_size)
        offset = jump_offsets[channel]
    return max(0, int((time.monotonic() - origin) / SEGMENT_SECONDS) + offset)


def manifest(channel: str, host: str, port: int,
             hold_back_seconds: float | None = None,
             window_size: int = WINDOW_SIZE,
             token_generation_for_segment: Callable[[str, int], int] | None = None) -> str:
    # current_sequence is the next segment's start slot. Only publish segments
    # whose full duration has elapsed; otherwise PDT puts the live edge in the future.
    if window_size < 1:
        raise ValueError("window size must be positive")
    end = min(MAX_SEQUENCE - 1, max(0, current_sequence(channel, window_size) - 1))
    start = max(0, end - window_size + 1)
    with lock:
        wall_origin = wall_origins[channel]
    rows = [
        "#EXTM3U",
        "#EXT-X-VERSION:3",
        "#EXT-X-TARGETDURATION:2",
        f"#EXT-X-MEDIA-SEQUENCE:{start}",
    ]
    if hold_back_seconds is not None:
        rows.append(f"#EXT-X-SERVER-CONTROL:HOLD-BACK={hold_back_seconds:.1f}")
    for number in range(start, end + 1):
        program_date_time = time.strftime(
            "%Y-%m-%dT%H:%M:%S",
            time.gmtime(wall_origin + number * SEGMENT_SECONDS),
        ) + ".%03dZ" % int((wall_origin + number * SEGMENT_SECONDS) % 1 * 1000)
        segment_uri = f"http://{host}:{port}/asset/{channel}/{number:03d}.ts"
        if token_generation_for_segment is not None:
            generation = token_generation_for_segment(channel, number)
            segment_uri += f"?generation={generation}"
        rows.extend((
            f"#EXT-X-PROGRAM-DATE-TIME:{program_date_time}",
            "#EXTINF:2.000000,",
            segment_uri,
        ))
    return "\n".join(rows) + "\n"


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *_args: object) -> None:
        pass

    def do_GET(self) -> None:  # noqa: N802 - stdlib handler API
        global sequence
        public_host = getattr(self.server, "public_host", "10.0.2.2")
        parsed = urllib.parse.urlsplit(self.path)
        query = urllib.parse.parse_qs(parsed.query)

        if parsed.path == "/health":
            return json_reply(self, {"ok": True, "port": self.server.server_port})

        if parsed.path == "/control":
            jump_event: tuple[str, int] | None = None
            with lock:
                if query.get("reset", ["0"])[0] == "1":
                    archive_current_run()
                    origins.clear()
                    wall_origins.clear()
                    jump_offsets.update({key: 0 for key in CHANNELS})
                    self.server.stall_origin = time.monotonic()
                    self.server.refusal_injector.reset()
                selected = query.get("channel", list(CHANNELS))[0]
                if query.get("jump", ["0"])[0] == "1":
                    if selected not in CHANNELS:
                        selected = list(CHANNELS)[0]
                    jump_offsets[selected] += JUMP_SEGMENTS
                    origin = origins.setdefault(
                        selected, time.monotonic() - SEGMENT_SECONDS * self.server.window_size
                    )
                    jump_event = (selected, max(0, int((time.monotonic() - origin) / SEGMENT_SECONDS) + jump_offsets[selected]))
                    wall_origins[selected] = time.time() - jump_event[1] * SEGMENT_SECONDS
                state = {"origins": dict(origins), "jumpOffsets": dict(jump_offsets)}
            if jump_event is not None:
                record("window-jump", channel=jump_event[0], jumpSegments=JUMP_SEGMENTS,
                       sequence=jump_event[1])
            return json_reply(self, state)

        if parsed.path == "/events":
            with lock:
                response = {"run": run_number, "events": list(events),
                            "completedRuns": list(completed_runs),
                            "origins": dict(origins),
                            "jumpOffsets": dict(jump_offsets)}
            response["fixtureSettings"] = {
                "windowSegments": self.server.window_size,
                "holdBackSeconds": self.server.hold_back_seconds,
                "refusalMode": self.server.refusal_injector.mode,
                "refusalAfterSeconds": self.server.refusal_injector.after_seconds,
                "refusalPeriodSeconds": self.server.refusal_injector.period_seconds,
                "refusalStatusCode": self.server.refusal_injector.status_code,
            }
            response["refusalState"] = self.server.refusal_injector.snapshot()
            return json_reply(self, response)

        if parsed.path == "/player_api.php":
            action = query.get("action", [""])[0]
            if not action:
                body = {
                    "user_info": {"username": "v5-fixture", "password": "v5-fixture", "auth": 1,
                                   "status": "Active", "max_connections": "5",
                                   "allowed_output_formats": ["m3u8"]},
                    "server_info": {"url": public_host, "port": str(self.server.server_port),
                                     "server_protocol": "http", "timezone": "UTC",
                                     "timestamp_now": int(time.time())},
                }
            elif action == "get_live_categories":
                body = [{"category_id": "501", "category_name": "V5 Dynamic Live", "parent_id": 0}]
            elif action == "get_live_streams":
                body = [{"stream_id": int(channel), "name": name, "category_id": "501",
                         "epg_channel_id": f"V5_{channel}", "tv_archive": 0,
                         "container_extension": "m3u8",
                         "direct_source": f"http://{public_host}:{self.server.server_port}/live/v5/v5/{channel}.m3u8"}
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
            started = time.monotonic()
            elapsed = started - self.server.stall_origin
            sequence_number = current_sequence(channel, self.server.window_size)
            record("manifest", channel=channel, sequence=sequence_number)
            token_generation_for_segment = None
            if self.server.refusal_injector.mode == "segment-403-rotate":
                token_generation_for_segment = self.server.refusal_injector.generation_for
            body = manifest(channel, public_host, self.server.server_port,
                            self.server.hold_back_seconds, self.server.window_size,
                            token_generation_for_segment)
            media_sequence_start = next(
                int(line.split(":", 1)[1]) for line in body.splitlines()
                if line.startswith("#EXT-X-MEDIA-SEQUENCE:")
            )
            decision = self.server.refusal_injector.decide(
                "playlist", channel, media_sequence_start, None, elapsed
            )
            advertised_generations = [
                {"sequence": int(line.split("/")[-1].split(".")[0]),
                 "generation": int(line.rsplit("=", 1)[1])}
                for line in body.splitlines()
                if "/asset/" in line and "?generation=" in line
            ]
            if decision["statusCode"] != 200:
                empty_reply(self, int(decision["statusCode"]))
                record("http-response", role="playlist", channel=channel,
                       sequence=media_sequence_start, tokenGeneration=None,
                       statusCode=decision["statusCode"], attempts=decision["attempts"],
                       bytes=0, expectedBytes=0, elapsedSeconds=time.monotonic() - started,
                       advertisedTokenGenerations=[])
                return
            payload = body.encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/vnd.apple.mpegurl")
            self.send_header("Content-Length", str(len(payload)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            try:
                self.wfile.write(payload)
            except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError, OSError):
                pass
            record("http-response", role="playlist", channel=channel,
                   sequence=media_sequence_start, tokenGeneration=None,
                   statusCode=200, attempts=decision["attempts"],
                   bytes=len(payload), expectedBytes=len(payload),
                   elapsedSeconds=time.monotonic() - started,
                   advertisedTokenGenerations=advertised_generations)
            return

        if len(parts) == 3 and parts[0] == "asset" and parts[1] in CHANNELS:
            channel, name = parts[1], parts[2]
            if not name.endswith(".ts"):
                return json_reply(self, {"error": "not found"}, status=404)
            try:
                number = int(name[:-3])
            except ValueError:
                return json_reply(self, {"error": "not found"}, status=404)
            started = time.monotonic()
            elapsed = started - self.server.stall_origin
            try:
                token_generation = int(query.get("generation", ["0"])[0])
                if token_generation < 0:
                    raise ValueError
            except ValueError:
                empty_reply(self, 400)
                record("http-response", role="media-segment", channel=channel,
                       sequence=number, tokenGeneration=None, statusCode=400,
                       attempts=1, bytes=0, expectedBytes=0,
                       elapsedSeconds=time.monotonic() - started)
                return
            asset_number = number % MEDIA_ASSET_COUNT
            target = self.server.segment_root / f"segment_{asset_number:03d}.ts"
            if number < 0 or number >= MAX_SEQUENCE or not target.is_file():
                record("segment-missing", channel=channel, sequence=number)
                empty_reply(self, 404)
                decision = self.server.refusal_injector.decide(
                    "media-segment", channel, number, token_generation, elapsed
                )
                record("http-response", role="media-segment", channel=channel,
                       sequence=number, tokenGeneration=token_generation,
                       statusCode=404, attempts=decision["attempts"], bytes=0,
                       expectedBytes=0, elapsedSeconds=time.monotonic() - started)
                return
            decision = self.server.refusal_injector.decide(
                "media-segment", channel, number, token_generation, elapsed
            )
            if decision["statusCode"] != 200:
                empty_reply(self, int(decision["statusCode"]))
                record("http-response", role="media-segment", channel=channel,
                       sequence=number, tokenGeneration=token_generation,
                       statusCode=decision["statusCode"], attempts=decision["attempts"],
                       bytes=0, expectedBytes=0, elapsedSeconds=time.monotonic() - started,
                       currentTokenGeneration=decision["currentTokenGeneration"],
                       selectedSequence=decision["selectedSequence"])
                return
            with lock:
                sequence += 1
                request_id = sequence
            record("segment", id=request_id, role="media-segment", channel=channel,
                   sequence=number, tokenGeneration=token_generation,
                   attempts=decision["attempts"])
            delay_seconds = segment_delay_seconds(
                time.monotonic() - self.server.stall_origin,
                self.server.stall_period, self.server.stall_duration, self.server.stall_start,
            )
            if delay_seconds > 0:
                record("segment-delay", channel=channel, sequence=number, seconds=delay_seconds)
                time.sleep(delay_seconds)
            payload = target.read_bytes()
            self.send_response(200)
            self.send_header("Content-Type", "video/mp2t")
            self.send_header("Content-Length", str(len(payload)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            cap = self.server.segment_rate_cap_bps
            delivery_started = time.monotonic()
            delivered = 0
            try:
                for offset, size, deadline_offset in paced_chunk_schedule(len(payload), cap):
                    if cap is not None:
                        remaining = delivery_started + deadline_offset - time.monotonic()
                        if remaining > 0:
                            time.sleep(remaining)
                    self.wfile.write(payload[offset:offset + size])
                    delivered += size
                self.wfile.flush()
            except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError, OSError):
                # Cancellation can happen during a paced wait or a write. The
                # full Content-Length remains correct for successful responses;
                # this event records the partial body when the peer disconnects.
                pass
            record("segment-delivery", id=request_id, channel=channel, sequence=number,
                   role="media-segment", tokenGeneration=token_generation,
                   statusCode=200, attempts=decision["attempts"],
                   bytes=delivered, expectedBytes=len(payload),
                   elapsedSeconds=time.monotonic() - delivery_started,
                   rateCapBitsPerSecond=cap)
            record("http-response", role="media-segment", channel=channel,
                   sequence=number, tokenGeneration=token_generation,
                   statusCode=200, attempts=decision["attempts"], bytes=delivered,
                   expectedBytes=len(payload), elapsedSeconds=time.monotonic() - started,
                   currentTokenGeneration=decision["currentTokenGeneration"],
                   selectedSequence=decision["selectedSequence"])
            return

        return json_reply(self, {"error": "not found"}, status=404)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=18785)
    parser.add_argument("--public-host", choices=("10.0.2.2", "127.0.0.1", "localhost"),
                        default="10.0.2.2", help="Host advertised in fixture API and segment URLs")
    parser.add_argument("--segment-root", required=True, type=pathlib.Path)
    parser.add_argument("--segment-stall-period", type=float, default=0)
    parser.add_argument("--segment-stall-duration", type=float, default=0)
    parser.add_argument("--segment-stall-start", type=float, default=20)
    parser.add_argument("--hold-back-seconds", type=float)
    parser.add_argument("--window-segments", type=int, default=WINDOW_SIZE)
    parser.add_argument("--segment-rate-cap-bps", type=int)
    parser.add_argument("--refusal-mode", choices=sorted(REFUSAL_MODES), default="off")
    parser.add_argument("--refusal-after-seconds", type=float, default=20.0)
    parser.add_argument("--refusal-period-seconds", type=float, default=0.0)
    parser.add_argument("--refusal-status-code", type=int, choices=sorted(REFUSAL_STATUS_CODES))
    args = parser.parse_args()
    if (args.segment_stall_period < 0 or args.segment_stall_duration < 0
            or args.segment_stall_start < 0
            or args.segment_stall_duration > 0 and args.segment_stall_period <= args.segment_stall_duration):
        parser.error("stall period must exceed duration; all stall settings must be nonnegative")
    try:
        hold_back = validated_hold_back_seconds(args.hold_back_seconds)
    except ValueError as exc:
        parser.error(str(exc))
    if args.segment_rate_cap_bps is not None and args.segment_rate_cap_bps <= 0:
        parser.error("segment rate cap must be positive")
    if args.window_segments < 1:
        parser.error("window segment count must be positive")
    try:
        refusal_injector = RefusalInjector(
            mode=args.refusal_mode,
            after_seconds=args.refusal_after_seconds,
            period_seconds=args.refusal_period_seconds,
            status_code=args.refusal_status_code,
        )
    except ValueError as exc:
        parser.error(str(exc))
    if not all((args.segment_root / f"segment_{number:03d}.ts").is_file() for number in range(MEDIA_ASSET_COUNT)):
        raise SystemExit(f"expected {MEDIA_ASSET_COUNT} two-second segments in {args.segment_root}")
    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    server.public_host = args.public_host
    server.segment_root = args.segment_root.resolve()
    server.stall_period = args.segment_stall_period
    server.stall_duration = args.segment_stall_duration
    server.stall_start = args.segment_stall_start
    server.stall_origin = time.monotonic()
    server.hold_back_seconds = hold_back
    server.window_size = args.window_segments
    server.segment_rate_cap_bps = args.segment_rate_cap_bps
    server.refusal_injector = refusal_injector
    print(f"V5 dynamic-live fixture listening on 0.0.0.0:{args.port}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
