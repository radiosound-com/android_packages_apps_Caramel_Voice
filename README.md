# Caramel Vanilla offline voice stack

This project supplies the first offline voice-assistant slice for Caramel
Vanilla on AOSP 16 / AAOS:

* `CaramelVoiceAssistant` is a privileged AAOS `VoiceInteractionService` with
  a PTT session, a Vosk `RecognitionService`, and a small deterministic command
  layer.
* `CaramelEspeakTts` is the upstream eSpeak Android TTS APK, built from the
  pinned GPL source snapshot in `provenance/sources/`.
* The Vosk US-English mobile model is embedded in the assistant APK. The build
  adds the small `uuid` marker expected by Vosk Android's `StorageService`; the
  downloaded model archive itself is retained unchanged under `app/model/`.
  Runtime recognition and speech synthesis do not require a network connection.

The command layer currently handles time, opening OsmAnd, Android `geo:` map
search/navigation phrases, and media placeholders. It checks the common
OsmAnd package names (`net.osmand.dev`, `net.osmand.plus`, and `net.osmand`)
so the product can use either the development or release build. It is
intentionally deterministic and does not claim to be a general-purpose LLM
assistant yet.

## Build the assistant APK

```sh
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleRelease
```

The build expects API 36. The Vosk AAR and model archive are checked by SHA-256
in `provenance/SOURCES.lock`.

## Rebuild eSpeak

```sh
tar -xzf provenance/sources/espeak-ng-1.52.0-*.tar.gz
cd espeak-ng-1.52.0
git apply /path/to/android_packages_apps_Caramel_Voice/provenance/patches/espeak-ng-headless-data.patch
cd android
$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager \
  "cmake;3.22.1" "ndk;26.1.10909125"
./gradlew assembleRelease
```

The eSpeak source archive is the preferred corresponding source for the
prebuilt TTS APK. The Caramel patch makes first-use data extraction headless,
so a product-installed engine does not need to launch its data-download
Activity. It contains the upstream `COPYING`, `COPYING.APACHE`, and
`COPYING.BSD2` notices.

## AOSP integration

Add this repository to the AOSP manifest at `vendor/radiosound/voiceassistant`,
then add `CaramelVoiceAssistant` and `CaramelEspeakTts` to the product package
list (or inherit `caramel_voice.mk`). The device overlay must set:

```xml
<string name="config_defaultAssistant" translatable="false">com.radiosound.caramelvoice</string>
<string name="config_systemSpeechRecognizer" translatable="false">com.radiosound.caramelvoice</string>
<string name="config_defaultOnDeviceSpeechRecognitionService" translatable="false">com.radiosound.caramelvoice/.VoskRecognitionService</string>
```

The product build signs both prebuilts with the platform key and installs them
under `/product/priv-app`. The final image also needs the corresponding
privapp permission allowlist entries for the permissions granted by the target
AAOS release.

Because the eSpeak package is a system package in the product image, AOSP's
`TtsEngines` fallback can select it when `tts_default_synth` is empty. A
sideloaded APK is not a system engine, so a sideload-only test must explicitly
select it for the test user:

```sh
adb -s 192.168.1.56:5555 shell settings --user 10 put secure \
  tts_default_synth com.reecedunn.espeak
```

### Live userdebug install (development only)

On an unlocked `userdebug` Pi, the image can be tested without reflashing by
remounting the root filesystem and copying the platform-signed APKs into
`/product/priv-app`. A plain `adb install` remains a data app and cannot test
privileged/system-package behavior. The image build is still the reproducible
release path, and this procedure is unavailable on a production `user` build.

```sh
adb -s 192.168.1.56:5555 root
adb -s 192.168.1.56:5555 remount
adb -s 192.168.1.56:5555 shell mkdir -p \
  /product/priv-app/CaramelVoiceAssistant \
  /product/priv-app/CaramelEspeakTts
adb -s 192.168.1.56:5555 push \
  out/target/product/rpi5/system/product/priv-app/CaramelVoiceAssistant/CaramelVoiceAssistant.apk \
  /data/local/tmp/CaramelVoiceAssistant.apk
adb -s 192.168.1.56:5555 push \
  out/target/product/rpi5/system/product/priv-app/CaramelEspeakTts/CaramelEspeakTts.apk \
  /data/local/tmp/CaramelEspeakTts.apk
adb -s 192.168.1.56:5555 shell \
  'cp /data/local/tmp/CaramelVoiceAssistant.apk /product/priv-app/CaramelVoiceAssistant/CaramelVoiceAssistant.apk && cp /data/local/tmp/CaramelEspeakTts.apk /product/priv-app/CaramelEspeakTts/CaramelEspeakTts.apk && chown 0:0 /product/priv-app/CaramelVoiceAssistant/CaramelVoiceAssistant.apk /product/priv-app/CaramelEspeakTts/CaramelEspeakTts.apk && chmod 0644 /product/priv-app/CaramelVoiceAssistant/CaramelVoiceAssistant.apk /product/priv-app/CaramelEspeakTts/CaramelEspeakTts.apk && restorecon -RF /product/priv-app/CaramelVoiceAssistant /product/priv-app/CaramelEspeakTts'
adb -s 192.168.1.56:5555 reboot
```

