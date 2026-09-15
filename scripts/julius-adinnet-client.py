#!/usr/bin/env python3
"""Minimal client for Julius running as `-input adinnet -module <port>`.

This talks to a Julius process started like:

    julius -h <hmm> -hlist <hlist> -gram <grammar-prefix> \
        -input adinnet -adport 5530 -module 10500 -nocutsilence

over two TCP connections:

  - the *module* port (default 10500): a line-oriented XML protocol.
    Julius pushes recognition results here as they become available. Each
    logical message is one or more lines followed by a line containing a
    single "." (see julius/output_module.c). Nothing needs to be written
    to this socket by the client; it is read-only from our side.

  - the *adinnet* port (default 5530): the raw audio feed. Julius acts as
    the TCP *server* on both ports and does not open the adinnet listen
    socket until a module client has connected first, so this script
    always connects to the module port before the adinnet port.

    The adinnet wire format (libsent/src/adin/adin_tcpip.c, rd()/wt() in
    libsent/src/net/rdwt.c) is:

      - a 4-byte *native-endian* (not network-byte-order!) signed int
        giving the byte length of the following chunk, followed by that
        many bytes of raw s16le mono PCM samples at the model's sample
        rate (16000 Hz for our acoustic model);
      - a segment (utterance) is ended by sending a 4-byte int 0 with no
        payload -- Julius treats this as "end of segment" and runs the
        2nd pass / emits a <RECOGOUT> on the module socket, but keeps the
        adinnet connection open for the next segment;
      - closing the adinnet TCP connection (or sending a negative-length
        frame) ends the whole input stream. We only do the former, at
        script exit.

    Both sides must agree on byte order; since our Julius binary and this
    script both run little-endian (armv7a Android device / x86_64-ish
    macOS host respectively), plain native "<i"/">i" struct packing on
    each side is fine -- we use "<i" (explicit little-endian) here since
    both the device (ARM, LE) and typical hosts running this script are
    little-endian, matching how the vendored adin_tcpip.c does no byte
    swapping unless WORDS_BIGENDIAN is defined.

Usage example (after `adb forward tcp:10500 tcp:10500` and
`adb forward tcp:5530 tcp:5530` to a device running Julius as above):

    python3 scripts/julius-adinnet-client.py \
        --segment .tools/hello-butler-ja-rec1.pcm:10.5:12.2 \
        --segment .tools/japanese-speech-neg1.pcm:5:8

Each --segment is `path:start_seconds:end_seconds` against a 16kHz mono
s16le raw PCM file (no WAV header). Segments are streamed one at a time
over a single adinnet connection, in order; after each segment's
end-of-segment marker, this script waits (up to --result-timeout seconds
of *inactivity* on the module socket, i.e. it keeps waiting as long as
data keeps arriving) for a "<ENDRECOG/>" (or a REJECTED/RECOGFAIL message)
before printing everything received for that segment and moving on.
"""

from __future__ import annotations

import argparse
import queue
import socket
import struct
import sys
import threading
import time
from pathlib import Path

CHUNK_SAMPLES = 1600  # ~100ms at 16kHz; matches the build brief's chunk size
SAMPLE_RATE = 16000
BYTES_PER_SAMPLE = 2


def parse_segment_arg(spec: str) -> tuple[Path, float, float]:
    parts = spec.split(":")
    if len(parts) != 3:
        raise argparse.ArgumentTypeError(
            f"--segment must be path:start_seconds:end_seconds, got: {spec!r}"
        )
    path_str, start_str, end_str = parts
    path = Path(path_str)
    start, end = float(start_str), float(end_str)
    if end <= start:
        raise argparse.ArgumentTypeError(f"segment end must be after start: {spec!r}")
    return path, start, end


def load_pcm_slice(path: Path, start_s: float, end_s: float) -> bytes:
    start_byte = int(start_s * SAMPLE_RATE) * BYTES_PER_SAMPLE
    end_byte = int(end_s * SAMPLE_RATE) * BYTES_PER_SAMPLE
    with path.open("rb") as f:
        f.seek(start_byte)
        data = f.read(end_byte - start_byte)
    if not data:
        raise SystemExit(f"error: read 0 bytes for segment {path}:{start_s}:{end_s}")
    return data


