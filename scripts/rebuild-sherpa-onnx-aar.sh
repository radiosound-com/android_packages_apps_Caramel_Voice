#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -lt 1 ] || [ "$#" -gt 2 ]; then
    echo "usage: $0 /path/to/sherpa-onnx-source-or-archive [output.aar]" >&2
    exit 2
fi

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
repo_root=$(CDPATH= cd -- "$script_dir/.." && pwd)
output=${2:-$repo_root/app/libs/sherpa-onnx-1.13.4-arm64-v8a.aar}
expected_commit=142807252687d81b40d6315f23470a1512a00de3

if [ -f "$1" ]; then
    source_archive=$(CDPATH= cd -- "$(dirname -- "$1")" && pwd)/$(basename -- "$1")
    echo '11d1be9ab4a0c67d5cb238ca554522752c5e981173f91d42ddcda1b79b260c38  '"$source_archive" \
        | shasum -a 256 -c -
    work_dir=$(mktemp -d "${TMPDIR:-/tmp}/caramel-sherpa-source.XXXXXX")
    trap 'rm -rf -- "$work_dir"' EXIT
    tar -xzf "$source_archive" -C "$work_dir"
    source_dir="$work_dir/sherpa-onnx-v1.13.4"
else
    source_dir=$(CDPATH= cd -- "$1" && pwd)
    if [ "$(git -C "$source_dir" rev-parse HEAD)" != "$expected_commit" ]; then
        echo "sherpa-onnx source must be exactly v1.13.4 ($expected_commit)" >&2
        exit 1
    fi
fi
: "${ANDROID_HOME:?Set ANDROID_HOME to an Android SDK containing NDK 27.2.12479018}"
ANDROID_NDK=${ANDROID_NDK:-$ANDROID_HOME/ndk/27.2.12479018}
export ANDROID_NDK
export BUILD_SHARED_LIBS=ON
export SHERPA_ONNX_ANDROID_PLATFORM=android-28
export SHERPA_ONNX_ENABLE_BINARY=ON
export SHERPA_ONNX_ENABLE_C_API=ON
export SHERPA_ONNX_ENABLE_JNI=ON
export SHERPA_ONNX_ENABLE_SPEAKER_DIARIZATION=OFF
export SHERPA_ONNX_ENABLE_TTS=OFF

(cd "$source_dir" && ./build-android-arm64-v8a.sh)

aar_project="$source_dir/android/SherpaOnnxAar"
jni_dir="$aar_project/sherpa_onnx/src/main/jniLibs/arm64-v8a"
install -m 0644 \
    "$source_dir/build-android-arm64-v8a/install/lib/libonnxruntime.so" \
    "$jni_dir/libonnxruntime.so"
install -m 0644 \
    "$source_dir/build-android-arm64-v8a/install/lib/libsherpa-onnx-jni.so" \
    "$jni_dir/libsherpa-onnx-jni.so"
(cd "$aar_project" && ./gradlew :sherpa_onnx:assembleRelease)
install -m 0644 \
    "$aar_project/sherpa_onnx/build/outputs/aar/sherpa_onnx-release.aar" \
    "$output"

actual=$(shasum -a 256 "$output" | awk '{print $1}')
expected=395d364c83eec7572834c204812570d664987dae712c211c4ef1a8738e96463f
if [ "$actual" != "$expected" ]; then
    echo "AAR built successfully, but hash differs: $actual (expected $expected)" >&2
    exit 1
fi
echo "$actual  $output"
