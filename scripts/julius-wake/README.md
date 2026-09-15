# Julius phone-loop wake-word grammar (offline PoC)

This directory holds a Julius DFA grammar used to evaluate an offline,
Julius-based alternative to the Vosk wake-word detector (see
`scripts/vosk-eval.py` / `LocalWakeWordEngine.kt` for the Vosk side). It is
driven by `scripts/julius-eval.py` from the repo root.

## Prerequisites (macOS)

- `brew install julius` (Julius 4.6, BSD-3-Clause; also installs `mkfa`, `dfa_minimize`, `mkdfa.pl`).
- The GMM-HMM acoustic model from the Julius Dictation Kit, expected at
  `.tools/julius/dictation-kit/model/phone_m/` (gitignored). Fetch only that
  directory with a sparse clone:

  ```sh
  mkdir -p .tools/julius && cd .tools/julius
  git clone --depth 1 --filter=blob:none --sparse https://github.com/julius-speech/dictation-kit.git
  cd dictation-kit && git sparse-checkout set model/phone_m
  ```

- License: the Dictation Kit models (`LICENSE.txt` in that clone, Japanese
  original plus English translation) may be used and redistributed
  royalty-free provided the copyright notice and the full license text are
  attached, modifications are marked with author and date, and use of the
  "Julius Dictation Kit" is acknowledged. If the model is ever bundled into
  the app, add it under `third_party/julius/` with that license file, the
  same way `third_party/vosk/` documents Vosk.

## Status (2026-09-15)

Offline PoC only; the app still ships Vosk. Compared on the same recordings
(user's 60 s positive with 11 utterances, user's 60 s negative, 90 min of
LibriVox Japanese audiobooks, macOS `say` synthetic sets):

| set | Vosk (partial+final, pre-2026-09-15 app) | Vosk (final only, current app) | Julius (this grammar) |
|---|---|---|---|
| user positive, 11 utterances | 11 | 11 | 10 |
| user negative 60 s | 0 | 0 | 0 |
| LibriVox 90 min | 25 | 0 | 0 |
| `say` chat 78 s | 0 | 0 | 0 |
| `say` confusable 91 s | 9 | 3 | 7 |
| `say` positive x5 | 5 | 5 | 0 |

Julius never detected the synthetic `say` voice (its phone sequence is decoded
as unrelated phones), so speaker generalization of the JNAS GMM model is
unverified; collect positives from other real speakers before considering a
switch. An Android port would also need a libjulius NDK build (armeabi-v7a)
and JNI glue.

## Grammar approach

Julius's grammar mode requires every accepted sentence to fully cover the
input audio (`S : NS_B BODY NS_E`), so a naive grammar containing only the
wake word would force every non-wake utterance through a failed/garbled
parse. Instead we give the grammar a "garbage" path built from a
monophone loop (`FILLER`) that can explain arbitrary speech, and let `BODY`
optionally sandwich the `WAKE` word between runs of filler phones:

```
S : NS_B BODY NS_E
BODY : WAKE
BODY : FILLERS WAKE
BODY : WAKE FILLERS
BODY : FILLERS WAKE FILLERS
BODY : FILLERS
FILLERS : FILLER
FILLERS : FILLER FILLER
FILLERS : FILLER FILLERS FILLER
```

This lets Julius decode *any* recording as "some filler phones, maybe the
wake word, some more filler phones" and we read off whether `WAKE` shows up
in the 1-best sentence and how confident Julius was in it (`cmscore1`).

### Why `FILLERS` isn't the textbook `FILLERS : FILLERS FILLER` loop

Julius's `mkfa` (invoked by `mkdfa.pl`) rejects **immediate left recursion**
("Left recursion is formed in class ...") because it does a top-down NFA
expansion. Worse: `mkdfa.pl` compiles *two* DFAs from the same grammar file
— the one actually used at runtime (`wake.dfa`) is built from a
word-order-*reversed* copy of the grammar, and the secondary
`wake.dfa.forward` (unused by the Julius engine, but `mkdfa.pl` still
insists on generating it and aborts if it fails) is built from the grammar
as written. Reversing a two-symbol binary-recursive rule always turns
right recursion into left recursion (or vice versa), so **no two-symbol
self-recursive rule can compile cleanly in both directions**: writing it as
`FILLERS : FILLERS FILLER` fails the forward compile, and `FILLERS : FILLER
FILLERS` fails the reverse compile.

