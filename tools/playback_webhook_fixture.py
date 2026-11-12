"""Local-only playback/webhook acceptance fixture. No external provider calls."""

import argparse, json, mimetypes, re, threading, time
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
from pathlib import Path
from urllib.parse import urlparse

parser = argparse.ArgumentParser()
parser.add_argument("--root", required=True)
parser.add_argument("--port", type=int, default=8877)
args = parser.parse_args()
root = Path(args.root).resolve()
lock = threading.Lock()
events = []
started = time.time()


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def reply(self, body, content="application/json", status=200):
        self.send_response(status)
        self.send_header("Content-Type", content)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        body = self.rfile.read(min(int(self.headers.get("Content-Length", "0")), 32768))
        try:
            data = json.loads(body)
        except Exception:
            return self.reply(b"{}", status=400)
        if self.headers.get("Authorization") != "Bearer fixture-token":
            return self.reply(b"{}", status=401)
        with lock:
            events.append(
                {"destination": self.path, "payload": data, "time": time.time()}
            )
            (root / "events.json").write_text(
                json.dumps(events, ensure_ascii=False, indent=2), encoding="utf-8"
            )
        self.reply(b"{}")

    def do_GET(self):
        path = urlparse(self.path).path
        if path == "/events":
            with lock:
                return self.reply(json.dumps(events).encode())
        if path == "/playlist.m3u":
            text = "#EXTM3U\n"
            for i, name in enumerate(["Webhook News", "Webhook Sports"]):
                text += f'#EXTINF:-1 tvg-id="webhook-{i}" group-title="Webhook",{name}\nhttp://10.0.2.2:{args.port}/live.m3u8?channel={i}\n'
            return self.reply(text.encode(), "audio/x-mpegurl")
        if path == "/live.m3u8":
            segments = sorted(root.glob("segment*.ts"))
            if not segments:
                return self.reply(b"{}", status=503)
            head = max(8, int((time.time() - started) / 2))
            first = head - 7
            text = f"#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:{first}\n#EXT-X-DISCONTINUITY-SEQUENCE:{(first - 1) // len(segments)}\n"
            for i in range(first, head + 1):
                if i % len(segments) == 0:
                    text += "#EXT-X-DISCONTINUITY\n"
                text += f"#EXTINF:2.000,\n{segments[i % len(segments)].name}\n"
            return self.reply(text.encode(), "application/vnd.apple.mpegurl")
        filename = root / path.lstrip("/")
        if filename.parent != root or not filename.is_file():
            return self.reply(b"{}", status=404)
        size = filename.stat().st_size
        start = 0
        end = size - 1
        status = 200
        if self.headers.get("Range"):
            match = re.fullmatch(r"bytes=(\d+)-(\d*)", self.headers["Range"])
            if not match:
                return self.reply(b"", status=416)
            start = int(match[1])
            end = min(int(match[2]) if match[2] else end, end)
            status = 206
        if start > end:
            return self.reply(b"", status=416)
        self.send_response(status)
        self.send_header(
            "Content-Type",
            "application/vnd.apple.mpegurl"
            if path.endswith("m3u8")
            else mimetypes.guess_type(path)[0] or "video/mp2t",
        )
        self.send_header("Cache-Control", "no-store")
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Length", str(end - start + 1))
        if status == 206:
            self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        self.end_headers()
        try:
            with filename.open("rb") as stream:
                stream.seek(start)
                left = end - start + 1
                while left > 0:
                    chunk = stream.read(min(left, 65536))
                    if not chunk:
                        break
                    self.wfile.write(chunk)
                    left -= len(chunk)
        except (ConnectionError, OSError):
            pass


ThreadingHTTPServer(("0.0.0.0", args.port), Handler).serve_forever()

