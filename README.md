# Caramel Vanilla offline voice stack

This project supplies the first offline voice-assistant slice for Caramel
Vanilla on AOSP 16 / AAOS:

* `CaramelVoiceAssistant` is a privileged AAOS `VoiceInteractionService` with
  a PTT session, product-selectable Vosk or streaming Zipformer recognition,
  and a small deterministic command layer.
* `CaramelEspeakTts` is the upstream eSpeak Android TTS APK, built from the
  pinned GPL source snapshot in `provenance/sources/`.
* The compact Vosk US-English mobile model is embedded in the assistant APK. The build
  adds the small `uuid` marker expected by Vosk Android's `StorageService`; the
  downloaded model archive itself is retained unchanged under `app/model/`.
  Runtime recognition and speech synthesis do not require a network connection.
* CaramelKokoroTts is an optional sherpa-onnx Android TTS engine containing
  the Apache-2.0 Kokoro English model and eleven named speakers.
* The optional INT8 Zipformer profile keeps its 179 MiB encoder and companion
  files under `/product/etc/caramel_voice/models/`; it is never downloaded at
  runtime and remains responsive on the 4 GB Pi.

The Android Zipformer decoder uses sherpa-onnx's native multi-stream decode
entry point. Version 1.13.4 exposes that entry point through JNI but omits it
from the Kotlin wrapper, so the checked-in AAR carries the small, reproducible
wrapper patch recorded in `provenance/SOURCES.lock`. The assistant feeds one
live stream plus a silent companion stream and decodes them as a batch; this
works around a singleton-batch failure observed with this INT8 export without
duplicating microphone audio or adding a second recognizer.

The command layer currently handles time, opening OsmAnd, Android `geo:` map
search/navigation phrases, and `play …` searches through standard Android
media sessions and media browsers. It checks the common
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
for Caramel Vanilla AOSP 16. Every checked-in runtime, model, source snapshot,
and built APK is pinned by SHA-256 in `provenance/SOURCES.lock`. The host unit
tests cover command normalization, time-query substitutions, navigation and
media-query extraction, N-best command selection, context resolution, backend
selection, and one-time native resource ownership.

### API 36 arm64 AVD smoke test

The standalone API 36 Google Play arm64 AVD cannot be remounted, so it is not
a substitute for a Caramel AOSP product image. A debuggable assistant APK has
an explicitly opt-in test path that reads `recognition.properties` and the
Zipformer files from its own external-files directory; release APKs and all
AOSP product builds continue to use the immutable `/product` paths. This lets
the exact arm64 AAR and model be exercised on Apple Silicon without granting
the emulator root access:

```sh
ANDROID_HOME=/path/to/android-sdk ./scripts/avd-smoke-test.sh
```

The script installs the debug assistant and a signed test copy of eSpeak,
grants only the two runtime permissions needed for local testing, binds the
assistant role, verifies Vosk prewarming, copies the checked-in INT8 Zipformer
files, reboots, and verifies Zipformer's model startup. It does not claim
microphone or CarInputService validation; those require an Android 16 Caramel
product AVD or the physical Pi. Set `CARAMEL_AVD_SKIP_ZIPFORMER=1` to run only
the compact profile check. Set `CARAMEL_AVD_USER=10` when testing an AAOS image
whose active driver user is 10 instead of the generic AVD's user 0. For a
nonzero user, the script requires a debuggable AVD with `adb root` so it can
stage the debug-only override in that user's `/data/media/<user>/Android/data`
tree; release/product builds never use this path.

### Recognition model profiles

The default `aosp_rpi5_car-caramel-userdebug` product uses the compact
`vosk-model-small-en-us-0.15` archive embedded in the assistant APK. The
`aosp_rpi5_car_lgraph-caramel-userdebug` product retains Vosk's
`vosk-model-en-us-0.22-lgraph` as a compatibility profile.

The recommended high-quality 4 GB profile set is
`aosp_rpi5_car_zipformer-caramel-userdebug` and
`aosp_rpi5_car_zipformer_kokoro-caramel-userdebug`; the latter also selects the
neural Kokoro voice. For 16 GB builds, use:

* `CARAMEL_VOICE_ASR_MODEL := zipformer-int8-highmem` (same Zipformer INT8
  assets, increased threading and beam budget).

Products can select the backend directly with:

```make
CARAMEL_VOICE_ASR_MODEL := zipformer-int8
```

