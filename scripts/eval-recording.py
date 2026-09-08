#!/usr/bin/env python3
"""Evaluate a wake-word phrase file against a real microphone recording.

`check-fixtures.py` only exercises the short, synthetic TTS fixtures under
`app/src/androidTest/assets/`. This script complements it for tuning against
a *real* speaker: point it at a phrase file plus one longer "positive"
recording (a speaker repeating the wake phrase several times) and any number
of "negative" recordings (unrelated speech), and it reports:

  - Part A: an ASR decode (fp32 upstream model, greedy search, with
    timestamps) of the positive recording, grouped into per-utterance token
    sequences (a new utterance starts whenever there is a >=1.5s gap between
    tokens). This is the same view used to write/tune phrase-file lines
    (e.g. `▁HE LL O ▁BUT LE R`) in `check-fixtures.py` Part A, but for a
    single long recording with several repetitions instead of one fixture
    per phrase.
  - Part B: the app's own KeywordSpotter (the bundled int8/fp32 model under
    `app/src/main/assets/wake/`, loaded with the phrase file under test) run
    against the positive and negative recordings, at the global default
    keywords_score=3.0 / keywords_threshold=0.25 (the WakePhrase default
    score and the settings default threshold, same as `check-fixtures.py`
    uses for `hello-butler.txt`). Hits on the positive recording within 2s
    of each other are merged into one "distinct utterance hit" so that a
    single spoken phrase that produces more than one KeywordSpotter result
    is not double-counted; hits on each negative recording are reported as
    plain counts and timestamps (any hit on a negative recording is a false
    wake).
  - Part C: the same KeywordSpotter/phrase-file combination run against
    every fixture under `app/src/androidTest/assets/`, with the same 0.5s
    leading / 1.0s trailing silence padding `check-fixtures.py` uses, so a
    phrase-file change can be checked against both the recording and the
    existing fixture suite in one run.

Run with the sherpa_onnx venv:

    .tools/wake-python/bin/python scripts/eval-recording.py \\
        app/src/main/assets/wake/hello-butler.txt \\
        .tools/hello-butler-ja-rec1.pcm \\
        .tools/japanese-speech-neg1.pcm [more negatives...]

Recordings are raw 16kHz mono s16le PCM (no header), matching what the app's
`WakeInstrumentation` record mode writes. Record one from a real device with:

    adb shell am instrument -w -e mode record -e seconds N \\
        dev.syumai.butler.test/dev.syumai.butler.WakeInstrumentation
    adb pull /storage/emulated/0/Android/data/dev.syumai.butler/files/record.pcm \\
        .tools/some-name.pcm

Recordings are expected to live under `.tools/` (gitignored) rather than
`app/src/androidTest/assets/`, since they capture a real person's voice and
should not be committed to git.
"""
from __future__ import annotations

import argparse
from pathlib import Path

import sherpa_onnx

import wakelib
from wakelib import (
    FIXTURES_DIR,
    WAKE_ASSETS_DIR,
    collect_hits,
    load_pcm_as_float,
    merge_hits,
    pad,
    to_bpe_token,
)

ROOT = Path(__file__).resolve().parents[1]
UPSTREAM_MODEL_DIR = ROOT / ".tools/sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01"
SAMPLE_RATE = wakelib.SAMPLE_RATE

# Global defaults: the WakePhrase default keywords_score and the settings
# default wakeThreshold, matching how the app actually runs (as opposed to
# check-fixtures.py's per-phrase-file PHRASE_FILES table, which is only used
# there to also exercise hello-computer.txt/keywords.txt at their own
# defaults).
KEYWORDS_SCORE = 3.0
KEYWORDS_THRESHOLD = 0.25

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

UTTERANCE_GAP_SECONDS = 1.5
MERGE_HIT_SECONDS = wakelib.MERGE_HIT_SECONDS


