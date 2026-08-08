#!/usr/bin/env bash
# Copyright 2026 Radio Sound, Inc.
# Licensed under the Apache License, Version 2.0.

set -euo pipefail

# This exercises the standalone APK on an API 36 arm64 AVD. A production
# image still reads its model/configuration from /product; the debug APK's
# external override is deliberately used here because Google Play AVDs do
# not permit adb root/remount.

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
sdk_root=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}
adb=${ADB:-$sdk_root/platform-tools/adb}
emulator=${EMULATOR:-$sdk_root/emulator/emulator}
avdmanager=${AVDMANAGER:-$sdk_root/cmdline-tools/latest/bin/avdmanager}
apksigner=${APKSIGNER:-$sdk_root/build-tools/36.0.0/apksigner}
avd_name=${CARAMEL_AVD_NAME:-caramel_api36}
avd_port=${CARAMEL_AVD_PORT:-5554}
serial=${CARAMEL_AVD_SERIAL:-emulator-$avd_port}
user_id=${CARAMEL_AVD_USER:-0}
skip_zipformer=${CARAMEL_AVD_SKIP_ZIPFORMER:-0}
tmp_dir=$(mktemp -d "${TMPDIR:-/tmp}/caramel-voice-avd.XXXXXX")

cleanup() {
    rm -rf "$tmp_dir"
}
trap cleanup EXIT

die() {
    echo "avd-smoke-test: $*" >&2
    exit 1
}

for required in "$adb" "$emulator" "$avdmanager" "$apksigner"; do
    [ -x "$required" ] || die "missing executable: $required"
done

if ! "$emulator" -list-avds | grep -Fxq "$avd_name"; then
    printf 'no\n' | "$avdmanager" create avd \
        -n "$avd_name" \
        -k 'system-images;android-36;google_apis_playstore;arm64-v8a' \
        -d pixel_6 \
        --force
fi

device_state=$($adb devices | awk -v wanted="$serial" '$1 == wanted { print $2; exit }')
if [ "$device_state" != "device" ]; then
    "$emulator" -avd "$avd_name" -port "$avd_port" \
        -no-window -no-boot-anim -gpu swiftshader_indirect \
        -no-snapshot -allow-host-audio \
        >"$tmp_dir/emulator.log" 2>&1 &
    emulator_pid=$!
fi

for attempt in $(seq 1 90); do
    boot=$($adb -s "$serial" shell getprop sys.boot_completed 2>/dev/null \
        | tr -d '\r' || true)
    if [ "$boot" = "1" ]; then break; fi
    if [ "$attempt" = 90 ]; then
        tail -120 "$tmp_dir/emulator.log" 2>/dev/null || true
        die "AVD did not boot within 90 seconds"
    fi
    sleep 1
done

sdk=$($adb -s "$serial" shell getprop ro.build.version.sdk | tr -d '\r')
[ "$sdk" = "36" ] || die "expected API 36 AVD, got SDK $sdk"

voice_apk="$repo_root/app/build/outputs/apk/debug/app-debug.apk"
if [ ! -f "$voice_apk" ]; then
    (
        cd "$repo_root"
        ANDROID_HOME="$sdk_root" ANDROID_SDK_ROOT="$sdk_root" ./gradlew assembleDebug
    )
fi

tts_apk="$repo_root/app/prebuilts/CaramelEspeakTts-1.52.0.apk"
signed_tts="$tmp_dir/CaramelEspeakTts.apk"
debug_keystore=${ANDROID_DEBUG_KEYSTORE:-$HOME/.android/debug.keystore}
[ -f "$debug_keystore" ] || die "missing debug keystore: $debug_keystore"

"$apksigner" sign --ks "$debug_keystore" --ks-pass pass:android \
    --out "$signed_tts" "$tts_apk"

if ! "$adb" -s "$serial" install -r "$voice_apk" >/dev/null; then
    "$adb" -s "$serial" uninstall com.radiosound.caramelvoice >/dev/null || true
    "$adb" -s "$serial" install "$voice_apk" >/dev/null
fi
"$adb" -s "$serial" install -r "$signed_tts" >/dev/null

"$adb" -s "$serial" shell pm grant --user "$user_id" com.radiosound.caramelvoice \
    android.permission.RECORD_AUDIO
"$adb" -s "$serial" shell pm grant --user "$user_id" com.radiosound.caramelvoice \
    android.permission.READ_MEDIA_AUDIO
"$adb" -s "$serial" shell settings --user "$user_id" put secure \
    tts_default_synth com.reecedunn.espeak
"$adb" -s "$serial" shell settings --user "$user_id" put secure \
    voice_interaction_service com.radiosound.caramelvoice/.CaramelVoiceInteractionService
"$adb" -s "$serial" shell settings --user "$user_id" put secure \
    assistant com.radiosound.caramelvoice/.CaramelVoiceInteractionService
