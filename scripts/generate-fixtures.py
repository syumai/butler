#!/usr/bin/env python3
"""Generate the wake-word instrumentation test fixtures.

Regenerates `app/src/androidTest/assets/*.pcm` with the OpenAI TTS API
(`POST /v1/audio/speech`).

Requires Python 3 (stdlib only) and `ffmpeg` on PATH.
"""
from __future__ import annotations

import argparse
import array
import datetime
import json
import math
import os
import shutil
import struct
import subprocess
import sys
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

# Current OpenAI TTS model and a neutral built-in voice (verified against
# https://developers.openai.com/api/reference/resources/audio/subresources/speech/methods/create
# on 2026-09-07: model one of tts-1 / tts-1-hd / gpt-4o-mini-tts /
# gpt-4o-mini-tts-2025-12-15; voice one of alloy / ash / ballad / coral /
# echo / fable / onyx / nova / sage / shimmer / verse / marin / cedar).
MODEL = "gpt-4o-mini-tts"
VOICE = "alloy"

API_URL = "https://api.openai.com/v1/audio/speech"
KEY_FILE = ROOT / ".tools/openai.key"

SAMPLE_RATE = 16000
TARGET_PEAK_DBFS = -3.0
FORBIDDEN_WORDS = ("hello", "hey", "hi", "butler", "computer", "world")

# name -> spoken text. The first four are the wake phrases the app listens
# for; ordinary-speech is a neutral sentence that must not contain any of
# them (word-boundary checked below) so it can be used as a negative case.
FIXTURES = {
    "hello-butler": "Hello Butler",
    "hello-computer": "Hello Computer",
    "hello-world": "Hello World",
    "hey-butler": "Hey Butler",
    "ordinary-speech": "Clouds are rolling in, so bring an umbrella this afternoon.",
}


def check_ordinary_speech() -> None:
    import re

    text = FIXTURES["ordinary-speech"]
    pattern = re.compile(r"\b(" + "|".join(FORBIDDEN_WORDS) + r")\b", re.IGNORECASE)
    match = pattern.search(text)
    if match:
        raise SystemExit(
            f"ordinary-speech text contains forbidden word {match.group(0)!r}: {text!r}"
        )


def read_api_key() -> str:
    key = os.environ.get("OPENAI_API_KEY", "").strip()
    if key:
        return key
    if KEY_FILE.exists():
        key = KEY_FILE.read_text().strip()
        if key:
            return key
    raise SystemExit(
        "No OpenAI API key found. Set OPENAI_API_KEY in the environment, or "
        f"place the key (trimmed) in {KEY_FILE}."
    )