After reboot, verify `pm list packages -s` and `dumpsys package` report both
packages as `SYSTEM`, `PRIVILEGED`, and `PRODUCT`. If the Pi was previously
sideloaded, Package Manager may also show `UPDATED_SYSTEM_APP` and a data APK
path; a fresh product image removes that development-state residue. An
existing user profile may need its assistant setting rebound once:

```sh
adb -s 192.168.1.56:5555 shell settings --user 10 put secure \
  voice_interaction_service com.radiosound.caramelvoice/.CaramelVoiceInteractionService
adb -s 192.168.1.56:5555 shell settings --user 10 put secure \
  assistant com.radiosound.caramelvoice/.CaramelVoiceInteractionService
adb -s 192.168.1.56:5555 shell cmd voiceinteraction disable false
```

## Device smoke test

```sh
adb -s 192.168.1.56:5555 shell dumpsys texttospeech
adb -s 192.168.1.56:5555 shell cmd package query-services \
  --brief -a android.speech.RecognitionService
adb -s 192.168.1.56:5555 shell cmd role get-role-holders \
  android.app.role.ASSISTANT --user 10
adb -s 192.168.1.56:5555 logcat -d -s CaramelVoice Vosk TextToSpeech
```

The AAOS push-to-talk path is CarInputService, not the generic Android input
dispatcher. Inject a short `KEYCODE_VOICE_ASSIST` press atomically; separate
ADB down/up commands can take long enough to be interpreted as a long press:

```sh
adb -s 192.168.1.56:5555 shell cmd car_service inject-key -t 200 231
```

For a product image, leave `tts_default_synth` unset to test the system-engine
fallback. For a sideload-only test, run the explicit setting command above.
A successful runtime test must show the eSpeak service in `dumpsys
texttospeech`, the Vosk recognizer in the service query, an active assistant
role holder, and a voice session after the AAOS PTT injection. Spoken output
also requires a real ALSA capture/playback device; `AudioRecord`/`AudioTrack`
return `ENODEV` on a Pi with no sound card even though the service wiring is
working. The Caramel product grants `RECORD_AUDIO` to the preinstalled
assistant on first boot; verify the effective grant before testing capture:

```sh
adb -s 192.168.1.56:5555 shell cmd package check-permission \
  android.permission.RECORD_AUDIO com.radiosound.caramelvoice 10
```

The expected result is `granted`. This is a normal dangerous-permission grant,
not a privileged-permission bypass, so a user or policy can revoke it.

## Reproducible host recognition check

This checks the pinned model and the deterministic command phrase without
requiring an audio device on the Pi. On macOS, create 16-bit mono 16 kHz WAV
speech, then run the same Vosk model with the pinned Python package:

```sh
tmpdir=$(mktemp -d)
python3 -m venv "$tmpdir/venv"
"$tmpdir/venv/bin/pip" install --disable-pip-version-check 'vosk==0.3.44'
say -v Samantha -o "$tmpdir/input.aiff" 'what time is it'
afconvert -f WAVE -d LEI16@16000 -c 1 "$tmpdir/input.aiff" "$tmpdir/input.wav"
unzip -q app/model/vosk-model-small-en-us-0.15.zip -d "$tmpdir/model"
"$tmpdir/venv/bin/python" - "$tmpdir/model/vosk-model-small-en-us-0.15" "$tmpdir/input.wav" <<'PY'
import json
import sys
import wave
from vosk import KaldiRecognizer, Model

model = Model(sys.argv[1])
with wave.open(sys.argv[2], "rb") as audio:
    recognizer = KaldiRecognizer(model, audio.getframerate())
    while data := audio.readframes(4000):
        recognizer.AcceptWaveform(data)
    print(json.loads(recognizer.FinalResult()))
PY
```

The expected result contains `what time is it`. This host check is test-only;
the Android build uses the checked-in Vosk AAR and model archive described in
`provenance/SOURCES.lock`.
