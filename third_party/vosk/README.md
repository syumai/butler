# Bundled Vosk wake-word inference

- Library: **vosk-android 0.3.75**, Apache-2.0 (see LICENSE, the upstream vosk-api COPYING file).
- Library source: https://github.com/alphacep/vosk-api
- Maven Central artifact: `com.alphacephei:vosk-android:0.3.75` (AAR — resolved by Gradle, not fetched by `scripts/fetch-deps.sh`). Bundles `libvosk.so` for `armeabi-v7a` (~9MB).
- Native dependency: **JNA 5.18.1**, dual-licensed LGPL-2.1-or-later / Apache-2.0; we use it under Apache-2.0 (see JNA-LICENSE). Source: https://github.com/java-native-access/jna. Maven Central artifact: `net.java.dev.jna:jna:5.18.1` (AAR, bundles `libjnidispatch.so` for `armeabi-v7a`).
- Both dependencies must be declared with the `@aar` Gradle notation (`implementation("net.java.dev.jna:jna:5.18.1@aar")`, `implementation("com.alphacephei:vosk-android:0.3.75@aar")`) — the plain (non-`@aar`) coordinate resolves to a jar with no Android native libraries.
- Model: **vosk-model-small-ja-0.22**, Apache-2.0. Model card: https://alphacephei.com/vosk/models
- Archive: https://alphacephei.com/vosk/models/vosk-model-small-ja-0.22.zip (49,704,573 bytes)

## AAR hashes (for reference only)

Gradle resolves these two AARs from Maven Central by coordinate; their hashes are recorded here for provenance, not for a local verification step (unlike the model, they are not fetched or checked by `scripts/fetch-deps.sh`):

```
ab2f8b91ac8051561aa325546b35fed9a68b36b8121bac5c6fb927525c4adfad  com.alphacephei:vosk-android:0.3.75 (AAR)
7f053e3ec99e14dd71259c82c1c8a02738d64a13c31226b2acc170f3060951e0  net.java.dev.jna:jna:5.18.1 (AAR)
```

## Model bundling

The unpacked model (~99MB: `am/final.mdl`, `conf/`, `graph/` including the runtime-grammar-capable `HCLr.fst`/`Gr.fst`, `ivector/`) is **not** checked into git. `scripts/fetch-deps.sh` downloads the zip above into `.tools/vosk/` (gitignored), verifies its sha256, and unpacks it into `app/src/main/assets/vosk/vosk-model-small-ja-0.22/` (also gitignored — see `.gitignore`'s `app/src/main/assets/vosk/` entry), then verifies every unpacked file against `third_party/vosk/SHA256SUMS` (the source of truth for the model tree; run `shasum -a 256 -c third_party/vosk/SHA256SUMS` from the repository root to check it directly). Gradle runs `fetch-deps.sh` automatically (the `fetchDeps` task in `app/build.gradle.kts`, gated on `app/src/main/assets/vosk/vosk-model-small-ja-0.22/am/final.mdl` existing) whenever any required dependency is missing.

At runtime, `VoskWakeDecoder` (`LocalWakeWordEngine.kt`) copies the asset tree once from `assets/vosk/vosk-model-small-ja-0.22/` to `File(context.filesDir, "vosk/vosk-model-small-ja-0.22")` on first use — Vosk's `Model` constructor needs a real filesystem path, not an asset path — walking assets recursively with `AssetManager.list`, copying into a temporary directory and renaming it into place only on full success (so a half-copied model, e.g. from a killed process, is never treated as ready), guarded by a `.ready` marker file so later launches skip the copy. `org.vosk.android.StorageService` (the helper used by Vosk's own Android demo) is not used, since it expects a `uuid` asset file and the app's external files directory, neither of which fits this app's bundling.

## Grammar and the adjacency rule

`VoskWakeDecoder` restricts the recognizer to a runtime grammar built from `WakePhrase.HELLO_BUTLER.voskPhrases` plus the catch-all — `["ハロー バトラー", "ヘイ バトラー", "[unk]"]` — via `Recognizer(model, 16000f, grammarJson)`, rather than running free-vocabulary recognition (see "Evaluation" below for why). "ヘイ" ("Hey") was added 2026-09-17 as a second pronunciation set (see "Hey Butler" below); it is present in the model's vocabulary (`graph/words.txt`), so no model change was needed. A result is only treated as a wake-word hit when the recognized text contains one phrase's words as an **adjacent** run — `VoskWake.hit` (pure Kotlin, unit-tested in `VoskWakeTest.kt`) splits the result text on whitespace and checks that phrase's word list appears contiguously in the token list, and `VoskWakeDecoder` calls it once per phrase in `voskPhrases`. This matters because Vosk's partial results transiently surface a bare `ハロー` or a bare `バトラー` on unrelated speech even in grammar mode (confirmed against a real negative recording), so requiring only "both words present somewhere" would false-wake; requiring adjacency does not.

As of 2026-09-15, `VoskWakeDecoder.accept` only checks `VoskWake.hit` against **final** results — partial results are no longer checked at all, adjacency rule or not (see "Evaluation" below for why).

## Evaluation

