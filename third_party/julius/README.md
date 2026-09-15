# Bundled Julius wake-word inference (experimental)

- Engine: **Julius 4.6**, BSD-3-Clause (see `LICENSE`, the upstream `julius-src/LICENSE` file), built
  from the pinned source commit as a standalone executable, `libjulius-bin.so`, cross-compiled for
  `armeabi-v7a`. The build script is `scripts/build-julius-android.sh`, with build notes in
  `scripts/build-julius-android.md` (both maintained alongside this README, not duplicated here).
- Engine source: https://github.com/julius-speech/julius
- Model: **Julius Dictation Kit**'s GMM-HMM triphone acoustic model (`jnas-tri-3k16-gid.binhmm` +
  `logicalTri-3k16-gid.bin`, the `phone_m` model), trained on the JNAS (Japanese Newspaper Article
  Sentences) corpus. License: see `DICTATION-KIT-LICENSE.txt` (the dictation-kit's own `LICENSE.txt`,
  Japanese original plus English translation, copied verbatim — already UTF-8 with CRLF line endings in
  the upstream file, so no Shift_JIS conversion was needed here, unlike what `scripts/julius-wake/README.md`
  anticipated might be necessary).
- Model source: https://github.com/julius-speech/dictation-kit, pinned at commit
  `1ceb4dec245ef482918ca33c55c71d383dce145e` (`git -C .tools/julius/dictation-kit rev-parse HEAD`).
- Grammar: a phone-loop "garbage" DFA grammar built specifically for this app's wake phrase, under
  `scripts/julius-wake/` (source of truth: `wake.grammar`, `wake.voca`, `am.jconf`, compiled to
  `wake.dfa`/`wake.dict`) — see that directory's README for the grammar design and how to regenerate it.
  This grammar is Butler's own work, not part of the Dictation Kit distribution, so it isn't covered by
  the Dictation Kit license.

## License obligations and how they're met

The Dictation Kit license (`DICTATION-KIT-LICENSE.txt`) permits free use and redistribution provided
that: the copyright notice and the full license text (both Japanese and English) are attached (this
directory does that — `DICTATION-KIT-LICENSE.txt` plus this README); any modification is marked with
the author and date of the modification (the model files themselves are **not** modified — bundled
byte-for-byte as fetched, verified against `SHA256SUMS`); and use of "Julius Dictation Kit" is
explicitly acknowledged when publishing or presenting results using it — the in-app "Third-party
notices" screen (`settings_licenses_body` in `strings.xml`/`values-ja/strings.xml`) and this repository's
top-level `README.md` both state "This app uses the Julius Dictation Kit" for that purpose. Julius
itself (`LICENSE`, BSD-3-Clause) only requires the copyright notice and license text to be retained,
which this directory and `third_party/README.md`'s index both do.

## Why a subprocess, not JNI

