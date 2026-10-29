#!/usr/bin/env python3
"""Prepare aligned video-only/audio-only MPEG-TS HLS assets for local tests."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import pathlib
import re
import subprocess


def parse_hls_playlist(path: pathlib.Path) -> list[dict[str, object]]:
    rows = path.read_text(encoding="utf-8").splitlines()
    durations: list[float] = []
    files: list[str] = []
    current_duration: float | None = None
    for row in rows:
        if row.startswith("#EXTINF:"):
            current_duration = float(row.split(":", 1)[1].split(",", 1)[0])
        elif row and not row.startswith("#"):
            if current_duration is None:
                raise ValueError(f"segment URI without EXTINF in {path}")
            durations.append(current_duration)
            files.append(row)
            current_duration = None
    if current_duration is not None or not files:
        raise ValueError(f"incomplete or empty HLS playlist: {path}")
    return [{"file": file, "durationSeconds": duration}
            for file, duration in zip(files, durations, strict=True)]


def trim_subsecond_tail(path: pathlib.Path, segments: list[dict[str, object]]) -> list[dict[str, object]]:
    if segments and float(segments[-1]["durationSeconds"]) < 0.5:
        trailing = path.parent / str(segments[-1]["file"])
        lines = path.read_text(encoding="utf-8").splitlines()
        uri = str(segments[-1]["file"])
        previous_extinf = next(i for i in range(len(lines) - 1, -1, -1) if lines[i].startswith("#EXTINF:"))
        uri_index = next((i for i in range(previous_extinf + 1, len(lines))
                          if lines[i] and not lines[i].startswith("#")), None)
        if uri_index is None or lines[uri_index] != uri:
            raise ValueError(f"could not locate trailing short segment URI {uri}")
        del lines[uri_index]
        del lines[previous_extinf]
        path.write_text("\n".join(lines) + "\n", encoding="utf-8")
        trailing.unlink()
        return segments[:-1]
    return segments


def assert_ts_packet_sync(path: pathlib.Path) -> None:
    with path.open("rb") as stream:
        offset = 0
        while True:
            packet = stream.read(188)
            if not packet:
                break
            if len(packet) != 188 or packet[0] != 0x47:
                raise ValueError(f"invalid MPEG-TS packet alignment in {path.name} at {offset}")
            offset += 188


def packet_metadata(ffprobe: str, path: pathlib.Path, selector: str) -> dict:
    return probe(ffprobe, path, selector)


def probe(ffprobe: str, path: pathlib.Path, select: str | None = None) -> dict:
    args = [ffprobe, "-v", "error"]
    if select:
        args.extend(("-select_streams", select))
    args.extend(("-show_entries",
                 "format=duration:stream=codec_name,codec_type,profile,level,start_time,duration,time_base,id,width,height,r_frame_rate",
                 "-show_packets", "-read_intervals", "%+#1", "-of", "json", str(path)))
    return json.loads(subprocess.check_output(args, text=True))


def avc_codec_string(path: pathlib.Path) -> str:
    payload = path.read_bytes()
    pattern = re.compile(b"\\x00\\x00\\x00\\x01|\\x00\\x00\\x01")
    for match in pattern.finditer(payload):
        header = match.end()
        if header + 3 >= len(payload):
            continue
        nal_header = payload[header]
        if nal_header & 0x80 or nal_header & 0x1F != 7:
            continue
        profile_idc, constraints, level_idc = payload[header + 1:header + 4]
        return f"avc1.{profile_idc:02X}{constraints:02X}{level_idc:02X}"
    raise ValueError(f"could not find AVC SPS in {path.name}")


def aac_codec_string(profile: str | None) -> str:
    profile_map = {"LC": 2, "HE-AAC": 5, "HE-AACv2": 29}
    if profile not in profile_map:
        raise ValueError(f"unsupported AAC profile for HLS CODECS: {profile}")
    return f"mp4a.40.{profile_map[profile]}"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", required=True, type=pathlib.Path)
    parser.add_argument("--output", required=True, type=pathlib.Path)
    parser.add_argument("--ffmpeg", required=True)
    parser.add_argument("--ffprobe", required=True)
    parser.add_argument("--segment-seconds", type=float, default=2.0)
    args = parser.parse_args()
    source = args.source.resolve(strict=True)
    output = args.output.resolve()
    if output.exists():
        raise SystemExit(f"refusing to overwrite existing output: {output}")
    if not math.isfinite(args.segment_seconds) or args.segment_seconds <= 0:
        raise SystemExit("segment duration must be finite and positive")
    output.mkdir(parents=True)
    try:
        source_probe = probe(args.ffprobe, source)
        source_streams = source_probe.get("streams", [])
        if not any(row.get("codec_type") == "video" and row.get("codec_name") == "h264"
                   for row in source_streams):
            raise ValueError("source must contain H.264 video")
        if not any(row.get("codec_type") == "audio" and row.get("codec_name") == "aac"
                   for row in source_streams):
            raise ValueError("source must contain AAC audio")
        video_duration: float | None = None
        for role, mapping in (("video", "0:v:0"), ("audio", "0:a:0")):
            role_dir = output / role
            role_dir.mkdir()
            command = [args.ffmpeg, "-hide_banner", "-nostdin", "-y", "-loglevel", "warning",
                       "-i", str(source)]
            # AAC can extend a fraction beyond the video timeline. Clamp it to
            # the measured video playlist so both live renditions have equal
            # segment counts and a common timeline.
            if role == "audio" and video_duration is not None:
                command.extend(("-t", f"{video_duration:.6f}"))
            command.extend(("-map", mapping, "-c", "copy", "-sn", "-dn",
                       "-mpegts_flags", "+resend_headers", "-f", "hls",
                       "-hls_time", str(args.segment_seconds), "-hls_list_size", "0",
                       "-hls_playlist_type", "vod", "-hls_flags", "independent_segments",
                       "-hls_segment_filename", str(role_dir / "segment_%03d.ts"),
                       str(role_dir / "index.m3u8")))
            subprocess.run(command, check=True)
            if role == "video":
                video_duration = sum(float(row["durationSeconds"])
                                     for row in parse_hls_playlist(role_dir / "index.m3u8"))

        video_segments = parse_hls_playlist(output / "video" / "index.m3u8")
        audio_segments = parse_hls_playlist(output / "audio" / "index.m3u8")
        if len(audio_segments) == len(video_segments) + 1:
            audio_segments = trim_subsecond_tail(output / "audio" / "index.m3u8", audio_segments)
        if len(video_segments) != len(audio_segments):
            raise ValueError(f"rendition segment counts differ: video={len(video_segments)} audio={len(audio_segments)}")
        video_total = sum(float(row["durationSeconds"]) for row in video_segments)
        audio_total = sum(float(row["durationSeconds"]) for row in audio_segments)
        source_duration = float(source_probe["format"]["duration"])
        if abs(video_total - audio_total) > 0.15:
            raise ValueError(f"rendition durations are not aligned: {video_total:.3f}s vs {audio_total:.3f}s")
        if abs(video_total - source_duration) > 0.5:
            raise ValueError(f"rendition duration {video_total:.3f}s does not cover source {source_duration:.3f}s")

        for role, segments, selector, expected_codec in (
            ("video", video_segments, "v:0", "h264"),
            ("audio", audio_segments, "a:0", "aac"),
        ):
            sample_indexes = {0, 1, len(segments) // 2, len(segments) - 1}
            for index, segment in enumerate(segments):
                path = output / role / str(segment["file"])
                assert_ts_packet_sync(path)
                segment["bytes"] = path.stat().st_size
                segment["sha256"] = hashlib.sha256(path.read_bytes()).hexdigest()
                if index in sample_indexes:
                    metadata = packet_metadata(args.ffprobe, path, selector)
                    stream = next((row for row in metadata.get("streams", [])
                                   if row.get("codec_name") == expected_codec), None)
                    if stream is None:
                        raise ValueError(f"{role} segment has no {expected_codec}: {path.name}")
                    packets = metadata.get("packets", [])
                    if not packets or "pts_time" not in packets[0]:
                        raise ValueError(f"{role} segment has no first packet PTS: {path.name}")
                    segment["firstPacketPtsSeconds"] = float(packets[0]["pts_time"])
                    segment["codec"] = stream["codec_name"]
                    segment["streamId"] = stream.get("id")
        audio_stream = next(row for row in source_streams if row.get("codec_type") == "audio")
        metadata = {
            "source": {"path": str(source), "bytes": source.stat().st_size,
                       "sha256": hashlib.sha256(source.read_bytes()).hexdigest(),
                       "durationSeconds": source_duration, "ffprobe": source_probe},
            "targetDurationSeconds": args.segment_seconds,
            "durationAlignmentDeltaSeconds": video_total - audio_total,
            "video": {"codec": "h264", "codecString": avc_codec_string(
                output / "video" / str(video_segments[0]["file"])), "segments": video_segments},
            "audio": {"codec": "aac", "codecString": aac_codec_string(audio_stream.get("profile")),
                      "segments": audio_segments},
        }
        (output / "media.json").write_text(json.dumps(metadata, indent=2), encoding="utf-8")
        (output / "prepare-command.json").write_text(json.dumps({"commands": [
            [args.ffmpeg, "-i", str(source), "-map", "0:v:0", "-c", "copy", "..."],
            [args.ffmpeg, "-i", str(source), "-map", "0:a:0", "-c", "copy", "..."],
        ]}, indent=2), encoding="utf-8")
        print(json.dumps({"output": str(output), "videoSegments": len(video_segments),
                          "audioSegments": len(audio_segments),
                          "videoDurationSeconds": video_total,
                          "audioDurationSeconds": audio_total,
                          "sourceDurationSeconds": source_duration}, indent=2))
    except Exception:
        # Never leave a partial fixture that looks valid.
        import shutil
        shutil.rmtree(output)
        raise


if __name__ == "__main__":
    main()
