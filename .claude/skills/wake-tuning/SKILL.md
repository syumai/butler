---
name: wake-tuning
description: Tune Butler's wake-phrase detection (sherpa-onnx KeywordSpotter) against a real speaker's recordings when the wake word is missed too often or false-wakes on unrelated speech.
---

Talk to the user in Japanese while following this skill; the skill body and any files it produces (phrase files, `docs/device-validation.md` entries) stay in English, matching the rest of this repo.

## When to use

The user reports the wake phrase (currently "Hello Butler", Japanese pronunciation "ハロー、バトラー") is missed too often, or wakes on unrelated speech. Detection is sherpa-onnx `KeywordSpotter` + GigaSpeech 3.3M KWS (English BPE tokens); phrase files are `app/src/main/assets/wake/*.txt` (one line = token sequence, optional trailing `:score #threshold` override and `@Label`). The selected phrase is `Settings.wakePhrase` (`WakePhrase` enum, `LocalWakeWordEngine.kt`).

### Prerequisites

- `.tools/wake-python/bin/python` — the sherpa_onnx venv used by every script below.
- `.tools/android-sdk`'s `adb` on PATH (or full path under `.tools/android-sdk/platform-tools/adb`).
- A connected device with the app **and** the androidTest APK installed (`./gradlew assembleDebug assembleDebugAndroidTest`, then install both). Confirm the device serial with `adb devices` (use `-s SERIAL` on every adb command below if more than one device is attached).
- If another session/teammate is actively editing `app/src/main/java/` or `docs/architecture.md`, do not touch those files — this workflow only needs phrase files under `app/src/main/assets/wake/`, `third_party/sherpa-onnx/SHA256SUMS`, `scripts/`, and `docs/device-validation.md`.

## Steps

### 1. Establish the current state

- Check the on-device threshold isn't the problem (it's read alongside secrets, so grep it out):
  `adb shell run-as dev.syumai.butler cat shared_prefs/settings.xml | grep wakeThreshold`
- Run `.tools/wake-python/bin/python scripts/check-fixtures.py` — confirms the shipped phrase files still pass against the synthetic fixtures before touching anything.

### 2. Ask the user for real recordings

Recordings must come from the actual device mic at the actual distance the user talks from — synthetic TTS fixtures do not predict real detection rates. Ask (in Japanese) for two recordings:

- **Positive** (~60s): the wake phrase spoken 6-10 times, at normal distance/volume, with a 3-4s pause between repetitions.
- **Negative** (~60s): ordinary chatter or reading aloud, no wake phrase at all.

Example request wording:
> 実機でのテストのため、2つの録音をお願いします。
> 1. 普段スマホに話しかける距離・声量で、「ハロー、バトラー」を6〜10回、3〜4秒間隔で言ってください(60秒程度)。
> 2. ウェイクフレーズを含まない、普段の雑談や本の音読を60秒程度お願いします。

Before recording, force-stop the app so the foreground service releases the mic:

```sh
adb shell am force-stop dev.syumai.butler
adb shell am instrument -w -e mode record -e seconds 60 dev.syumai.butler.test/dev.syumai.butler.WakeInstrumentation
adb pull /storage/emulated/0/Android/data/dev.syumai.butler/files/record.pcm .tools/hello-butler-<name>-rec1.pcm
```

Repeat for the negative recording (different `-e mode record` run, different pull destination, e.g. `.tools/<name>-speech-neg1.pcm`). **Recordings capture a real voice — keep them under `.tools/` (gitignored), never commit them.**

### 3. See how the ASR actually hears the recording

```sh
.tools/wake-python/bin/python scripts/eval-recording.py \
    app/src/main/assets/wake/hello-butler.txt \
    .tools/hello-butler-<name>-rec1.pcm \
    .tools/<name>-speech-neg1.pcm
```

Part A prints the fp32 ASR decode of the positive recording grouped into utterances (>=1.5s token gap = new utterance) — this is what the model actually hears, which is often very different from the intended phrase (e.g. a Japanese "Hello" may not decode as anything resembling `▁HE LL O`). Part B reports the current phrase file's hit rate on the recording plus negative hit counts. Part C re-checks the synthetic fixture suite. Use Part A's token sequences as the raw material for new candidate lines.

### 4. Build candidate lines

From Part A's output, look for a substring that decoded consistently across utterances (e.g. `▁BUT` was stable for "Butler" even when "Hello" was not) and write candidate lines combining it with the variations seen before/after it. Put one raw token sequence per line (no `@Label`, no `:score #threshold` — the grid script adds those) in a scratch file, e.g.:

```
▁HU D D LE ▁BUT ▁THE
▁HU D D LE ▁BUT ▁THERE
▁HOW ▁BUT ▁THE ▁RO
```

### 5. Grid-search each candidate line

