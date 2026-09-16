#!/usr/bin/env python3
"""Evaluate the offline Julius wake-word grammar against one or more .pcm recordings.

Companion to `scripts/vosk-eval.py`, but for the Julius phone-loop "garbage"
grammar under `scripts/julius-wake/` (see that directory's README for the
grammar design). Unlike the Vosk evaluator, Julius has no built-in streaming
VAD in library form here, so this script implements a simple energy-based
VAD in Python, cuts each recording into speech segments, writes each segment
out as a 16kHz mono WAV file, and runs Julius once per input file (batch
mode over a `-filelist`) against the `wake` DFA grammar.

For each segment Julius is asked for its 1-best sentence together with a
per-word confidence measure (`cmscore1`, computed in Julius's 2nd pass). A
segment is a "hit" if the WAKE word appears anywhere in the recognized word
sequence AND its confidence score is >= --threshold.

Usage:

    python3 scripts/julius-eval.py FILE.pcm [FILE.pcm ...] \\
        [--threshold 0.5] [--penalty1 X --penalty2 Y] [--julius-args "..."]

Recordings are raw 16kHz mono s16le PCM (no header), matching what the app's
`WakeInstrumentation` record mode writes. Recordings capturing a real voice
belong under `.tools/` (gitignored), never committed.

Requires the `julius` binary on PATH (Homebrew: /opt/homebrew/bin/julius)
and the Julius Dictation Kit acoustic model under
`.tools/julius/dictation-kit/` (see scripts/julius-wake/README.md).
"""
from __future__ import annotations

import argparse
import array
import shlex
import subprocess
import sys
import tempfile
import time
import wave
from dataclasses import dataclass, field
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
GRAMMAR_DIR = ROOT / "scripts" / "julius-wake"
AM_JCONF = GRAMMAR_DIR / "am.jconf"
GRAMMAR_PREFIX = GRAMMAR_DIR / "wake"
MODEL_DIR = ROOT / ".tools" / "julius" / "dictation-kit"
# Both pronunciation-set members of WakePhrase.HELLO_BUTLER's juliusWords (LocalWakeWordEngine.kt) —
# a hit on either word counts, matching JuliusWake.hit(line, wakeWords, threshold) in the app.
WAKE_WORDS = {"ハローバトラー", "ヘイバトラー"}

SAMPLE_RATE = 16000
BYTES_PER_SAMPLE = 2
FRAME_MS = 10
FRAME_SAMPLES = SAMPLE_RATE * FRAME_MS // 1000  # 160 samples per 10ms frame
HANGOVER_MS = 300
MIN_SEGMENT_MS = 300
MAX_SEGMENT_S = 8.0
PAD_MS = 200

# Tuned so that rec1 (11 spoken repetitions) yields ~11 VAD segments while
# staying below Julius's WAKE-word insertions on unrelated Japanese speech.
# See scripts/julius-wake/README.md "Tuning results" for the sweep.
DEFAULT_VAD_THRESHOLD = 1200.0
# Recordings vary widely in mic gain (observed peak |sample| from ~1000 to
# ~13000 across rec1/neg1/gongitsune). Normalize each file's peak amplitude
# to this target before VAD and before handing audio to Julius, so a single
# absolute --vad-threshold default is meaningful across files.
NORMALIZE_TARGET_PEAK = 16000.0
NORMALIZE_MAX_GAIN = 20.0  # cap gain so near-silent files don't blow up noise

# See scripts/julius-wake/README.md "Tuning results" for the sweep that
# picked these. cmscore1 values from this grammar run low overall (~0.04-0.1
# for genuine WAKE hits) since there are only 4 grammar categories, so there
# isn't much competing search-graph mass for the confidence measure to work
# with -- the real discriminator turned out to be -penalty1/-penalty2, not
# --threshold. -0.8 was the most negative penalty (of the values tried) that
# still produced zero false WAKE insertions on both negative recordings.
DEFAULT_THRESHOLD = 0.05
DEFAULT_PENALTY1 = -0.8
DEFAULT_PENALTY2 = -0.8

THRESHOLD_TABLE = [0.0, 0.3, 0.5, 0.7, 0.9, 0.95]


@dataclass
class Segment:
    start_s: float
    end_s: float
    wav_path: Path


@dataclass
class SegmentResult:
    segment: Segment
    sentence: str = ""
    wake_cm: float | None = None  # max cmscore over WAKE occurrences, if any
    wake_word: str | None = None  # which WAKE_WORDS member wake_cm came from
    ok: bool = True  # False if Julius produced no result for this segment