"$adb" -s "$serial" shell cmd role add-role-holder --user "$user_id" \
    android.app.role.ASSISTANT com.radiosound.caramelvoice >/dev/null

wait_for_log() {
    needle=$1
    for attempt in $(seq 1 60); do
        if "$adb" -s "$serial" logcat -d -s 'CaramelVoice:D' '*:S' 2>/dev/null \
                | grep -Fq "$needle"; then
            echo "PASS: $needle"
            return 0
        fi
        sleep 1
    done
    echo "FAIL: $needle" >&2
    "$adb" -s "$serial" logcat -d -s 'CaramelVoice:D' '*:S' | tail -160 >&2
    return 1
}

remote_root=/sdcard/Android/data/com.radiosound.caramelvoice/files
if [ "$user_id" != "0" ]; then
    # /sdcard is user 0 on AAOS. The debug-only model override must be placed
    # in the active user's media tree so Context#getExternalFilesDir() resolves
    # to the same files for user 10 (or another driver user).
    "$adb" -s "$serial" root >/dev/null 2>&1 || die \
        "CARAMEL_AVD_USER=$user_id requires a debuggable AVD with adb root"
    sleep 1
    "$adb" -s "$serial" shell id | grep -q 'uid=0' || die \
        "CARAMEL_AVD_USER=$user_id requires adb root for external model staging"
    remote_root="/data/media/$user_id/Android/data/com.radiosound.caramelvoice/files"
fi

"$adb" -s "$serial" shell mkdir -p "$remote_root"
# Start from the immutable compact default even when the AVD was used by a
# previous run that left the debug-only Zipformer override in place.
"$adb" -s "$serial" shell rm -f "$remote_root/recognition.properties"
"$adb" -s "$serial" logcat -c
"$adb" -s "$serial" shell am force-stop com.radiosound.caramelvoice
"$adb" -s "$serial" shell cmd role add-role-holder --user "$user_id" \
    android.app.role.ASSISTANT com.radiosound.caramelvoice >/dev/null
wait_for_log 'Vosk model ready and prewarmed'

if [ "$skip_zipformer" != "1" ]; then
    model_name=sherpa-onnx-streaming-zipformer-en-2023-06-21
    model_dir="$repo_root/app/model/$model_name"
    remote_model="$remote_root/models/$model_name"
    "$adb" -s "$serial" shell mkdir -p "$remote_model"
    for model_file in \
        encoder-epoch-99-avg-1.int8.onnx \
        decoder-epoch-99-avg-1.onnx \
        joiner-epoch-99-avg-1.int8.onnx \
        tokens.txt \
        bpe.vocab; do
        "$adb" -s "$serial" push "$model_dir/$model_file" \
            "$remote_model/$model_file" >/dev/null
    done

    printf '%s\n' \
        'engine=zipformer' \
        'model=zipformer-int8' \
        'threads=4' \
        'decoding_method=modified_beam_search' \
        'max_active_paths=4' \
        'hotwords_score=4.0' >"$tmp_dir/recognition.properties"
    "$adb" -s "$serial" push "$tmp_dir/recognition.properties" \
        "$remote_root/recognition.properties" >/dev/null

    "$adb" -s "$serial" logcat -c
    "$adb" -s "$serial" reboot >/dev/null
    for attempt in $(seq 1 90); do
        boot=$($adb -s "$serial" shell getprop sys.boot_completed 2>/dev/null \
            | tr -d '\r' || true)
        if [ "$boot" = "1" ]; then break; fi
        if [ "$attempt" = 90 ]; then die "AVD reboot did not complete"; fi
        sleep 1
    done
    # AAOS user services may not rebind an already-held role after boot. Cycle
    # the debug role so the VoiceInteractionService is started deterministically
    # for the active user before waiting for model initialization.
    "$adb" -s "$serial" shell cmd role remove-role-holder --user "$user_id" \
        android.app.role.ASSISTANT com.radiosound.caramelvoice >/dev/null 2>&1 || true
    "$adb" -s "$serial" shell cmd role add-role-holder --user "$user_id" \
        android.app.role.ASSISTANT com.radiosound.caramelvoice >/dev/null
    wait_for_log 'Zipformer model ready in'
fi

echo "PASS: API 36 assistant AVD smoke test"
echo "assistant role:"
"$adb" -s "$serial" shell cmd role get-role-holders --user "$user_id" \
    android.app.role.ASSISTANT
echo "recognition services:"
"$adb" -s "$serial" shell cmd package query-services --brief -a \
    android.speech.RecognitionService
echo "selected TTS engine:"
"$adb" -s "$serial" shell settings --user "$user_id" get secure tts_default_synth
echo "CaramelVoice log:"
"$adb" -s "$serial" logcat -d -s 'CaramelVoice:D' '*:S' | tail -80
