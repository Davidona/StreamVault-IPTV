#!/usr/bin/env python3
"""Create and validate one-pass continuous raw TS from the V5 2-second assets."""

from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import subprocess
import tempfile

if __package__:
    from .raw_ts_backpressure_fixture_server import scan_transport_stream
else:
    from raw_ts_backpressure_fixture_server import scan_transport_stream


SEGMENT_COUNT = 300
EXPECTED_DURATION_SECONDS = 600.0
PTS_REORDER_TOLERANCE_SECONDS = 0.25


def ffprobe_metadata(ffprobe: pathlib.Path, media: pathlib.Path) -> dict[str, object]:
    result = subprocess.check_output([
        str(ffprobe), "-v", "error", "-show_entries",
        "format=duration,size,bit_rate:stream=index,codec_type,codec_name,width,height,avg_frame_rate,r_frame_rate,time_base,start_time,duration",
        "-of", "json", str(media),
    ], text=True)
    return json.loads(result)


def validate_packet_timestamps(ffprobe: pathlib.Path, media: pathlib.Path) -> dict[str, object]:
    process = subprocess.Popen([
        str(ffprobe), "-v", "error", "-show_packets", "-show_entries",
        "packet=stream_index,pts,dts,pts_time,dts_time", "-of", "compact=p=0:nk=0", str(media),
    ], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    assert process.stdout is not None
    stats: dict[str, dict[str, object]] = {}
    packet_count = 0
    for line in process.stdout:
        values = {}
        for field in line.rstrip("\r\n").split("|"):
            if "=" in field:
                key, value = field.split("=", 1)
                values[key] = value
        if values.get("stream_index") is None:
            continue
        index = values["stream_index"]
        pts = float(values["pts_time"]) if values.get("pts_time") not in (None, "N/A") else None
        dts = float(values["dts_time"]) if values.get("dts_time") not in (None, "N/A") else None
        row = stats.setdefault(index, {
            "packets": 0, "firstPtsSeconds": None, "lastPtsSeconds": None,
            "firstDtsSeconds": None, "lastDtsSeconds": None,
            "maxPtsRegressionSeconds": 0.0,
        })
        if pts is not None:
            previous_pts = row["lastPtsSeconds"]
            if previous_pts is None:
                row["firstPtsSeconds"] = pts
            else:
                regression = float(previous_pts) - pts
                row["maxPtsRegressionSeconds"] = max(float(row["maxPtsRegressionSeconds"]), regression)
                if regression > PTS_REORDER_TOLERANCE_SECONDS:
                    process.kill()
                    raise ValueError(
                        f"PTS regressed by {regression:.6f}s in stream {index}; likely timestamp reset"
                    )
            row["lastPtsSeconds"] = pts
        if dts is not None:
            previous_dts = row["lastDtsSeconds"]
            if previous_dts is None:
                row["firstDtsSeconds"] = dts
            elif dts <= float(previous_dts):
                process.kill()
                raise ValueError(f"DTS did not advance in stream {index}: {previous_dts} -> {dts}")
            row["lastDtsSeconds"] = dts
        row["packets"] = int(row["packets"]) + 1
        packet_count += 1

    stderr = process.stderr.read() if process.stderr else ""
    return_code = process.wait()
    if return_code != 0:
        raise RuntimeError(f"ffprobe packet scan failed ({return_code}): {stderr[-2000:]}")
    if packet_count == 0 or not stats:
        raise ValueError("ffprobe returned no packets")
    if any(row["firstPtsSeconds"] is None or row["lastPtsSeconds"] is None
           or row["firstDtsSeconds"] is None or row["lastDtsSeconds"] is None for row in stats.values()):
        raise ValueError("packet stream is missing PTS or DTS")
    return {"packetCount": packet_count, "streams": stats,
            "ptsRegressionToleranceSeconds": PTS_REORDER_TOLERANCE_SECONDS}


def segment_list(root: pathlib.Path) -> list[pathlib.Path]:
    assets = [root / f"segment_{index:03d}.ts" for index in range(SEGMENT_COUNT)]
    missing = [str(path) for path in assets if not path.is_file()]
    if missing:
        raise FileNotFoundError(f"missing expected TS segment(s): {missing[:4]}")
    for path in assets:
        if path.stat().st_size == 0 or path.stat().st_size % 188:
            raise ValueError(f"segment is not nonempty and 188-byte aligned: {path.name}")
    return assets


def concat_manifest(assets: list[pathlib.Path], manifest: pathlib.Path) -> None:
    rows = []
    for asset in assets:
        # FFmpeg concat demuxer accepts quoted absolute paths; escape single quotes.
        escaped = str(asset.resolve()).replace("'", "'\\''")
        rows.append(f"file '{escaped}'")
    manifest.write_text("\n".join(rows) + "\n", encoding="utf-8")


def prepare_media(segment_root: pathlib.Path, output: pathlib.Path,
                  ffmpeg: pathlib.Path, ffprobe: pathlib.Path) -> dict[str, object]:
    if output.exists():
        raise FileExistsError(f"refusing to overwrite existing output: {output}")
    assets = segment_list(segment_root.resolve(strict=True))
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="raw-ts-concat-") as temp:
        manifest = pathlib.Path(temp) / "segments.ffconcat"
        concat_manifest(assets, manifest)
        command = [
            str(ffmpeg), "-hide_banner", "-nostdin", "-loglevel", "warning",
            "-f", "concat", "-safe", "0", "-i", str(manifest),
            "-map", "0", "-c", "copy", "-mpegts_flags", "+resend_headers",
            "-muxdelay", "0", "-muxpreload", "0", "-f", "mpegts", str(output),
        ]
        result = subprocess.run(command, capture_output=True, text=True)
        if result.returncode != 0:
            raise RuntimeError(f"ffmpeg remux failed ({result.returncode}): {result.stderr[-4000:]}")

    transport = scan_transport_stream(output)
    metadata = ffprobe_metadata(ffprobe, output)
    fmt = metadata.get("format", {})
    duration = float(fmt.get("duration", 0))
    streams = metadata.get("streams", [])
    videos = [stream for stream in streams if stream.get("codec_type") == "video"]
    audios = [stream for stream in streams if stream.get("codec_type") == "audio"]
    if len(videos) != 1 or videos[0].get("codec_name") != "h264":
        raise ValueError(f"expected one H.264 video stream, got {videos}")
    if len(audios) != 1 or audios[0].get("codec_name") != "aac":
        raise ValueError(f"expected one AAC audio stream, got {audios}")
    if abs(duration - EXPECTED_DURATION_SECONDS) > 2.0:
        raise ValueError(f"remux duration {duration:.3f}s is not approximately 600s")
    timestamp_scan = validate_packet_timestamps(ffprobe, output)
    return {
        "inputSegmentRoot": str(segment_root.resolve()),
        "inputSegmentCount": len(assets),
        "inputSegmentBytes": sum(asset.stat().st_size for asset in assets),
        "outputFile": str(output.resolve()),
        "outputBytes": output.stat().st_size,
        "outputSha256": hashlib.sha256(output.read_bytes()).hexdigest(),
        "transportScan": transport,
        "ffprobe": metadata,
        "timestampScan": timestamp_scan,
        "ffmpegCommand": command,
        "looped": False,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--segment-root", required=True, type=pathlib.Path)
    parser.add_argument("--output", required=True, type=pathlib.Path)
    parser.add_argument("--ffmpeg", required=True, type=pathlib.Path)
    parser.add_argument("--ffprobe", required=True, type=pathlib.Path)
    parser.add_argument("--report", required=True, type=pathlib.Path)
    args = parser.parse_args()
    result = prepare_media(args.segment_root, args.output, args.ffmpeg, args.ffprobe)
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(result, indent=2), encoding="utf-8")
    print(json.dumps({key: result[key] for key in (
        "inputSegmentCount", "outputFile", "outputBytes", "outputSha256",
        "transportScan", "timestampScan", "looped",
    )}, indent=2), flush=True)


if __name__ == "__main__":
    main()
