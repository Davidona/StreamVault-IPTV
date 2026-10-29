import importlib.util
import math
from datetime import datetime
from pathlib import Path
import json
import tempfile
import threading
import time
import urllib.parse
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer
import unittest
from unittest import mock


spec = importlib.util.spec_from_file_location(
    "dynamic_live_fixture", Path(__file__).parents[1] / "v5_dynamic_live_fixture_server.py"
)
fixture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixture)


class SegmentDelayTest(unittest.TestCase):
    def test_healthy_mode_never_delays(self):
        for elapsed in (0, 20, 55, 90):
            self.assertEqual(0, fixture.segment_delay_seconds(elapsed, 0, 0, 20))

    def test_only_requests_inside_each_gap_wait_for_its_end(self):
        expected = {19: 0, 20: 6, 22: 4, 26: 0, 49: 0, 50: 6, 55: 1, 56: 0}
        for elapsed, delay in expected.items():
            with self.subTest(elapsed=elapsed):
                self.assertEqual(delay, fixture.segment_delay_seconds(elapsed, 30, 6, 20))


class SourceShapeTest(unittest.TestCase):
    def test_last_published_segment_has_finished_at_current_wall_clock(self):
        with mock.patch.object(fixture.time, "time", return_value=1_000.0), \
                mock.patch.object(fixture.time, "monotonic", return_value=500.0), \
                mock.patch.dict(fixture.origins, {}, clear=True), \
                mock.patch.dict(fixture.wall_origins, {}, clear=True), \
                mock.patch.dict(fixture.jump_offsets, {"1001": 0}, clear=True):
            playlist = fixture.manifest("1001", "127.0.0.1", 18785)
        dates = [line.split(":", 1)[1] for line in playlist.splitlines()
                 if line.startswith("#EXT-X-PROGRAM-DATE-TIME:")]
        self.assertEqual(6, len(dates))
        last_end = datetime.fromisoformat(dates[-1].replace("Z", "+00:00")).timestamp() + 2
        self.assertLessEqual(last_end, 1_000.0)
        self.assertIn("/asset/1001/005.ts", playlist)

    def test_short_window_preserves_initial_wall_clock_alignment(self):
        with mock.patch.object(fixture.time, "time", return_value=1_000.0), \
                mock.patch.object(fixture.time, "monotonic", return_value=500.0), \
                mock.patch.dict(fixture.origins, {}, clear=True), \
                mock.patch.dict(fixture.wall_origins, {}, clear=True), \
                mock.patch.dict(fixture.jump_offsets, {"1001": 0}, clear=True):
            playlist = fixture.manifest("1001", "127.0.0.1", 18785, 10.0, window_size=3)
        dates = [line.split(":", 1)[1] for line in playlist.splitlines()
                 if line.startswith("#EXT-X-PROGRAM-DATE-TIME:")]
        self.assertEqual(3, len(dates))
        first_start = datetime.fromisoformat(dates[0].replace("Z", "+00:00")).timestamp()
        last_end = datetime.fromisoformat(dates[-1].replace("Z", "+00:00")).timestamp() + 2
        self.assertEqual(994.0, first_start)
        self.assertLessEqual(last_end, 1_000.0)
        self.assertIn("/asset/1001/002.ts", playlist)
        self.assertIn("#EXT-X-SERVER-CONTROL:HOLD-BACK=10.0", playlist)

    def test_hold_back_is_optional_and_respects_three_target_duration_floor(self):
        self.assertIsNone(fixture.validated_hold_back_seconds(None))
        self.assertEqual(6, fixture.validated_hold_back_seconds(6))
        with self.assertRaisesRegex(ValueError, "at least 6 seconds"):
            fixture.validated_hold_back_seconds(5.999)
        for invalid in (math.nan, math.inf, -math.inf):
            with self.subTest(invalid=invalid), self.assertRaisesRegex(ValueError, "finite"):
                fixture.validated_hold_back_seconds(invalid)

    def test_manifest_omits_hold_back_by_default_and_emits_explicit_value(self):
        fixture.origins.clear()
        fixture.wall_origins.clear()
        default = fixture.manifest("1001", "127.0.0.1", 18785)
        self.assertNotIn("#EXT-X-SERVER-CONTROL", default)
        with_hold_back = fixture.manifest("1001", "127.0.0.1", 18785, 6)
        self.assertIn("#EXT-X-SERVER-CONTROL:HOLD-BACK=6.0", with_hold_back)

    def test_paced_schedule_uses_bounded_chunks_and_absolute_byte_deadlines(self):
        schedule = fixture.paced_chunk_schedule(25, 80, chunk_bytes=10)
        self.assertEqual([(0, 10, 0.0), (10, 10, 2.0), (20, 5, 2.5)], schedule)

    def test_unlimited_delivery_is_immediate_and_still_chunked(self):
        schedule = fixture.paced_chunk_schedule(25, None, chunk_bytes=10)
        self.assertEqual([(0, 10, 0.0), (10, 10, 0.0), (20, 5, 0.0)], schedule)

    def test_invalid_pacing_settings_are_rejected(self):
        for args in ((-1, None, 10), (1, 0, 10), (1, None, 0)):
            with self.subTest(args=args), self.assertRaises(ValueError):
                fixture.paced_chunk_schedule(*args)

    def test_reset_archives_prior_run_events(self):
        old_events = list(fixture.events)
        old_completed = list(fixture.completed_runs)
        old_run_number = fixture.run_number
        try:
            fixture.events.clear()
            fixture.completed_runs.clear()
            fixture.run_number = 7
            fixture.record("test-event", marker="keep")
            fixture.archive_current_run()
            self.assertEqual(8, fixture.run_number)
            self.assertEqual("keep", fixture.completed_runs[0]["events"][0]["marker"])
            self.assertEqual([], fixture.events)
        finally:
            fixture.events[:] = old_events
            fixture.completed_runs[:] = old_completed
            fixture.run_number = old_run_number


