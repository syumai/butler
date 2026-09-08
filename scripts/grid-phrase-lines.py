#!/usr/bin/env python3
"""Grid-search candidate wake-phrase lines against a real recording.

`eval-recording.py` evaluates one finished phrase file (as it will ship,
lines and all) against a positive/negative recording set. This script is
the step *before* that: given a file of candidate lines (one raw token
sequence per line, no `@Label` or `:score #threshold` suffix — those are
added by this script itself, not written by hand), it builds a
single-line phrase file for every (line, keywords_score, keywords_threshold)
combination, runs the app's own KeywordSpotter against it, and reports how
many distinct utterances of the positive recording it catches and how many
false hits it produces on a fixed negative set. This is for narrowing down
candidate lines and their score/threshold *before* combining a handful of
survivors into one phrase file and re-checking that combination with
`eval-recording.py` (lines can interact when combined into one file, so the
per-line numbers here are not the final answer, but they cut down the
search space a lot).

Run with the sherpa_onnx venv:

    .tools/wake-python/bin/python scripts/grid-phrase-lines.py \\
        candidate-lines.txt \\
        .tools/hello-butler-ja-rec1.pcm \\
        .tools/japanese-speech-neg1.pcm [more negatives...] \\
        [--scores 1.5,3.0,5.0] [--thresholds 0.25,0.15,0.1,0.05]

`candidate-lines.txt` has one candidate token sequence per line, e.g.:

    ▁HU D D LE ▁BUT ▁THE
    ▁HOW ▁BUT ▁THE ▁RO

Blank lines and lines starting with `#` are ignored. The negative set
always includes the fixed fixtures under `app/src/androidTest/assets/`
(`ordinary-speech.pcm`, `ordinary-speech-ja.pcm`, `near-miss-ja.pcm`,
`hey-butler.pcm`, `hello-computer.pcm`, `hello-world.pcm`, padded the same
way `check-fixtures.py` pads them) in addition to whatever negative
recordings are passed on the command line.
"""
from __future__ import annotations

import argparse
import array
import tempfile
from pathlib import Path

import wakelib

DEFAULT_SCORES = [1.5, 3.0, 5.0]
DEFAULT_THRESHOLDS = [0.25, 0.15, 0.1, 0.05]

# Fixed negative fixtures, in addition to whatever negative recordings are
# passed on the command line. Same padding as check-fixtures.py: 0.5s
# leading silence, 1.0s trailing silence.
FIXED_NEGATIVE_FIXTURES = [
    "ordinary-speech.pcm",
    "ordinary-speech-ja.pcm",
    "near-miss-ja.pcm",
    "hey-butler.pcm",
    "hello-computer.pcm",
    "hello-world.pcm",
]

TOP_N = 20


def parse_float_list(raw: str) -> list[float]:
    return [float(x) for x in raw.split(",") if x.strip()]


def load_candidate_lines(path: Path) -> list[str]:
    lines = []
    for raw_line in path.read_text().splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        lines.append(line)
    return lines


def load_negative_samples(
    negative_recordings: list[Path],
) -> list[tuple[str, array.array]]:
    """Return (name, samples) pairs for every negative source: the
    recordings passed on the command line (as-is) plus the fixed fixture
    set (padded like check-fixtures.py)."""
    samples: list[tuple[str, array.array]] = []
    for path in negative_recordings:
        samples.append((path.name, wakelib.load_pcm_as_float(path)))
    for name in FIXED_NEGATIVE_FIXTURES:
        path = wakelib.FIXTURES_DIR / name
        if not path.exists():
            print(f"(skipping missing fixture {name})", flush=True)
            continue
        raw = wakelib.load_pcm_as_float(path)
        padded = wakelib.pad(raw, leading=8000, trailing=16000)
        samples.append((name, padded))
    return samples


