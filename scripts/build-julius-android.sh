#!/usr/bin/env bash
#
# Cross-compile Julius 4.6 (libsent + libjulius + the `julius` executable) for
# armeabi-v7a Android using the NDK's standalone clang wrappers, and install
# the result as app/src/main/jniLibs/armeabi-v7a/libjulius-bin.so so the
# Android package manager extracts it into the app's nativeLibraryDir where
# it can be run with ProcessBuilder.
#
# Usage: scripts/build-julius-android.sh
#
# This script is idempotent: it always rebuilds from a fresh copy of the
# Julius source tree (.tools/julius/julius-src, untouched) into a scratch
# build directory (.tools/julius/build-android), so it can be re-run safely
# and never leaves permanent patches in the source tree.
#
# See scripts/build-julius-android.md for background, the configure flags
# chosen and why, and on-device validation results.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JULIUS_SRC="$REPO_ROOT/.tools/julius/julius-src"
BUILD_DIR="$REPO_ROOT/.tools/julius/build-android"
OUT_DIR="$REPO_ROOT/app/src/main/jniLibs/armeabi-v7a"
OUT_BIN="$OUT_DIR/libjulius-bin.so"
NOTES_FILE="$REPO_ROOT/scripts/build-julius-android.md"

: "${ANDROID_NDK:=/opt/homebrew/share/android-ndk}"
NDK="$ANDROID_NDK"
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/darwin-x86_64"
API=30
TARGET_TRIPLE=armv7a-linux-androideabi
HOST_TRIPLE=arm-linux-androideabi   # autoconf --host= triple for the cross build

CC="$TOOLCHAIN/bin/${TARGET_TRIPLE}${API}-clang"
AR="$TOOLCHAIN/bin/llvm-ar"
RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
STRIP="$TOOLCHAIN/bin/llvm-strip"

if [ ! -x "$CC" ]; then
  echo "error: NDK compiler not found at $CC (set ANDROID_NDK to override the NDK root)" >&2
  exit 1
fi
if [ ! -d "$JULIUS_SRC" ]; then
  echo "error: Julius source tree not found at $JULIUS_SRC" >&2
  exit 1
fi
for tool in autoconf python3; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    echo "error: required host tool '$tool' not found on PATH" >&2
    exit 1
  fi
done

export CC AR RANLIB STRIP
# -O2, section GC to shrink the static-ish executable, explicit -fPIE/-pie
# even though the NDK clang wrapper already defaults to PIE for API>=21
# (belt and suspenders, per the build brief). mfpu/mfloat-abi are left at
# the NDK's own defaults for armv7a (softfp calling convention, VFP/NEON
# instructions), i.e. we do not override them.
# -std=gnu11 + -Wno-int-conversion: NDK r30's clang (21) treats an implicit
# pointer<->int conversion as a hard error in every -std mode (clang made
# -Wint-conversion error-by-default a while back, it's not just a C23
# thing). Julius's own source doesn't hit this, but libjulius's ./configure
# pthread probe does: it calls `pthread_equal(NULL, NULL)`, which is
# technically invalid since pthread_t is `long` on bionic, not a pointer.
# That makes the probe fail to link, and pthread gets silently disabled
# (shows up as "NoPThread" in `julius -version`) even though bionic has
# real pthread support. -std=gnu11 restores C11-ish laxer implicit-int
# rules generally (good defensive hygiene for this 2013-era codebase) and
# -Wno-int-conversion specifically downgrades that one diagnostic back to
# non-fatal so the pthread probe (and anything else relying on old-style
# implicit conversions) can link and report correctly.
export CFLAGS="-O2 -std=gnu11 -Wno-int-conversion -fPIE -ffunction-sections -fdata-sections"
export LDFLAGS="-fPIE -pie -Wl,--gc-sections"

echo "== Julius source commit: $(git -C "$JULIUS_SRC" rev-parse HEAD 2>/dev/null || echo unknown) =="

echo "== Preparing reproducible build copy at $BUILD_DIR =="
rm -rf "$BUILD_DIR"
mkdir -p "$(dirname "$BUILD_DIR")"
cp -R "$JULIUS_SRC" "$BUILD_DIR"

