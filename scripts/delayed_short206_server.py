#!/usr/bin/env python3
import argparse
import re
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

RANGE_RE = re.compile(r"bytes=(\d+)-(\d*)$")


def parse_args():
    parser = argparse.ArgumentParser(description="Serve one MP4 with delayed intentionally short 206 responses.")
    parser.add_argument("--file", required=True)
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=18080)
    parser.add_argument("--max-body-bytes", type=int, default=8192)
    parser.add_argument("--delay-ms", type=float, default=12.0)
    return parser.parse_args()


def main():
    args = parse_args()
    media = Path(args.file).resolve()
    if not media.is_file():
        raise SystemExit(f"fixture file does not exist: {media}")
    if args.max_body_bytes <= 0:
        raise SystemExit("--max-body-bytes must be positive")
    if args.delay_ms < 0:
        raise SystemExit("--delay-ms must be non-negative")
    size = media.stat().st_size
    if size <= 0:
        raise SystemExit("fixture file is empty")

    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def do_HEAD(self):
            if self.path.split("?", 1)[0] != "/video.mp4":
                self.send_error(404)
                return
            self.send_response(200)
            self.send_header("Accept-Ranges", "bytes")
            self.send_header("Content-Type", "video/mp4")
            self.send_header("Content-Length", str(size))
            self.end_headers()
            print(f"FC2_SHORT206 method=HEAD size={size}", flush=True)

        def do_GET(self):
            if self.path.split("?", 1)[0] != "/video.mp4":
                self.send_error(404)
                return
            range_value = self.headers.get("Range", "")
            match = RANGE_RE.fullmatch(range_value.strip())
            if match is None:
                self.send_response(200)
                self.send_header("Accept-Ranges", "bytes")
                self.send_header("Content-Type", "video/mp4")
                self.send_header("Content-Length", str(size))
                self.end_headers()
                with media.open("rb") as source:
                    while True:
                        chunk = source.read(64 * 1024)
                        if not chunk:
                            break
                        self.wfile.write(chunk)
                print(f"FC2_SHORT206 method=GET mode=full bytes={size}", flush=True)
                return

            start = int(match.group(1))
            requested_end = int(match.group(2)) if match.group(2) else size - 1
            if start >= size or requested_end < start:
                self.send_response(416)
                self.send_header("Content-Range", f"bytes */{size}")
                self.send_header("Content-Length", "0")
                self.end_headers()
                print(f"FC2_SHORT206 method=GET mode=range status=416 start={start} end={requested_end}", flush=True)
                return

            requested_end = min(requested_end, size - 1)
            actual_end = min(requested_end, start + args.max_body_bytes - 1)
            body_length = actual_end - start + 1
            if args.delay_ms:
                time.sleep(args.delay_ms / 1000.0)
            self.send_response(206)
            self.send_header("Accept-Ranges", "bytes")
            self.send_header("Content-Type", "video/mp4")
            self.send_header("Content-Range", f"bytes {start}-{actual_end}/{size}")
            self.send_header("Content-Length", str(body_length))
            self.end_headers()
            with media.open("rb") as source:
                source.seek(start)
                body = source.read(body_length)
            self.wfile.write(body)
            print(
                "FC2_SHORT206 method=GET mode=range "
                f"requested={start}-{requested_end} returned={start}-{actual_end} "
                f"bytes={body_length} delay_ms={args.delay_ms:g}",
                flush=True,
            )

        def log_message(self, fmt, *values):
            return

    server = ThreadingHTTPServer((args.host, args.port), Handler)
    print(
        f"FC2_SHORT206_READY host={args.host} port={args.port} size={size} "
        f"max_body_bytes={args.max_body_bytes} delay_ms={args.delay_ms:g}",
        flush=True,
    )
    try:
        server.serve_forever()
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