def evaluate_line(
    line: str,
    score: float,
    threshold: float,
    positive_samples: array.array,
    negative_samples: list[tuple[str, array.array]],
) -> tuple[int, list[float], dict[str, int]]:
    """Write `line` as a one-line phrase file (no per-line override, so the
    global score/threshold applies to it) and run the KeywordSpotter built
    at that global score/threshold against the positive recording and every
    negative sample. Returns (distinct_positive_hits, hit_times,
    {negative_name: hit_count})."""
    with tempfile.NamedTemporaryFile(
        mode="w", suffix=".txt", delete=False
    ) as f:
        f.write(line + "\n")
        phrase_file = Path(f.name)
    try:
        spotter = wakelib.create_keyword_spotter(phrase_file, score, threshold)
        positive_hits = wakelib.collect_hits(spotter, positive_samples)
        distinct = wakelib.merge_hits(positive_hits)

        negative_counts: dict[str, int] = {}
        for name, samples in negative_samples:
            spotter = wakelib.create_keyword_spotter(phrase_file, score, threshold)
            hits = wakelib.collect_hits(spotter, samples)
            negative_counts[name] = len(hits)
        return len(distinct), distinct, negative_counts
    finally:
        phrase_file.unlink(missing_ok=True)


def main() -> None:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument(
        "candidate_lines_file",
        type=Path,
        help="File with one candidate token sequence per line (no @Label or :score #threshold)",
    )
    parser.add_argument(
        "positive_recording",
        type=Path,
        help="Raw 16kHz mono s16le PCM recording of the wake phrase being spoken repeatedly",
    )
    parser.add_argument(
        "negative_recordings",
        type=Path,
        nargs="*",
        help="Raw 16kHz mono s16le PCM recording(s) of unrelated speech",
    )
    parser.add_argument(
        "--scores",
        type=parse_float_list,
        default=DEFAULT_SCORES,
        help=f"Comma-separated keywords_score values to try (default: {','.join(map(str, DEFAULT_SCORES))})",
    )
    parser.add_argument(
        "--thresholds",
        type=parse_float_list,
        default=DEFAULT_THRESHOLDS,
        help=f"Comma-separated keywords_threshold values to try (default: {','.join(map(str, DEFAULT_THRESHOLDS))})",
    )
    args = parser.parse_args()

    lines = load_candidate_lines(args.candidate_lines_file)
    if not lines:
        raise SystemExit(f"No candidate lines found in {args.candidate_lines_file}")

    positive_samples = wakelib.load_pcm_as_float(args.positive_recording)
    negative_samples = load_negative_samples(args.negative_recordings)

    print(
        f"{len(lines)} candidate line(s) x {len(args.scores)} score(s) x "
        f"{len(args.thresholds)} threshold(s) = "
        f"{len(lines) * len(args.scores) * len(args.thresholds)} run(s)",
        flush=True,
    )
    print(f"negatives: {', '.join(name for name, _ in negative_samples)}", flush=True)
    print(flush=True)

    results: list[tuple[int, int, str, float, float, list[float], dict[str, int]]] = []
    for line in lines:
        for score in args.scores:
            for threshold in args.thresholds:
                positive_count, hit_times, negative_counts = evaluate_line(
                    line, score, threshold, positive_samples, negative_samples
                )
                negative_total = sum(negative_counts.values())
                times_str = ", ".join(f"{t:.1f}s" for t in hit_times)
                neg_str = ", ".join(
                    f"{name}={count}"
                    for name, count in negative_counts.items()
                    if count > 0
                )
                print(
                    f"[{line}] score={score} threshold={threshold}: "
                    f"positive={positive_count} at [{times_str}] "
                    f"negative_total={negative_total}"
                    + (f" ({neg_str})" if neg_str else ""),
                    flush=True,
                )
                results.append(
                    (positive_count, negative_total, line, score, threshold, hit_times, negative_counts)
                )

    print(flush=True)
    print(f"=== Top {TOP_N} by positive hits (desc), then negative hits (asc) ===", flush=True)
    results.sort(key=lambda r: (-r[0], r[1]))
    for positive_count, negative_total, line, score, threshold, hit_times, negative_counts in results[:TOP_N]:
        times_str = ", ".join(f"{t:.1f}s" for t in hit_times)
        neg_str = ", ".join(
            f"{name}={count}" for name, count in negative_counts.items() if count > 0
        )
        print(
            f"positive={positive_count} negative_total={negative_total} "
            f"[{line}] score={score} threshold={threshold} "
            f"at [{times_str}]"
            + (f" ({neg_str})" if neg_str else ""),
            flush=True,
        )


if __name__ == "__main__":
    main()
