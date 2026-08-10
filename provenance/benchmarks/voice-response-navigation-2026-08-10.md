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
  `3ddea13c44814c07989a2934a6830128a3c9cdc2a545907214d6fa3cce39d34f`

The first product-signed AVD replay confirmed the ordering fix: TTS completed
at `11:05:17.068`, followed by the OsmAnd activity start at `11:05:17.072`.
The first physical replay then exposed a separate fallback edge case: Kokoro
needed about 6.4 seconds to synthesize this sentence and another 1.9 seconds
to drain it, so the old 8-second missing-callback fallback could still launch
OsmAnd just before the audio track stopped. The coordinator now gives
responses with an external action a 20-second fallback window; ordinary
responses retain the 8-second timeout. Normal TTS completion remains the fast
path.

The prebuilt is unsigned; the Caramel product build signs it with the platform
key when installing it under `/product/priv-app`. A clean AOSP image and a
post-reboot physical-Pi replay are required before the timeout adjustment is
considered a release benchmark.