Android 10+ enforces W^X (a mapped executable page can't also be writable) for anything outside
`nativeLibraryDir`: an executable placed under the app's own writable storage (`filesDir`,
`cacheDir`, an unpacked asset) simply cannot be `exec`'d. `nativeLibraryDir` is the one writable-at-install,
executable-at-runtime location the system grants an app, and it's populated from `jniLibs` — so
`libjulius-bin.so` is packaged as if it were a native library (`app/build.gradle.kts`'s
`packaging.jniLibs.useLegacyPackaging = true`, which makes the system extract it there instead of
leaving it zipped inside the APK) even though it's a real standalone Julius executable, not a JNI
`.so`. This sidesteps needing a JNI build of libjulius (linking Julius's C API into a JNI bridge)
entirely: `JuliusWakeDecoder` (`LocalWakeWordEngine.kt`) just runs it as a child process
(`ProcessBuilder`) and talks to it over two loopback TCP sockets — Julius's own adinnet (audio in) and
module (recognition results out, XML) protocols — instead of calling into it directly.

**adinnet** (`libsent/src/adin/adin_tcpip.c`, `libsent/src/net/rdwt.c` in the Julius source): each
packet is a 4-byte native-byte-order (little-endian on this armeabi-v7a target — Julius has no
cross-endian support here, `rdwt.c`'s own doc comment: "Does not work between different machine byte
order") int byte count, followed by that many bytes of s16le PCM; a 0-count packet ends the current
speech segment. Since Julius does not cut silence itself for adinnet input (`-nocutsilence`), the
decoder runs its own live energy VAD (`WakeVad.kt`, ported from `scripts/julius-eval.py`'s offline VAD)
to decide segment boundaries.

**module** (`julius/module.c`, `julius/output_module.c`): Julius writes `<RECOGOUT>`/`<SHYPO>`/`<WHYPO
.../>` XML fragments per recognized segment, terminated by a line containing a single `.`, interleaved
with status lines. `JuliusWake.kt` parses `<WHYPO WORD="..." CM="...".../>` lines and reports a hit when
`WORD` is the wake phrase and `CM` (Julius's per-word confidence measure) is at or above 0.05.

Julius blocks at startup waiting for a module client to connect before it opens its adinnet server, so
`JuliusWakeDecoder` connects the module socket first, then adinnet, each retried for up to 15s.

## 2026-09-15 offline comparison (why Julius ships experimental, off by default)

Evaluated with `scripts/julius-eval.py` against the same recordings used to tune the shipped Vosk
grammar (see `third_party/vosk/README.md`'s "Evaluation" section): the user's own 60s positive recording
(11 spoken repetitions of the wake phrase), the user's own 60s negative recording, ~90 minutes of
LibriVox Japanese audiobooks, and a synthetic macOS `say` TTS voice.

| set | Julius (`-penalty1 -0.8 -penalty2 -0.8`, threshold 0.05) |
|---|---|
| user positive, 11 utterances | 10/11 |
| user negative 60s | 0 false wakes |
| LibriVox ~90 min | 0 false wakes |
| synthetic `say` TTS voice, 5 utterances | 0/5 |

Julius never detected the synthetic `say` voice at all — its phone sequence decodes as unrelated
phones under this grammar/model, unlike Vosk, which does catch some of it. Since the JNAS acoustic
model was evaluated against exactly one real speaker (the user), speaker generalization is unverified;
0 false wakes across ~92 minutes of real negative speech is a solid true-negative result, but 10/11 true
positives from a single speaker isn't enough evidence to make Julius the default.

**Confirmed again on-device, 2026-09-16, with the OpenAI-TTS `WakeInstrumentation` fixtures**: the same
non-detection pattern reproduces with `hello-butler-ja.pcm`/`hello-butler-ja-2.pcm` (OpenAI TTS, not
macOS `say`) — replaying the exact adinnet bytes `JuliusWakeDecoder` sends through
`scripts/julius-adinnet-client.py` (bypassing the Kotlin client entirely) across several
`-penalty1`/`-penalty2` settings (`0`, `-0.8`, `-2`, `-5`) always decoded both fixtures entirely as
`<garbage>`, with `ハローバトラー` never proposed as a competing hypothesis at all — no confidence
threshold could have recovered it. The three negative fixtures correctly stayed silent throughout, so
`WakeInstrumentation` hard-asserts only that property for Julius; the two positive fixtures are fed
through and their hit count is reported, not asserted (see its source comment). This is consistent with,
not contrary to, the finding above: this build's JNAS model reliably avoids false wakes on unrelated
speech (synthetic or real) but has only been confirmed to detect the wake phrase from real human speech.

Hence Julius ships as a selectable, clearly-labeled "experimental" engine in Settings -> Wake, with Vosk
remaining the default. See `scripts/julius-wake/README.md`'s "Tuning results" for the full penalty/threshold sweep
this table's numbers come from, and `.claude/skills/wake-tuning/SKILL.md` for how to collect more
real-speaker recordings and re-evaluate either engine.

## License files

- `LICENSE`: Julius's own `LICENSE` file (BSD-3-Clause), copied verbatim from
  `.tools/julius/julius-src/LICENSE` (the pinned Julius source checkout).
- `DICTATION-KIT-LICENSE.txt`: the Dictation Kit's own `LICENSE.txt` (Japanese original plus English
  translation), copied verbatim from `.tools/julius/dictation-kit/LICENSE.txt` at the pinned commit.
- `SHA256SUMS`: sha256 hashes of the two bundled model files, computed with `shasum -a 256` from the
  files already present under `.tools/julius/dictation-kit/model/phone_m/` (the sparse clone described
  in `scripts/julius-wake/README.md`'s Prerequisites) — this is the source of truth `scripts/fetch-deps.sh`
  verifies fetched files against, the same way `third_party/vosk/SHA256SUMS` works for the Vosk model.

## Model bundling

The two model files (~12MB total) are not checked into git. `scripts/fetch-deps.sh` downloads them from
`https://raw.githubusercontent.com/julius-speech/dictation-kit/<pinned commit>/model/phone_m/<file>`
into `.tools/julius/` (gitignored) and copies them into
`app/src/main/assets/julius/model/` (also gitignored — see `.gitignore`'s
`app/src/main/assets/julius/model/` entry), verifying both against `SHA256SUMS`. The grammar
(`wake.dfa`/`wake.dict`) is not fetched — `app/build.gradle.kts`'s `copyJuliusGrammar` task copies it
straight from the checked-in `scripts/julius-wake/` into a generated assets directory
(`build/generated/juliusGrammar/julius/grammar/`) that's added to the main source set's assets, so
`scripts/julius-wake/` stays the single source of truth and no copy of it needs to be committed under
`app/src/main/assets/`.

At runtime, `JuliusWakeDecoder` unpacks the merged `julius/` asset tree (both `model/` and `grammar/`)
to `File(context.filesDir, "julius")` once via the shared `AssetUnpacker` (also used by
`VoskWakeDecoder`), the same temp-dir-then-rename-plus-`.ready`-marker approach documented in
`third_party/vosk/README.md`'s "Model bundling" section — the marker text includes
`BuildConfig.VERSION_CODE` so a grammar change between app versions (unlike the model, which isn't
expected to change) triggers a re-unpack rather than being silently reused.