The fix used here is self-*embedding* recursion instead of self-*recursion*
at an edge: `FILLERS : FILLER FILLERS FILLER`. Because the leftmost symbol
of the RHS is the terminal category `FILLER` (never nullable), `FILLERS`
can never be leftmost-derived from itself — so this isn't left recursion in
either direction (reversing the 3-token sequence is a palindrome: it maps
to itself). Combined with the two non-recursive base cases (`FILLERS :
FILLER` for length 1, `FILLERS : FILLER FILLER` for length 2), this
generates a filler run of *any* length (odd lengths via the length-1 base
case, even lengths via the length-2 base case, both extended by +2 per
recursion), which is what we actually want (a stretch of unrelated speech
of any duration up to the ~8s segment cap used by `julius-eval.py`).

### Vocabulary (`wake.voca`)

Four categories:

- `NS_B` / `NS_E`: forced sentence boundary silence (`silB`/`silE`), as
  required by Julius grammar mode.
- `WAKE`: the wake phrase「ハローバトラー」, with four pronunciation
  variants covering the vowel-length/consonant ambiguity in how the phrase
  tends to get pronounced (`h a r o: b a t o r a:`, `h a r o b a t o r a:`,
  `h a r o: b a t o r a`, `h e r o: b a t o r a:`). All four map to the same
  word string, so any of them recognizing counts as the WAKE word appearing
  in `wseq1`/`sentence1`.
- `FILLER`: one entry per monophone in the acoustic model, each written as
  `<garbage> <phone>` — the word string is the same for every phone (only
  the pronunciation differs), so `FILLER` acts as a "match any single
  phone" category. Phones were taken from the `~h "..."` names in
  `.tools/julius/dictation-kit/model/phone_m/jnas-mono-16mix-gid.hmmdefs`,
  excluding `silB`/`silE`/`sp` (those are handled by `NS_B`/`NS_E`/word
  boundaries, not by the garbage loop): `a a: b by ch d dy e e: f g gy h hy
  i i: j k ky m my n N ny o o: p py q r ry s sh t ts u u: w y z` (39
  phones).

## Regenerating `wake.dfa` / `wake.dict`

The textbook command is:

```sh
cd scripts/julius-wake
mkdfa.pl wake
```

**This does not work as-is on this machine's Julius 4.6 (Homebrew,
macOS).** `mkdfa.pl` unconditionally shells out to `` `cygpath -w ...` ``
when invoking `dfa_minimize`, even outside Cygwin; on macOS `cygpath`
doesn't exist, so those substitutions silently become empty strings and
`dfa_minimize` fails ("usage: dfa_minimize ..."). `mkdfa.pl` does not check
`dfa_minimize`'s exit status, so it happily prints "generated: wake.dfa
wake.term wake.dict wake.dfa.forward" even though `wake.dfa` and
`wake.dfa.forward` were never actually written — the run looks successful
but silently produces a stale/missing `.dfa`. On top of that, the
mid-script filename `${dfafile}.tmp` is written two different ways in the
script (string-interpolated as `wake.dfa.tmp` in the `dfa_minimize` calls,
but built as a bareword concatenation `wake.dfatmp` — no dot — in the
`mkfa()` calls), so even a working `cygpath` shim wouldn't reconcile the
two paths.

Given that, regenerate by hand instead of trusting `mkdfa.pl`'s exit code.
From this directory:

```sh
# 1. Build a word-order-reversed copy of the grammar (mkdfa.pl's own logic:
#    reverse the RHS token order of every rule).
python3 - <<'EOF'
lines = open("wake.grammar", encoding="utf-8").read().splitlines()
out = []
for line in lines:
    if not line.strip():
        out.append("")
        continue
    left, right = line.split(":", 1)
    out.append(f"{left}: {' '.join(reversed(right.split()))}")
open("wake.rev.grammar", "w", encoding="utf-8").write("\n".join(out) + "\n")
EOF

# 2. Build the stripped vocabulary mkfa expects (category headers only,
#    "%NAME" -> "#NAME").
python3 - <<'EOF'
import re
out = []
for line in open("wake.voca", encoding="utf-8"):
    line = line.rstrip("\n")
    if not line.strip():
        out.append("")
        continue
    m = re.match(r"^%[ \t]*([A-Za-z0-9_]*)", line)
    if m:
        out.append(f"#{m.group(1)}")
open("wake.tmpvoca", "w", encoding="utf-8").write("\n".join(out) + "\n")
EOF

# 3. Compile + minimize both directions. wake.dfa (from the *reversed*
#    grammar) is the file Julius actually loads at runtime.
mkfa -e1 -fg wake.rev.grammar -fv wake.tmpvoca -fo wake.rev.raw -fh wake.rev.h
dfa_minimize wake.rev.raw -o wake.dfa
mkfa -e1 -fg wake.grammar     -fv wake.tmpvoca -fo wake.fwd.raw -fh wake.fwd.h
dfa_minimize wake.fwd.raw -o wake.dfa.forward   # optional secondary file, not used by julius itself

# 4. wake.dict: one line per vocabulary entry, "<id>\t[<word>]\t<pron>",
#    <id> = 0-based category index in wake.voca's "%" order (0=NS_B,
#    1=NS_E, 2=WAKE, 3=FILLER). Easiest to just let mkdfa.pl's own
#    (unaffected) dict-generation logic produce it -- rerun `mkdfa.pl wake`
#    once; it *will* also regenerate wake.dict/wake.term correctly even
#    though the .dfa side fails, since that part of the script doesn't call
#    dfa_minimize. Or write it by hand from wake.voca in the same format.

# 5. Clean up intermediates -- only wake.dfa and wake.dict need to be kept
#    (plus wake.grammar/wake.voca/am.jconf, which are the sources).
rm -f wake.rev.grammar wake.tmpvoca wake.rev.raw wake.rev.h wake.fwd.raw wake.fwd.h wake.dfa.forward wake.term wake.dfatmp
```

If a future Julius/Homebrew update fixes the `cygpath` bug, plain `mkdfa.pl
wake` (using the `FILLER FILLERS FILLER` grammar above, not the textbook
left-recursive form) should work directly.

## Running Julius against this grammar

```sh
julius -C scripts/julius-wake/am.jconf -gram scripts/julius-wake/wake \
       -n 1 -output 1 -input rawfile -filelist <list-of-wav-paths> \
       -penalty1 -0.8 -penalty2 -0.8
```

Run from the repo root (or anywhere) — `-gram`/`-filelist` are resolved
relative to the process's cwd, but note that `-h`/`-hlist` *inside*
`am.jconf` are resolved relative to `am.jconf`'s own directory (a
Julius-specific quirk), which is why `am.jconf` uses `../../.tools/...`
rather than repo-root-relative paths.

For each input file Julius (2nd pass enabled, i.e. not `-1pass`) prints:

```
sentence1: <s> <garbage> ハローバトラー <garbage> </s>
wseq1: 0 3 2 3 1
phseq1: silB | z | h a r o: b a t o r a | a | silE
cmscore1: 0.093 0.068 0.111 0.064 1.000
score1: -4188.459473
```

`scripts/julius-eval.py` parses `sentence1`/`cmscore1` pairwise by position
to read off the WAKE word's confidence score.

## Knobs

- `-penalty1`/`-penalty2` (1st/2nd pass word insertion penalty, Julius
  default `0.0`): the dominant knob for false-positive control here, more
  so than `--threshold`. A *more negative* penalty makes inserting many
  words more expensive, which — contrary to naive intuition — makes the
  decoder *prefer* explaining a stretch of audio as one longer word (WAKE)
  rather than many short filler words, so it makes the garbage path
  *cheaper relative to* the single WAKE word only as it goes *less*
  negative/more positive, not more negative. See "Tuning results" below.
- `--threshold` / Julius's `cmscore1` (`-cmalpha`, default `0.05`, left at
  default): with only 4 grammar categories there's very little competing
  search-graph mass for the confidence measure to work with, so genuine
  WAKE hits score in the ~0.04–0.14 range rather than the >0.5 one might
  expect from a richer (e.g. n-gram dictation) grammar. Presence-in-`wseq1`
  combined with the penalty tuning turned out to be the real discriminator;
  `--threshold` mainly guards against the rare case where WAKE is proposed
  as a low-confidence alternative to a much better-scoring path.
