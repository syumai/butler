#!/usr/bin/env python3
"""Replicate the WakeInstrumentation ASR/KWS checks locally, without a device.

Run with the sherpa_onnx venv:

    .tools/wake-python/bin/python scripts/check-fixtures.py

Part A decodes each `app/src/androidTest/assets/*.pcm` fixture as plain ASR
(the fp32 upstream transducer model, greedy search) and prints the token
sequence, so phrase-file lines (e.g. `▁HE Y ▁BU G G LE R`) can be written or
tuned by comparing against what the model actually hears.

Part B runs the app's own KeywordSpotter (the bundled int8/fp32 model under
`app/src/main/assets/wake/`) against every phrase file for every fixture,
feeding audio in 1600-sample chunks with the same silence padding as
`WakeInstrumentation.feed()`, and reports a HIT/miss matrix plus PASS/FAIL
against the expected phrase-to-fixture mapping.
"""
from __future__ import annotations

import array
from pathlib import Path

import sherpa_onnx

ROOT = Path(__file__).resolve().parents[1]
UPSTREAM_MODEL_DIR = ROOT / ".tools/sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01"
WAKE_ASSETS_DIR = ROOT / "app/src/main/assets/wake"
FIXTURES_DIR = ROOT / "app/src/androidTest/assets"
SAMPLE_RATE = 16000

FIXTURE_NAMES = [
    "hey-butler.pcm",
    "hello-butler.pcm",
    "hello-butler-ja.pcm",
    "hello-butler-ja-2.pcm",
    "hello-computer.pcm",
    "hello-world.pcm",
    "ordinary-speech.pcm",
    "ordinary-speech-ja.pcm",
    "near-miss-ja.pcm",
]

# Phrase file -> (keywords_score, keywords_threshold), matching the
# WakePhrase enum defaults in LocalWakeWordEngine.kt (score) and the
# settings default threshold of 0.25.
PHRASE_FILES = [
    ("hey-butler.txt", 3.0, 0.25),
    ("hello-butler.txt", 3.0, 0.25),
    ("hello-computer.txt", 1.5, 0.25),
    ("keywords.txt", 1.5, 0.25),
]

# Phrase file -> fixtures it must detect (all of them) for a PASS.
EXPECTED_HITS: dict[str, list[str]] = {
    "hey-butler.txt": ["hey-butler.pcm"],
    "hello-butler.txt": ["hello-butler.pcm", "hello-butler-ja.pcm", "hello-butler-ja-2.pcm"],
    "hello-computer.txt": ["hello-computer.pcm"],
    "keywords.txt": ["hello-world.pcm"],
}

# Fixtures every phrase file must NOT detect; a hit here is a hard FAIL.
MANDATORY_NEGATIVES = ["ordinary-speech.pcm"]

# Fixtures hello-butler.txt must also not detect (its own, stricter
# negative set per the Hello Butler / Japanese-pronunciation tuning work).
# For the *other* phrase files, a hit on these is a WARN only, not a FAIL,
# since they were not tuned against these new Japanese fixtures.
HELLO_BUTLER_EXTRA_NEGATIVES = ["ordinary-speech-ja.pcm", "near-miss-ja.pcm"]
WARN_ONLY_NEGATIVES: dict[str, list[str]] = {
    "hey-butler.txt": HELLO_BUTLER_EXTRA_NEGATIVES,
    "hello-butler.txt": [],  # these are mandatory (hard FAIL) for hello-butler.txt, handled below.
    "hello-computer.txt": HELLO_BUTLER_EXTRA_NEGATIVES,
    "keywords.txt": HELLO_BUTLER_EXTRA_NEGATIVES,
}
# hello-butler.txt must not hit its extra negatives either, as a hard FAIL.
MANDATORY_NEGATIVES_BY_PHRASE: dict[str, list[str]] = {
    "hello-butler.txt": MANDATORY_NEGATIVES + HELLO_BUTLER_EXTRA_NEGATIVES,
}


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
    data = path.read_bytes()
    shorts = array.array("h")
    shorts.frombytes(data[: len(data) - (len(data) % 2)])
    return array.array("f", (s / 32768.0 for s in shorts))


def pad(samples: array.array, leading: int, trailing: int) -> array.array:
    out = array.array("f", [0.0] * leading)
    out.extend(samples)
    out.extend([0.0] * trailing)
    return out