@dataclass
class FileReport:
    path: Path
    audio_seconds: float
    results: list[SegmentResult] = field(default_factory=list)
    decode_seconds: float = 0.0


def read_pcm_samples(path: Path) -> array.array:
    data = path.read_bytes()
    samples = array.array("h")
    samples.frombytes(data[: len(data) - (len(data) % 2)])
    if sys.byteorder == "big":
        samples.byteswap()
    return samples


def normalize_samples(samples: array.array) -> array.array:
    """Scale so the file's peak |sample| hits NORMALIZE_TARGET_PEAK.

    Input recordings differ a lot in mic gain (observed peak amplitudes from
    ~1000 to ~13000 across the sample recordings used to tune this script),
    which would otherwise make a single absolute --vad-threshold meaningless
    across files. Gain is capped by NORMALIZE_MAX_GAIN so a near-silent
    recording doesn't get amplified into pure noise.
    """
    peak = max((abs(s) for s in samples), default=0)
    if peak == 0:
        return samples
    gain = min(NORMALIZE_TARGET_PEAK / peak, NORMALIZE_MAX_GAIN)
    if abs(gain - 1.0) < 1e-6:
        return samples
    out = array.array("h", (0,)) * len(samples)
    for i, s in enumerate(samples):
        v = int(s * gain)
        if v > 32767:
            v = 32767
        elif v < -32768:
            v = -32768
        out[i] = v
    return out


def compute_frame_rms(samples: array.array) -> list[float]:
    """RMS per FRAME_SAMPLES-sample frame (10ms at 16kHz), last partial frame included."""
    n = len(samples)
    frames = []
    for i in range(0, n, FRAME_SAMPLES):
        chunk = samples[i : i + FRAME_SAMPLES]
        if not chunk:
            continue
        ssum = sum(s * s for s in chunk)
        rms = (ssum / len(chunk)) ** 0.5
        frames.append(rms)
    return frames


def vad_segments(samples: array.array, vad_threshold: float) -> list[tuple[float, float]]:
    """Energy-based VAD: 10ms frames, RMS threshold, ~300ms hangover.

    Returns a list of (start_s, end_s) speech spans (pre-padding, pre-split,
    pre-min-duration-filter — those are applied by the caller).
    """
    frame_rms = compute_frame_rms(samples)
    hangover_frames = HANGOVER_MS // FRAME_MS

    spans: list[tuple[int, int]] = []  # frame indices, end exclusive
    in_seg = False
    seg_start = 0
    hangover_left = 0
    last_active_frame = -1

    for i, rms in enumerate(frame_rms):
        active = rms >= vad_threshold
        if active:
            if not in_seg:
                in_seg = True
                seg_start = i
            hangover_left = hangover_frames
            last_active_frame = i
        elif in_seg:
            if hangover_left > 0:
                hangover_left -= 1
            else:
                spans.append((seg_start, last_active_frame + 1))
                in_seg = False
    if in_seg:
        spans.append((seg_start, last_active_frame + 1))

    return [
        (start * FRAME_MS / 1000.0, end * FRAME_MS / 1000.0)
        for start, end in spans
    ]


def split_and_pad(
    spans: list[tuple[float, float]], audio_seconds: float
) -> list[tuple[float, float]]:
    """Drop too-short spans, split too-long ones at ~MAX_SEGMENT_S, then pad."""
    min_s = MIN_SEGMENT_MS / 1000.0
    pad_s = PAD_MS / 1000.0

    chunked: list[tuple[float, float]] = []
    for start, end in spans:
        dur = end - start
        if dur < min_s:
            continue
        if dur <= MAX_SEGMENT_S:
            chunked.append((start, end))
            continue
        n_chunks = max(1, round(dur / MAX_SEGMENT_S))
        chunk_len = dur / n_chunks
        for i in range(n_chunks):
            c_start = start + i * chunk_len
            c_end = start + (i + 1) * chunk_len if i < n_chunks - 1 else end
            chunked.append((c_start, c_end))

    padded = []
    for start, end in chunked:
        padded.append((max(0.0, start - pad_s), min(audio_seconds, end + pad_s)))
    return padded