class RefusalInjectionTest(unittest.TestCase):
    def test_segment_404_is_one_shot_for_same_url_and_does_not_refuse_playlists(self):
        injector = fixture.RefusalInjector("segment-404-once", after_seconds=20)

        before = injector.decide("media-segment", "1001", 10, 0, 19.999)
        first = injector.decide("media-segment", "1001", 10, 0, 20.0)
        retry = injector.decide("media-segment", "1001", 10, 0, 20.1)
        next_segment = injector.decide("media-segment", "1001", 11, 0, 21.0)
        playlist = injector.decide("playlist", "1001", 8, None, 22.0)

        self.assertEqual(200, before["statusCode"])
        self.assertEqual(404, first["statusCode"])
        self.assertEqual(2, first["attempts"])
        self.assertEqual(200, retry["statusCode"])
        self.assertEqual(3, retry["attempts"])
        self.assertEqual(200, next_segment["statusCode"])
        self.assertEqual(200, playlist["statusCode"])

    def test_segment_404_can_repeat_only_once_per_configured_period(self):
        injector = fixture.RefusalInjector(
            "segment-404-once", after_seconds=20, period_seconds=30
        )

        self.assertEqual(404, injector.decide("media-segment", "1001", 10, 0, 20)["statusCode"])
        self.assertEqual(200, injector.decide("media-segment", "1001", 10, 0, 21)["statusCode"])
        self.assertEqual(200, injector.decide("media-segment", "1001", 10, 0, 50)["statusCode"])
        self.assertEqual(404, injector.decide("media-segment", "1001", 11, 0, 50.1)["statusCode"])
        self.assertEqual(200, injector.decide("media-segment", "1001", 11, 0, 50.2)["statusCode"])

    def test_stale_generation_refuses_old_uri_and_accepts_rotated_uri_for_same_sequence(self):
        injector = fixture.RefusalInjector("segment-403-rotate", after_seconds=20)
        with mock.patch.object(fixture.time, "time", return_value=1_000.0), \
                mock.patch.object(fixture.time, "monotonic", return_value=500.0), \
                mock.patch.dict(fixture.origins, {}, clear=True), \
                mock.patch.dict(fixture.wall_origins, {}, clear=True), \
                mock.patch.dict(fixture.jump_offsets, {"1001": 0}, clear=True):
            before = fixture.manifest(
                "1001", "127.0.0.1", 18785,
                token_generation_for_segment=injector.generation_for
            )
            refusal = injector.decide("media-segment", "1001", 3, 0, 20)
            after = fixture.manifest(
                "1001", "127.0.0.1", 18785,
                token_generation_for_segment=injector.generation_for
            )

        old_uri = "/asset/1001/003.ts?generation=0"
        new_uri = "/asset/1001/003.ts?generation=1"
        self.assertIn(old_uri, before)
        self.assertNotIn(old_uri, after)
        self.assertIn(new_uri, after)
        self.assertEqual(403, refusal["statusCode"])
        self.assertEqual(403, injector.decide("media-segment", "1001", 3, 0, 20.1)["statusCode"])
        accepted = injector.decide("media-segment", "1001", 3, 1, 20.2)
        self.assertEqual(200, accepted["statusCode"])
        self.assertEqual(1, accepted["attempts"])
        self.assertEqual(200, injector.decide("playlist", "1001", 0, None, 21)["statusCode"])

    def test_reset_clears_failure_selection_generations_and_uri_attempt_counts(self):
        injector = fixture.RefusalInjector("segment-403-rotate", after_seconds=20)
        first = injector.decide("media-segment", "1001", 10, 0, 20)
        self.assertEqual(403, first["statusCode"])
        self.assertEqual(1, injector.generation_for("1001", 10))

        injector.reset()

        self.assertEqual({}, injector.snapshot()["selectedSequences"])
        self.assertEqual([], injector.snapshot()["tokenGenerations"])
        after_reset = injector.decide("media-segment", "1001", 10, 0, 20)
        self.assertEqual(403, after_reset["statusCode"])
        self.assertEqual(1, after_reset["attempts"])

    def test_playlist_403_negative_control_never_refuses_media_segments(self):
        injector = fixture.RefusalInjector("playlist-403-after-start", after_seconds=20)

        self.assertEqual(200, injector.decide("playlist", "1001", 10, None, 19.9)["statusCode"])
        self.assertEqual(403, injector.decide("playlist", "1001", 10, None, 20)["statusCode"])
        self.assertEqual(403, injector.decide("playlist", "1001", 11, None, 21)["statusCode"])
        self.assertEqual(200, injector.decide("media-segment", "1001", 10, 0, 21)["statusCode"])

    def test_permanent_media_403_refuses_new_sequences_but_not_playlists_and_resets(self):
        injector = fixture.RefusalInjector("segment-403-permanent-after-start", after_seconds=20)

        self.assertEqual(200, injector.decide("media-segment", "1001", 10, 0, 19.9)["statusCode"])
        self.assertEqual(403, injector.decide("media-segment", "1001", 10, 0, 20)["statusCode"])
        self.assertEqual(403, injector.decide("media-segment", "1001", 11, 0, 20.1)["statusCode"])
        self.assertEqual(200, injector.decide("playlist", "1001", 11, None, 20.2)["statusCode"])

        injector.reset()

        self.assertEqual(403, injector.decide("media-segment", "1001", 10, 0, 20.3)["statusCode"])
        self.assertEqual(200, injector.decide("playlist", "1001", 10, None, 20.4)["statusCode"])

    def test_injector_exposes_http_status_field_for_unrun_410_case(self):
        injector = fixture.RefusalInjector(
            "segment-404-once", after_seconds=20, status_code=410
        )
        decision = injector.decide("media-segment", "1001", 10, 0, 20)

        self.assertEqual(410, decision["statusCode"])

    def test_refusal_configuration_rejects_invalid_mode_timing_and_status(self):
        with self.assertRaisesRegex(ValueError, "unsupported refusal mode"):
            fixture.RefusalInjector("all-urls")
        with self.assertRaisesRegex(ValueError, "finite and nonnegative"):
            fixture.RefusalInjector("segment-404-once", after_seconds=float("nan"))
        with self.assertRaisesRegex(ValueError, "supported only"):
            fixture.RefusalInjector("segment-403-rotate", period_seconds=30)
        with self.assertRaisesRegex(ValueError, "403, 404, or 410"):
            fixture.RefusalInjector("segment-404-once", status_code=401)


