# Voice response/navigation timing test — 2026-08-10

The physical Pi reproduced a truncated navigation announcement with the
previous assistant prebuilt. For `Opening home in OsmAnd.`, AudioFlinger showed
the Kokoro `AudioTrack` starting and being removed after approximately 0.8
seconds, and the assistant never logged its TTS completion callback. The
command handler launched OsmAnd before calling `TextToSpeech.speak()`, allowing
the navigation activity to interrupt the speech output.

The fix is commit `f44a455` (`Wait for voice response before launching
navigation`). Navigation intents are resolved without launching, the response
is spoken, and the activity is started from the utterance-completion callback.
The empty-command path no longer calls `finish()` immediately after enqueueing
speech. `VoiceResponseCoordinatorTest` locks down the callback ordering.

The AOSP prebuilt consumed by `android_app_import` was rebuilt from the same
source revision and is recorded in `SOURCES.lock`:

* `app/prebuilts/CaramelVoiceAssistant-0.3.0.apk`
* SHA-256:
  `a21880c0541f77f563fd0a0fb6e9ccbaba1627f1ba31179f9509936925d7121e`

The prebuilt is unsigned; the Caramel product build signs it with the platform
key when installing it under `/product/priv-app`. A clean AOSP image and a
post-reboot physical-Pi replay are required before this fix is considered a
release benchmark.