# libjulius's ./configure --with-pthread (default: on) probes `-lpthread`
# for non-FreeBSD hosts (see libjulius/configure.in's pthread case
# statement). Bionic has had pthread_* fully in libc since forever and the
# NDK (since ~r23) no longer ships a libpthread.a/.so stub at all, so that
# probe fails with "unable to find library -lpthread" and pthread support
# silently ends up disabled. Older NDKs shipped an empty stub archive for
# exactly this kind of legacy -lpthread/-lrt/-ldl probe; recreate that
# trick here with a throwaway empty libpthread.a so the link succeeds
# (pthread symbols still resolve from libc, nothing is actually pulled
# from this archive).
STUB_LIB_DIR="$BUILD_DIR/stub-libs"
mkdir -p "$STUB_LIB_DIR"
"$AR" crs "$STUB_LIB_DIR/libpthread.a"
export LDFLAGS="$LDFLAGS -L$STUB_LIB_DIR"

# ---------------------------------------------------------------------------
# Patch libsent/configure.in: there is no --with-mictype value that compiles
# without a sound library (oss/alsa/esd/pulseaudio/portaudio/... all require
# headers/libs that don't exist for Android; the catch-all case aborts
# configure with "mictype not supported"). We only need -input adinnet
# (libsent/src/adin/adin_tcpip.c, always built) and -input rawfile
# (adin_file.c, always built) for -module streaming and the offline
# rawfile validation, so add a "none" mic type that builds no mic backend
# object and leaves USE_MIC undefined (mic input, i.e. -input mic, is then
# simply unavailable at runtime, same as any other libsent build lacking a
# platform driver).
# ---------------------------------------------------------------------------
# Patch the pre-generated libsent/configure script directly (not
# configure.in + autoconf): the vendored configure was generated with an
# older autoconf/automake than what Homebrew ships today, and a fresh
# `autoconf` run here pulls in AM_PROG_AR, which expects a support/ar-lib
# auxiliary script that this source tree doesn't have. Patching the
# generated shell script's case statement directly (same marker text
# that's in configure.in) avoids that version skew entirely.
echo "== Patching libsent/configure: add --with-mictype=none =="
python3 - "$BUILD_DIR/libsent/configure" "$BUILD_DIR/libsent/configure.in" <<'PYEOF'
import sys

configure_path, configure_in_path = sys.argv[1], sys.argv[2]

# generated configure: uses as_fn_error instead of AC_MSG_ERROR's expansion
gen_text = open(configure_path).read()
gen_marker = '    *)\n\taldesc="no support"'
if gen_marker not in gen_text:
    raise SystemExit(f"error: expected mictype catch-all case not found in {configure_path}")
gen_insertion = (
    '    none)\n'
    '\taldesc="no mic input (Android cross build; adinnet/rawfile/module input only)"\n'
    '\t;;\n'
)
gen_text = gen_text.replace(gen_marker, gen_insertion + gen_marker, 1)
open(configure_path, 'w').write(gen_text)
print("patched:", configure_path)

# also patch configure.in itself, purely so it stays in sync as documentation
# (it is not re-run through autoconf by this script).
in_text = open(configure_in_path).read()
if gen_marker in in_text:
    in_text = in_text.replace(gen_marker, gen_insertion + gen_marker, 1)
    open(configure_in_path, 'w').write(in_text)
    print("patched (doc only, not regenerated):", configure_in_path)
PYEOF

# ---------------------------------------------------------------------------
# Route around Julius's own hand-written "config-android-*.h" headers.
#
# libjulius/include/julius/julius.h and libsent/include/sent/stddefs.h both
# do `#elif defined(__ANDROID__) -> #include <.../config-android-*.h>`
# *instead of* the real autoconf-generated config.h/sent/config.h, whenever
# __ANDROID__ is predefined (which the NDK clang always does). Those
# android config headers are legacy, hand-maintained snapshots for an old
# Android.mk/ndk-build setup (see msvc/Library_PortAudio/Android.mk) that
# assumes OpenSLES mic input, zlib, and libfvad are all present -- exactly
# the things we disabled through configure. Left alone, they silently
# override every --disable-*/--with-mictype=none choice above and the
# final link fails with undefined adin_mic_*/fvad_* symbols. So make the
# __ANDROID__ branch permanently dead in our build copy, and fall through
# to the normal `#else -> #include <julius/config.h>` path that reflects
# what we actually configured. (Two other __ANDROID__ uses -- a
# pthread_cancel shim in stddefs.h and an aligned_alloc avoidance in
# mymalloc.c -- are genuine bionic-compat workarounds and are left as-is.)
# ---------------------------------------------------------------------------
echo "== Patching config-header selection: force autoconf config.h on Android =="
python3 - "$BUILD_DIR/libjulius/include/julius/julius.h" "$BUILD_DIR/libsent/include/sent/stddefs.h" <<'PYEOF'
import sys