def request_speech(api_key: str, model: str, voice: str, text: str) -> bytes:
    payload = json.dumps(
        {
            "model": model,
            "voice": voice,
            "input": text,
            "response_format": "wav",
        }
    ).encode("utf-8")
    req = urllib.request.Request(
        API_URL,
        data=payload,
        method="POST",
        headers={
            "Authorization": f"Bearer {api_key}",
            "Content-Type": "application/json",
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            return resp.read()
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", errors="replace")
        raise SystemExit(f"OpenAI TTS request failed: HTTP {e.code}\n{body}")


def ffmpeg_path() -> str:
    path = shutil.which("ffmpeg") or "/opt/homebrew/bin/ffmpeg"
    if not Path(path).exists() and not shutil.which(path):
        raise SystemExit("ffmpeg not found on PATH")
    return path


def trim_resample_to_pcm(ffmpeg: str, wav_path: Path, pcm_path: Path) -> None:
    """Trim leading/trailing silence (keeping ~100ms padding), resample to
    16kHz mono, and write raw s16le PCM."""
    silenceremove = (
        "silenceremove="
        "start_periods=1:start_threshold=-40dB:start_silence=0.1:"
        "stop_periods=1:stop_threshold=-40dB:stop_silence=0.1"
    )
    subprocess.run(
        [
            ffmpeg,
            "-y",
            "-loglevel", "error",
            "-i", str(wav_path),
            "-af", silenceremove,
            "-ar", str(SAMPLE_RATE),
            "-ac", "1",
            "-f", "s16le",
            str(pcm_path),
        ],
        check=True,
    )


def normalize_peak(pcm_path: Path, target_dbfs: float = TARGET_PEAK_DBFS) -> None:
    """Scale s16le samples in place so the peak sample hits target_dbfs."""
    data = pcm_path.read_bytes()
    if len(data) % 2 != 0:
        data = data[:-1]
    samples = array.array("h")
    samples.frombytes(data)
    if sys.byteorder == "big":
        samples.byteswap()
    peak = max((abs(s) for s in samples), default=0)
    if peak == 0:
        return  # silent fixture; leave as-is (caller should have raised earlier)
    target_peak = 32767.0 * (10.0 ** (target_dbfs / 20.0))
    gain = target_peak / peak
    out = array.array("h", (max(-32768, min(32767, round(s * gain))) for s in samples))
    if sys.byteorder == "big":
        out.byteswap()
    pcm_path.write_bytes(out.tobytes())


def measure(pcm_path: Path) -> tuple[int, float, float]:
    """Return (bytes, duration_seconds, peak_dbfs)."""
    data = pcm_path.read_bytes()
    samples = array.array("h")
    body = data[: len(data) - (len(data) % 2)]
    samples.frombytes(body)
    if sys.byteorder == "big":
        samples.byteswap()
    peak = max((abs(s) for s in samples), default=0)
    peak_dbfs = 20 * math.log10(peak / 32768.0) if peak else float("-inf")
    duration = len(samples) / SAMPLE_RATE
    return len(data), duration, peak_dbfs


def write_manifest(out_dir: Path, model: str, voice: str, names: list[str]) -> None:
    manifest = out_dir / "FIXTURES.md"
    date = datetime.date.today().isoformat()
    lines = [
        "# Fixture provenance",
        "",
        "These `.pcm` fixtures (16 kHz mono PCM16 little-endian raw audio) are "
        "synthetic speech generated with the OpenAI TTS API "
        "(`POST /v1/audio/speech`) via `scripts/generate-fixtures.py`.",
        "",
        f"- Generator: `scripts/generate-fixtures.py`",
        f"- Model: `{model}`",
        f"- Voice: `{voice}`",
        f"- Generated: {date}",
        "",
        "| Fixture | Text |",
        "| --- | --- |",
    ]
    for name in names:
        lines.append(f"| `{name}.pcm` | {FIXTURES[name]} |")
    lines.append("")
    manifest.write_text("\n".join(lines))
    print(f"wrote {manifest}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model", default=MODEL, help=f"OpenAI TTS model (default: {MODEL})")
    parser.add_argument("--voice", default=VOICE, help=f"OpenAI TTS voice (default: {VOICE})")
    parser.add_argument("--only", help="Generate only this fixture name (e.g. hey-butler)")
    parser.add_argument(
        "--out-dir",
        default=str(ROOT / "app/src/androidTest/assets"),
        help="Output directory for the .pcm files and FIXTURES.md",
    )
    parser.add_argument(
        "--from-wav",
        metavar="DIR",
        help="Debug: read <name>.wav from DIR instead of calling the OpenAI API "
        "(skips network access; still runs the ffmpeg trim/resample/normalize path)",
    )
    args = parser.parse_args()

    check_ordinary_speech()

    names = [args.only] if args.only else list(FIXTURES.keys())
    for name in names:
        if name not in FIXTURES:
            raise SystemExit(f"Unknown fixture {name!r}; choices: {', '.join(FIXTURES)}")

    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    ffmpeg = ffmpeg_path()

    api_key = None
    if not args.from_wav:
        api_key = read_api_key()

    import tempfile

    for name in names:
        text = FIXTURES[name]
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            if args.from_wav:
                wav_path = Path(args.from_wav) / f"{name}.wav"
                if not wav_path.exists():
                    raise SystemExit(f"--from-wav: missing {wav_path}")
            else:
                print(f"requesting speech for {name!r} ({args.model}/{args.voice}) ...")
                audio_bytes = request_speech(api_key, args.model, args.voice, text)
                wav_path = tmp / f"{name}.wav"
                wav_path.write_bytes(audio_bytes)

            pcm_path = out_dir / f"{name}.pcm"
            trim_resample_to_pcm(ffmpeg, wav_path, pcm_path)
            normalize_peak(pcm_path)

            size, duration, peak_dbfs = measure(pcm_path)
            print(
                f"{name}.pcm: {size} bytes, {duration:.2f}s, peak={peak_dbfs:.1f} dBFS"
            )

    write_manifest(out_dir, args.model, args.voice, names)


if __name__ == "__main__":
    main()
