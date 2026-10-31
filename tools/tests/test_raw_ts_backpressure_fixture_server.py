import http.client
from pathlib import Path
import tempfile
import threading
import time
import unittest

from tools.raw_ts_backpressure_fixture_server import (
    TS_PACKET_BYTES,
    chunk_payload,
    create_server,
    paced_deadline_seconds,
    sha256_file,
    scan_transport_stream,
)


def valid_ts_packets(count: int) -> bytes:
    packets = []
    for index in range(count):
        packets.append(bytes((
            [0x47, 0x01, 0x00, 0x10 | (index & 0x0F)] + [0xFF] * 184
        )))
    return b"".join(packets)


class RawTsFixtureHelpersTest(unittest.TestCase):
    def test_scanner_accepts_continuous_packet_stream_and_rejects_sync_or_counter_errors(self):
        with tempfile.TemporaryDirectory() as directory:
            media = Path(directory) / "valid.ts"
            media.write_bytes(valid_ts_packets(32))
            result = scan_transport_stream(media)
            self.assertEqual(32 * TS_PACKET_BYTES, result["bytes"])
            self.assertEqual(32, result["packets"])
            self.assertEqual(0, result["badSync"])
            self.assertEqual(0, result["continuityErrors"])

            bad_sync = Path(directory) / "bad-sync.ts"
            bad_sync.write_bytes(b"X" + valid_ts_packets(2)[1:])
            with self.assertRaisesRegex(ValueError, "badSync=1"):
                scan_transport_stream(bad_sync)

            bad_counter = bytearray(valid_ts_packets(3))
            bad_counter[2 * TS_PACKET_BYTES + 3] = 0x17
            bad_cc = Path(directory) / "bad-counter.ts"
            bad_cc.write_bytes(bad_counter)
            with self.assertRaisesRegex(ValueError, "continuityErrors=1"):
                scan_transport_stream(bad_cc)

    def test_absolute_pacing_deadlines_and_packet_aligned_chunks(self):
        self.assertEqual(0.0, paced_deadline_seconds(0, 80.0))
        self.assertEqual(2.0, paced_deadline_seconds(20, 80.0))
        self.assertEqual(2.5, paced_deadline_seconds(25, 80.0))
        payload = bytes(range(256)) * 200
        chunks = list(chunk_payload(payload, 188 * 4))
        self.assertEqual(payload, b"".join(chunks))
        self.assertTrue(all(len(chunk) % 188 == 0 for chunk in chunks[:-1]))
        with self.assertRaisesRegex(ValueError, "multiple of 188"):
            list(chunk_payload(b"x", 64))

    def test_pacing_timeout_and_buffer_settings_are_validated(self):
        with tempfile.TemporaryDirectory() as directory:
            media = Path(directory) / "valid.ts"
            media.write_bytes(valid_ts_packets(8))
            for kwargs in (
                {"pace_multiplier": 0},
                {"media_duration_seconds": 0},
                {"send_buffer_bytes": 0},
                {"send_timeout_seconds": -1},
                {"send_timeout_seconds": float("nan")},
                {"chunk_bytes": 187},
                {"continuation_file": media},
            ):
                with self.subTest(kwargs=kwargs), self.assertRaises(ValueError):
                    options = {
                        "media_file": media,
                        "port": 0,
                        "pace_multiplier": 1.0,
                        "media_duration_seconds": 10.0,
                        "send_buffer_bytes": 4096,
                        "send_timeout_seconds": 0.0,
                    }
                    options.update(kwargs)
                    create_server(**options)


class RawTsFixtureHttpTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.media = Path(self.temp.name) / "stream.ts"
        self.media.write_bytes(valid_ts_packets(512))
        self.server = create_server(
            media_file=self.media,
            port=0,
            pace_multiplier=1.0,
            media_duration_seconds=0.01,
            send_buffer_bytes=4096,
            send_timeout_seconds=0,
            chunk_bytes=188 * 32,
            write_block_threshold_ms=10,
        )
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.addCleanup(self._close)
        self.host, self.port = self.server.server_address

    def _close(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)
        self.temp.cleanup()

    def test_fast_reader_gets_one_chunked_stream_and_events_report_settings(self):
        connection = http.client.HTTPConnection(self.host, self.port, timeout=5)
        connection.request("GET", "/raw/1001.ts")
        response = connection.getresponse()
        self.assertEqual(200, response.status)
        self.assertEqual("chunked", response.getheader("Transfer-Encoding"))
        self.assertIsNone(response.getheader("Content-Length"))
        self.assertEqual(self.media.read_bytes(), response.read())
        connection.close()

        events = self._get_json("/events")
        self.assertEqual(1, len(events["events"]))
        stream = events["events"][0]
        self.assertEqual("raw-stream", stream["event"])
        self.assertEqual("1001", stream["channel"])
        self.assertEqual("complete", stream["connectionCloseReason"])
        self.assertEqual(self.media.stat().st_size, stream["bytesSent"])
        self.assertGreater(stream["sendallCalls"], 1)
        self.assertIsNotNone(stream["sendBufferBytesActual"])
        self.assertEqual(0.0, stream["sendTimeoutSeconds"])
        self.assertEqual(1, stream["requestIndex"])
        self.assertEqual(str(self.media.resolve()), stream["mediaFile"])
        self.assertEqual(sha256_file(self.media), stream["mediaSha256"])
        self.assertIsNone(events["settings"]["continuationFile"])

    def test_continuation_is_selected_after_first_request_and_reset_restarts_sequence(self):
        continuation = Path(self.temp.name) / "continuation.ts"
        continuation.write_bytes(valid_ts_packets(256))
        self.server.continuation_file = continuation.resolve()
        self.server.continuation_scan = scan_transport_stream(continuation)
        self.server.continuation_duration_seconds = 0.01
        self.server.continuation_wire_rate_bps = continuation.stat().st_size * 8 / 0.01
        self.server.continuation_sha256 = sha256_file(continuation)

        def get_body():
            connection = http.client.HTTPConnection(self.host, self.port, timeout=5)
            connection.request("GET", "/raw/1001.ts")
            response = connection.getresponse()
            self.assertEqual(200, response.status)
            body = response.read()
            connection.close()
            return body

        first_body = get_body()
        second_body = get_body()
        third_body = get_body()
        self.assertEqual(self.media.read_bytes(), first_body)
        self.assertEqual(continuation.read_bytes(), second_body)
        self.assertEqual(continuation.read_bytes(), third_body)

        events = self._get_json("/events")
        self.assertEqual([1, 2, 3], [row["requestIndex"] for row in events["events"]])
        self.assertEqual(
            [str(self.media.resolve()), str(continuation.resolve()), str(continuation.resolve())],
            [row["mediaFile"] for row in events["events"]],
        )
        self.assertEqual(
            [self.media.stat().st_size, continuation.stat().st_size, continuation.stat().st_size],
            [row["bytesSent"] for row in events["events"]],
        )
        self.assertEqual(
            [sha256_file(self.media), sha256_file(continuation), sha256_file(continuation)],
            [row["mediaSha256"] for row in events["events"]],
        )
        self.assertTrue(all(row["connectionCloseReason"] == "complete" for row in events["events"]))

        reset = self._get_json("/control?reset=1")
        self.assertTrue(reset["ok"])
        reset_body = get_body()
        self.assertEqual(self.media.read_bytes(), reset_body)
        reset_events = self._get_json("/events")["events"]
        self.assertEqual(1, len(reset_events))
        self.assertEqual(1, reset_events[0]["requestIndex"])
        self.assertEqual(str(self.media.resolve()), reset_events[0]["mediaFile"])

    def test_stalled_reader_records_real_blocked_send_timeout_not_pacing_wait(self):
        # Reduce the server send queue, leave the HTTP response unread, and send
        # enough real bytes for the kernel to apply TCP backpressure.
        self.media.write_bytes(valid_ts_packets(32_768))
        self.server.media_scan = scan_transport_stream(self.media)
        self.server.media_file = self.media
        self.server.media_duration_seconds = 0.01
        self.server.wire_rate_bps = self.media.stat().st_size * 8 / 0.01
        self.server.send_timeout_seconds = 0.15
        self.server.send_buffer_bytes = 4096
        self.server.chunk_bytes = 188 * 256

        connection = http.client.HTTPConnection(self.host, self.port, timeout=5)
        connection.request("GET", "/raw/1002.ts")
        response = connection.getresponse()  # Read headers only; intentionally do not consume the body.
        self.assertEqual(200, response.status)
        self.assertEqual("chunked", response.getheader("Transfer-Encoding"))

        deadline = time.monotonic() + 3
        while time.monotonic() < deadline:
            events = self._get_json("/events")
            if events["events"]:
                break
            time.sleep(0.02)
        connection.close()
        self.assertEqual(1, len(events["events"]))
        stream = events["events"][0]
        self.assertEqual("send-timeout", stream["connectionCloseReason"])
        self.assertGreater(stream["maxSendallMs"], 100)
        self.assertGreater(stream["sendallCalls"], 0)
        self.assertGreaterEqual(stream["pacingWaitSeconds"], 0)
        self.assertTrue(stream.get("partialChunkMayHaveBeenSent", False))
        self.assertTrue(stream["failedWrites"])
        self.assertEqual("send-timeout", stream["failedWrites"][-1]["outcome"])
        self.assertGreater(stream["sendBufferBytesActual"], 0)

    def test_real_pacing_wait_is_reported_separately_from_sendall_time(self):
        self.server.media_duration_seconds = 0.2
        self.server.wire_rate_bps = self.media.stat().st_size * 8 / 0.2
        self.server.send_timeout_seconds = 0
        started = time.monotonic()
        connection = http.client.HTTPConnection(self.host, self.port, timeout=5)
        connection.request("GET", "/raw/1001.ts")
        response = connection.getresponse()
        payload = response.read()
        elapsed = time.monotonic() - started
        connection.close()

        self.assertEqual(self.media.read_bytes(), payload)
        self.assertGreaterEqual(elapsed, 0.15)
        events = self._get_json("/events")
        stream = events["events"][0]
        self.assertEqual("complete", stream["connectionCloseReason"])
        self.assertGreater(stream["pacingWaitSeconds"], 0.05)
        wall_delta = stream["beganUnixSeconds"] - stream["startedUnixSeconds"]
        monotonic_delta = stream["beganElapsedMonotonic"] - stream["startedMonotonicSeconds"]
        self.assertLess(abs(wall_delta - monotonic_delta), 0.1)
        self.assertGreaterEqual(stream["totalSendallMs"], 0)

    def _get_json(self, path):
        connection = http.client.HTTPConnection(self.host, self.port, timeout=5)
        connection.request("GET", path)
        response = connection.getresponse()
        payload = response.read()
        connection.close()
        self.assertEqual(200, response.status)
        import json
        return json.loads(payload)


if __name__ == "__main__":
    unittest.main()