- `--vad-threshold`: the energy-VAD's RMS cutoff (after `julius-eval.py`
  peak-normalizes each recording — see next section).

## Tuning results

`julius-eval.py` peak-normalizes each recording to a common target before
VAD and before handing audio to Julius, because the sample recordings
differ hugely in mic gain (observed peak `|sample|`: rec1 ≈2755, neg1 ≈972,
gongitsune_01 ≈13126) — without normalization, a fixed absolute
`--vad-threshold` either misses most of neg1's speech or over-segments
gongitsune_01.

Sweep performed (`-penalty1`/`-penalty2` tied together, `--threshold 0.0`
i.e. presence-only, to isolate the penalty's effect from the confidence
threshold):

| penalty1/2 | rec1 hits (of 11-12 VAD segments) | neg1 false hits | gongitsune_01 false hits (316s) |
|---:|---:|---:|---:|
| 0 (Julius default) | 9 | 0 | 0 |
| -0.7 | 10 | — | 0 |
| -0.8 | **10** | **0** | **0** |
| -0.9 | 10 | — | 0 |
| -1 | 10 | 0 | 1 (cmscore 0.112, inside the true-positive range) |
| -2 | 11 | — | 1 (cmscore 0.124, inside the true-positive range) |
| -3 | 11 | — | more false hits |
| -5, -10, -15, -20 | 11 | 1 (cmscore 0.406 at -20) | many false hits, cmscores overlapping/exceeding true positives |

Penalties less negative than -0.7 (e.g. 0) leave one extra genuine
utterance undetected; penalties more negative than -0.9 start producing
false WAKE insertions on `gongitsune_01` whose `cmscore1` falls *inside*
the true-positive range, so no `--threshold` value can cleanly separate
them. **`-0.8` was chosen as the default for both passes**: it is the most
negative penalty (of the values tried) that still produces zero false WAKE
insertions on both negative recordings, while recovering one more true
positive than the Julius default of `0.0`.

Final result with the shipped defaults (`--penalty1 -0.8 --penalty2 -0.8
--threshold 0.05 --vad-threshold 1200`):

| file | segments | hits | false hits | decode wall time |
|---|---:|---:|---:|---:|
| `hello-butler-ja-rec1.pcm` (60s, 11 spoken repetitions) | 12 | 10 | n/a | 0.65s |
| `japanese-speech-neg1.pcm` (60s) | 27 | n/a | 0 | 2.23s |
| `neg-librivox/gongitsune_01.pcm` (316s) | 158 | n/a | 0 | 10.73s |

10 of rec1's 11 utterances hit (one, at 54.43–55.84s, decodes as pure
`<garbage>` with no WAKE candidate at all under `-0.8`; a 12th VAD segment
at 59.28–59.99s is a truncated tail at the very end of the 60s recording,
not counted among the 11 spoken repetitions). This exceeds the ">=9 of 11"
requirement with margin, at 0 false wakes on both negative recordings.
Decoding is comfortably faster than real time (~10x–90x).

Things tried that did **not** make it into the defaults:
- Very negative penalties (-5 to -20): recover the last one or two rec1
  utterances but introduce false WAKE insertions on unrelated speech whose
  confidence overlaps the true-positive range — not worth the trade.
- Requiring `BODY : FILLERS WAKE FILLERS` only (i.e. WAKE must always be
  surrounded by at least one filler phone on each side) was considered to
  suppress spurious WAKE-at-segment-boundary matches, but wasn't needed
  once the penalty was tuned — left the grammar with `BODY : WAKE` /
  `BODY : FILLERS WAKE` / `BODY : WAKE FILLERS` alternatives too, so short,
  tightly-cropped segments (little to no filler either side, as our VAD
  padding of 200ms tends to produce) still match cleanly.
- `-cmalpha` was left at the Julius default (`0.05`); given the confidence
  measure separates cleanly on presence + penalty alone, tuning it wasn't
  necessary for this grammar size.
