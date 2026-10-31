from __future__ import annotations

import json
import pathlib
import re
import sys
import tempfile
import threading
import time
import unittest
import urllib.request
from http.server import ThreadingHTTPServer

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))

from split_hls_timestamp_fixture_server import (  # noqa: E402
    FixtureState,
    Handler,
    PACKET_SIZE,
    build_prefix_and_suffix,
    find_program_packets,
    null_packet,
    ts_packets,
)


def _ts_packet(pid: int, payload: bytes, *, start: bool = False, cc: int = 0) -> bytes:
    header = bytes((0x47, ((0x40 if start else 0) | ((pid >> 8) & 0x1F)), pid & 0xFF,
                    0x10 | (cc & 0x0F)))
    if len(payload) > PACKET_SIZE - 4:
        raise ValueError("payload too long")
    return header + payload + b"\xff" * (PACKET_SIZE - 4 - len(payload))


def _test_avc_ts() -> bytes:
    # PAT: program 1 -> PMT PID 0x100.
    pat = bytes.fromhex("00 b0 0d 00 01 c1 00 00 00 01 e1 00 00 00 00 00")
    # PMT: program 1, PCR/video PID 0x101, AVC stream type 0x1b.
    pmt = bytes.fromhex("02 b0 12 00 01 c1 00 00 e1 01 f0 00 1b e1 01 f0 00 00 00 00 00")
    pts = bytes((0x21, 0x00, 0x01, 0x00, 0x01))
    pes = b"\x00\x00\x01\xe0\x00\x00\x80\x80\x05" + pts + b"\x00\x00\x01\x65\xaa\xbb"
    return b"".join((
        _ts_packet(0, b"\x00" + pat, start=True),
        _ts_packet(0x100, b"\x00" + pmt, start=True),
        _ts_packet(0x101, pes, start=True),
    ))


