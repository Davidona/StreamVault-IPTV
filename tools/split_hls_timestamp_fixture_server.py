#!/usr/bin/env python3
"""Split audio/video MPEG-TS HLS fixture for timestamp-adjuster wait tests.

The fixture expects pre-generated ``video/segment_NNN.ts`` and
``audio/segment_NNN.ts`` files plus ``media.json`` from
prepare_split_hls_timestamp_fixture.py. It serves an explicit-codec master
playlist and independent, aligned live media playlists. In delayed-valid mode
the first requested video segment sends PAT/PMT and paced null packets before
the original first video PES/PTS. In missing-primary mode it keeps the
initially selected video sequence active with PAT/PMT and null packets but
never emits a video PES; later sequences are playable. In
missing-primary-all mode every video sequence remains without a PES. Audio
segments remain available normally.

This is test tooling only; no provider URLs or credentials are used.
"""

from __future__ import annotations

import argparse
import json
import math
import pathlib
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit

PACKET_SIZE = 188


def ts_packets(payload: bytes) -> list[bytes]:
    if len(payload) % PACKET_SIZE:
        raise ValueError("MPEG-TS asset size is not a multiple of 188")
    packets = [payload[i:i + PACKET_SIZE] for i in range(0, len(payload), PACKET_SIZE)]
    if any(packet[0] != 0x47 for packet in packets):
        raise ValueError("MPEG-TS asset contains a bad sync byte")
    return packets


def _payload(packet: bytes) -> tuple[int, bool, bytes] | None:
    pid = ((packet[1] & 0x1F) << 8) | packet[2]
    start = bool(packet[1] & 0x40)
    control = (packet[3] >> 4) & 0x03
    if control not in (1, 3):
        return None
    offset = 4
    if control == 3:
        offset += 1 + packet[4]
    if offset >= PACKET_SIZE:
        return None
    return pid, start, packet[offset:]


def _psi_section(packets: list[bytes], pid: int, table_id: int) -> tuple[bytes, list[int]]:
    data = bytearray()
    packet_indices: list[int] = []
    expected: int | None = None
    for index, packet in enumerate(packets):
        info = _payload(packet)
        if info is None or info[0] != pid:
            continue
        _, start, payload = info
        if start:
            pointer = payload[0]
            payload = payload[1 + pointer:]
            data.clear()
            packet_indices.clear()
            expected = None
        if not payload:
            continue
        packet_indices.append(index)
        data.extend(payload)
        if len(data) >= 3 and expected is None:
            if data[0] != table_id:
                data.clear()
                packet_indices.clear()
                continue
            expected = 3 + (((data[1] & 0x0F) << 8) | data[2])
        if expected is not None and len(data) >= expected:
            return bytes(data[:expected]), packet_indices
    raise ValueError(f"MPEG-TS PID {pid} has no complete PSI table 0x{table_id:02x}")


