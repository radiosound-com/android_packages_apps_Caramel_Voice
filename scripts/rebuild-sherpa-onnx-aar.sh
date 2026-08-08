#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -lt 1 ] || [ "$#" -gt 2 ]; then
    echo "usage: $0 /path/to/sherpa-onnx-source-or-archive [output.aar]" >&2
    exit 2
fi

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
repo_root=$(CDPATH= cd -- "$script_dir/.." && pwd -P)
output=${2:-$repo_root/app/libs/sherpa-onnx-1.13.4-arm64-v8a.aar}
expected_commit=142807252687d81b40d6315f23470a1512a00de3

if [ -f "$1" ]; then
    source_archive=$(CDPATH= cd -- "$(dirname -- "$1")" && pwd -P)/$(basename -- "$1")
    echo '11d1be9ab4a0c67d5cb238ca554522752c5e981173f91d42ddcda1b79b260c38  '"$source_archive" \
        | shasum -a 256 -c -
    work_dir=$(mktemp -d "${TMPDIR:-/tmp}/caramel-sherpa-source.XXXXXX")
    trap 'rm -rf -- "$work_dir"' EXIT
    tar -xzf "$source_archive" -C "$work_dir"
    source_dir=$(CDPATH= cd -- "$work_dir/sherpa-onnx-v1.13.4" && pwd -P)
else
    source_dir=$(CDPATH= cd -- "$1" && pwd -P)
    if [ "$(git -C "$source_dir" rev-parse HEAD)" != "$expected_commit" ]; then
        echo "sherpa-onnx source must be exactly v1.13.4 ($expected_commit)" >&2
        exit 1
    fi
fi

# The upstream native JNI already exports the multi-stream decode entry point,
# but v1.13.4's Kotlin wrapper omits it.  Caramel's streaming Zipformer profile
# uses the API to keep a live stream stable with a silent companion stream.
batch_patch="$repo_root/provenance/patches/sherpa-onnx-online-batch-decode.patch"
batch_api="$source_dir/sherpa-onnx/kotlin-api/OnlineRecognizer.kt"
if ! grep -q 'fun decodeStreams(streams: Array<OnlineStream>)' "$batch_api"; then
    patch -p1 -d "$source_dir" < "$batch_patch"
fi

repro_patch="$repo_root/provenance/patches/sherpa-onnx-reproducible-build-paths.patch"
repro_script="$source_dir/build-android-arm64-v8a.sh"
if ! grep -q 'CMAKE_CXX_FLAGS="\$CXXFLAGS"' "$repro_script" \
        || ! grep -q 'make -j\${SHERPA_ONNX_BUILD_JOBS:-1}' "$repro_script"; then
    # A caller may hand us a checkout that already has one hunk applied.
    # --forward skips that hunk; the postcondition check keeps a partial patch
    # from being mistaken for a valid build recipe.
    patch --forward -p1 -d "$source_dir" < "$repro_patch" || true
fi
if ! grep -q 'CMAKE_CXX_FLAGS="\$CXXFLAGS"' "$repro_script" \
        || ! grep -q 'make -j\${SHERPA_ONNX_BUILD_JOBS:-1}' "$repro_script"; then
    echo "failed to apply reproducible-build patch to $repro_script" >&2
    exit 1
fi

