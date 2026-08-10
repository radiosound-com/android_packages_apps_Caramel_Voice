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
  `412a36678dda6422683cbdc6bb6ddec9c5f43bab1e6d4953025a83b39e2f9e00`

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

That verification was completed on the physical Pi after reboot with the
platform-signed system APK (`CaramelVoiceAssistant.apk`, SHA-256
`205191f4fd70e4bd008e99336cad5fcf909fc3642f524b90c3b1931ea5b90729`). The
device was running the `aosp_rpi5_car_zipformer_kokoro-caramel-userdebug`
product with user 10's Caramel Voice assistant role restored. A Daniel voice
replay produced the following ordering:

* `15:24:23.779` — Zipformer final result: `TAKE ME HOME`
* `15:24:23.781` — command response queued: `Opening home in OsmAnd.`
* `15:24:23.895` — Kokoro TTS started
* `15:24:32.718` — Android TTS completion callback
* `15:24:32.728` — OsmAnd `CarAppActivity` launch

The Pi log contained no TTS completion timeout, fatal exception, or assistant
crash during this replay. The full-size image was then rebuilt with
`RPI5_IMAGE_SIZE_BYTES=250059350016`; its logical size is 250,059,350,016
bytes and its userdata partition is 229.4 GiB. The image was transferred as
`RaspberryVanillaAOSP16-20260810-rpi5_car_zipformer_kokoro-be58523.img.gz`.
This timing test used a post-reboot system-APK deployment rather than a fresh
userdata flash, so clean-userdata validation of the complete image remains a
separate release-install check.