class RefusalHttpIntegrationTest(unittest.TestCase):
    def setUp(self):
        self.original_events = list(fixture.events)
        self.original_origins = dict(fixture.origins)
        self.original_wall_origins = dict(fixture.wall_origins)
        self.original_jump_offsets = dict(fixture.jump_offsets)
        fixture.events.clear()
        fixture.origins.clear()
        fixture.wall_origins.clear()
        fixture.jump_offsets.clear()
        fixture.jump_offsets.update({channel: 0 for channel in fixture.CHANNELS})

    def tearDown(self):
        fixture.events[:] = self.original_events
        fixture.origins.clear()
        fixture.origins.update(self.original_origins)
        fixture.wall_origins.clear()
        fixture.wall_origins.update(self.original_wall_origins)
        fixture.jump_offsets.clear()
        fixture.jump_offsets.update(self.original_jump_offsets)

    def create_server(self, mode):
        media_root = tempfile.TemporaryDirectory()
        for number in range(6):
            (Path(media_root.name) / f"segment_{number:03d}.ts").write_bytes(
                f"segment-{number}".encode()
            )
        server = ThreadingHTTPServer(("127.0.0.1", 0), fixture.Handler)
        server.segment_root = Path(media_root.name)
        server.stall_period = 0
        server.stall_duration = 0
        server.stall_start = 20
        server.stall_origin = time.monotonic() - 25
        server.hold_back_seconds = None
        server.window_size = 6
        server.segment_rate_cap_bps = None
        server.refusal_injector = fixture.RefusalInjector(mode, after_seconds=20)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()

        def close():
            server.shutdown()
            server.server_close()
            thread.join(timeout=2)
            media_root.cleanup()

        self.addCleanup(close)
        return f"http://127.0.0.1:{server.server_port}", server

    @staticmethod
    def request(url):
        try:
            with urllib.request.urlopen(url, timeout=2) as response:
                return response.status, response.headers, response.read()
        except urllib.error.HTTPError as error:
            return error.code, error.headers, error.read()

    def test_public_host_covers_account_stream_and_manifest_urls_with_default_preserved(self):
        base, server = self.create_server("off")
        for host in ("10.0.2.2", "127.0.0.1"):
            with self.subTest(host=host):
                if host == "127.0.0.1":
                    server.public_host = host
                status, _, body = self.request(f"{base}/player_api.php")
                self.assertEqual(200, status)
                self.assertEqual(host, json.loads(body)["server_info"]["url"])
                status, _, body = self.request(f"{base}/player_api.php?action=get_live_streams")
                self.assertEqual(200, status)
                for stream in json.loads(body):
                    source = urllib.parse.urlsplit(stream["direct_source"])
                    self.assertEqual(host, source.hostname)
                    self.assertEqual(server.server_port, source.port)
                status, _, body = self.request(f"{base}/live/v5/v5/1001.m3u8")
                self.assertEqual(200, status)
                uris = [line for line in body.decode().splitlines() if line and not line.startswith("#")]
                self.assertTrue(uris)
                for uri in uris:
                    parsed = urllib.parse.urlsplit(uri)
                    self.assertEqual(host, parsed.hostname)
                    self.assertEqual(server.server_port, parsed.port)

    def test_segment_404_is_empty_same_url_retry_succeeds_and_playlist_stays_200(self):
        base, server = self.create_server("segment-404-once")
        playlist_status, _, playlist_body = self.request(f"{base}/live/v5/v5/1001.m3u8")
        first_status, first_headers, first_body = self.request(f"{base}/asset/1001/003.ts")
        retry_status, _, retry_body = self.request(f"{base}/asset/1001/003.ts")
        event_status, _, event_body = self.request(f"{base}/events")
        events = json.loads(event_body)["events"]
        responses = [event for event in events if event["event"] == "http-response"]

        self.assertEqual(200, playlist_status)
        self.assertIn(b"#EXTM3U", playlist_body)
        self.assertEqual(404, first_status)
        self.assertEqual("0", first_headers["Content-Length"])
        self.assertEqual(b"", first_body)
        self.assertEqual(200, retry_status)
        self.assertEqual(b"segment-3", retry_body)
        self.assertEqual(200, event_status)
        media_responses = [event for event in responses if event["role"] == "media-segment"]
        self.assertEqual([404, 200], [event["statusCode"] for event in media_responses])
        self.assertEqual([1, 2], [event["attempts"] for event in media_responses])
        self.assertEqual([0, len(retry_body)], [event["bytes"] for event in media_responses])
        playlist_responses = [event for event in responses if event["role"] == "playlist"]
        self.assertEqual([200], [event["statusCode"] for event in playlist_responses])
        self.assertEqual("segment-404-once", server.refusal_injector.mode)

    def test_segment_403_rotates_only_same_sequence_generation_and_tracks_attempts(self):
        base, _ = self.create_server("segment-403-rotate")
        playlist_url = f"{base}/live/v5/v5/1001.m3u8"
        playlist_status, _, initial_manifest = self.request(playlist_url)
        self.assertEqual(200, playlist_status)
        initial_segment_url = next(
            line for line in initial_manifest.decode().splitlines()
            if "/asset/1001/003.ts?generation=0" in line
        )
        initial_path = urllib.parse.urlsplit(initial_segment_url).path
        first_status, first_headers, first_body = self.request(
            f"{base}{initial_path}?generation=0"
        )
        self.assertEqual(403, first_status)
        self.assertEqual("0", first_headers["Content-Length"])
        self.assertEqual(b"", first_body)

        second_playlist_status, _, rotated_manifest = self.request(playlist_url)
        self.assertEqual(200, second_playlist_status)
        rotated_segment_url = next(
            line for line in rotated_manifest.decode().splitlines()
            if "/asset/1001/003.ts?generation=1" in line
        )
        rotated_path = urllib.parse.urlsplit(rotated_segment_url).path
        old_retry_status, _, _ = self.request(f"{base}{initial_path}?generation=0")
        new_generation_status, _, payload = self.request(
            f"{base}{rotated_path}?generation=1"
        )
        event_status, _, event_body = self.request(f"{base}/events")
        response_events = [
            event for event in json.loads(event_body)["events"]
            if event["event"] == "http-response"
        ]
        media_events = [event for event in response_events if event["role"] == "media-segment"]
        playlist_events = [event for event in response_events if event["role"] == "playlist"]

        self.assertEqual(403, old_retry_status)
        self.assertEqual(200, new_generation_status)
        self.assertTrue(payload.startswith(b"segment-3"))
        self.assertEqual(200, event_status)
        self.assertEqual([0, 0, 1], [event["tokenGeneration"] for event in media_events])
        self.assertEqual([403, 403, 200], [event["statusCode"] for event in media_events])
        self.assertEqual([1, 2, 1], [event["attempts"] for event in media_events])
        self.assertEqual([200, 200], [event["statusCode"] for event in playlist_events])
        self.assertTrue(any(
            {"sequence": 3, "generation": 1} in event["advertisedTokenGenerations"]
            for event in playlist_events
        ))

    def test_playlist_403_negative_control_leaves_media_segments_healthy(self):
        base, _ = self.create_server("playlist-403-after-start")
        status, headers, body = self.request(f"{base}/live/v5/v5/1001.m3u8")
        media_status, _, media_body = self.request(f"{base}/asset/1001/003.ts")
        events_status, _, events_body = self.request(f"{base}/events")
        response_events = [
            event for event in json.loads(events_body)["events"]
            if event["event"] == "http-response"
        ]

        self.assertEqual(403, status)
        self.assertEqual("0", headers["Content-Length"])
        self.assertEqual(b"", body)
        self.assertEqual(200, media_status)
        self.assertEqual(b"segment-3", media_body)
        self.assertEqual(200, events_status)
        self.assertEqual([403, 200], [event["statusCode"] for event in response_events])
        self.assertEqual(["playlist", "media-segment"], [event["role"] for event in response_events])

    def test_permanent_media_403_is_recorded_per_sequence_while_playlists_stay_healthy(self):
        base, _ = self.create_server("segment-403-permanent-after-start")
        first_media_status, _, _ = self.request(f"{base}/asset/1001/003.ts")
        next_media_status, _, _ = self.request(f"{base}/asset/1001/004.ts")
        playlist_status, _, playlist_body = self.request(f"{base}/live/v5/v5/1001.m3u8")
        events_status, _, events_body = self.request(f"{base}/events")
        response_events = [
            event for event in json.loads(events_body)["events"]
            if event["event"] == "http-response"
        ]

        self.assertEqual([403, 403], [first_media_status, next_media_status])
        self.assertEqual(200, playlist_status)
        self.assertIn(b"#EXTM3U", playlist_body)
        self.assertEqual(200, events_status)
        self.assertEqual(
            [("media-segment", 3, 403), ("media-segment", 4, 403), ("playlist", 0, 200)],
            [(event["role"], event["sequence"], event["statusCode"]) for event in response_events],
        )


if __name__ == "__main__":
    unittest.main()