class ModuleReader(threading.Thread):
    """Background reader for the module (result) socket.

    Buffers raw bytes, splits on newlines, and pushes each line onto a
    queue so the main thread can consume them without blocking on recv().
    """

    def __init__(self, sock: socket.socket):
        super().__init__(daemon=True)
        self.sock = sock
        self.lines: "queue.Queue[str]" = queue.Queue()
        self._buf = b""
        self._stop = False

    def run(self) -> None:
        self.sock.settimeout(0.5)
        while not self._stop:
            try:
                chunk = self.sock.recv(4096)
            except socket.timeout:
                continue
            except OSError:
                break
            if not chunk:
                break
            self._buf += chunk
            while b"\n" in self._buf:
                line, self._buf = self._buf.split(b"\n", 1)
                self.lines.put(line.decode("utf-8", errors="replace"))

    def stop(self) -> None:
        self._stop = True


def send_segment(adin_sock: socket.socket, pcm: bytes, chunk_samples: int = CHUNK_SAMPLES) -> None:
    chunk_bytes = chunk_samples * BYTES_PER_SAMPLE
    for off in range(0, len(pcm), chunk_bytes):
        piece = pcm[off : off + chunk_bytes]
        adin_sock.sendall(struct.pack("<i", len(piece)))
        adin_sock.sendall(piece)
    # end-of-segment marker: 4-byte int 0, no payload
    adin_sock.sendall(struct.pack("<i", 0))


def drain_until_quiet(reader: ModuleReader, result_timeout: float) -> list[str]:
    """Collect lines until no new line has arrived for `result_timeout` seconds,
    or until an <ENDRECOG/> / <RECOGFAIL.../> / <REJECTED.../> line is seen
    (then we still drain briefly to catch the trailing "." terminator)."""
    collected: list[str] = []
    saw_end_marker = False
    deadline = time.monotonic() + result_timeout
    while True:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            break
        try:
            line = reader.lines.get(timeout=remaining)
        except queue.Empty:
            break
        collected.append(line)
        deadline = time.monotonic() + result_timeout
        if any(
            marker in line
            for marker in ("<ENDRECOG", "<RECOGFAIL", "<REJECTED")
        ):
            saw_end_marker = True
            # give it a brief extra moment to flush the closing "." line
            deadline = min(deadline, time.monotonic() + 0.5)
    if not saw_end_marker and collected:
        print("(warning: no <ENDRECOG/>-class terminator seen before timeout)", file=sys.stderr)
    return collected


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--host", default="127.0.0.1", help="host where the ports are reachable (default: 127.0.0.1, e.g. via adb forward)")
    ap.add_argument("--module-port", type=int, default=10500)
    ap.add_argument("--adin-port", type=int, default=5530)
    ap.add_argument("--segment", action="append", type=parse_segment_arg, required=True,
                     help="path:start_seconds:end_seconds against a 16kHz mono s16le raw PCM file; may be repeated")
    ap.add_argument("--chunk-samples", type=int, default=CHUNK_SAMPLES)
    ap.add_argument("--result-timeout", type=float, default=8.0,
                     help="seconds of inactivity on the module socket before giving up on a segment's result (default: 8)")
    ap.add_argument("--connect-timeout", type=float, default=10.0)
    args = ap.parse_args()

    print(f"connecting to module port {args.host}:{args.module_port} ...")
    module_sock = socket.create_connection((args.host, args.module_port), timeout=args.connect_timeout)
    print("module connected. Julius should now open its adinnet listen socket.")
    reader = ModuleReader(module_sock)
    reader.start()

    # small grace period: give Julius a moment to move from module_connect()
    # into adin_tcpip_standby()/accept() before we dial the adinnet port.
    time.sleep(0.3)

    print(f"connecting to adinnet port {args.host}:{args.adin_port} ...")
    adin_sock = socket.create_connection((args.host, args.adin_port), timeout=args.connect_timeout)
    print("adinnet connected.\n")

    try:
        for i, (path, start_s, end_s) in enumerate(args.segment, 1):
            pcm = load_pcm_slice(path, start_s, end_s)
            print(f"=== segment {i}: {path} [{start_s:.2f}s-{end_s:.2f}s], {len(pcm)} bytes ===")
            send_segment(adin_sock, pcm, args.chunk_samples)
            print("  (sent, waiting for module output...)")
            lines = drain_until_quiet(reader, args.result_timeout)
            if lines:
                print("  --- module output ---")
                for line in lines:
                    print(f"  {line}")
                print("  --- end module output ---\n")
            else:
                print("  (no module output received before timeout)\n")
    finally:
        reader.stop()
        try:
            adin_sock.close()
        except OSError:
            pass
        try:
            module_sock.close()
        except OSError:
            pass


if __name__ == "__main__":
    main()