# The native JNI library retains source paths in debug metadata and in some
# compiler-generated diagnostic strings even after Gradle packages the AAR.
# Map the temporary checkout (or an exact checkout's path) to a stable prefix
# so clean rebuilds have the same byte hash.
debug_prefix=/caramel-sherpa-source
export CFLAGS="${CFLAGS:-} -ffile-prefix-map=${source_dir}=${debug_prefix} -fmacro-prefix-map=${source_dir}=${debug_prefix}"
export CXXFLAGS="${CXXFLAGS:-} -ffile-prefix-map=${source_dir}=${debug_prefix} -fmacro-prefix-map=${source_dir}=${debug_prefix}"
: "${ANDROID_HOME:?Set ANDROID_HOME to an Android SDK containing NDK 27.2.12479018}"
ANDROID_NDK=${ANDROID_NDK:-$ANDROID_HOME/ndk/27.2.12479018}
export ANDROID_NDK
export BUILD_SHARED_LIBS=ON
export SHERPA_ONNX_ANDROID_PLATFORM=android-28
export SHERPA_ONNX_BUILD_JOBS=${SHERPA_ONNX_BUILD_JOBS:-1}
# The AAR needs the shared JNI/C APIs, not sherpa's command-line binaries.
# Keeping the binary targets out of the native link also removes unrelated
# target-order variability from clean rebuilds.  Allow callers to opt back in
# while retaining the deterministic JNI-only default.
export SHERPA_ONNX_ENABLE_BINARY=${SHERPA_ONNX_ENABLE_BINARY:-OFF}
export SHERPA_ONNX_ENABLE_C_API=ON
export SHERPA_ONNX_ENABLE_JNI=ON
export SHERPA_ONNX_ENABLE_SPEAKER_DIARIZATION=OFF
export SHERPA_ONNX_ENABLE_TTS=OFF

(cd "$source_dir" && ./build-android-arm64-v8a.sh)

normalize_native_library() {
    local jni_binary=$1
    [ -f "$jni_binary" ] || return 0

    # Some sherpa dependency projects still embed the absolute checkout path
    # in diagnostic strings. Normalize those fixed-size strings before
    # packaging; remove the linker build-id as it was calculated from the
    # pre-normalized bytes. This keeps the native library's runtime code
    # unchanged while making archive rebuilding independent of mktemp's
    # random directory name.
    CARAMEL_SOURCE_DIR="$source_dir" CARAMEL_DEBUG_PREFIX="$debug_prefix" \
        perl -0pi -e '
            my $from = $ENV{"CARAMEL_SOURCE_DIR"};
            my $to = $ENV{"CARAMEL_DEBUG_PREFIX"};
            die "debug prefix is longer than source path\n" if length($to) > length($from);
            my $padding = "\0" x (length($from) - length($to));
            s/\Q$from\E/$to . $padding/ge;
        ' "$jni_binary"
    if CARAMEL_SOURCE_DIR="$source_dir" perl -0ne \
        'exit(index($_, $ENV{"CARAMEL_SOURCE_DIR"}) >= 0 ? 0 : 1)' "$jni_binary"; then
        echo "failed to normalize source path in $jni_binary" >&2
        return 1
    fi
    case "$(uname -s)" in
        Darwin) ndk_host_tag=darwin-x86_64 ;;
        Linux) ndk_host_tag=linux-x86_64 ;;
        *) echo "unsupported host for llvm-objcopy: $(uname -s)" >&2; exit 1 ;;
    esac
    llvm_objcopy="$ANDROID_NDK/toolchains/llvm/prebuilt/$ndk_host_tag/bin/llvm-objcopy"
    "$llvm_objcopy" --remove-section .note.gnu.build-id "$jni_binary"
}

normalize_native_library \
    "$source_dir/build-android-arm64-v8a/install/lib/libsherpa-onnx-jni.so"

aar_project="$source_dir/android/SherpaOnnxAar"
jni_dir="$aar_project/sherpa_onnx/src/main/jniLibs/arm64-v8a"
install -m 0644 \
    "$source_dir/build-android-arm64-v8a/install/lib/libonnxruntime.so" \
    "$jni_dir/libonnxruntime.so"
install -m 0644 \
    "$source_dir/build-android-arm64-v8a/install/lib/libsherpa-onnx-jni.so" \
    "$jni_dir/libsherpa-onnx-jni.so"
normalize_native_library "$jni_dir/libsherpa-onnx-jni.so"
(cd "$aar_project" && ./gradlew :sherpa_onnx:assembleRelease)
install -m 0644 \
    "$aar_project/sherpa_onnx/build/outputs/aar/sherpa_onnx-release.aar" \
    "$output"

actual=$(shasum -a 256 "$output" | awk '{print $1}')
expected=cb548bf2e4c297916d92d78a2f68255ff62e9cbb80de7adff8deedbf6ce95a93
if [ "$actual" != "$expected" ]; then
    echo "AAR built successfully, but hash differs: $actual (expected $expected)" >&2
    exit 1
fi
echo "$actual  $output"