class SplitHlsTimestampFixtureServerTest(unittest.TestCase):
    def test_extracts_program_psi_and_first_playable_video_pes(self) -> None:
        payload = _test_avc_ts()
        details = find_program_packets(payload)
        self.assertEqual(0x100, details["pmtPid"])
        self.assertEqual(0x101, details["videoPid"])
        self.assertEqual(2, details["firstVideoPesPacketIndex"])
        prefix, suffix, _ = build_prefix_and_suffix(payload)
        self.assertEqual(payload[:2 * PACKET_SIZE], prefix)
        self.assertEqual(payload[2 * PACKET_SIZE:], suffix)

    def test_null_packet_uses_null_pid_and_is_well_formed_ts(self) -> None:
        packet = null_packet(7)
        self.assertEqual(PACKET_SIZE, len(packet))
        self.assertEqual(0x1FFF, ((packet[1] & 0x1F) << 8) | packet[2])
        self.assertEqual([packet], ts_packets(packet))

    def test_master_and_renditions_are_separate_and_aligned(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary)
            for role in ("video", "audio"):
                (root / role).mkdir()
                for index in range(6):
                    (root / role / f"segment_{index:03d}.ts").write_bytes(_test_avc_ts())
            metadata = {
                "video": {"codecString": "avc1.42C01E",
                          "segments": [{"file": f"segment_{i:03d}.ts", "durationSeconds": 2.0}
                                       for i in range(6)]},
                "audio": {"codecString": "mp4a.40.2",
                          "segments": [{"file": f"segment_{i:03d}.ts", "durationSeconds": 2.0}
                                       for i in range(6)]},
            }
            (root / "media.json").write_text(json.dumps(metadata), encoding="utf-8")
            state = FixtureState(root, "off", 0.1, 20.0, 6)
            server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
            server.fixture_state = state
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            try:
                base = f"http://127.0.0.1:{server.server_port}"
                master = urllib.request.urlopen(base + "/master.m3u8", timeout=2).read().decode()
                video = urllib.request.urlopen(base + "/video/index.m3u8", timeout=2).read().decode()
                audio = urllib.request.urlopen(base + "/audio/index.m3u8", timeout=2).read().decode()
                self.assertIn('CODECS="avc1.42C01E,mp4a.40.2"', master)
                self.assertIn('URI="audio/index.m3u8"', master)
                self.assertIn("video/index.m3u8", master)
                video_sequence, video_rows = self._playlist_rows(video, "video", "ts")
                audio_sequence, audio_rows = self._playlist_rows(audio, "audio", "ts")
                self.assertEqual(6, len(video_rows))
                self.assertEqual(6, len(audio_rows))
                self.assertEqual(video_sequence, video_rows[0][0])
                self.assertEqual(audio_sequence, audio_rows[0][0])
                self.assertEqual(
                    list(range(video_sequence, video_sequence + len(video_rows))),
                    [row[0] for row in video_rows],
                )
                self.assertEqual(
                    list(range(audio_sequence, audio_sequence + len(audio_rows))),
                    [row[0] for row in audio_rows],
                )
                common_sequences = set(row[0] for row in video_rows) & set(row[0] for row in audio_rows)
                self.assertGreaterEqual(len(common_sequences), 5)
                video_program_dates = {sequence: program_date for sequence, program_date, _ in video_rows}
                audio_program_dates = {sequence: program_date for sequence, program_date, _ in audio_rows}
                video_durations = {sequence: duration for sequence, _, duration in video_rows}
                audio_durations = {sequence: duration for sequence, _, duration in audio_rows}
                for sequence in common_sequences:
                    self.assertEqual(video_program_dates[sequence], audio_program_dates[sequence])
                    self.assertEqual(video_durations[sequence], audio_durations[sequence])
            finally:
                server.shutdown()
                server.server_close()
                thread.join(1)

    @staticmethod
    def _playlist_rows(playlist: str, role: str, extension: str) -> tuple[int, list[tuple[int, str, str]]]:
        rows = playlist.splitlines()
        media_sequence = next(
            int(line.split(":", 1)[1]) for line in rows
            if line.startswith("#EXT-X-MEDIA-SEQUENCE:")
        )
        segments: list[tuple[int, str, str]] = []
        program_date: str | None = None
        duration: str | None = None
        for line in rows:
            if line.startswith("#EXT-X-PROGRAM-DATE-TIME:"):
                program_date = line.split(":", 1)[1]
            elif line.startswith("#EXTINF:"):
                duration = line.split(":", 1)[1].split(",", 1)[0]
            elif line.startswith(f"/{role}/segment/"):
                match = re.fullmatch(rf"/{role}/segment/(\d+)\.{extension}", line)
                if match is None or program_date is None or duration is None:
                    raise AssertionError(f"Malformed {role} playlist segment row: {line}")
                segments.append((int(match.group(1)), program_date, duration))
                program_date = None
                duration = None
        return media_sequence, segments

    def test_delayed_valid_sends_psi_then_paced_nulls_then_original_pes(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary)
            for role in ("video", "audio"):
                (root / role).mkdir()
                for index in range(6):
                    (root / role / f"segment_{index:03d}.ts").write_bytes(_test_avc_ts())
            rows = [{"file": f"segment_{i:03d}.ts", "durationSeconds": 2.0} for i in range(6)]
            (root / "media.json").write_text(json.dumps({"video": {"codecString": "avc1.42C01E", "segments": rows},
                                                          "audio": {"codecString": "mp4a.40.2", "segments": rows}}), encoding="utf-8")
            state = FixtureState(root, "delayed-valid", 0.12, 25.0, 6)
            server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
            server.fixture_state = state
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            try:
                base = f"http://127.0.0.1:{server.server_port}"
                request_started = time.monotonic()
                with urllib.request.urlopen(base + "/video/segment/5.ts", timeout=2) as response:
                    body = response.read()
                    self.assertEqual(200, response.status)
                elapsed = time.monotonic() - request_started
                self.assertGreaterEqual(elapsed, 0.10)
                prefix, suffix, _ = build_prefix_and_suffix(_test_avc_ts())
                self.assertEqual(prefix, body[:len(prefix)])
                expected_null_count = math_floor(0.12 * 25.0)
                null_start = len(prefix)
                nulls = body[null_start:null_start + expected_null_count * PACKET_SIZE]
                self.assertEqual(expected_null_count * PACKET_SIZE, len(nulls))
                self.assertTrue(all(((packet[1] & 0x1F) << 8 | packet[2]) == 0x1FFF
                                    for packet in ts_packets(nulls)))
                self.assertEqual(suffix, body[null_start + len(nulls):])
                events = state.events
                self.assertEqual(1, sum(row["event"] == "primary-delay-target-selected" for row in events))
                sent = next(row for row in events if row["event"] == "primary-playable-pes-sent")
                self.assertGreaterEqual(sent["firstPlayablePesAfterMs"], 100)
                self.assertEqual(expected_null_count, sent["nullPackets"])
            finally:
                server.shutdown()
                server.server_close()
                thread.join(1)

    def test_video_subtitle_profile_uses_non_audio_text_rendition(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary)
            for role in ("video", "audio"):
                (root / role).mkdir()
                for index in range(6):
                    (root / role / f"segment_{index:03d}.ts").write_bytes(_test_avc_ts())
            rows = [{"file": f"segment_{i:03d}.ts", "durationSeconds": 2.0} for i in range(6)]
            (root / "media.json").write_text(json.dumps({
                "video": {"codecString": "avc1.42C01E", "segments": rows},
                "audio": {"codecString": "mp4a.40.2", "segments": rows},
            }), encoding="utf-8")
            state = FixtureState(root, "missing-primary", 0.1, 20.0, 6,
                                 rendition_profile="video-subtitles")
            server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
            server.fixture_state = state
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            try:
                base = f"http://127.0.0.1:{server.server_port}"
                master = urllib.request.urlopen(base + "/master.m3u8", timeout=2).read().decode()
                subtitle = urllib.request.urlopen(base + "/subtitle/index.m3u8", timeout=2).read().decode()
                video = urllib.request.urlopen(base + "/video/index.m3u8", timeout=2).read().decode()
                vtt = urllib.request.urlopen(base + "/subtitle/segment/5.vtt", timeout=2).read().decode()
                self.assertIn("TYPE=SUBTITLES", master)
                self.assertIn('SUBTITLES="subs"', master)
                self.assertNotIn("TYPE=AUDIO", master)
                self.assertIn("/subtitle/segment/", subtitle)
                self.assertNotIn("#EXT-X-DISCONTINUITY-SEQUENCE:", subtitle)
                self.assertNotIn("#EXT-X-DISCONTINUITY-SEQUENCE:", video)
                self.assertIn("X-TIMESTAMP-MAP=LOCAL:00:00:00.000,MPEGTS:1026000", vtt)
                self.assertIn("Fixture subtitle 5", vtt)
            finally:
                server.shutdown()
                server.server_close()
                thread.join(1)

    def test_subtitle_discontinuity_sequence_offset_is_rendition_scoped(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary)
            for role in ("video", "audio"):
                (root / role).mkdir()
                for index in range(6):
                    (root / role / f"segment_{index:03d}.ts").write_bytes(_test_avc_ts())
            rows = [{"file": f"segment_{i:03d}.ts", "durationSeconds": 2.0} for i in range(6)]
            (root / "media.json").write_text(json.dumps({
                "video": {"codecString": "avc1.42C01E", "segments": rows},
                "audio": {"codecString": "mp4a.40.2", "segments": rows},
            }), encoding="utf-8")
            state = FixtureState(root, "off", 0.1, 20.0, 6,
                                 rendition_profile="video-subtitles",
                                 subtitle_discontinuity_sequence_offset=1)
            server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
            server.fixture_state = state
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            try:
                base = f"http://127.0.0.1:{server.server_port}"
                subtitle = urllib.request.urlopen(base + "/subtitle/index.m3u8", timeout=2).read().decode()
                video = urllib.request.urlopen(base + "/video/index.m3u8", timeout=2).read().decode()
                status = json.loads(urllib.request.urlopen(base + "/status", timeout=2).read())
                events = json.loads(urllib.request.urlopen(base + "/events", timeout=2).read())
                self.assertIn("#EXT-X-DISCONTINUITY-SEQUENCE:1", subtitle)
                self.assertNotIn("#EXT-X-DISCONTINUITY-SEQUENCE:", video)
                self.assertEqual(1, status["subtitleDiscontinuitySequenceOffset"])
                self.assertEqual(1, events["settings"]["subtitleDiscontinuitySequenceOffset"])
            finally:
                server.shutdown()
                server.server_close()
                thread.join(1)

    def test_missing_primary_keeps_chunked_stream_without_video_pes(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary)
            for role in ("video", "audio"):
                (root / role).mkdir()
                for index in range(6):
                    (root / role / f"segment_{index:03d}.ts").write_bytes(_test_avc_ts())
            rows = [{"file": f"segment_{i:03d}.ts", "durationSeconds": 2.0} for i in range(6)]
            (root / "media.json").write_text(json.dumps({"video": {"codecString": "avc1.42C01E", "segments": rows},
                                                          "audio": {"codecString": "mp4a.40.2", "segments": rows}}), encoding="utf-8")
            state = FixtureState(root, "missing-primary", 0.1, 40.0, 6)
            server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
            server.fixture_state = state
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            response = None
            try:
                response = urllib.request.urlopen(
                    f"http://127.0.0.1:{server.server_port}/video/segment/5.ts", timeout=2)
                self.assertEqual(200, response.status)
                prefix_size = len(build_prefix_and_suffix(_test_avc_ts())[0])
                prefix = response.read(prefix_size)
                self.assertEqual(prefix_size, len(prefix))
                filler = response.read(5 * PACKET_SIZE)
                self.assertTrue(filler)
                self.assertTrue(all(((packet[1] & 0x1F) << 8 | packet[2]) == 0x1FFF
                                    for packet in ts_packets(filler[:len(filler) // PACKET_SIZE * PACKET_SIZE])))
                self.assertTrue(any(row["event"] == "primary-headers-and-psi-sent" and
                                    row.get("bodyMode") == "indefinite-chunked" for row in state.events))
            finally:
                if response is not None:
                    response.close()
                server.shutdown()
                server.server_close()
                thread.join(1)

    def test_missing_primary_retries_remain_invalid_but_later_sequence_is_playable(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary)
            for role in ("video", "audio"):
                (root / role).mkdir()
                for index in range(6):
                    (root / role / f"segment_{index:03d}.ts").write_bytes(_test_avc_ts())
            rows = [{"file": f"segment_{i:03d}.ts", "durationSeconds": 2.0} for i in range(6)]
            (root / "media.json").write_text(json.dumps({
                "video": {"codecString": "avc1.42C01E", "segments": rows},
                "audio": {"codecString": "mp4a.40.2", "segments": rows},
            }), encoding="utf-8")
            state = FixtureState(root, "missing-primary", 0.1, 40.0, 6)
            server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
            server.fixture_state = state
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            responses = []
            try:
                base = f"http://127.0.0.1:{server.server_port}/video/segment"
                prefix_size = len(build_prefix_and_suffix(_test_avc_ts())[0])
                for _ in range(2):
                    response = urllib.request.urlopen(f"{base}/5.ts", timeout=2)
                    responses.append(response)
                    self.assertEqual(200, response.status)
                    self.assertEqual(prefix_size, len(response.read(prefix_size)))
                    filler = response.read(5 * PACKET_SIZE)
                    self.assertTrue(filler)
                    complete_packets = len(filler) // PACKET_SIZE
                    self.assertGreater(complete_packets, 0)
                    self.assertTrue(all(
                        ((packet[1] & 0x1F) << 8 | packet[2]) == 0x1FFF
                        for packet in ts_packets(filler[:complete_packets * PACKET_SIZE])
                    ))
                    response.close()
                    responses.remove(response)

                next_sequence = urllib.request.urlopen(f"{base}/6.ts", timeout=2)
                responses.append(next_sequence)
                playable = next_sequence.read()
                self.assertEqual(_test_avc_ts(), playable)
                next_sequence.close()
                responses.remove(next_sequence)

                events = state.events
                self.assertEqual(1, sum(
                    row["event"] == "primary-delay-target-selected" and row["sequence"] == 5
                    for row in events
                ))
                self.assertEqual(2, sum(
                    row["event"] == "primary-headers-and-psi-sent" and row["sequence"] == 5
                    for row in events
                ))
                self.assertTrue(any(
                    row["event"] == "stream-close" and row["sequence"] == 6 and
                    row.get("closeReason") == "body-complete"
                    for row in events
                ))
            finally:
                for response in responses:
                    response.close()
                server.shutdown()
                server.server_close()
                thread.join(1)

    def test_missing_primary_all_keeps_every_video_sequence_invalid_and_audio_valid(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary)
            for role in ("video", "audio"):
                (root / role).mkdir()
                for index in range(6):
                    (root / role / f"segment_{index:03d}.ts").write_bytes(_test_avc_ts())
            rows = [{"file": f"segment_{i:03d}.ts", "durationSeconds": 2.0} for i in range(6)]
            (root / "media.json").write_text(json.dumps({
                "video": {"codecString": "avc1.42C01E", "segments": rows},
                "audio": {"codecString": "mp4a.40.2", "segments": rows},
            }), encoding="utf-8")
            state = FixtureState(root, "missing-primary-all", 0.1, 40.0, 6)
            server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
            server.fixture_state = state
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            responses = []
            try:
                base = f"http://127.0.0.1:{server.server_port}"
                prefix_size = len(build_prefix_and_suffix(_test_avc_ts())[0])
                for sequence in (5, 6):
                    response = urllib.request.urlopen(
                        f"{base}/video/segment/{sequence}.ts", timeout=2)
                    responses.append(response)
                    self.assertEqual(200, response.status)
                    self.assertEqual(prefix_size, len(response.read(prefix_size)))
                    filler = response.read(5 * PACKET_SIZE)
                    packet_bytes = len(filler) // PACKET_SIZE * PACKET_SIZE
                    self.assertGreater(packet_bytes, 0)
                    self.assertTrue(all(
                        ((packet[1] & 0x1F) << 8 | packet[2]) == 0x1FFF
                        for packet in ts_packets(filler[:packet_bytes])
                    ))
                    response.close()
                    responses.remove(response)

                audio = urllib.request.urlopen(f"{base}/audio/segment/6.ts", timeout=2)
                responses.append(audio)
                self.assertEqual(_test_avc_ts(), audio.read())
                audio.close()
                responses.remove(audio)
                before_reset_events = state.events
                self.assertTrue(any(
                    row["event"] == "stream-close" and row["role"] == "audio" and
                    row["sequence"] == 6 and row.get("closeReason") == "body-complete"
                    for row in before_reset_events
                ))

                # Reset re-anchors the live window, but must not change the
                # selected failure mode or make a later video sequence valid.
                reset_status = state.reset()
                self.assertEqual("missing-primary-all", reset_status["mode"])
                later = urllib.request.urlopen(f"{base}/video/segment/7.ts", timeout=2)
                responses.append(later)
                self.assertEqual(200, later.status)
                self.assertEqual(prefix_size, len(later.read(prefix_size)))
                filler = later.read(5 * PACKET_SIZE)
                self.assertGreater(len(filler) // PACKET_SIZE, 0)
                later.close()
                responses.remove(later)

                events = state.events
                self.assertEqual(1, sum(
                    row["event"] == "primary-delay-target-selected" for row in events
                ))
                self.assertTrue(all(
                    row.get("bodyMode") == "indefinite-chunked"
                    for row in events if row["event"] == "primary-headers-and-psi-sent"
                ), events)
            finally:
                for response in responses:
                    response.close()
                server.shutdown()
                server.server_close()
                thread.join(1)


def math_floor(value: float) -> int:
    return int(value // 1)


if __name__ == "__main__":
    unittest.main()
