#!/usr/bin/env python3
"""Generate the wake-word instrumentation test fixtures.

Regenerates `app/src/androidTest/assets/*.pcm` with the OpenAI TTS API
(`POST /v1/audio/speech`).

Each fixture can specify its own TTS voice and (optionally) `instructions`
(a free-form style/delivery hint the `gpt-4o-mini-tts` model accepts), so
e.g. the Japanese-pronunciation fixtures can ask for "katakana English"
delivery while the original English fixtures keep using the plain
alloy voice with no instructions (so their generated audio is unchanged).

Requires Python 3 (stdlib only) and `ffmpeg` on PATH.
"""
from __future__ import annotations

import argparse
import array
import dataclasses
import datetime
import json
import math
import os
import re
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
# Katakana forms of the same wake-related words, for the Japanese negative
# fixtures (simple substring check; Japanese has no word-boundary regex
# equivalent to \b, so this deliberately only checks these fixed strings).
FORBIDDEN_WORDS_JA = ("ハロー", "バトラー", "バター")


@dataclasses.dataclass(frozen=True)
class Fixture:
    text: str
    voice: str = VOICE
    instructions: str | None = None


# name -> Fixture(text, voice, instructions). hello-butler, hey-butler and
# ordinary-speech keep their original voice (alloy) and no instructions, so
# regenerating them produces the same audio as before. hello-computer and
# hello-world were dropped once the wake phrase was fixed to Hello Butler
# and the keyword-spotter engine that used them was removed (see
# docs/device-validation.md). The rest are additions for the Hello Butler /
# Japanese-pronunciation wake phrase work:
#   - hello-butler-ja / hello-butler-ja-2: the Japanese pronunciation of
#     "Hello Butler" ("ハロー、バトラー"), two different voices/instructions
#     to cover more of the pronunciation variance a real speaker produces.
#   - ordinary-speech-ja: a neutral Japanese sentence containing none of the
#     wake words, for a Japanese negative case.
#   - near-miss-ja: short Japanese phrases that come close to the wake
#     phrase's sound (e.g. "バター" or "ハロー" alone) but must NOT trigger
#     detection, for a stricter Japanese negative case.
FIXTURES: dict[str, Fixture] = {
    "hello-butler": Fixture("Hello Butler"),
    "hey-butler": Fixture("Hey Butler"),
    "ordinary-speech": Fixture(
        "Clouds are rolling in, so bring an umbrella this afternoon."
    ),
    # Voice/instructions chosen by generating several candidates (alloy,
    # nova, onyx, echo, shimmer, ash, coral x with/without instructions)
    # and comparing their Part-A-style ASR decode (see
    # docs/device-validation.md, "Switching the wake phrase to Hello
    # Butler with Japanese pronunciation"). gpt-4o-mini-tts output for the
    # same voice/instructions is NOT deterministic across requests: repeat
    # runs of the same (voice, instructions) sometimes decode "Butler" as
    # a clean `▁BUT` token and sometimes as `▁BA ...` instead, and the
    # "hello" shape also varies. alloy and nova, both with no instructions,
    # were the most reliable at landing on a clean `▁BUT` across attempts;
    # instructions did not measurably help. The currently checked-in audio
    # for these two decodes as `▁HU D D LE ▁BUT U D A` (alloy) and
    # `▁HU D D LE ▁BUT TER` (nova) — see docs/device-validation.md for the
    # full set of candidates tried (historical: that tuning targeted the
    # since-removed keyword-spotter engine). Regenerating either fixture
    # will very likely produce different audio; re-run the on-device
    # WakeInstrumentation (see README.md) against the Vosk engine after
    # doing so.
    "hello-butler-ja": Fixture("ハロー、バトラー", voice="alloy"),
    "hello-butler-ja-2": Fixture("ハロー、バトラー", voice="nova"),
    "ordinary-speech-ja": Fixture(
        "今日は夕方から雨らしいから、洗濯物は早めに取り込んでおいてね。"
    ),
    "near-miss-ja": Fixture("バター取って"),
}


