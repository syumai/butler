# Building Julius for Android (armeabi-v7a)

`scripts/build-julius-android.sh` cross-compiles Julius 4.6 (libsent +
libjulius + the `julius` CLI) with the Android NDK's standalone clang
wrappers and installs the result as
`app/src/main/jniLibs/armeabi-v7a/libjulius-bin.so`. Naming it `.so` (even
though it's an ELF executable, not a shared library) is deliberate: it's the
only way to get the Android package manager to extract a native binary into
the app's `nativeLibraryDir`, where the app can then run it with
`ProcessBuilder`.

Run it from the repo root:

```
scripts/build-julius-android.sh
```

It is idempotent and non-destructive: it always deletes and recreates
`.tools/julius/build-android` from the pristine, untouched
`.tools/julius/julius-src` checkout, applies all patches to that scratch
copy, builds, strips, and copies the result into `jniLibs/`. Nothing under
`.tools/julius/julius-src` is ever modified.

- Julius source commit: `3b7174d0d4091f5e6ebb917769822032d079996f` (tag v4.6)
- Last build result: `app/src/main/jniLibs/armeabi-v7a/libjulius-bin.so`,
  **460160 bytes**, sha256
  `f42f769e4c4e08c78dfb08f2e8c839f06d01617d28e322946eec2b78cc418694`

## Toolchain

- NDK r30 at `/opt/homebrew/share/android-ndk` (override with `$ANDROID_NDK`)
- Compiler: `toolchains/llvm/prebuilt/darwin-x86_64/bin/armv7a-linux-androideabi30-clang`
  (API level 30, matches this app's target device: 32-bit ARM / Android 11 /
  LineageOS 18.1)
- `AR`/`RANLIB`/`STRIP` = the toolchain's `llvm-ar`/`llvm-ranlib`/`llvm-strip`
- `--host=arm-linux-androideabi` passed to all three `./configure` invocations
- `CFLAGS=-O2 -std=gnu11 -Wno-int-conversion -fPIE -ffunction-sections -fdata-sections`,
  `LDFLAGS=-fPIE -pie -Wl,--gc-sections -L<stub-libs dir>` (see below). mfpu/mfloat-abi
  are left at the NDK clang driver's own defaults for armv7a (it already
  passes `-target-feature +vfp... -mfloat-abi soft` and, at link time,
  `-pie -dynamic-linker /system/bin/linker` automatically for API>=21 — the
  explicit `-fPIE`/`-pie` in our flags are redundant with that but kept per
  the build brief, belt and suspenders).

## configure flags chosen, and why

- **libsent**: `--with-mictype=none --disable-zlib --without-sndfile`
  (plus `ac_cv_prog_c_openmp=unsupported`, see patches below). We only ever
  use `-input adinnet` and `-input rawfile`, both always built
  (`libsent/src/adin/adin_tcpip.c`, `adin_file.c`), so no mic driver, zlib,
  or libsndfile is needed.
- **libjulius**: `--disable-plugin --disable-libfvad`. We don't load
  runtime plugins or use the bundled VAD library; pthread is left at its
  default (enabled) — see the pthread patch below for why it needed help to
  actually detect as available.
- **julius**: default flags (`--host` only). In particular
  `--enable-charconv` is left at its default (`auto`) — see "Patches" for
  why `--enable-charconv=no` is actually a build-breaking combination
  upstream. `auto` resolves to `iconv` in this cross build (`AM_ICONV`'s
  link-time check succeeds against the NDK's bionic iconv).

## Patches applied (all to the `.tools/julius/build-android` scratch copy, never to `julius-src`)

1. **`libsent/configure --with-mictype=none`.** Julius's mic-type
   `case` statement (`libsent/configure.in` / the generated `configure`)
   only recognizes real sound-driver names (oss/alsa/esd/pulseaudio/
   portaudio/coreaudio/...); every one of them either requires a header
   that doesn't exist for Android or fails its own capability probe, and
   the catch-all `*)` case aborts configure with "mictype not supported".
   The script patches the pre-generated `libsent/configure` shell script
   directly (not `configure.in` + a fresh `autoconf` run — see next point)
   to add a `none)` case that just sets a description string and pulls in
   no mic backend object, leaving `USE_MIC` undefined. `-input mic` is then
   simply unavailable at runtime, like any other libsent build lacking a
   platform audio driver; `-input adinnet`/`-input rawfile` are unaffected.

   Why patch the generated `configure` instead of `configure.in` +
   `autoconf`: this source tree's checked-in `configure` scripts were
   generated with an older autoconf/automake than Homebrew's current one.
   A fresh `autoconf` run pulls in `AM_PROG_AR`, which expects a
   `support/ar-lib` auxiliary script this tree doesn't have, and fails
   with "cannot find required auxiliary files: ar-lib". Patching the
   generated shell script's `case` statement directly (the same marker
   text exists verbatim in both `configure.in` and the generated
   `configure`) sidesteps that version skew entirely. `configure.in` is
   still patched too, purely to stay in sync as documentation — it is
   never re-run through `autoconf` by this script.