def run_part_a(positive_path: Path) -> None:
    print("=== Part A: ASR decode of the positive recording (fp32 upstream model, greedy search) ===")
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
    samples = load_pcm_as_float(positive_path)
    stream = recognizer.create_stream()
    stream.accept_waveform(SAMPLE_RATE, samples)
    stream.input_finished()
    while recognizer.is_ready(stream):
        recognizer.decode_stream(stream)
    tokens = [to_bpe_token(t) for t in recognizer.tokens(stream)]
    timestamps = list(recognizer.timestamps(stream))

    if not tokens:
        print("(no tokens decoded)")
        print()
        return

    utterance: list[tuple[str, float]] = [(tokens[0], timestamps[0])]
    utterances: list[list[tuple[str, float]]] = [utterance]
    for tok, ts in zip(tokens[1:], timestamps[1:]):
        if ts - utterance[-1][1] >= UTTERANCE_GAP_SECONDS:
            utterance = []
            utterances.append(utterance)
        utterance.append((tok, ts))

    for utt in utterances:
        start = utt[0][1]
        toks_str = " ".join(f"{tok}@{ts:.1f}" for tok, ts in utt)
        print(f"~{start:.1f}s  {toks_str}")
    print()


def run_keyword_spotter(phrase_file: Path) -> sherpa_onnx.KeywordSpotter:
    return wakelib.create_keyword_spotter(phrase_file, KEYWORDS_SCORE, KEYWORDS_THRESHOLD)


def run_part_b(phrase_file: Path, positive_path: Path, negative_paths: list[Path]) -> None:
    print(f"=== Part B: KeywordSpotter ({phrase_file.name}, score={KEYWORDS_SCORE}, threshold={KEYWORDS_THRESHOLD}) vs recordings ===")
    spotter = run_keyword_spotter(phrase_file)

    positive_samples = load_pcm_as_float(positive_path)
    positive_hits = collect_hits(spotter, positive_samples)
    distinct = merge_hits(positive_hits, MERGE_HIT_SECONDS)
    times_str = ", ".join(f"{t:.1f}s" for t in distinct)
    print(f"positive {positive_path.name}: {len(distinct)} distinct utterance hit(s) at [{times_str}]")
    if len(positive_hits) != len(distinct):
        raw_str = ", ".join(f"{t:.1f}s" for t in positive_hits)
        print(f"  (raw hits before 2s merge: {len(positive_hits)} at [{raw_str}])")

    for negative_path in negative_paths:
        spotter = run_keyword_spotter(phrase_file)
        negative_samples = load_pcm_as_float(negative_path)
        negative_hits = collect_hits(spotter, negative_samples)
        times_str = ", ".join(f"{t:.1f}s" for t in negative_hits)
        print(f"negative {negative_path.name}: {len(negative_hits)} hit(s) at [{times_str}]")
    print()


def run_part_c(phrase_file: Path) -> None:
    print(f"=== Part C: KeywordSpotter ({phrase_file.name}) vs app/src/androidTest/assets fixtures ===")
    spotter = run_keyword_spotter(phrase_file)
    for name in FIXTURE_NAMES:
        path = FIXTURES_DIR / name
        if not path.exists():
            print(f"{name}: MISSING")
            continue
        raw = load_pcm_as_float(path)
        # Matches WakeInstrumentation.feed() / check-fixtures.py: 0.5s
        # leading silence, 1.0s trailing silence.
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
        print(f"{name}: {'HIT' if hit else 'miss'}")
    print()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("phrase_file", type=Path, help="Phrase file to evaluate, e.g. app/src/main/assets/wake/hello-butler.txt")
    parser.add_argument("positive_recording", type=Path, help="Raw 16kHz mono s16le PCM recording of the wake phrase being spoken repeatedly")
    parser.add_argument("negative_recordings", type=Path, nargs="*", help="Raw 16kHz mono s16le PCM recording(s) of unrelated speech")
    args = parser.parse_args()

    run_part_a(args.positive_recording)
    run_part_b(args.phrase_file, args.positive_recording, args.negative_recordings)
    run_part_c(args.phrase_file)


if __name__ == "__main__":
    main()