```sh
.tools/wake-python/bin/python scripts/grid-phrase-lines.py \
    candidate-lines.txt \
    .tools/hello-butler-<name>-rec1.pcm \
    .tools/<name>-speech-neg1.pcm \
    [--scores 1.5,3.0,5.0] [--thresholds 0.25,0.15,0.1,0.05]
```

Each line is tried alone (its own one-line phrase file) at every (score, threshold) pair, against the positive recording plus a fixed negative set (the passed-in negative recordings, `ordinary-speech.pcm`, `ordinary-speech-ja.pcm`, `near-miss-ja.pcm`, `hey-butler.pcm`, `hello-computer.pcm`, `hello-world.pcm`). Output streams per-run, then a top-20 summary sorted by positive hits desc / negative hits asc. Prefer lines with 4+ tokens and the lowest threshold that still keeps negative hits at 0 — see Rules of thumb.

### 6. Combine survivors and re-check as a whole file

Per-line results do not add up: lines interact (a short line can suppress a longer line's later match). Assemble 2-4 surviving lines (with their chosen `:score #threshold`) into the real phrase file and re-run:

```sh
.tools/wake-python/bin/python scripts/eval-recording.py \
    app/src/main/assets/wake/hello-butler.txt \
    .tools/hello-butler-<name>-rec1.pcm \
    .tools/<name>-speech-neg1.pcm [other negatives...]
```

Iterate (drop/replace lines) until the combined file clears the pass bar below.

### 7. Widen the negative set before shipping

Add more negatives so the grid isn't overfit to one recording: amplified copies of the negative recording (background speech can be quieter than foreground), and TTS-generated long-form sentences containing near-miss words. Use `scripts/generate-fixtures.py`'s helpers directly:

- `request_speech(api_key, model, voice, text, instructions)` — calls the OpenAI TTS API (`.tools/openai.key`).
- `trim_resample_to_pcm(ffmpeg, wav_path, pcm_path)` — trims silence, resamples to 16kHz mono s16le.
- `normalize_peak(pcm_path)` — scales to a consistent peak dBFS.

For amplifying an existing negative recording, a plain gain multiply on the s16le samples (clamped to int16 range) is enough — no need for ffmpeg.

### 8. Land the change

1. Edit `app/src/main/assets/wake/hello-butler.txt` (or the relevant phrase file).
2. Update the checksum: `shasum -a 256 app/src/main/assets/wake/hello-butler.txt`, paste into `third_party/sherpa-onnx/SHA256SUMS`, then verify: `shasum -a 256 -c third_party/sherpa-onnx/SHA256SUMS`.
3. `.tools/wake-python/bin/python scripts/check-fixtures.py` — must print `OVERALL: PASS`.
4. Build:
   ```sh
   JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
   GRADLE_USER_HOME=<repo>/.tools/gradle-home \
   ./gradlew -q :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
   ```
5. Install both APKs on the device, run `WakeInstrumentation` (no `mode`/`seconds` args = normal PASS/FAIL run, ~2 minutes), confirm PASS.
6. Return the device to standby: `adb shell am start -n dev.syumai.butler/.MainActivity`.
7. Append an English-language section to `docs/device-validation.md` describing the recording(s) used, the ASR decode findings, the grid results, the final lines, and the `eval-recording.py`/instrumentation results.
8. Ask the user to say the wake phrase ~10 more times on the real device and report back the hit rate.

## Rules of thumb

- Real speakers, especially non-native English speakers, may not produce the expected sounds at all (e.g. Japanese "ハロー" often doesn't decode as anything resembling "Hello"). Always make the final call from a real recording, not TTS fixtures.
- Lines with 3 or fewer tokens, or thresholds below 0.1 on a short line, are the main source of false wakes. Lines with 4+ tokens were safe down to threshold 0.05 in past tuning.
- Don't put a short line in the same phrase file as a longer line it's a prefix of — it can suppress the longer line's later match (observed: a 7-utterance hit rate dropped to 5 once a short prefix line was added). Drop the short line instead of keeping it as a "fallback".
- Try score 1.5 as well as 3.0/5.0 — lower score sometimes detects strictly more utterances of the same line; let the grid script confirm this per line.
- Per-line grid results don't sum to the combined-file result (lines interact) — always re-verify the assembled phrase file with `eval-recording.py`.
- Use several kinds of negatives: the user's own chatter recording (as-is and amplified 4x/8x), TTS long-form sentences containing near-miss words, and the existing fixture suite (`ordinary-speech*.pcm`, `near-miss-ja.pcm`, other phrases' fixtures).

## Pass bar

Ship a change only once, on the positive recording, at least 8 of 10 spoken utterances are detected (`eval-recording.py` Part B "distinct utterance hits") and every negative recording/fixture has 0 hits.
