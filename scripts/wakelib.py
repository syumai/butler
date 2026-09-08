"""Shared helpers for wake-phrase tuning scripts.

Used by `eval-recording.py` and `grid-phrase-lines.py`. `check-fixtures.py`
deliberately stays self-contained (it predates this module and its Part A/B
behavior must not change), so it does not import from here.
"""
from __future__ import annotations

import array
from pathlib import Path

import sherpa_onnx

ROOT = Path(__file__).resolve().parents[1]
WAKE_ASSETS_DIR = ROOT / "app/src/main/assets/wake"
FIXTURES_DIR = ROOT / "app/src/androidTest/assets"
SAMPLE_RATE = 16000

# The WakePhrase default keywords_score and the settings default
# wakeThreshold (see check-fixtures.py / eval-recording.py), used as the
# fallback when a caller does not need to override them.
DEFAULT_KEYWORDS_SCORE = 3.0
DEFAULT_KEYWORDS_THRESHOLD = 0.25

# Hits within this many seconds of each other are treated as one spoken
# repetition rather than double-counted (matches eval-recording.py).
MERGE_HIT_SECONDS = 2.0


def to_bpe_token(tok: str) -> str:
    """sherpa_onnx's OnlineRecognizer.tokens() returns the BPE word-boundary
    marker '▁' as a literal leading space (e.g. ' HE' instead of '▁HE') so it
    reads like normal text. Convert it back to '▁' so the printed sequence
    is directly comparable to phrase-file lines such as
    `▁HE Y ▁BU G G LE R` in app/src/main/assets/wake/*.txt."""
    if tok.startswith(" "):
        return "▁" + tok[1:]
    return tok


def load_pcm_as_float(path: Path) -> array.array:
    """Load a raw 16kHz mono s16le PCM file (no header) as float32 samples
    in [-1, 1], matching what WakeInstrumentation records."""
    data = path.read_bytes()
    shorts = array.array("h")
    shorts.frombytes(data[: len(data) - (len(data) % 2)])
    return array.array("f", (s / 32768.0 for s in shorts))


def pad(samples: array.array, leading: int, trailing: int) -> array.array:
    out = array.array("f", [0.0] * leading)
    out.extend(samples)
    out.extend([0.0] * trailing)
    return out


def create_keyword_spotter(
    phrase_file: Path,
    score: float = DEFAULT_KEYWORDS_SCORE,
    threshold: float = DEFAULT_KEYWORDS_THRESHOLD,
) -> sherpa_onnx.KeywordSpotter:
    """Build a KeywordSpotter against the bundled app model (same model
    path/construction as check-fixtures.py), using `phrase_file` as the
    keyword list at the given global score/threshold. A line's own
    `:score #threshold` suffix (if any) still overrides these globally."""
    return sherpa_onnx.KeywordSpotter(
        tokens=str(WAKE_ASSETS_DIR / "tokens.txt"),
        encoder=str(WAKE_ASSETS_DIR / "encoder.onnx"),
        decoder=str(WAKE_ASSETS_DIR / "decoder.onnx"),
        joiner=str(WAKE_ASSETS_DIR / "joiner.onnx"),
        keywords_file=str(phrase_file),
        num_threads=1,
        sample_rate=SAMPLE_RATE,
        keywords_score=score,
        keywords_threshold=threshold,
    )


def collect_hits(spotter: sherpa_onnx.KeywordSpotter, samples: array.array) -> list[float]:
    """Feed `samples` in 1600-sample chunks (matching WakeInstrumentation's
    feed()) and return the time (in seconds from the start of `samples`) of
    every KeywordSpotter hit."""
    stream = spotter.create_stream()
    hits: list[float] = []
    for offset in range(0, len(samples), 1600):
        chunk = samples[offset : offset + 1600]
        stream.accept_waveform(SAMPLE_RATE, chunk)
        while spotter.is_ready(stream):
            spotter.decode_stream(stream)
            if spotter.get_result(stream).strip():
                hits.append((offset + len(chunk)) / SAMPLE_RATE)
                spotter.reset_stream(stream)
    return hits


def merge_hits(hits: list[float], gap: float = MERGE_HIT_SECONDS) -> list[float]:
    """Collapse hits within `gap` seconds of the previous (kept) hit into a
    single distinct-utterance hit, keeping the first hit's timestamp."""
    merged: list[float] = []
    for hit in hits:
        if merged and hit - merged[-1] < gap:
            continue
        merged.append(hit)
    return merged