replacements = 0
for path in sys.argv[1:]:
    text = open(path).read()
    marker = '#elif defined(__ANDROID__)\n'
    count = text.count(marker)
    if count != 1:
        raise SystemExit(f"error: expected exactly one config-header __ANDROID__ branch in {path}, found {count}")
    text = text.replace(
        marker,
        '#elif 0 && defined(__ANDROID__) /* patched by build-julius-android.sh: use the autoconf config.h below instead */\n',
        1,
    )
    open(path, 'w').write(text)
    replacements += 1
    print("patched:", path)
print(f"{replacements} file(s) patched")
PYEOF

# ---------------------------------------------------------------------------
# Configure + build libsent
# ---------------------------------------------------------------------------
echo "== Configuring libsent =="
(
  cd "$BUILD_DIR/libsent"
  # libsent's configure.in unconditionally probes AC_OPENMP and, if found,
  # bakes "-fopenmp" into libsent-config --cflags -- which is used not only
  # to compile but also on the *final link line* for julius itself. Nothing
  # in libsent/libjulius actually uses OpenMP pragmas, but clang's driver
  # still adds a dynamic dependency on libomp.so to the link whenever
  # -fopenmp is present, and libomp.so isn't part of the Android system
  # image, so the resulting binary fails to start with "library libomp.so
  # not found". ac_cv_prog_c_openmp=unsupported preempts AC_OPENMP's cache
  # variable so it reports no OpenMP support without probing, keeping
  # OPENMP_CFLAGS empty.
  ac_cv_prog_c_openmp=unsupported \
  ./configure \
    --host="$HOST_TRIPLE" \
    --with-mictype=none \
    --disable-zlib \
    --without-sndfile
)

echo "== Building libsent =="
make -C "$BUILD_DIR/libsent" -j"$(sysctl -n hw.ncpu 2>/dev/null || echo 4)"

# ---------------------------------------------------------------------------
# Configure + build libjulius
# ---------------------------------------------------------------------------
echo "== Configuring libjulius =="
(
  cd "$BUILD_DIR/libjulius"
  ./configure \
    --host="$HOST_TRIPLE" \
    --disable-plugin \
    --disable-libfvad
)

echo "== Building libjulius =="
make -C "$BUILD_DIR/libjulius" -j"$(sysctl -n hw.ncpu 2>/dev/null || echo 4)"

# ---------------------------------------------------------------------------
# Configure + build the julius executable itself
# ---------------------------------------------------------------------------
echo "== Configuring julius =="
(
  cd "$BUILD_DIR/julius"
  # --enable-charconv=no is actually a build-breaking combination upstream:
  # main.c unconditionally calls charconv_add_option()/charconv_setup(), but
  # with use_charconv=no the Makefile's CCOBJ (which normally builds
  # charconv.o) is left empty, so those symbols go undefined at link time.
  # The default ("auto") is safe to cross-compile: it never lands on that
  # broken "no" case, it only chooses between iconv (AM_ICONV, a link-time
  # check, safe when cross-compiling) and the bundled libjcode as a
  # fallback. So just take the default here.
  ./configure \
    --host="$HOST_TRIPLE"
)

echo "== Building julius =="
make -C "$BUILD_DIR/julius" -j"$(sysctl -n hw.ncpu 2>/dev/null || echo 4)"

JULIUS_BIN="$BUILD_DIR/julius/julius"
if [ ! -f "$JULIUS_BIN" ]; then
  echo "error: build did not produce $JULIUS_BIN" >&2
  exit 1
fi

file_info="$(file "$JULIUS_BIN" 2>/dev/null || true)"
echo "== Built: $JULIUS_BIN =="
echo "$file_info"
case "$file_info" in
  *ARM*|*arm*) ;;
  *) echo "warning: unexpected file(1) output, expected an ARM ELF binary" >&2 ;;
esac

# ---------------------------------------------------------------------------
# Strip and install as libjulius-bin.so
# ---------------------------------------------------------------------------
mkdir -p "$OUT_DIR"
cp "$JULIUS_BIN" "$OUT_BIN"
"$STRIP" --strip-all "$OUT_BIN"

SIZE_BYTES=$(stat -f%z "$OUT_BIN" 2>/dev/null || stat -c%s "$OUT_BIN")
SHA256=$(shasum -a 256 "$OUT_BIN" | awk '{print $1}')

echo "== Installed: $OUT_BIN =="
echo "size:   ${SIZE_BYTES} bytes"
echo "sha256: ${SHA256}"
echo "== Done. See $NOTES_FILE for build notes and on-device validation results. =="