def find_program_packets(payload: bytes) -> dict[str, int | list[int]]:
    """Return PAT/PMT packet indexes, AVC PID, and first AVC PES packet index."""
    packets = ts_packets(payload)
    pat, pat_indices = _psi_section(packets, 0, 0x00)
    pat_end = len(pat) - 4
    pmt_pid = None
    for offset in range(8, pat_end, 4):
        program = int.from_bytes(pat[offset:offset + 2], "big")
        pid = ((pat[offset + 2] & 0x1F) << 8) | pat[offset + 3]
        if program:
            pmt_pid = pid
            break
    if pmt_pid is None:
        raise ValueError("PAT has no program entry")
    pmt, pmt_indices = _psi_section(packets, pmt_pid, 0x02)
    pmt_end = len(pmt) - 4
    program_info_length = ((pmt[10] & 0x0F) << 8) | pmt[11]
    offset = 12 + program_info_length
    video_pid = None
    while offset + 5 <= pmt_end:
        stream_type = pmt[offset]
        elementary_pid = ((pmt[offset + 1] & 0x1F) << 8) | pmt[offset + 2]
        es_info_length = ((pmt[offset + 3] & 0x0F) << 8) | pmt[offset + 4]
        if stream_type == 0x1B:
            video_pid = elementary_pid
            break
        offset += 5 + es_info_length
    if video_pid is None:
        raise ValueError("PMT has no AVC elementary stream")
    first_video_pes = None
    for index, packet in enumerate(packets):
        info = _payload(packet)
        if info is None:
            continue
        pid, start, elementary = info
        if pid == video_pid and start:
            if len(elementary) < 9 or elementary[:3] != b"\x00\x00\x01":
                raise ValueError("first AVC payload unit does not begin with a PES header")
            has_pts = bool(elementary[7] & 0x80)
            if not has_pts:
                raise ValueError("first AVC PES has no PTS")
            first_video_pes = index
            break
    if first_video_pes is None:
        raise ValueError("MPEG-TS has no AVC PES start")
    return {
        "patPid": 0,
        "pmtPid": pmt_pid,
        "videoPid": video_pid,
        "patPacketIndexes": pat_indices,
        "pmtPacketIndexes": pmt_indices,
        "firstVideoPesPacketIndex": first_video_pes,
    }


def null_packet(counter: int = 0) -> bytes:
    """Build one legal MPEG-TS null-PID packet."""
    return b"\x47\x1f\xff\x10" + bytes([counter & 0x0F]) + b"\xff" * (PACKET_SIZE - 5)


def build_prefix_and_suffix(payload: bytes) -> tuple[bytes, bytes, dict[str, int | list[int]]]:
    details = find_program_packets(payload)
    packets = ts_packets(payload)
    indexes = list(details["patPacketIndexes"]) + list(details["pmtPacketIndexes"])
    prefix = b"".join(packets[index] for index in indexes)
    suffix = b"".join(packets[int(details["firstVideoPesPacketIndex"]):])
    if not prefix or not suffix:
        raise ValueError("could not isolate PSI prefix and first AVC PES")
    return prefix, suffix, details


class FixtureState:
    def __init__(self, root: pathlib.Path, mode: str, delay_seconds: float,
                 null_packets_per_second: float, window_segments: int,
                 rendition_profile: str = "audio-video",
                 subtitle_discontinuity_sequence_offset: int = 0) -> None:
        self.root = root
        self.mode = mode
        self.delay_seconds = delay_seconds
        self.null_packets_per_second = null_packets_per_second
        self.window_segments = window_segments
        if rendition_profile not in ("audio-video", "video-subtitles"):
            raise ValueError(f"unsupported rendition profile: {rendition_profile}")
        if subtitle_discontinuity_sequence_offset < 0:
            raise ValueError("subtitle discontinuity sequence offset must be nonnegative")
        self.rendition_profile = rendition_profile
        self.subtitle_discontinuity_sequence_offset = subtitle_discontinuity_sequence_offset
        metadata = json.loads((root / "media.json").read_text(encoding="utf-8"))
        self.video = metadata["video"]
        self.audio = metadata["audio"]
        self.video_segments = len(self.video["segments"])
        self.audio_segments = len(self.audio["segments"])
        if self.video_segments != self.audio_segments or self.video_segments < window_segments:
            raise ValueError("video/audio segment counts must match and cover the live window")
        self.lock = threading.Lock()
        self.events: list[dict] = []
        self.origin = time.monotonic() - 2.0 * window_segments
        self.wall_origin = time.time() - 2.0 * window_segments
        self.delayed_sequences: set[int] = set()
        self.delay_target_sequence: int | None = None
        self.request_counter = 0
        self.started_at = time.monotonic()

    def record(self, event: str, **data: object) -> None:
        with self.lock:
            self.events.append({"wallTime": time.time(), "monotonicSeconds": time.monotonic(),
                                "event": event, **data})

    def reset(self) -> dict:
        with self.lock:
            self.origin = time.monotonic() - 2.0 * self.window_segments
            self.wall_origin = time.time() - 2.0 * self.window_segments
            self.delayed_sequences.clear()
            self.delay_target_sequence = None
            self.events.clear()
            self.started_at = time.monotonic()
        self.record("reset", mode=self.mode)
        return self.status()

    def status(self) -> dict:
        with self.lock:
            elapsed = time.monotonic() - self.origin
            current = max(0, int(elapsed / 2.0))
            opened = sum(event["event"] == "stream-open" for event in self.events)
            closed = sum(event["event"] in ("stream-close", "client-disconnect")
                         for event in self.events)
            return {"mode": self.mode, "renditionProfile": self.rendition_profile,
                    "delaySeconds": self.delay_seconds,
                    "nullPacketsPerSecond": self.null_packets_per_second,
                    "subtitleDiscontinuitySequenceOffset": self.subtitle_discontinuity_sequence_offset,
                    "windowSegments": self.window_segments, "currentSequence": current,
            "delayedSequences": sorted(self.delayed_sequences),
                    "delayTargetSequence": self.delay_target_sequence,
                    "activeRequestCount": max(0, opened - closed)}

    def window(self) -> tuple[int, int, float, float]:
        with self.lock:
            current = max(1, int((time.monotonic() - self.origin) / 2.0))
            end = current - 1
            start = max(0, end - self.window_segments + 1)
            return start, end, self.wall_origin, self.origin


