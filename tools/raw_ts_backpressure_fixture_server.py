#!/usr/bin/env python3
"""One-pass raw MPEG-TS fixture with measurable socket-write backpressure.

The server does not loop or modify the supplied media. A paced source uses an
absolute byte deadline; time deliberately spent pacing is reported separately
from time blocked inside socket.sendall().
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import pathlib
import socket
import threading
import time
from collections import defaultdict
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit


TS_PACKET_BYTES = 188
DEFAULT_CHUNK_BYTES = 48_128  # 256 MPEG-TS packets
DEFAULT_WRITE_BLOCK_THRESHOLD_MS = 50.0
CHANNELS = {"1001", "1002"}


def scan_transport_stream(path: pathlib.Path) -> dict[str, int]:
    """Validate packet alignment, sync bytes, and per-PID payload continuity."""
    size = path.stat().st_size
    if size == 0 or size % TS_PACKET_BYTES:
        raise ValueError("MPEG-TS file must be nonempty and 188-byte packet aligned")

    last_counter: dict[int, int] = {}
    last_packet: dict[int, bytes] = {}
    packet_count = 0
    continuity_errors = 0
    bad_sync = 0
    with path.open("rb") as media:
        offset = 0
        while True:
            packet = media.read(TS_PACKET_BYTES)
            if not packet:
                break
            packet_count += 1
            if len(packet) != TS_PACKET_BYTES or packet[0] != 0x47:
                bad_sync += 1
                offset += TS_PACKET_BYTES
                continue

            pid = ((packet[1] & 0x1F) << 8) | packet[2]
            adaptation_control = (packet[3] >> 4) & 0x03
            counter = packet[3] & 0x0F
            has_payload = adaptation_control in (1, 3)
            has_discontinuity = (
                adaptation_control in (2, 3) and packet[4] > 0 and bool(packet[5] & 0x80)
            )
            if has_discontinuity:
                last_counter.pop(pid, None)
                last_packet.pop(pid, None)
            if has_payload and pid == 0x1FFF:
                # The null PID is not continuity-counter checked by the TS spec.
                offset += TS_PACKET_BYTES
                continue
            if has_payload and pid in last_counter:
                expected = (last_counter[pid] + 1) & 0x0F
                # A repeated counter is only legal for an exact duplicate packet.
                if counter != expected and not (
                    counter == last_counter[pid] and packet == last_packet[pid]
                ):
                    continuity_errors += 1
            if has_payload:
                last_counter[pid] = counter
                last_packet[pid] = packet
            offset += TS_PACKET_BYTES

    if bad_sync or continuity_errors:
        raise ValueError(
            f"invalid MPEG-TS packet stream: badSync={bad_sync} "
            f"continuityErrors={continuity_errors}"
        )
    return {"bytes": size, "packets": packet_count, "badSync": bad_sync,
            "continuityErrors": continuity_errors}


def sha256_file(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as media:
        for block in iter(lambda: media.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def paced_deadline_seconds(payload_bytes_sent: int, wire_rate_bps: float) -> float:
    if payload_bytes_sent < 0 or not math.isfinite(wire_rate_bps) or wire_rate_bps <= 0:
        raise ValueError("payload bytes must be nonnegative and wire rate must be finite and positive")
    return payload_bytes_sent * 8.0 / wire_rate_bps


def chunk_payload(data: bytes, chunk_bytes: int = DEFAULT_CHUNK_BYTES):
    if chunk_bytes <= 0 or chunk_bytes % TS_PACKET_BYTES:
        raise ValueError("chunk size must be a positive multiple of 188 bytes")
    for offset in range(0, len(data), chunk_bytes):
        yield data[offset:offset + chunk_bytes]


def json_bytes(value: object) -> bytes:
    return json.dumps(value, separators=(",", ":")).encode("utf-8")


def snapshot_stream(stream: dict[str, object]) -> dict[str, object]:
    snapshot = dict(stream)
    for key in ("blockedWrites", "failedWrites"):
        value = stream.get(key)
        if isinstance(value, list):
            snapshot[key] = [dict(item) for item in value]
    pending = stream.get("pendingSend")
    if isinstance(pending, dict):
        snapshot["pendingSend"] = dict(pending)
    return snapshot


def create_server(
    media_file: pathlib.Path,
    port: int,
    pace_multiplier: float,
    media_duration_seconds: float,
    send_buffer_bytes: int,
    send_timeout_seconds: float,
    write_block_threshold_ms: float = DEFAULT_WRITE_BLOCK_THRESHOLD_MS,
    chunk_bytes: int = DEFAULT_CHUNK_BYTES,
    continuation_file: pathlib.Path | None = None,
    continuation_duration_seconds: float | None = None,
) -> ThreadingHTTPServer:
    if not math.isfinite(pace_multiplier) or pace_multiplier <= 0:
        raise ValueError("pace multiplier must be finite and positive")
    if not math.isfinite(media_duration_seconds) or media_duration_seconds <= 0:
        raise ValueError("media duration must be finite and positive")
    if send_buffer_bytes <= 0:
        raise ValueError("send buffer size must be positive")
    if not math.isfinite(send_timeout_seconds) or send_timeout_seconds < 0:
        raise ValueError("send timeout must be finite and nonnegative")
    if not math.isfinite(write_block_threshold_ms) or write_block_threshold_ms < 0:
        raise ValueError("write block threshold must be finite and nonnegative")
    if chunk_bytes <= 0 or chunk_bytes % TS_PACKET_BYTES:
        raise ValueError("chunk size must be a positive multiple of 188 bytes")
    media_file = media_file.resolve(strict=True)
    media_scan = scan_transport_stream(media_file)
    media_sha256 = sha256_file(media_file)
    wire_rate_bps = media_scan["bytes"] * 8.0 / media_duration_seconds * pace_multiplier
    if (continuation_file is None) != (continuation_duration_seconds is None):
        raise ValueError("continuation file and duration must be supplied together")
    continuation_scan = None
    continuation_sha256 = None
    continuation_wire_rate_bps = None
    if continuation_file is not None:
        if not math.isfinite(continuation_duration_seconds) or continuation_duration_seconds <= 0:
            raise ValueError("continuation duration must be finite and positive")
        continuation_file = continuation_file.resolve(strict=True)
        continuation_scan = scan_transport_stream(continuation_file)
        continuation_sha256 = sha256_file(continuation_file)
        continuation_wire_rate_bps = (
            continuation_scan["bytes"] * 8.0 / continuation_duration_seconds * pace_multiplier
        )

    state_lock = threading.Lock()
    events: list[dict[str, object]] = []
    active_streams: dict[str, dict[str, object]] = {}
    next_id = 0
    next_request_index = 0
    server = ThreadingHTTPServer(("127.0.0.1", port), RawTsHandler)
    server.daemon_threads = True
    server.media_file = media_file
    server.media_scan = media_scan
    server.media_sha256 = media_sha256
    server.media_duration_seconds = media_duration_seconds
    server.pace_multiplier = pace_multiplier
    server.wire_rate_bps = wire_rate_bps
    server.continuation_file = continuation_file
    server.continuation_scan = continuation_scan
    server.continuation_sha256 = continuation_sha256
    server.continuation_duration_seconds = continuation_duration_seconds
    server.continuation_wire_rate_bps = continuation_wire_rate_bps
    server.send_buffer_bytes = send_buffer_bytes
    server.send_timeout_seconds = send_timeout_seconds
    server.write_block_threshold_ms = write_block_threshold_ms
    server.chunk_bytes = chunk_bytes
    server.state_lock = state_lock
    server.events = events
    server.active_streams = active_streams
    server.next_id = next_id
    server.next_request_index = next_request_index
    server.reset_wall_time = time.time()
    return server


class RawTsHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "RawTsBackpressureFixture/1.0"

    def log_message(self, *_args) -> None:
        pass

    def _reply(self, status: int, payload: bytes, content_type: str = "application/json") -> None:
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(payload)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("Connection", "close")
        self.end_headers()
        try:
            self.wfile.write(payload)
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError, OSError):
            pass
        self.close_connection = True

    def do_GET(self) -> None:
        parsed = urlsplit(self.path)
        if parsed.path == "/status":
            with self.server.state_lock:
                value = {
                    "ok": True,
                    "port": self.server.server_port,
                    "mediaFile": str(self.server.media_file),
                    "mediaDurationSeconds": self.server.media_duration_seconds,
                    "mediaScan": self.server.media_scan,
                    "mediaSha256": self.server.media_sha256,
                    "paceMultiplier": self.server.pace_multiplier,
                    "wireRateBps": self.server.wire_rate_bps,
                    "continuationFile": str(self.server.continuation_file) if self.server.continuation_file else None,
                    "continuationDurationSeconds": self.server.continuation_duration_seconds,
                    "continuationScan": self.server.continuation_scan,
                    "continuationSha256": self.server.continuation_sha256,
                    "continuationWireRateBps": self.server.continuation_wire_rate_bps,
                    "sendBufferBytesRequested": self.server.send_buffer_bytes,
                    "sendTimeoutSeconds": self.server.send_timeout_seconds,
                    "writeBlockThresholdMs": self.server.write_block_threshold_ms,
                    "activeStreams": len(self.server.active_streams),
                }
            return self._reply(200, json_bytes(value))

        if parsed.path == "/events":
            with self.server.state_lock:
                value = {
                    "settings": {
                        "mediaFile": str(self.server.media_file),
                        "mediaDurationSeconds": self.server.media_duration_seconds,
                        "mediaScan": self.server.media_scan,
                        "mediaSha256": self.server.media_sha256,
                        "paceMultiplier": self.server.pace_multiplier,
                        "wireRateBps": self.server.wire_rate_bps,
                        "continuationFile": str(self.server.continuation_file) if self.server.continuation_file else None,
                        "continuationDurationSeconds": self.server.continuation_duration_seconds,
                        "continuationScan": self.server.continuation_scan,
                        "continuationSha256": self.server.continuation_sha256,
                        "continuationWireRateBps": self.server.continuation_wire_rate_bps,
                        "sendBufferBytesRequested": self.server.send_buffer_bytes,
                        "sendTimeoutSeconds": self.server.send_timeout_seconds,
                        "writeBlockThresholdMs": self.server.write_block_threshold_ms,
                        "chunkBytes": self.server.chunk_bytes,
                    },
                    "events": list(self.server.events),
                    "activeStreams": [snapshot_stream(row) for row in self.server.active_streams.values()],
                }
            return self._reply(200, json_bytes(value))

        if parsed.path == "/control" and parsed.query == "reset=1":
            with self.server.state_lock:
                if self.server.active_streams:
                    return self._reply(409, json_bytes({"error": "stream active"}))
                self.server.events.clear()
                self.server.reset_wall_time = time.time()
                self.server.next_request_index = 0
                value = {"ok": True, "resetUnixSeconds": self.server.reset_wall_time}
            return self._reply(200, json_bytes(value))

        parts = parsed.path.strip("/").split("/")
        if len(parts) != 2 or parts[0] != "raw" or parts[1] not in {"1001.ts", "1002.ts"}:
            return self._reply(404, json_bytes({"error": "not found"}))
        channel = parts[1].removesuffix(".ts")
        if self.headers.get("Range"):
            return self._reply(416, b"", "text/plain")

        with self.server.state_lock:
            self.server.next_id += 1
            stream_id = f"{channel}-{self.server.next_id}"
            self.server.next_request_index += 1
            request_index = self.server.next_request_index
            use_continuation = (
                self.server.continuation_file is not None and request_index > 1
            )
            selected_file = self.server.continuation_file if use_continuation else self.server.media_file
            selected_duration = (
                self.server.continuation_duration_seconds if use_continuation
                else self.server.media_duration_seconds
            )
            selected_scan = self.server.continuation_scan if use_continuation else self.server.media_scan
            selected_wire_rate_bps = (
                self.server.continuation_wire_rate_bps if use_continuation
                else self.server.wire_rate_bps
            )
            started_unix = time.time()
            started_mono = time.monotonic()
            stream = {
                "streamId": stream_id,
                "channel": channel,
                "requestIndex": request_index,
                "mediaFile": str(selected_file),
                "mediaDurationSeconds": selected_duration,
                "mediaScan": selected_scan,
                "mediaSha256": self.server.continuation_sha256 if use_continuation else self.server.media_sha256,
                "wireRateBps": selected_wire_rate_bps,
                "startedUnixSeconds": started_unix,
                "startedMonotonicSeconds": started_mono,
                "elapsedSeconds": 0.0,
                "bytesSent": 0,
                "sendallCalls": 0,
                "totalSendallMs": 0.0,
                "maxSendallMs": 0.0,
                "blockedWriteCount": 0,
                "blockedWrites": [],
                "failedWrites": [],
                "pendingSend": None,
                "pacingWaitSeconds": 0.0,
                "connectionCloseReason": "active",
                "sendBufferBytesRequested": self.server.send_buffer_bytes,
                "sendTimeoutSeconds": self.server.send_timeout_seconds,
                "sendBufferBytesActual": None,
                "requestRange": None,
            }
            self.server.active_streams[stream_id] = snapshot_stream(stream)

        actual_send_buffer = None
        try:
            self.connection.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF,
                                       self.server.send_buffer_bytes)
            actual_send_buffer = self.connection.getsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF)
            if self.server.send_timeout_seconds > 0:
                self.connection.settimeout(self.server.send_timeout_seconds)
            with self.server.state_lock:
                stream["sendBufferBytesActual"] = actual_send_buffer
                self.server.active_streams[stream_id] = snapshot_stream(stream)
            self.send_response(200)
            self.send_header("Content-Type", "video/mp2t")
            self.send_header("Transfer-Encoding", "chunked")
            self.send_header("Cache-Control", "no-store")
            self.send_header("Connection", "close")
            self.end_headers()
            self.wfile.flush()

            began = time.monotonic()
            began_unix = time.time()
            with self.server.state_lock:
                stream["beganUnixSeconds"] = began_unix
                stream["beganElapsedMonotonic"] = began
                self.server.active_streams[stream_id] = snapshot_stream(stream)
            sent = 0
            reason = "complete"
            with selected_file.open("rb") as media:
                while True:
                    payload = media.read(self.server.chunk_bytes)
                    if not payload:
                        self.connection.sendall(b"0\r\n\r\n")
                        break
                    due_offset = paced_deadline_seconds(sent, selected_wire_rate_bps)
                    wait_seconds = due_offset - (time.monotonic() - began)
                    if wait_seconds > 0:
                        time.sleep(wait_seconds)
                        with self.server.state_lock:
                            stream["pacingWaitSeconds"] = float(stream["pacingWaitSeconds"]) + wait_seconds
                            self.server.active_streams[stream_id] = snapshot_stream(stream)
                    wire_chunk = f"{len(payload):X}\r\n".encode("ascii") + payload + b"\r\n"
                    send_started = time.monotonic()
                    pending = {
                        "startElapsedSeconds": send_started - began,
                        "startUnixSeconds": time.time(),
                        "payloadBytes": len(payload),
                        "wireBytes": len(wire_chunk),
                    }
                    with self.server.state_lock:
                        stream["pendingSend"] = pending
                        self.server.active_streams[stream_id] = snapshot_stream(stream)
                    try:
                        self.connection.sendall(wire_chunk)
                    except socket.timeout:
                        elapsed_ms = (time.monotonic() - send_started) * 1000.0
                        with self.server.state_lock:
                            stream["pendingSend"] = None
                            stream["sendallCalls"] = int(stream["sendallCalls"]) + 1
                            stream["totalSendallMs"] = float(stream["totalSendallMs"]) + elapsed_ms
                            stream["maxSendallMs"] = max(float(stream["maxSendallMs"]), elapsed_ms)
                            stream["partialChunkMayHaveBeenSent"] = True
                            failed = stream["failedWrites"]
                            assert isinstance(failed, list)
                            failed.append({**pending, "durationMs": elapsed_ms, "outcome": "send-timeout"})
                            if elapsed_ms >= self.server.write_block_threshold_ms:
                                stream["blockedWriteCount"] = int(stream["blockedWriteCount"]) + 1
                                blocked = stream["blockedWrites"]
                                assert isinstance(blocked, list)
                                blocked.append({**pending, "durationMs": elapsed_ms, "outcome": "send-timeout"})
                            self.server.active_streams[stream_id] = snapshot_stream(stream)
                        reason = "send-timeout"
                        break
                    except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError, OSError):
                        elapsed_ms = (time.monotonic() - send_started) * 1000.0
                        with self.server.state_lock:
                            stream["pendingSend"] = None
                            stream["sendallCalls"] = int(stream["sendallCalls"]) + 1
                            stream["totalSendallMs"] = float(stream["totalSendallMs"]) + elapsed_ms
                            stream["maxSendallMs"] = max(float(stream["maxSendallMs"]), elapsed_ms)
                            stream["partialChunkMayHaveBeenSent"] = True
                            failed = stream["failedWrites"]
                            assert isinstance(failed, list)
                            failed.append({**pending, "durationMs": elapsed_ms, "outcome": "client-disconnect"})
                            if elapsed_ms >= self.server.write_block_threshold_ms:
                                stream["blockedWriteCount"] = int(stream["blockedWriteCount"]) + 1
                                blocked = stream["blockedWrites"]
                                assert isinstance(blocked, list)
                                blocked.append({**pending, "durationMs": elapsed_ms, "outcome": "client-disconnect"})
                            self.server.active_streams[stream_id] = snapshot_stream(stream)
                        reason = "client-disconnect"
                        break
                    elapsed_ms = (time.monotonic() - send_started) * 1000.0
                    sent += len(payload)
                    with self.server.state_lock:
                        stream["pendingSend"] = None
                        stream["bytesSent"] = sent
                        stream["sendallCalls"] = int(stream["sendallCalls"]) + 1
                        stream["totalSendallMs"] = float(stream["totalSendallMs"]) + elapsed_ms
                        stream["maxSendallMs"] = max(float(stream["maxSendallMs"]), elapsed_ms)
                        if elapsed_ms >= self.server.write_block_threshold_ms:
                            stream["blockedWriteCount"] = int(stream["blockedWriteCount"]) + 1
                            blocked = stream["blockedWrites"]
                            assert isinstance(blocked, list)
                            blocked.append({**pending, "durationMs": elapsed_ms, "outcome": "sent"})
                        stream["elapsedSeconds"] = time.monotonic() - started_mono
                        self.server.active_streams[stream_id] = snapshot_stream(stream)
            with self.server.state_lock:
                stream["connectionCloseReason"] = reason
                self.server.active_streams[stream_id] = snapshot_stream(stream)
            self.close_connection = True
        except socket.timeout:
            with self.server.state_lock:
                stream["connectionCloseReason"] = "send-timeout"
                stream["partialChunkMayHaveBeenSent"] = True
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError, OSError):
            with self.server.state_lock:
                stream["connectionCloseReason"] = "client-disconnect"
        finally:
            with self.server.state_lock:
                stream["elapsedSeconds"] = time.monotonic() - started_mono
                stream["endedUnixSeconds"] = time.time()
                stream["sendBufferBytesActual"] = actual_send_buffer
                if stream["connectionCloseReason"] == "active":
                    stream["connectionCloseReason"] = reason if "reason" in locals() else "connection-error"
                self.server.active_streams.pop(stream_id, None)
                self.server.events.append({"event": "raw-stream", **stream})
            self.close_connection = True


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=18796)
    parser.add_argument("--media-file", required=True, type=pathlib.Path)
    parser.add_argument("--media-duration-seconds", type=float, required=True)
    parser.add_argument("--continuation-file", type=pathlib.Path)
    parser.add_argument("--continuation-duration-seconds", type=float)
    parser.add_argument("--pace-multiplier", type=float, default=1.0)
    parser.add_argument("--send-buffer-bytes", type=int, default=65_536)
    parser.add_argument("--send-timeout-seconds", type=float, default=0.0)
    parser.add_argument("--write-block-threshold-ms", type=float,
                        default=DEFAULT_WRITE_BLOCK_THRESHOLD_MS)
    parser.add_argument("--chunk-bytes", type=int, default=DEFAULT_CHUNK_BYTES)
    args = parser.parse_args()
    try:
        server = create_server(
            media_file=args.media_file,
            port=args.port,
            pace_multiplier=args.pace_multiplier,
            media_duration_seconds=args.media_duration_seconds,
            send_buffer_bytes=args.send_buffer_bytes,
            send_timeout_seconds=args.send_timeout_seconds,
            write_block_threshold_ms=args.write_block_threshold_ms,
            chunk_bytes=args.chunk_bytes,
            continuation_file=args.continuation_file,
            continuation_duration_seconds=args.continuation_duration_seconds,
        )
    except (OSError, ValueError) as exc:
        parser.error(str(exc))
    print(json.dumps({
        "listening": f"127.0.0.1:{args.port}",
        "mediaFile": str(server.media_file),
        "mediaDurationSeconds": server.media_duration_seconds,
        "mediaScan": server.media_scan,
        "mediaSha256": server.media_sha256,
        "paceMultiplier": server.pace_multiplier,
        "wireRateBps": server.wire_rate_bps,
        "continuationFile": str(server.continuation_file) if server.continuation_file else None,
        "continuationDurationSeconds": server.continuation_duration_seconds,
        "continuationScan": server.continuation_scan,
        "continuationSha256": server.continuation_sha256,
        "continuationWireRateBps": server.continuation_wire_rate_bps,
        "sendBufferBytesRequested": server.send_buffer_bytes,
        "sendTimeoutSeconds": server.send_timeout_seconds,
    }), flush=True)
    try:
        server.serve_forever()
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