def write_segment_wav(samples: array.array, start_s: float, end_s: float, out_path: Path) -> None:
    start_i = max(0, int(round(start_s * SAMPLE_RATE)))
    end_i = min(len(samples), int(round(end_s * SAMPLE_RATE)))
    chunk = samples[start_i:end_i]
    with wave.open(str(out_path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(BYTES_PER_SAMPLE)
        w.setframerate(SAMPLE_RATE)
        w.writeframes(chunk.tobytes())


def build_segments(pcm_path: Path, vad_threshold: float, work_dir: Path) -> tuple[list[Segment], float]:
    samples = normalize_samples(read_pcm_samples(pcm_path))
    audio_seconds = len(samples) / SAMPLE_RATE
    raw_spans = vad_segments(samples, vad_threshold)
    final_spans = split_and_pad(raw_spans, audio_seconds)

    segments = []
    for idx, (start, end) in enumerate(final_spans):
        wav_path = work_dir / f"{pcm_path.stem}_{idx:03d}.wav"
        write_segment_wav(samples, start, end, wav_path)
        segments.append(Segment(start_s=start, end_s=end, wav_path=wav_path))
    return segments, audio_seconds


def check_prereqs() -> None:
    import shutil

    if shutil.which("julius") is None:
        raise SystemExit(
            "error: `julius` binary not found on PATH. Install it (e.g. `brew install julius`) "
            "and ensure the acoustic model is present under .tools/julius/dictation-kit "
            "(see scripts/julius-wake/README.md)."
        )
    if not MODEL_DIR.is_dir():
        raise SystemExit(
            f"error: acoustic model directory not found: {MODEL_DIR}\n"
            "Expected the Julius Dictation Kit under .tools/julius/dictation-kit "
            "(see scripts/julius-wake/README.md)."
        )
    if not AM_JCONF.is_file() or not (GRAMMAR_DIR / "wake.dfa").is_file() or not (GRAMMAR_DIR / "wake.dict").is_file():
        raise SystemExit(
            f"error: grammar files missing under {GRAMMAR_DIR}. "
            "Run `mkdfa.pl wake` there to (re)generate wake.dfa/wake.dict "
            "(see scripts/julius-wake/README.md)."
        )


def run_julius(
    segments: list[Segment],
    penalty1: float,
    penalty2: float,
    extra_args: list[str],
    work_dir: Path,
) -> tuple[str, float]:
    filelist_path = work_dir / "filelist.txt"
    filelist_path.write_text("\n".join(str(s.wav_path) for s in segments) + "\n")

    cmd = [
        "julius",
        "-C", str(AM_JCONF),
        "-gram", str(GRAMMAR_PREFIX),
        "-n", "1",
        "-output", "1",
        "-input", "rawfile",
        "-filelist", str(filelist_path),
        "-penalty1", str(penalty1),
        "-penalty2", str(penalty2),
    ] + extra_args

    started = time.time()
    try:
        proc = subprocess.run(cmd, capture_output=True, text=True, cwd=str(ROOT))
    except FileNotFoundError as exc:
        raise SystemExit(f"error: failed to run julius: {exc}") from exc
    elapsed = time.time() - started

    if proc.returncode != 0:
        raise SystemExit(
            f"error: julius exited with code {proc.returncode}.\n"
            f"command: {' '.join(cmd)}\n"
            f"--- stderr (tail) ---\n{proc.stderr[-4000:]}\n"
            "Check the acoustic model under .tools/julius/dictation-kit and "
            "the grammar files under scripts/julius-wake/."
        )
    return proc.stdout, elapsed


def parse_julius_output(stdout: str, segments: list[Segment]) -> list[SegmentResult]:
    """Align Julius's per-file result blocks (in -filelist order) to segments."""
    results = [SegmentResult(segment=s, ok=False) for s in segments]
    idx = -1
    for line in stdout.splitlines():
        line = line.strip()
        if "input speechfile:" in line:
            idx += 1
            continue
        if idx < 0 or idx >= len(results):
            continue
        if line.startswith("sentence1:"):
            sentence = line[len("sentence1:"):].strip()
            results[idx].sentence = sentence
            results[idx].ok = True
        elif line.startswith("cmscore1:"):
            cm_values = [float(x) for x in line[len("cmscore1:"):].split()]
            tokens = results[idx].sentence.split()
            wake_hits = [(tok, cm) for tok, cm in zip(tokens, cm_values) if tok in WAKE_WORDS]
            if wake_hits:
                tok, cm = max(wake_hits, key=lambda pair: pair[1])
                results[idx].wake_cm = cm
                results[idx].wake_word = tok
    return results


def is_hit(result: SegmentResult, threshold: float) -> bool:
    return result.wake_cm is not None and result.wake_cm >= threshold


def evaluate_file(
    pcm_path: Path,
    vad_threshold: float,
    penalty1: float,
    penalty2: float,
    extra_args: list[str],
    threshold: float,
    work_dir: Path,
) -> FileReport:
    segments, audio_seconds = build_segments(pcm_path, vad_threshold, work_dir)
    report = FileReport(path=pcm_path, audio_seconds=audio_seconds)

    if not segments:
        print(f"=== {pcm_path} ===")
        print("  (no speech segments found by VAD)")
        return report

    stdout, decode_seconds = run_julius(segments, penalty1, penalty2, extra_args, work_dir)
    report.decode_seconds = decode_seconds
    report.results = parse_julius_output(stdout, segments)

    print(f"=== {pcm_path} ({audio_seconds:.1f}s audio, {len(segments)} segment(s)) ===")
    for r in report.results:
        s = r.segment
        cm_str = f"{r.wake_cm:.3f}" if r.wake_cm is not None else "-"
        hit_str = f"  <== HIT ({r.wake_word})" if is_hit(r, threshold) else ""
        sentence = r.sentence if r.ok else "(no result)"
        print(f"  {s.start_s:6.2f}-{s.end_s:6.2f}  {sentence}  wakeCm={cm_str}{hit_str}")

    hits = sum(1 for r in report.results if is_hit(r, threshold))
    wake_cms = [r.wake_cm for r in report.results if r.wake_cm is not None]
    hours = audio_seconds / 3600.0
    hits_per_hour = hits / hours if hours > 0 else 0.0
    hits_by_word: dict[str, int] = {}
    for r in report.results:
        if is_hit(r, threshold) and r.wake_word is not None:
            hits_by_word[r.wake_word] = hits_by_word.get(r.wake_word, 0) + 1

    print(f"  summary: {len(report.results)} segment(s), {hits} hit(s) @ threshold={threshold} "
          f"({hits_by_word}), {hits_per_hour:.1f} hits/hour, decode {decode_seconds:.2f}s")
    print(f"  WAKE candidate cmscores: {[f'{c:.3f}' for c in wake_cms]}")
    print()
    return report


def print_threshold_table(reports: list[FileReport]) -> None:
    print("=== hit counts per file at various thresholds ===")
    header = "file".ljust(40) + "".join(f"t={t:<7}" for t in THRESHOLD_TABLE)
    print(header)
    for report in reports:
        row = str(report.path).ljust(40)
        for t in THRESHOLD_TABLE:
            hits = sum(1 for r in report.results if is_hit(r, t))
            row += f"{hits:<9}"
        print(row)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("pcm", nargs="+", type=Path, help="one or more raw 16kHz mono s16le .pcm files")
    parser.add_argument("--threshold", type=float, default=DEFAULT_THRESHOLD,
                         help=f"minimum WAKE cmscore to count as a hit (default: {DEFAULT_THRESHOLD})")
    parser.add_argument("--vad-threshold", type=float, default=DEFAULT_VAD_THRESHOLD,
                         help=f"RMS threshold for the energy VAD (default: {DEFAULT_VAD_THRESHOLD})")
    parser.add_argument("--penalty1", type=float, default=DEFAULT_PENALTY1,
                         help=f"Julius -penalty1 (1st pass word insertion penalty, default: {DEFAULT_PENALTY1})")
    parser.add_argument("--penalty2", type=float, default=DEFAULT_PENALTY2,
                         help=f"Julius -penalty2 (2nd pass word insertion penalty, default: {DEFAULT_PENALTY2})")
    parser.add_argument("--julius-args", default="", help="extra raw arguments appended to the julius command line")
    args = parser.parse_args()

    check_prereqs()

    extra_args = shlex.split(args.julius_args)

    reports = []
    with tempfile.TemporaryDirectory(prefix="julius-eval-") as tmp:
        tmp_root = Path(tmp)
        for file_idx, pcm_path in enumerate(args.pcm):
            # Separate subdir per input file: avoids wav filename collisions
            # when two input files share a basename.
            work_dir = tmp_root / f"file{file_idx:02d}"
            work_dir.mkdir()
            report = evaluate_file(
                pcm_path,
                args.vad_threshold,
                args.penalty1,
                args.penalty2,
                extra_args,
                args.threshold,
                work_dir,
            )
            reports.append(report)

    print_threshold_table(reports)


if __name__ == "__main__":
    main()