def chunked_write(handler: BaseHTTPRequestHandler, payload: bytes) -> None:
    if payload:
        handler.wfile.write(f"{len(payload):X}\r\n".encode("ascii"))
        handler.wfile.write(payload)
        handler.wfile.write(b"\r\n")
        handler.wfile.flush()


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *_args: object) -> None:
        pass

    @property
    def state(self) -> FixtureState:
        return self.server.fixture_state  # type: ignore[attr-defined]

    def _reply(self, body: bytes, content_type: str, status: int = 200) -> None:
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self) -> None:  # noqa: N802
        route = urlsplit(self.path).path
        if route == "/health":
            return self._reply(b'{"ok":true}', "application/json")
        if route == "/status":
            return self._reply(json.dumps(self.state.status()).encode(), "application/json")
        if route == "/events":
            with self.state.lock:
                snapshot = {"settings": {"mode": self.state.mode,
                                         "renditionProfile": self.state.rendition_profile,
                                         "delaySeconds": self.state.delay_seconds,
                                         "nullPacketsPerSecond": self.state.null_packets_per_second,
                                         "subtitleDiscontinuitySequenceOffset": self.state.subtitle_discontinuity_sequence_offset,
                                         "windowSegments": self.state.window_segments},
                            "events": list(self.state.events)}
            return self._reply(json.dumps(snapshot).encode(), "application/json")
        if route == "/control":
            return self._reply(json.dumps(self.state.reset()).encode(), "application/json")
        if route == "/master.m3u8":
            if self.state.rendition_profile == "video-subtitles":
                body = ("#EXTM3U\n#EXT-X-VERSION:3\n"
                        '#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="Fixture Subtitles",'
                        'DEFAULT=YES,AUTOSELECT=YES,FORCED=NO,URI="subtitle/index.m3u8"\n'
                        f'#EXT-X-STREAM-INF:BANDWIDTH=1900000,CODECS="{self.state.video["codecString"]}",'
                        'SUBTITLES="subs"\nvideo/index.m3u8\n').encode("utf-8")
            else:
                body = ("#EXTM3U\n#EXT-X-VERSION:3\n"
                        '#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="Fixture Audio",'
                        'DEFAULT=YES,AUTOSELECT=YES,URI="audio/index.m3u8"\n'
                        f'#EXT-X-STREAM-INF:BANDWIDTH=2200000,CODECS="{self.state.video["codecString"]},'
                        f'{self.state.audio["codecString"]}",'
                        'AUDIO="audio"\nvideo/index.m3u8\n').encode("utf-8")
            return self._reply(body, "application/vnd.apple.mpegurl")
        if route in ("/video/index.m3u8", "/audio/index.m3u8", "/subtitle/index.m3u8"):
            role = route.split("/")[1]
            start, end, wall_origin, _ = self.state.window()
            metadata = self.state.video if role in ("video", "subtitle") else self.state.audio
            count = len(metadata["segments"])
            target = max(2, math.ceil(max(metadata["segments"][n % count]["durationSeconds"]
                                         for n in range(start, end + 1))) if end >= start else 2)
            rows = ["#EXTM3U", "#EXT-X-VERSION:3", f"#EXT-X-TARGETDURATION:{target}",
                    f"#EXT-X-MEDIA-SEQUENCE:{start}"]
            if role == "subtitle" and self.state.subtitle_discontinuity_sequence_offset:
                rows.insert(3, f"#EXT-X-DISCONTINUITY-SEQUENCE:{self.state.subtitle_discontinuity_sequence_offset}")
            for number in range(start, end + 1):
                wall = wall_origin + number * 2.0
                stamp = time.strftime("%Y-%m-%dT%H:%M:%S", time.gmtime(wall)) + ".%03dZ" % int(wall % 1 * 1000)
                rows.extend((f"#EXT-X-PROGRAM-DATE-TIME:{stamp}",
                             f"#EXTINF:{metadata['segments'][number % count]['durationSeconds']:.6f},",
                             f"/{role}/segment/{number}.{'vtt' if role == 'subtitle' else 'ts'}"))
            return self._reply(("\n".join(rows) + "\n").encode(), "application/vnd.apple.mpegurl")
        if route.startswith("/subtitle/segment/"):
            try:
                sequence = int(route.rsplit("/", 1)[1].removesuffix(".vtt"))
            except ValueError:
                return self._reply(b"not found", "text/plain", 404)
            if sequence < 0:
                return self._reply(b"not found", "text/plain", 404)
            index = sequence % len(self.state.video["segments"])
            first_pts = self.state.video["segments"][0].get("firstPacketPtsSeconds", 1.4)
            mpegts_pts = int(round((float(first_pts) + 2.0 * index) * 90_000)) % (1 << 33)
            duration = float(self.state.video["segments"][index]["durationSeconds"])
            cue_end = min(1.0, max(0.1, duration - 0.01))
            seconds = int(cue_end)
            millis = int(round((cue_end - seconds) * 1000))
            body = ("WEBVTT\n"
                    f"X-TIMESTAMP-MAP=LOCAL:00:00:00.000,MPEGTS:{mpegts_pts}\n\n"
                    f"00:00:00.000 --> 00:00:{seconds:02d}.{millis:03d}\n"
                    f"Fixture subtitle {sequence}\n").encode("utf-8")
            self.state.record("subtitle-segment", sequence=sequence, assetIndex=index,
                              bytes=len(body), mpegtsPts=mpegts_pts)
            return self._reply(body, "text/vtt")
        if route.startswith("/video/segment/") or route.startswith("/audio/segment/"):
            parts = route.strip("/").split("/")
            role = parts[0]
            try:
                sequence = int(parts[2].removesuffix(".ts"))
            except (IndexError, ValueError):
                return self._reply(b"not found", "text/plain", 404)
            metadata = self.state.video if role == "video" else self.state.audio
            segments = metadata["segments"]
            if sequence < 0 or not segments:
                return self._reply(b"not found", "text/plain", 404)
            asset_index = sequence % len(segments)
            asset = self.state.root / role / segments[asset_index]["file"]
            if not asset.is_file():
                return self._reply(b"not found", "text/plain", 404)
            payload = asset.read_bytes()
            opened = time.monotonic()
            with self.state.lock:
                self.state.request_counter += 1
                request_id = self.state.request_counter
            event = {"role": role, "sequence": sequence, "assetIndex": asset_index,
                     "assetBytes": len(payload), "requestWallTime": time.time(),
                     "requestId": request_id}
            self.state.record("stream-open", **event)
            if role != "video" or self.state.mode == "off":
                try:
                    self._reply(payload, "video/mp2t")
                    self.state.record("stream-close", **event, statusCode=200,
                                      bytes=len(payload), elapsedMs=(time.monotonic() - opened) * 1000,
                                      closeReason="body-complete")
                except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError, OSError) as error:
                    self.state.record("client-disconnect", **event, phase="normal-segment",
                                      elapsedMs=(time.monotonic() - opened) * 1000,
                                      exception=type(error).__name__)
                return

            prefix, suffix, transport = build_prefix_and_suffix(payload)
            should_delay = False
            selected_target = False
            with self.state.lock:
                if self.state.delay_target_sequence is None:
                    self.state.delay_target_sequence = sequence
                    selected_target = True
                if self.state.mode == "missing-primary-all":
                    should_delay = True
                elif sequence == self.state.delay_target_sequence:
                    if self.state.mode == "missing-primary":
                        # A retry of the selected sequence must remain without
                        # primary PTS; otherwise a reconnect would invalidate
                        # the missing-timestamp condition.
                        should_delay = True
                    elif sequence not in self.state.delayed_sequences:
                        self.state.delayed_sequences.add(sequence)
                        should_delay = True
            if selected_target:
                self.state.record("primary-delay-target-selected", sequence=sequence,
                                  assetIndex=asset_index, mode=self.state.mode)
            if not should_delay:
                try:
                    self._reply(payload, "video/mp2t")
                    self.state.record("stream-close", **event, statusCode=200,
                                      bytes=len(payload), elapsedMs=(time.monotonic() - opened) * 1000,
                                      closeReason="body-complete", videoPid=transport["videoPid"])
                except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError, OSError) as error:
                    self.state.record("client-disconnect", **event, phase="normal-segment",
                                      elapsedMs=(time.monotonic() - opened) * 1000,
                                      exception=type(error).__name__)
                return

            chunks_per_second = self.state.null_packets_per_second
            if self.state.mode == "delayed-valid":
                delay = self.state.delay_seconds
                filler_count = max(0, int(math.floor(delay * chunks_per_second)))
                filler_bytes = filler_count * PACKET_SIZE
                total_length = len(prefix) + filler_bytes + len(suffix)
                self.send_response(200)
                self.send_header("Content-Type", "video/mp2t")
                self.send_header("Content-Length", str(total_length))
                self.send_header("Cache-Control", "no-store")
                self.end_headers()
                self.wfile.write(prefix)
                self.wfile.flush()
                header_sent = time.monotonic()
                self.state.record("primary-headers-and-psi-sent", **event, statusCode=200,
                                  prefixBytes=len(prefix), firstVideoPesPacketIndex=transport["firstVideoPesPacketIndex"],
                                  videoPid=transport["videoPid"], declaredLength=total_length)
                delivered_nulls = 0
                try:
                    for index in range(filler_count):
                        deadline = header_sent + (index + 1) / chunks_per_second
                        remaining = deadline - time.monotonic()
                        if remaining > 0:
                            time.sleep(remaining)
                        self.wfile.write(null_packet(index))
                        delivered_nulls += 1
                    self.wfile.write(suffix)
                    self.wfile.flush()
                    self.state.record("primary-playable-pes-sent", **event,
                                      firstPlayablePesAfterMs=(time.monotonic() - header_sent) * 1000,
                                      nullPackets=delivered_nulls, bytes=len(suffix),
                                      videoPid=transport["videoPid"])
                    self.state.record("stream-close", **event, bytes=total_length,
                                      elapsedMs=(time.monotonic() - opened) * 1000,
                                      closeReason="body-complete")
                except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError, OSError) as error:
                    self.state.record("client-disconnect", **event, phase="delayed-valid",
                                      deliveredNullPackets=delivered_nulls,
                                      elapsedMs=(time.monotonic() - opened) * 1000,
                                      exception=type(error).__name__)
                return

            # Missing-primary mode deliberately keeps a valid HTTP chunked body
            # and null-PID TS packets flowing; no video PES/PTS is sent.
            self.send_response(200)
            self.send_header("Content-Type", "video/mp2t")
            self.send_header("Transfer-Encoding", "chunked")
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.state.record("primary-headers-and-psi-sent", **event, statusCode=200,
                              prefixBytes=len(prefix), firstVideoPesPacketIndex=transport["firstVideoPesPacketIndex"],
                              videoPid=transport["videoPid"], bodyMode="indefinite-chunked")
            try:
                chunked_write(self, prefix)
                group_size = max(1, int(chunks_per_second / 2))
                counter = 0
                while True:
                    deadline = opened + (counter + group_size) / chunks_per_second
                    remaining = deadline - time.monotonic()
                    if remaining > 0:
                        time.sleep(remaining)
                    packets = b"".join(null_packet(counter + n) for n in range(group_size))
                    chunked_write(self, packets)
                    counter += group_size
                    if counter % max(group_size, int(chunks_per_second * 5)) == 0:
                        self.state.record("primary-null-packet-progress", **event,
                                          nullPackets=counter,
                                          elapsedMs=(time.monotonic() - opened) * 1000)
            except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError, OSError) as error:
                self.state.record("client-disconnect", **event, phase="missing-primary",
                                  elapsedMs=(time.monotonic() - opened) * 1000,
                                  exception=type(error).__name__)
            return
        return self._reply(b"not found", "text/plain", 404)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", required=True, type=pathlib.Path)
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=18798)
    parser.add_argument("--mode", choices=("off", "delayed-valid", "missing-primary",
                                            "missing-primary-all"), default="off")
    parser.add_argument("--delay-seconds", type=float, default=25.0)
    parser.add_argument("--null-packets-per-second", type=float, default=20.0)
    parser.add_argument("--window-segments", type=int, default=30)
    parser.add_argument("--rendition-profile", choices=("audio-video", "video-subtitles"),
                        default="audio-video")
    parser.add_argument("--subtitle-discontinuity-sequence-offset", type=int, default=0,
                        help="Optional discontinuity sequence offset applied only to the subtitle playlist")
    args = parser.parse_args()
    if not math.isfinite(args.delay_seconds) or args.delay_seconds < 0:
        raise SystemExit("--delay-seconds must be finite and nonnegative")
    if not math.isfinite(args.null_packets_per_second) or args.null_packets_per_second <= 0:
        raise SystemExit("--null-packets-per-second must be finite and positive")
    if args.window_segments < 6:
        raise SystemExit("--window-segments must be at least six")
    if args.subtitle_discontinuity_sequence_offset < 0:
        raise SystemExit("--subtitle-discontinuity-sequence-offset must be nonnegative")
    root = args.root.resolve(strict=True)
    state = FixtureState(root, args.mode, args.delay_seconds,
                         args.null_packets_per_second, args.window_segments,
                         args.rendition_profile, args.subtitle_discontinuity_sequence_offset)
    server = ThreadingHTTPServer((args.host, args.port), Handler)
    server.daemon_threads = True
    server.fixture_state = state  # type: ignore[attr-defined]
    state.record("started", host=args.host, port=args.port,
                 renditionProfile=args.rendition_profile,
                 subtitleDiscontinuitySequenceOffset=args.subtitle_discontinuity_sequence_offset)
    print(json.dumps({"host": args.host, "port": args.port, "mode": args.mode,
                      "root": str(root), "windowSegments": args.window_segments}), flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