On the 4 GB Pi 5, the selected INT8 Zipformer loaded in about 1.3--1.7 seconds
and held the assistant near 395 MiB RSS in the earlier device measurements.
The batched wrapper fix is required for those measurements to represent the
real Android path; a singleton decode can return empty or nonsensical text even
with the official model files. The larger Vosk lgraph remains available as the
robustness-oriented 4 GB alternative, especially for far-field or noisy
microphones. On 16 GB boards, the higher-memory profile keeps the same model
family and increases beam/thread budget for longer commands and music-heavy
requests. The equivalent Whisper.cpp `small.en-q5_1` evaluation was accurate
but ran at 0.98 real-time factor, so Whisper is reserved for a future optional
second pass rather than the push-to-talk primary on a 4 GB board.

Selected larger models are copied only to
`/product/etc/caramel_voice/models/`. Vosk lgraph is extracted lazily into the
app's private no-backup directory; Zipformer maps its ONNX files directly from
the read-only product partition. The selector is reproducible at build time
and the compact image does not carry either larger model:

```sh
lunch aosp_rpi5_car_zipformer_kokoro-caramel-userdebug
RPI5_AUDIO=usb m systemimage -j8
```

The lgraph model is Apache-2.0 per the Vosk catalog; its URL, hash, size, and
product inclusion rule are recorded in `provenance/SOURCES.lock`. The
`aosp_rpi5_car_lgraph_kokoro`, `aosp_rpi5_car_zipformer_kokoro`, and
`aosp_rpi5_car_16gb` products include the Kokoro neural TTS engine. The compact
and Vosk-recognition products keep eSpeak as their default.

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
process instead of being reloaded for every push-to-talk session. Zipformer
starts its initial built-in graph after a 250 ms bootstrap delay; context
collector notifications cannot cancel that first load and instead schedule the
full hotword graph after the 2.5-second debounce. This keeps cold PTT startup
responsive while preserving the later personalized catalog update.

### Generic recognition context

The assistant builds a bounded local context index without opening another
app's private database. It uses Android's standard surfaces:

* active `MediaSession` metadata;
* up to 300 entries from each of four prioritized exported
  `MediaBrowserService` catalogs, with only two browsers connected
  concurrently;
* granted `MediaStore` audio metadata;
* assistant-visible place, media, contact, and app documents from the platform
  `GlobalSearchSession` AppSearch API; and
* locally learned arguments from successful commands.

AppSearch documents remain available only when their owning app explicitly
made them globally visible to this assistant/role. Unrelated document schema
types are ignored. Private SQLite files are never scraped. Catalog and
AppSearch changes are coalesced into one final hotword update. A foreground PTT
request also refreshes cheap learned and active-session metadata; Zipformer's
model repository defers its hotword-graph reload while a capture lease is
active, then applies it immediately after the command so model loading cannot
compete with recognition or TTS. On a cold process start the session shows
**Preparing microphone…** until the selected model is ready, preventing the
first spoken words from being lost while native assets load.

The assistant registers a debounced observer for standard MediaStore audio and
playlist changes, and performs a throttled full context refresh on foreground
use for sources that do not expose an observer. This keeps newly indexed local
music, playlists, and assistant-visible app/navigation documents available
without scraping private databases. Post-ASR resolution also compares multiple
matching tokens with a small phonetic fallback, while retaining distance and
ambiguity guards; the catalog supplies the authoritative spelling rather than
any hard-coded artist, title, or destination.

The observer and noisy-catalog end-to-end check is recorded in
`provenance/benchmarks/context-refresh-2026-08-08.md`.

The index feeds up to 1,024 normalized phrases to Zipformer's modified-beam
hotword graph and also performs conservative post-ASR resolution. For example,
the live Pi corrected `PLAY ERIC PRIDES OPUS` to the authoritative catalog
value `Eric Prydz Opus`. There are no hard-coded artist, playlist, destination,
or player names.

### Generic media search

`play …` is sent through
`MediaController.TransportControls.playFromSearch`. The assistant scores all
active sessions that advertise `ACTION_PLAY_FROM_SEARCH`, preferring the
currently playing or paused app. If none exists, it tries at most four exported
`MediaBrowserService` players sequentially, prioritizing active and non-system
apps. The selected application's normal label is used in the spoken response.
The assistant contains no player SDK, credentials, proprietary code, or
package-specific catalog logic. Recognition remains offline; a selected
streaming player's own network behavior is unchanged.

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
provenance/SOURCES.lock. The reproducible AVD memory and synthesis result is
recorded in `provenance/benchmarks/offline-tts-2026-08-08.md`; it is not a
substitute for the pending physical 4 GB Pi measurement.

## AOSP integration

Add this repository to the AOSP manifest at `vendor/radiosound/voiceassistant`,
then add the packages through `caramel_voice.mk`. eSpeak is always included as
the small fallback; Kokoro is included by products that set
`CARAMEL_VOICE_TTS := kokoro`. All products set the assistant package defaults:

```xml
<string name="config_defaultAssistant" translatable="false">com.radiosound.caramelvoice</string>
<string name="config_systemSpeechRecognizer" translatable="false">com.radiosound.caramelvoice</string>
```

The compact and lgraph products set
`config_defaultOnDeviceSpeechRecognitionService` to `.VoskRecognitionService`;
the Zipformer products apply a higher-priority product overlay selecting
`.SherpaRecognitionService`. Caramel Voice also reads the same immutable
`recognition.properties`, so the AAOS PTT session and Android's public
on-device recognition API use the same backend.

The product build signs the selected prebuilts with the platform key and
installs them under `/product/priv-app`. Caramel Voice requests
`android.permission.MEDIA_CONTENT_CONTROL`; the device product grants that
signature-or-privileged permission in
`/product/etc/permissions/privapp-permissions-rpi5.xml` on the same partition
as the app. `RECORD_AUDIO` and `READ_MEDIA_AUDIO` remain user-controllable
dangerous permissions granted by the default-permissions policy. The latter
allows local MediaStore titles to improve recognition without bypassing app
sandboxing.

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
adb -s 192.168.1.56:5555 logcat -d -s CaramelVoice TextToSpeech sherpa-onnx
```

The AAOS push-to-talk path is CarInputService, not the generic Android input
dispatcher. Inject a short `KEYCODE_VOICE_ASSIST` press atomically; separate
ADB down/up commands can take long enough to be interpreted as a long press:

```sh
adb -s 192.168.1.56:5555 shell cmd car_service inject-key -t 200 231
```

For a product image, leave `tts_default_synth` unset to test the system-engine
fallback. For a sideload-only test, run the explicit setting command above.
A successful runtime test must show the selected offline engine in `dumpsys
texttospeech`, both bundled recognition services in the service query, an
active assistant role holder, and a voice session after the AAOS PTT injection.
The `CaramelVoice` log must report either `Vosk model ready` or `Zipformer model
ready` according to the product configuration. Spoken output
also requires a real ALSA capture/playback device; `AudioRecord`/`AudioTrack`
return `ENODEV` on a Pi with no sound card even though the service wiring is
working. The Caramel product grants `RECORD_AUDIO` to the preinstalled
assistant on first boot; verify the effective grant before testing capture:

```sh
adb -s 192.168.1.56:5555 shell cmd package check-permission \
  android.permission.RECORD_AUDIO com.radiosound.caramelvoice 10
adb -s 192.168.1.56:5555 shell cmd package check-permission \
  android.permission.READ_MEDIA_AUDIO com.radiosound.caramelvoice 10
```

The expected result is `granted`. This is a normal dangerous-permission grant,
not a privileged-permission bypass, so a user or policy can revoke it.

## Reproduce the Zipformer runtime and model

The model fetch script downloads the original upstream 483 MiB release
archive, verifies it before extraction, regenerates `bpe.vocab` with pinned
SentencePiece 0.2.2, installs only the selected INT8 files, and verifies every
output hash:

```sh
./scripts/fetch-zipformer-assets.sh
```

The AAR rebuild script accepts either the retained source archive or an exact
checkout at the recorded v1.13.4 commit. It applies the recorded Kotlin API
patch, builds only arm64-v8a ASR/JNI, packages ONNX Runtime 1.27.0, and rejects
an output whose hash differs from the checked prebuilt:

```sh
export ANDROID_HOME=/path/to/android-sdk
./scripts/rebuild-sherpa-onnx-aar.sh \
  provenance/sources/sherpa-onnx-v1.13.4-142807252687d81b40d6315f23470a1512a00de3.tar.gz
```

The script also applies the recorded reproducible-build patch. It canonicalizes
macOS physical checkout paths, removes the unused command-line binary targets,
and defaults the native build to one worker (`SHERPA_ONNX_BUILD_JOBS` can be
overridden for non-reproducibility experiments). Two clean builds of the
checked source/archive produced the same AAR hash recorded in
`provenance/SOURCES.lock`.

## Reproducible compact Vosk host check

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
`provenance/SOURCES.lock`. Zipformer is exercised through the Android unit and
Pi device tests because the shipped runtime is the pinned arm64 Android AAR.

If Android reports `SpeechRecognizer.ERROR_CLIENT` while a USB microphone is
still appearing, the PTT session retries microphone startup three times at
750 ms intervals before reporting that the microphone is unavailable. This
handles a transient audio-policy registration delay; it does not replace the
product-level USB Aconfig workaround.