2. **Route around Julius's own hand-written `config-android-*.h`
   headers.** `libjulius/include/julius/julius.h` and
   `libsent/include/sent/stddefs.h` both do:
   ```c
   #elif defined(__ANDROID__)
   # include <julius/config-android-libjulius.h>
   # include <sent/config-android-libsent.h>
   ```
   *instead of* including the real autoconf-generated `config.h`s, whenever
   `__ANDROID__` is predefined — which the NDK clang always does. These
   `config-android-*.h` headers are legacy, hand-maintained snapshots for
   an old Android.mk/ndk-build setup (see
   `.tools/julius/julius-src/msvc/Library_PortAudio/Android.mk`, which
   built PortAudio's OpenSLES backend for Julius on Android). They
   hard-code `USE_MIC 1`, `HAVE_ZLIB 1`, `HAVE_LIBFVAD`, etc. — exactly the
   things we disabled through `./configure` above. Left alone, they
   silently override every one of our `--disable-*`/`--with-mictype=none`
   choices, and the final link fails with undefined
   `adin_mic_*`/`fvad_*` symbols (that's exactly what happened on the
   first build attempt). The build script patches both files so the
   `__ANDROID__` branch is permanently dead
   (`#elif 0 && defined(__ANDROID__)`) and execution falls through to the
   normal `#else` branch that includes the real, autoconf-generated
   `config.h`. (Two *other* `__ANDROID__` uses — a `pthread_cancel` shim in
   `stddefs.h` and an `aligned_alloc` avoidance in `libsent/src/util/
   mymalloc.c` — are genuine bionic-compat workarounds and are left as-is.)

3. **Force-disable OpenMP detection
   (`ac_cv_prog_c_openmp=unsupported` env var for libsent's `./configure`).**
   `libsent/configure.in` unconditionally runs `AC_OPENMP` and, if it
   succeeds, bakes `-fopenmp` into `libsent-config --cflags` — which is
   used not just to compile but also appears on **julius's final link
   line**. Nothing in libsent/libjulius/julius actually uses OpenMP
   pragmas, but clang's driver still adds a dynamic dependency on
   `libomp.so` to the link whenever `-fopenmp` is present at link time,
   and `libomp.so` is not part of the Android system image. The resulting
   binary failed to start: `CANNOT LINK EXECUTABLE "...": library
   "libomp.so" not found: needed by main executable`. Pre-seeding
   `AC_OPENMP`'s cache variable (`ac_cv_prog_c_openmp=unsupported`) makes
   it report "no OpenMP" without probing, so `OPENMP_CFLAGS` stays empty.

4. **`-Wno-int-conversion` (and `-std=gnu11`) in `CFLAGS`, to make
   pthread detection succeed.** NDK r30's clang (21) treats an implicit
   pointer↔int conversion as a hard *error* regardless of `-std=`. This
   broke libjulius's own pthread probe
   (`libjulius/configure.in`: `pthread_equal(NULL, NULL)`, which is
   technically invalid since `pthread_t` is `long` on bionic, not a
   pointer — a 2013-era portability wart, not an Android-specific bug).
   With the probe failing to compile, `use_pthread` silently ended up
   `no` (visible as `Extension: NoMic NoPThread` in `julius -version`)
   even though bionic has real, always-available pthread support.
   `-Wno-int-conversion` downgrades that one diagnostic back to
   non-fatal; `-std=gnu11` is added defensively for the same reason (this
   is old C that assumes much looser implicit-conversion rules than
   clang's modern defaults).

5. **Empty stub `libpthread.a`, added to `LDFLAGS` as `-L<dir>`.** Even
   after fixing (4), libjulius's `-lpthread` link probe still failed:
   `ld.lld: error: unable to find library -lpthread`. Bionic has had
   `pthread_*` fully in libc since forever, and the NDK (since ~r23) no
   longer ships even a stub `libpthread.a`/`.so` for old build scripts
   that still pass `-lpthread`. Older NDKs *did* ship such an empty stub
   for exactly this purpose; the build script recreates that trick with a
   throwaway empty archive (`llvm-ar crs stub-libs/libpthread.a` with no
   members) and adds its directory to `LDFLAGS -L`. The link then
   succeeds (`-lpthread` resolves to an archive with zero symbols to
   contribute — pthread symbols are still resolved from libc), and
   `julius -version` correctly reports pthread as available (no more
   `NoPThread`).

6. **Not actually a patch, but a trap avoided:
   `--enable-charconv=no` is a broken combination upstream.**
   `julius/main.c` unconditionally calls `charconv_add_option()` and
   `charconv_setup()`. With `--enable-charconv=no`,
   `julius/configure.in` sets `CCOBJ=""`, so `charconv.c` (which defines
   those two functions, guarded internally by `#ifdef
   CHARACTER_CONVERSION`, but defined unconditionally at the top level)
   never gets compiled at all, and the link fails with "undefined
   symbol: charconv_add_option / charconv_setup". This isn't
   Android-specific — it would break on any host. The fix is simply to
   not pass `--enable-charconv=no`; the default (`auto`) never lands on
   that broken path (it only chooses between `iconv` and the bundled
   `libjcode` fallback), and cross-compiles fine since `AM_ICONV`'s
   probe is a link-time check, not a run-time one.

## On-device validation

Device: `G091QV06204307FV` (32-bit ARM, Android 11 / LineageOS 18.1),
connected at `.tools/android-sdk/platform-tools/adb`.

### 2a. `-version` sanity check

```
adb push app/src/main/jniLibs/armeabi-v7a/libjulius-bin.so /data/local/tmp/julius
adb shell chmod 755 /data/local/tmp/julius
adb shell /data/local/tmp/julius -version
```

Runs correctly (dynamic linker resolves fine, no missing libraries):

```
JuliusLib rev.4.6 (fast)

Engine specification:
 -  Base setup   : fast
 -  Supported LM : DFA, N-gram, Word
 -  Extension    : NoMic
 -  Compiled by  : .../armv7a-linux-androideabi30-clang -O2 -std=gnu11 -Wno-int-conversion -fPIE -ffunction-sections -fdata-sections -fPIC
...
```

(`NoMic` only — pthread is enabled, confirming patches 4/5 worked.
`-version` exits with a non-zero status; that's normal Julius behavior,
also seen from the reference macOS Homebrew build, not a build defect.)

### 2b. Offline rawfile decode (`-input rawfile`)

Pushed the acoustic model (`jnas-tri-3k16-gid.binhmm`,
`logicalTri-3k16-gid.bin`), `wake.dfa`/`wake.dict`, and a 15s WAV
(`ffmpeg -f s16le -ar 16000 -ac 1 -i rec1.pcm -t 15 out.wav`) covering the
first ~2 wake-word repetitions of `.tools/hello-butler-ja-rec1.pcm`, then:

```
./julius -h jnas-tri-3k16-gid.binhmm -hlist logicalTri-3k16-gid.bin \
  -gram wake -input rawfile -filelist list.txt \
  -n 1 -output 1 -penalty1 -0.8 -penalty2 -0.8
```

Result (verbatim key lines):

```
STAT: 240000 samples (15.00 sec.)
pass1_best: <s> ハローバトラー <garbage> </s>
sentence1: <s> ハローバトラー </s>
wseq1: 0 2 1
phseq1: silB | h a r o: b a t o r a | silE
cmscore1: 0.091 0.063 1.000
score1: -30194.070312
```

`ハローバトラー` recognized with `cmscore=0.063`, above the 0.05 threshold
used by `scripts/julius-eval.py`.

**Timing / real-time factor**: `time` on-device reported
`0m11.20s real  0m10.35s user  0m00.21s system` for 15.00s of audio, i.e.
**RTF ≈ 0.747** (single-threaded, faster than real time; no beam narrowing
needed for this workload). This includes model load time inside the same
process invocation (load + 15s decode in 11.2s wall), so the *steady-state*
decode-only RTF for the 2nd (adinnet/module) test below, where model load
happens once at startup and is excluded from per-segment timing, should be
similar or a bit better. No `-b`/`-b2` narrowing was tried since RTF was
already sub-1.0; if beam narrowing is ever needed for headroom, `-b`
(1st-pass beam, default guessed ~200 here) is the cheaper lever than `-b2`
(2nd-pass beam, default 30) since pass 1 dominates wall time — but expect
accuracy loss on the already-fairly-low confidence scores (~0.05-0.1) this
grammar produces (see `scripts/julius-wake/README.md`), so narrowing
further wasn't attempted.

**Memory**: sampled `/proc/<pid>/status` on-device mid-decode:
`VmPeak: 37536 kB`, `VmHWM`/`VmRSS: 30576 kB` (~30.5 MB resident, driven
mostly by the ~11 MB `.binhmm` + ~1.2 MB `.bin` hmmlist being loaded and
expanded into in-memory HMM/lexicon structures).

### 2c. Streaming `-input adinnet -module` (the mode the app will actually use)

Exact command line that worked in streaming mode:

```
./julius -h jnas-tri-3k16-gid.binhmm -hlist logicalTri-3k16-gid.bin \
  -gram wake -input adinnet -adport 5530 -module 10500 -nocutsilence
```

Started on-device via `adb shell` with `nohup ... &` fully detached
(needs an explicit backgrounded subshell, e.g.
`(nohup ./julius ... > module.log 2>&1 &)`, and the `adb shell` command
must not itself block waiting on that job — see caveat below), then on the
Mac:

```
adb forward tcp:10500 tcp:10500
adb forward tcp:5530 tcp:5530
python3 scripts/julius-adinnet-client.py \
  --segment .tools/hello-butler-ja-rec1.pcm:10.5:12.2 \
  --segment .tools/japanese-speech-neg1.pcm:5:8
```

`scripts/julius-adinnet-client.py` (new, saved for future debugging)
connects to the **module port first**, waits briefly, then connects to
the **adinnet port** — confirming the task brief's expectation that
Julius does not open its adinnet listen socket until a module client has
connected. It streams each segment as native-endian 4-byte-length-prefixed
~1600-sample (3200-byte) chunks, followed by a 4-byte `0` to mark
end-of-segment, and prints whatever text arrives on the module socket.

**Module XML verbatim, for the positive segment**
(`.tools/hello-butler-ja-rec1.pcm`, 10.5s-12.2s):

```
<STARTPROC/>
.
<INPUT STATUS="LISTEN" TIME="1789488862"/>
.
<INPUT STATUS="STARTREC" TIME="1789488862"/>
.
<STARTRECOG/>
.
<INPUT STATUS="ENDREC" TIME="1789488863"/>
.
<ENDRECOG/>
.
<INPUTPARAM FRAMES="168" MSEC="1680"/>
.
<RECOGOUT>
  <SHYPO RANK="1" SCORE="-3980.250732" GRAM="0">
    <WHYPO WORD="&lt;s&gt;" CLASSID="0" PHONE="silB" CM="0.077"/>
    <WHYPO WORD="ハローバトラー" CLASSID="2" PHONE="h a r o: b a t o r a" CM="0.066"/>
    <WHYPO WORD="&lt;garbage&gt;" CLASSID="3" PHONE="q" CM="0.046"/>
    <WHYPO WORD="&lt;/s&gt;" CLASSID="1" PHONE="silE" CM="1.000"/>
  </SHYPO>
</RECOGOUT>
.
<INPUT STATUS="LISTEN" TIME="1789488863"/>
.
```

(Note: the module's default text-output XML uses `<SHYPO RANK="1" ...>`,
not the `<SHYPO PASS="1" ...>` form speculated in the build brief —
`RANK=` is what `julius/output_module.c` actually emits for the N-best
module format we get by default with `-module`; word/CM shape otherwise
matches: `WHYPO WORD=... CLASSID=... PHONE=... CM=...`. XML special
characters in words like `<s>`/`<garbage>` are entity-escaped, e.g.
`&lt;s&gt;`.)

For the negative segment (`.tools/japanese-speech-neg1.pcm`, 5.0s-8.0s),
the `<RECOGOUT>` contains only `<garbage>` `WHYPO`s between `<s>`/`</s>` —
no `ハローバトラー` `WHYPO` anywhere, i.e. no wake-word false positive.

**Adinnet-socket pause/resume bytes**: yes, Julius does send them, and we
captured them directly. During/immediately after sending the positive
segment, two 5-byte messages arrived unsolicited on the **adinnet** socket
(not the module socket): `\x01\x00\x00\x00` + `'0'` (0x30) immediately
followed by `\x01\x00\x00\x00` + `'1'` (0x31) — i.e. a 4-byte
little-endian length prefix of `1`, then the single payload byte, exactly
matching `adin_tcpip_send_pause()`/`adin_tcpip_send_resume()` in
`libsent/src/adin/adin_tcpip.c` (`wt(fd, "0", 1)` / `wt(fd, "1", 1)`).
This is Julius telling the client to briefly pause (`'0'`) while it
finishes 2nd-pass processing of the just-ended segment, then resume
(`'1'`) once ready for more audio — a real client (unlike our test script,
which ignores this) should stop sending audio chunks between pause and
resume to avoid the server-side buffer filling up during longer segments.

**Caveats hit while starting the module-mode process over `adb shell`**:
a plain `adb shell "... &"` does not actually return control — the adb
shell session still blocks until the backgrounded job's stdout/stdin are
fully detached. Two symptoms seen while iterating: (1) a hung `adb shell`
call that had to be treated as backgrounded/killed; (2) as a result, two
Julius processes briefly raced for the same `-adport`/`-module` ports
(visible as `Error: server-client: bind() error` /
`Error: gzfile: failed to open "wake.dfa.forward"` in the log of the
process that lost the race — that second error is unrelated/benign, it's
Julius's normal DFA-cache-file probe, present on any run, not a build
defect). The reliable pattern is
`(nohup ./julius ... > module.log 2>&1 &) ; sleep 1; ps -A | grep julius`
as one `adb shell` invocation — the subshell parens let `adb shell` see
EOF and return immediately while the child (reparented to `init`, PPID 1)
keeps running.

### 2d. Cleanup

Device `julius` process killed and everything pushed under
`/data/local/tmp` (`julius`, the two model files, `wake.dfa`/`wake.dict`,
`rec1-15s.wav`, `list.txt`, `module.log`, `out.log`) removed; `adb forward`
entries for 10500/5530 removed.

## Other caveats / things to watch

- The `julius` binary is dynamically linked against the NDK's bionic
  libc/libm/libdl (`ELF 32-bit LSB pie executable, ARM, EABI5 ...,
  dynamically linked, interpreter /system/bin/linker`) — "standalone" here
  means no extra `.so` dependencies beyond what's always on the Android
  system image (confirmed by `-version` and the on-device runs above
  working with nothing else pushed alongside it). It is *not* a fully
  static binary.
- `-input mic` is unavailable in this build (by design — see patch 1).
  Only `-input adinnet`/`-input rawfile`/`-input file`-style inputs work.
- `fork on adinnet input` is `no` (the default; `--enable-fork` was not
  passed). Each `julius` process handles one adinnet client connection at
  a time, looping back to "waiting connection..." after a client
  disconnects — fine for this app's single always-on decoder process
  model.
- Rebuilding is not fast (~1-2 minutes: full libsent + libjulius + julius
  compile every run, no incremental build) since the script always starts
  from a fresh copy for reproducibility. If iterating on the patches
  themselves, it can be faster to temporarily work directly in
  `.tools/julius/build-android` and only `make` the changed subdir, then
  re-run the full script once to confirm the reproducible path still
  works.
