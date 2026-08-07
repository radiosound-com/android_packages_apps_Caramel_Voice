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
* CaramelKokoroTts is an optional sherpa-onnx Android TTS engine containing
  the Apache-2.0 Kokoro English model and eleven named speakers.

The command layer currently handles time, opening OsmAnd, Android `geo:` map
search/navigation phrases, and `play …` searches through Spotify's standard
Android media session. It checks the common
OsmAnd package names (`net.osmand.dev`, `net.osmand.plus`, and `net.osmand`)
so the product can use either the development or release build. It is
intentionally deterministic and does not claim to be a general-purpose LLM
assistant yet.

## Build the assistant APK

```sh
export ANDROID_HOME=/path/to/android-sdk
./gradlew testReleaseUnitTest assembleRelease lintRelease
```

The product app has minimum and target API 36 because it is built specifically
for Caramel Vanilla AOSP 16. The Vosk AAR and model archive are checked by SHA-256
in `provenance/SOURCES.lock`. The host unit tests cover command normalization,
time-query substitutions, navigation and media-query extraction, N-best
command selection, and safe fallback to an echo response.

### Recognition model profiles

The default `aosp_rpi5_car-caramel-userdebug` product uses the compact
`vosk-model-small-en-us-0.15` archive embedded in the assistant APK. The
`aosp_rpi5_car_lgraph-caramel-userdebug` product selects Vosk's
`vosk-model-en-us-0.22-lgraph` model, which is a reasonable larger profile for
a 4 GB Pi with measured headroom. The same lgraph profile is selected by
`aosp_rpi5_car_16gb-caramel-userdebug`.

The 125 MiB lgraph archive is copied only to the larger products at
`/product/etc/caramel_voice/models/` and extracted lazily into the app's
private no-backup directory. The model selector is therefore reproducible at
build time and the compact image does not carry the larger archive:

```sh
lunch aosp_rpi5_car_lgraph-caramel-userdebug
RPI5_AUDIO=usb m systemimage -j8
```

The lgraph model is Apache-2.0 per the Vosk catalog; its URL, hash, size, and
product inclusion rule are recorded in `provenance/SOURCES.lock`. The larger
profile currently changes recognition only; the
aosp_rpi5_car_lgraph_kokoro and aosp_rpi5_car_16gb products also include
the Kokoro neural TTS engine. The compact product keeps eSpeak as its default.

The recognizer asks Vosk for five alternatives. Android receives the ordered
N-best list, and the command router can select an actionable alternative when
the first hypothesis contains a near-homophone such as `plate` instead of
`play`. A dedicated thread continuously drains 100 ms chunks from a two-second
`AudioRecord` ring while a second thread performs Vosk decoding. The queue is
bounded by source-side voice activity detection and prevents cold lgraph work
from overflowing the microphone path. The detector keeps 300 ms of pre-roll,
ends capture after 1.2 seconds of trailing silence, and sends only 300 ms of
that trailing silence to Vosk. This avoids spending twice-real-time decoder
work on idle room audio while retaining short pauses inside a command. The
selected model is loaded and prewarmed
when the voice interaction service becomes ready, then retained for the app
process instead of being reloaded for every push-to-talk session.

### Spotify media search

`play Eric Prydz Opus` is sent to Spotify with
`MediaController.TransportControls.playFromSearch`. An already-active Spotify
session is preferred; otherwise the assistant connects to Spotify's exported
`MediaBrowserService` and obtains its session token without opening a phone UI.
The assistant contains no Spotify SDK, credentials, or proprietary code. Voice
recognition remains offline, while Spotify playback follows Spotify's own
network and downloaded-content behavior.

The `aconfig/` directory contains the small Apache-2.0 Caramel release value
set used by the device product to disable the Pi USB-ALSA enumeration race.
It adds no third-party code or runtime dependency; the device release config
opts it in only for `aosp_rpi5_car-caramel-*` builds.

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

## Try other offline voices

Open Caramel Voice's settings page and choose **Choose offline voice engine**.
This opens Android's TTS picker, where eSpeak and Kokoro can coexist. Then
choose **Configure selected voice**.

eSpeak NG includes many voice variants without downloading additional model
files. Its configuration screen exposes female, male 1--8, Klatt, NVDA, young,
old, croak, whisper, rate, pitch, pitch range, and volume controls. For an
ADB-only test, launch the selected engine's settings directly:

```sh
adb -s 192.168.1.56:5555 shell am start \
  -a android.speech.tts.engine.CONFIGURE_ENGINE \
  -n com.reecedunn.espeak/.TtsSettingsActivity
```

Kokoro exposes eleven named English speakers: af, af_bella, af_nicole,
af_sarah, af_sky, am_adam, am_michael, bf_emma, bf_isabella, bm_george,
and bm_lewis. The Caramel configuration screen presents these as a voice
dropdown and stores the upstream speaker_id value (0--10). The selected
engine is the engine used by the assistant; it is no longer hard-coded to
eSpeak.

Kokoro is the natural-speech option for the 4 GB Pi. It uses substantially
more resident memory than eSpeak, so eSpeak remains installed as a fallback.
The model and sherpa engine provenance, including the embedded GPL eSpeak
pronunciation data and MIT ONNX Runtime notice, are recorded in
provenance/SOURCES.lock.

## AOSP integration

Add this repository to the AOSP manifest at `vendor/radiosound/voiceassistant`,
then add the packages through `caramel_voice.mk`. eSpeak is always included as
the small fallback; Kokoro is included by products that set
`CARAMEL_VOICE_TTS := kokoro`. The device overlay must set:

```xml
<string name="config_defaultAssistant" translatable="false">com.radiosound.caramelvoice</string>
<string name="config_systemSpeechRecognizer" translatable="false">com.radiosound.caramelvoice</string>
<string name="config_defaultOnDeviceSpeechRecognitionService" translatable="false">com.radiosound.caramelvoice/.VoskRecognitionService</string>
```

The product build signs both prebuilts with the platform key and installs them
under `/product/priv-app`. Caramel Voice requests
`android.permission.MEDIA_CONTENT_CONTROL`; the device product grants that
signature-or-privileged permission in
`/product/etc/permissions/privapp-permissions-rpi5.xml` on the same partition
as the app. `RECORD_AUDIO` remains a user-controllable dangerous permission
granted by the default-permissions policy.

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
adb -s 192.168.1.56:5555 shell dumpsys package \
  com.radiosound.caramelvoice | grep -A 12 'install permissions:'
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

If Android reports `SpeechRecognizer.ERROR_CLIENT` while a USB microphone is
still appearing, the PTT session retries microphone startup three times at
750 ms intervals before reporting that the microphone is unavailable. This
handles a transient audio-policy registration delay; it does not replace the
product-level USB Aconfig workaround.
