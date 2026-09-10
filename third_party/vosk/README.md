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

`VoskWakeDecoder` restricts the recognizer to a runtime grammar of exactly two entries — `["ハロー バトラー", "[unk]"]` — via `Recognizer(model, 16000f, grammarJson)`, rather than running free-vocabulary recognition (see "Evaluation" below for why). A result is only treated as a wake-word hit when the recognized text contains the phrase's words as an **adjacent** run — `VoskWake.hit` (pure Kotlin, unit-tested in `VoskWakeTest.kt`) splits the result text on whitespace and checks the phrase's word list appears contiguously in the token list. This matters because Vosk's partial results transiently surface a bare `ハロー` or a bare `バトラー` on unrelated speech even in grammar mode (confirmed against a real negative recording), so requiring only "both words present somewhere" would false-wake; requiring adjacency does not.

## Evaluation

`scripts/vosk-eval.py` (run with `.tools/vosk-python/bin/python`, a venv with `pip install vosk`) replicates this decode+hit-check offline against arbitrary `.pcm` recordings, for tuning without a device. Against the user's own 60s recordings, the grammar `["ハロー バトラー", "[unk]"]` detected all 11 spoken utterances (each already at the partial-result stage) with zero false adjacent-pair hits in 60s of unrelated negative speech, at 0.4s decode time per 60s of audio; free-vocabulary decoding (no grammar) only caught 9/11 (misses heard as "ハロー から", "部屋 を バトラー") at 3.8s per 60s — both slower and less accurate, hence the grammar-restricted approach.

## Limitation: Japanese pronunciation only

`vosk-model-small-ja-0.22` is a Japanese acoustic/language model; it does not reliably recognize the English pronunciation of "Hello Butler" as `ハロー バトラー`. Only the Japanese pronunciation ("ハロー、バトラー") wakes the app — saying "Hello Butler" in English is not detected. There is no other on-device engine to fall back to for English-pronunciation coverage.

## License files

- `LICENSE`: vosk-api's own Apache-2.0 COPYING file (fetched from https://raw.githubusercontent.com/alphacep/vosk-api/master/COPYING), which also covers vosk-android.
- `JNA-LICENSE`: JNA's own `LICENSE` file (fetched from https://raw.githubusercontent.com/java-native-access/jna/master/LICENSE), describing its LGPL-2.1-or-later / Apache-2.0 dual licensing, with the full Apache-2.0 text appended since we use JNA under that license.