`scripts/vosk-eval.py` (run with `.tools/vosk-python/bin/python`, a venv with `pip install vosk`) replicates this decode+hit-check offline against arbitrary `.pcm` recordings, for tuning without a device. Against the user's own 60s recordings, the grammar `["ハロー バトラー", "[unk]"]` detected all 11 spoken utterances (each already at the partial-result stage) with zero false adjacent-pair hits in 60s of unrelated negative speech, at 0.4s decode time per 60s of audio; free-vocabulary decoding (no grammar) only caught 9/11 (misses heard as "ハロー から", "部屋 を バトラー") at 3.8s per 60s — both slower and less accurate, hence the grammar-restricted approach.

**Final-result-only decision (2026-09-15).** A larger offline eval against ~92 minutes of unrelated real Japanese speech — the user's own 60s negative recording plus 90 minutes of public-domain LibriVox Japanese audiobooks (converted to 16kHz mono s16le PCM with ffmpeg) — found 25 false wakes, every one of them a partial-result hit (e.g. the partial `[unk] ハロー バトラー` triggered by the spoken words 「はりきりという網をゆすぶって」); final results produced 0 false wakes across the same audio. The user's positive recording still hit 11/11 at the final-result stage, about 1.2s later on average than the corresponding partial-result hit. Given that trade-off, `VoskWakeDecoder.accept` now fires only on final results (`recognizer.acceptWaveForm(...)` returning `true`); partial results are still printed by `scripts/vosk-eval.py` as a diagnostic, but no longer wake the app.

## Limitation: Japanese pronunciation only

`vosk-model-small-ja-0.22` is a Japanese acoustic/language model; it does not reliably recognize the English pronunciation of "Hello Butler"/"Hey Butler" as `ハロー バトラー`/`ヘイ バトラー`. Only the Japanese pronunciation ("ハロー、バトラー" or "ヘイ、バトラー") wakes the app — saying either phrase in English is not detected. There is no other on-device engine to fall back to for English-pronunciation coverage.

## "Hey Butler" as a second wake phrase (2026-09-17)

`WakePhrase.HELLO_BUTLER` covers two pronunciation sets — `voskPhrases = listOf("ハロー バトラー", "ヘイ バトラー")` — rather than a single phrase, so both "Hello Butler" and "Hey Butler" (Japanese pronunciation) wake the app via Vosk. "Hey Butler" was rejected once before (README.md's "Confirmed requirements"), but that was under the earlier sherpa-onnx keyword-spotting engine (removed 2026-09-11), whose GigaSpeech KWS model needed short, low-threshold phrase lines to catch a Japanese speaker and those false-woke too often — a different failure mode from Vosk's grammar-restricted ASR here, which still requires the full two-word phrase (`ヘイ バトラー`, checked with the same adjacency rule as `ハロー バトラー`) to appear contiguously in a final result. "ヘイ" is present in the model's vocabulary (`graph/words.txt`), so no model change was needed.

The same phrase was also tried for Julius (the default engine)'s phone-loop grammar and evaluated offline (`scripts/julius-eval.py`). A first pass, gating on `cmscore1` alone like `ハローバトラー`, produced false wakes on real Japanese speech (LibriVox) even after dropping its most confusable pronunciation variants. A second pass the same day found that `cmscore1` overlapping between genuine and false wakes wasn't the whole story: every false wake sat inside running speech with many surrounding `<garbage>` filler words, while genuine wake utterances are short, standalone segments with few fillers — a structural difference `cmscore1` alone can't see. Gating on both `cmscore1 >= 0.05` and total filler-word count `<= 10` in the same recognized sentence produced **zero** false wakes across the user negative recording, ~92 minutes of LibriVox, and the `say` confusable set, while still detecting 11/11 of the positive recording's spoken utterances — so "Hey Butler" **is** shipped for Julius too, as of 2026-09-17; `WakePhrase.juliusWords` now has two entries, `"ハローバトラー"` and `"ヘイバトラー"`. See `scripts/julius-wake/README.md`'s "2026-09-17: Hey Butler, take two — a structural gate" section and `third_party/julius/README.md` for the full sweep. Vosk's own addition here was not re-evaluated with an equivalent real-speech sweep beyond the unit tests (`VoskWakeTest.kt`); its detection mechanism — an exact two-word adjacency match in a final ASR result, restricted to a two-entry runtime grammar — remains different from and independent of Julius's block-level confidence-plus-filler-count gate. Recall for a real speaker actually saying "Hey Butler" on Julius is unverified (no real-voice recording of "Hey Butler" exists; the one Julius hit in the evaluation is a misrecognized "Hello Butler" utterance) and should be checked on-device.

## License files

- `LICENSE`: vosk-api's own Apache-2.0 COPYING file (fetched from https://raw.githubusercontent.com/alphacep/vosk-api/master/COPYING), which also covers vosk-android.
- `JNA-LICENSE`: JNA's own `LICENSE` file (fetched from https://raw.githubusercontent.com/java-native-access/jna/master/LICENSE), describing its LGPL-2.1-or-later / Apache-2.0 dual licensing, with the full Apache-2.0 text appended since we use JNA under that license.
