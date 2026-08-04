# Caramel Vanilla offline voice stack

This project supplies the first offline voice-assistant slice for Caramel
Vanilla on AOSP 16 / AAOS:

* `CaramelVoiceAssistant` is a privileged AAOS `VoiceInteractionService` with
  a PTT session, a Vosk `RecognitionService`, and a small deterministic command
  layer.
* `CaramelEspeakTts` is the upstream eSpeak Android TTS APK, built from the
  pinned GPL source snapshot in `provenance/sources/`.
* The Vosk US-English mobile model is embedded in the assistant APK. Runtime
  recognition and speech synthesis do not require a network connection.

The command layer currently handles time, opening OsmAnd, map/navigation
phrases, and media placeholders. It is intentionally deterministic and does
not claim to be a general-purpose LLM assistant yet.

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
cd espeak-ng-1.52.0/android
$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager \
  "cmake;3.22.1" "ndk;26.1.10909125"
./gradlew assembleRelease
```

The eSpeak source archive is the preferred corresponding source for the
prebuilt TTS APK. It contains the upstream `COPYING`, `COPYING.APACHE`, and
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

## Device smoke test

```sh
adb -s 192.168.1.56:5555 shell dumpsys texttospeech
adb -s 192.168.1.56:5555 shell cmd package query-services \
  --brief -a android.speech.RecognitionService
adb -s 192.168.1.56:5555 shell cmd role get-role-holders \
  android.app.role.ASSISTANT --user 0
adb -s 192.168.1.56:5555 logcat -d -s CaramelVoice Vosk TextToSpeech
```

Set eSpeak as the default engine in the target user's TTS settings before
testing OsmAnd. A successful test must show the eSpeak service in
`dumpsys texttospeech`, the Vosk recognizer in the service query, an active
assistant role holder, and spoken output from an OsmAnd navigation prompt.
