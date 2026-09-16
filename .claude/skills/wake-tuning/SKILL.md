---
name: wake-tuning
description: Evaluate and tune Butler's Vosk wake detection against a real speaker's recordings when the wake word is missed too often or false-wakes on unrelated speech.
---

Talk to the user in Japanese while following this skill; the skill body and any files it produces stay in English, matching the rest of this repo.

## When to use

The user reports the wake phrase ("Hello Butler" / "Hey Butler", Japanese pronunciation "ハロー、バトラー" / "ヘイ、バトラー") is missed too often, or wakes on unrelated speech. The wake engine is selectable in Settings → Wake (`Settings.wakeEngine`, `WakeEngine.VOSK`/`WakeEngine.JULIUS`); this skill covers the selectable alternative, **Vosk** (`vosk-model-small-ja-0.22`) running as a continuous ASR restricted to the runtime grammar `["ハロー バトラー", "ヘイ バトラー", "[unk]"]` (`VoskWakeDecoder`, `LocalWakeWordEngine.kt`), with a hit requiring one phrase's words to appear adjacent in the recognized token list (`VoskWake.hit`). There is no confidence threshold to tune — only the grammar phrase list and the adjacency rule are knobs. **Julius** is the default engine (since 2026-09-16, after on-device verification — see `third_party/julius/README.md` for its design and the offline comparison between the two engines); `scripts/julius-eval.py` is its counterpart to this skill's `scripts/vosk-eval.py` (same recording flow, same `.pcm` fixtures, but running the real Julius grammar/decoder offline instead), for anyone extending this workflow to tune Julius's penalty/threshold knobs instead.

### Prerequisites

- `.tools/vosk-python` — a venv with `pip install vosk`, used by `scripts/vosk-eval.py`.
- The Vosk model available locally, either at `.tools/vosk/vosk-model-small-ja-0.22` or the unpacked `app/src/main/assets/vosk/vosk-model-small-ja-0.22` copy.
- `.tools/android-sdk`'s `adb` on PATH (or full path under `.tools/android-sdk/platform-tools/adb`).
- A connected device with the app **and** the androidTest APK installed (`./gradlew assembleDebug assembleDebugAndroidTest`, then install both). Confirm the device serial with `adb devices` (use `-s SERIAL` on every adb command below if more than one device is attached).

## Steps

### 1. Ask the user for real recordings

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

### 2. Evaluate with scripts/vosk-eval.py

```sh
.tools/vosk-python/bin/python scripts/vosk-eval.py \
    .tools/hello-butler-<name>-rec1.pcm \
    .tools/<name>-speech-neg1.pcm
```

Prints, per recording, a summary line (e.g. `summary: 11 final hit(s) [= app wakes] (660.0/hr), 11 first-partial hit(s) [diagnostic only], decode 0.4s for 60s audio`) using the same grammar and `VoskWake.hit` adjacency rule the app ships. Final hits are what actually wakes the app; `VoskWakeDecoder.accept` only checks final results (`recognizer.acceptWaveForm(...)` returning `true`) — as of 2026-09-15, partial results are no longer accepted, because an offline eval against ~92 minutes of unrelated Japanese speech found 25 false wakes, all of them at the partial stage and none at the final stage, at the cost of firing about 1.2s later. First-partial hits are still printed as a diagnostic (how much earlier detection *would* land if partials were accepted), but no longer wake the app. For the positive recording, count distinct spoken utterances that produced a final hit; for the negative recording, any final hit at all is a false wake.

### 3. Knobs, if the pass bar isn't met

- **Grammar phrase list** — `WakePhrase.voskPhrases` (`LocalWakeWordEngine.kt`) and the grammar JSON built in `VoskWakeDecoder`'s `init` (one entry per phrase, plus `"[unk]"`). Adding alternate spellings/readings as extra grammar entries (e.g. a katakana variant the model tends to produce) can catch more of a real speaker's pronunciation — Vosk's runtime grammar accepts a list of alternative phrases, not just one.
- **Adjacency rule** — `VoskWake.hit` in `LocalWakeWordEngine.kt` requires the phrase's words to appear as a contiguous run in the whitespace-split token list (unit-tested in `VoskWakeTest.kt`). Loosening this (e.g. allowing a bounded gap between words) would catch more recognitions but risks false wakes, per the false-wake analysis in `third_party/vosk/README.md`'s "Grammar and the adjacency rule" section — change it only with fresh negative-recording evidence that it doesn't regress that.
- **Partial vs. final results** — `VoskWakeDecoder.accept` only checks final results (`recognizer.result`, once `acceptWaveForm` returns `true`); partial results are ignored entirely as of 2026-09-15, since an offline eval found 25 partial-only false wakes in ~92 minutes of unrelated Japanese speech and 0 final-result false wakes, at the cost of ~1.2s more latency. Reverting to checking partials too would need fresh negative-recording evidence that it doesn't reintroduce those false wakes — see `third_party/vosk/README.md`'s "Evaluation" section for the numbers. Longer negative material makes for a better stress test than a single 60s clip: public-domain LibriVox Japanese audiobooks, converted with ffmpeg to 16kHz mono s16le PCM under `.tools/` (gitignored), are a good source of extended unrelated Japanese speech for this.

### 4. Rebuild, verify, and record results

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
GRADLE_USER_HOME=<repo>/.tools/gradle-home \
./gradlew -q :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
```

Install both APKs on the device, run `WakeInstrumentation` (no `mode`/`seconds` args = normal PASS/FAIL run), confirm PASS. Return the device to standby: `adb shell am start -n dev.syumai.butler/.MainActivity`. Describe in the commit message (in English) the recording(s) used, the `vosk-eval.py` results, any grammar/adjacency-rule change made and why, and the instrumentation result. Ask the user to say the wake phrase ~10 more times on the real device and report back the hit rate.

## Pass bar

Ship a change only once, on the positive recording, at least 8 of 10 spoken utterances are detected (final hits) and the negative recording has 0 hits.
