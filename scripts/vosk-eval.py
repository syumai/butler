#!/usr/bin/env python3
"""Evaluate the Vosk wake-word engine against one or more .pcm recordings.

Cleaned-up version of the scratch script used to pick the shipped grammar
(`["ハロー バトラー", "[unk]"]`) and confirm the adjacency rule implemented in
`VoskWake.hit` (`LocalWakeWordEngine.kt`, unit-tested in `VoskWakeTest.kt`).
For each input file it prints:

  - every FINAL result, and every PARTIAL result that changed since the last
    one printed, each with a timestamp;
  - a summary: how many FINAL results contain the phrase as an adjacent word
    run, how many *first* PARTIAL hits occurred (the earliest partial where
    the adjacency rule first becomes true for a given utterance - the same
    signal `VoskWakeDecoder.accept` reacts to on the device, reimplemented
    here in Python rather than imported, since the app-side check is Kotlin),
    and the decode wall time.

Run with a venv that has the `vosk` package installed:

    python3 -m venv .tools/vosk-python
    .tools/vosk-python/bin/pip install vosk
    .tools/vosk-python/bin/python scripts/vosk-eval.py \\
        .tools/hello-butler-ja-rec1.pcm .tools/japanese-speech-neg1.pcm

Recordings are raw 16kHz mono s16le PCM (no header), matching what the app's
`WakeInstrumentation` record mode writes (see README "Wake word on-device
testing"). Recordings capturing a real voice belong under `.tools/`
(gitignored), never committed.
"""
from __future__ import annotations

import argparse
import json
import time
from pathlib import Path

from vosk import KaldiRecognizer, Model, SetLogLevel

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_MODEL = ROOT / ".tools" / "vosk" / "vosk-model-small-ja-0.22"
DEFAULT_PHRASE = "ハロー バトラー"
CHUNK_BYTES = 3200  # 100ms of 16kHz mono s16le


def is_adjacent_hit(text: str, phrase: str) -> bool:
    """Same rule as VoskWake.hit: the phrase's words must appear as a contiguous
    run in the whitespace-split token list (not merely both present anywhere)."""
    if not text.strip():
        return False
    tokens = text.strip().split()
    words = phrase.strip().split()
    if not words:
        return False
    for start in range(len(tokens) - len(words) + 1):
        if tokens[start:start + len(words)] == words:
            return True
    return False


def evaluate(model: Model, pcm_path: Path, phrase: str, grammar: bool) -> None:
    data = pcm_path.read_bytes()
    grammar_json = json.dumps([phrase, "[unk]"], ensure_ascii=False) if grammar else None
    rec = KaldiRecognizer(model, 16000.0, grammar_json) if grammar_json else KaldiRecognizer(model, 16000.0)
    rec.SetWords(False)

    print(f"=== {pcm_path} (grammar={grammar}) ===")
    started = time.time()
    last_partial = ""
    final_hits = 0
    first_partial_hits = 0
    saw_hit_this_utterance = False
    for offset in range(0, len(data), CHUNK_BYTES):
        ts = offset / 32000.0  # 16000 samples/s * 2 bytes/sample
        chunk = data[offset:offset + CHUNK_BYTES]
        if rec.AcceptWaveform(chunk):
            result = json.loads(rec.Result())
            text = result.get("text", "")
            hit = is_adjacent_hit(text, phrase)
            if hit:
                final_hits += 1
            print(f"{ts:7.2f}s FINAL   {text!r}{'  <== HIT' if hit else ''}")
            saw_hit_this_utterance = False
            last_partial = ""
        else:
            partial = json.loads(rec.PartialResult()).get("partial", "")
            if partial != last_partial:
                hit = is_adjacent_hit(partial, phrase)
                if hit and not saw_hit_this_utterance:
                    first_partial_hits += 1
                    saw_hit_this_utterance = True
                print(f"{ts:7.2f}s partial {partial!r}{'  <== HIT' if hit else ''}")
                last_partial = partial
    result = json.loads(rec.FinalResult())
    text = result.get("text", "")
    if is_adjacent_hit(text, phrase):
        final_hits += 1
    print(f"   end FINAL   {text!r}")
    elapsed = time.time() - started
    audio_seconds = len(data) / 32000.0
    print(f"summary: {final_hits} final hit(s), {first_partial_hits} first-partial hit(s), "
          f"decode {elapsed:.1f}s for {audio_seconds:.0f}s audio\n")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("pcm", nargs="+", type=Path, help="one or more raw 16kHz mono s16le .pcm files")
    parser.add_argument("--model", type=Path, default=DEFAULT_MODEL, help=f"Vosk model directory (default: {DEFAULT_MODEL})")
    parser.add_argument("--phrase", default=DEFAULT_PHRASE, help=f"wake phrase (default: {DEFAULT_PHRASE!r})")
    grammar_group = parser.add_mutually_exclusive_group()
    grammar_group.add_argument("--grammar", dest="grammar", action="store_true", default=True,
                                help="restrict recognition to [phrase, \"[unk]\"] (default)")
    grammar_group.add_argument("--no-grammar", dest="grammar", action="store_false",
                                help="run free-vocabulary recognition instead")
    args = parser.parse_args()

    SetLogLevel(-1)
    model = Model(str(args.model))
    for pcm_path in args.pcm:
        evaluate(model, pcm_path, args.phrase, args.grammar)


if __name__ == "__main__":
    main()