def run_part_a() -> None:
    print("=== Part A: ASR decode (fp32 upstream model, greedy search) ===")
    recognizer = sherpa_onnx.OnlineRecognizer.from_transducer(
        tokens=str(UPSTREAM_MODEL_DIR / "tokens.txt"),
        encoder=str(UPSTREAM_MODEL_DIR / "encoder-epoch-12-avg-2-chunk-16-left-64.onnx"),
        decoder=str(UPSTREAM_MODEL_DIR / "decoder-epoch-12-avg-2-chunk-16-left-64.onnx"),
        joiner=str(UPSTREAM_MODEL_DIR / "joiner-epoch-12-avg-2-chunk-16-left-64.onnx"),
        num_threads=1,
        sample_rate=SAMPLE_RATE,
        decoding_method="greedy_search",
        enable_endpoint_detection=False,
    )
    for name in FIXTURE_NAMES:
        path = FIXTURES_DIR / name
        if not path.exists():
            print(f"{name}: MISSING")
            continue
        raw = load_pcm_as_float(path)
        # 0.5s leading silence, 1.5s trailing silence.
        samples = pad(raw, leading=8000, trailing=24000)
        stream = recognizer.create_stream()
        stream.accept_waveform(SAMPLE_RATE, samples)
        stream.input_finished()
        while recognizer.is_ready(stream):
            recognizer.decode_stream(stream)
        tokens = [to_bpe_token(t) for t in recognizer.tokens(stream)]
        text = recognizer.get_result(stream)
        print(f"{name}: tokens=[{' '.join(tokens)}] text={text!r}")
    print()


def run_part_b() -> dict[str, dict[str, bool]]:
    print("=== Part B: KeywordSpotter (bundled app model) ===")
    matrix: dict[str, dict[str, bool]] = {}
    for phrase_file, score, threshold in PHRASE_FILES:
        spotter = sherpa_onnx.KeywordSpotter(
            tokens=str(WAKE_ASSETS_DIR / "tokens.txt"),
            encoder=str(WAKE_ASSETS_DIR / "encoder.onnx"),
            decoder=str(WAKE_ASSETS_DIR / "decoder.onnx"),
            joiner=str(WAKE_ASSETS_DIR / "joiner.onnx"),
            keywords_file=str(WAKE_ASSETS_DIR / phrase_file),
            num_threads=1,
            sample_rate=SAMPLE_RATE,
            keywords_score=score,
            keywords_threshold=threshold,
        )
        row: dict[str, bool] = {}
        for name in FIXTURE_NAMES:
            path = FIXTURES_DIR / name
            raw = load_pcm_as_float(path)
            # Matches WakeInstrumentation.feed(): 8000 leading zero samples
            # (0.5s), then the fixture, then trailing zeros so the total
            # array length is len(fixture) + 24000 samples (16000 trailing).
            samples = pad(raw, leading=8000, trailing=16000)
            stream = spotter.create_stream()
            hit = False
            for offset in range(0, len(samples), 1600):
                chunk = samples[offset : offset + 1600]
                stream.accept_waveform(SAMPLE_RATE, chunk)
                while spotter.is_ready(stream):
                    spotter.decode_stream(stream)
                    if spotter.get_result(stream).strip():
                        hit = True
                        spotter.reset_stream(stream)
            row[name] = hit
        matrix[phrase_file] = row

    # Matrix.
    col_width = max(len(n) for n in FIXTURE_NAMES) + 2
    header = "phrase file".ljust(20) + "".join(n.ljust(col_width) for n in FIXTURE_NAMES)
    print(header)
    for phrase_file, _, _ in PHRASE_FILES:
        row = matrix[phrase_file]
        cells = "".join(("HIT" if row[n] else "-").ljust(col_width) for n in FIXTURE_NAMES)
        print(phrase_file.ljust(20) + cells)
    print()
    return matrix


def report_pass_fail(matrix: dict[str, dict[str, bool]]) -> bool:
    print("=== PASS/FAIL ===")
    overall = True
    for phrase_file, _, _ in PHRASE_FILES:
        row = matrix[phrase_file]
        expected_fixtures = EXPECTED_HITS[phrase_file]
        mandatory_negatives = MANDATORY_NEGATIVES_BY_PHRASE.get(phrase_file, MANDATORY_NEGATIVES)
        warn_negatives = WARN_ONLY_NEGATIVES.get(phrase_file, [])

        positive_hits = {name: row.get(name, False) for name in expected_fixtures}
        negative_hits = {name: row.get(name, False) for name in mandatory_negatives}
        all_positive = all(positive_hits.values())
        no_negative = not any(negative_hits.values())
        ok = all_positive and no_negative
        overall = overall and ok

        status = "PASS" if ok else "FAIL"
        pos_str = ", ".join(f"{n}={h}" for n, h in positive_hits.items())
        neg_str = ", ".join(f"{n}={h}" for n, h in negative_hits.items())
        print(f"{status}: {phrase_file} -> positives: [{pos_str}] negatives: [{neg_str}]")

        tracked = set(expected_fixtures) | set(mandatory_negatives) | set(warn_negatives)
        for name, hit in row.items():
            if hit and name in warn_negatives:
                print(f"  WARN: {phrase_file} also hit {name} (new Japanese negative, not tuned against this phrase file, not a failure)")
            elif hit and name not in tracked:
                print(f"  WARN: {phrase_file} also hit {name} (cross-phrase hit, not a failure)")
    print()
    print("OVERALL: " + ("PASS" if overall else "FAIL"))
    return overall


def main() -> None:
    run_part_a()
    matrix = run_part_b()
    ok = report_pass_fail(matrix)
    raise SystemExit(0 if ok else 1)


if __name__ == "__main__":
    main()