def check_ordinary_speech() -> None:
    text = FIXTURES["ordinary-speech"].text
    pattern = re.compile(r"\b(" + "|".join(FORBIDDEN_WORDS) + r")\b", re.IGNORECASE)
    match = pattern.search(text)
    if match:
        raise SystemExit(
            f"ordinary-speech text contains forbidden word {match.group(0)!r}: {text!r}"
        )
    text_ja = FIXTURES["ordinary-speech-ja"].text
    for word in FORBIDDEN_WORDS_JA:
        if word in text_ja:
            raise SystemExit(
                f"ordinary-speech-ja text contains forbidden word {word!r}: {text_ja!r}"
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


def request_speech(
    api_key: str,
    model: str,
    voice: str,
    text: str,
    instructions: str | None = None,
) -> bytes:
    body: dict[str, str] = {
        "model": model,
        "voice": voice,
        "input": text,
        "response_format": "wav",
    }
    if instructions:
        body["instructions"] = instructions
    payload = json.dumps(body).encode("utf-8")
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
    16kHz mono, and write raw s16le PCM.

    Trims leading silence, then reverses the audio and trims what is now
    leading silence (i.e. the original trailing silence), then reverses
    back. This avoids ffmpeg's `silenceremove` "stop" mode, which is a
    streaming detector: with `stop_periods=1` it commits to the *first*
    silence gap of qualifying length/threshold as if it were the trailing
    silence and drops everything from there on. A short mid-phrase pause
    (e.g. between "Hey" and "Butler") can trigger that at -40dB/100ms just
    as easily as genuine trailing silence, truncating real speech.
    Symmetric start-trimming (used twice, via reversal) only ever removes
    a single leading run of silence on each pass, so it can't fall into
    that trap.
    """
    start_trim = "silenceremove=start_periods=1:start_threshold=-40dB:start_silence=0.1"
    filt = f"{start_trim},areverse,{start_trim},areverse"
    subprocess.run(
        [
            ffmpeg,
            "-y",
            "-loglevel", "error",
            "-i", str(wav_path),
            "-af", filt,
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


def write_manifest(out_dir: Path, model: str, all_names: list[str]) -> None:
    """Regenerate the manifest for every fixture the FIXTURES dict knows
    about (not just the ones generated in this invocation), so `--only`
    runs don't drop rows for fixtures generated earlier."""
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
        f"- Generated: {date}",
        "",
        "| Fixture | Text | Voice | Instructions |",
        "| --- | --- | --- | --- |",
    ]
    for name in all_names:
        if name not in FIXTURES:
            continue
        fx = FIXTURES[name]
        lines.append(f"| `{name}.pcm` | {fx.text} | {fx.voice} | {fx.instructions or ''} |")
    lines.append("")
    manifest.write_text("\n".join(lines))
    print(f"wrote {manifest}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model", default=MODEL, help=f"OpenAI TTS model (default: {MODEL})")
    parser.add_argument(
        "--voice",
        default=None,
        help="Override the TTS voice for the fixture(s) generated (default: each "
        "fixture's own voice in FIXTURES). Only meaningful with --only.",
    )
    parser.add_argument(
        "--instructions",
        default=None,
        help="Override the TTS instructions for the fixture(s) generated (default: "
        "each fixture's own instructions in FIXTURES). Only meaningful with --only.",
    )
    parser.add_argument("--only", help="Generate only this fixture name (e.g. hey-butler)")
    parser.add_argument(
        "--out-dir",
        default=str(ROOT / "app/src/androidTest/assets"),
        help="Output directory for the .pcm files and FIXTURES.md",
    )
    parser.add_argument(
        "--no-manifest",
        action="store_true",
        help="Skip writing FIXTURES.md (e.g. for one-off experimental fixtures written "
        "outside the real assets directory).",
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
        fx = FIXTURES[name]
        voice = args.voice if args.voice is not None else fx.voice
        instructions = args.instructions if args.instructions is not None else fx.instructions
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            if args.from_wav:
                wav_path = Path(args.from_wav) / f"{name}.wav"
                if not wav_path.exists():
                    raise SystemExit(f"--from-wav: missing {wav_path}")
            else:
                print(f"requesting speech for {name!r} ({args.model}/{voice}, instructions={instructions!r}) ...")
                audio_bytes = request_speech(api_key, args.model, voice, fx.text, instructions)
                wav_path = tmp / f"{name}.wav"
                wav_path.write_bytes(audio_bytes)

            pcm_path = out_dir / f"{name}.pcm"
            trim_resample_to_pcm(ffmpeg, wav_path, pcm_path)
            normalize_peak(pcm_path)

            size, duration, peak_dbfs = measure(pcm_path)
            print(
                f"{name}.pcm: {size} bytes, {duration:.2f}s, peak={peak_dbfs:.1f} dBFS"
            )

    if not args.no_manifest:
        write_manifest(out_dir, args.model, list(FIXTURES.keys()))


if __name__ == "__main__":
    main()
