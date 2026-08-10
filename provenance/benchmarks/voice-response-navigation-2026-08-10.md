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
  `c87853ef9cf4f4a3bcccd25ed298fa9d3a56d0be9555772a43c932baffc933e9`

The first product-signed AVD replay confirmed the ordering fix: TTS completed
at `11:05:17.068`, followed by the OsmAnd activity start at `11:05:17.072`.
The first physical replay then exposed a separate fallback edge case: Kokoro
needed about 6.4 seconds to synthesize this sentence and another 1.9 seconds
to drain it, so the old 8-second missing-callback fallback could still launch
OsmAnd just before the audio track stopped. The coordinator now gives both
external-action and ordinary responses a 20-second fallback window. Normal
TTS completion remains the fast path.

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

The follow-up media-recovery build is commit `aa9a79d` (the preceding
contextual-media implementation is `f981dac`). It adds generic recovery for
ASR output whose play prefix or surrounding words are corrupted while a
distinctive catalog token survives, and raises the ordinary Kokoro completion
fallback to 20 seconds. Unit tests, lint, and the release build passed. The
new platform-signed APK has SHA-256
`dbf941fa65e0be9c9663f1fec93618f606289d5b906a8c097b7a2456c1c242f8`.

After rebooting the Pi with that APK, a controlled Daniel replay produced:

* `15:49:11.830` — Zipformer final result: `TAKE ME HOME`
* `15:49:11.877` — Kokoro TTS started
* `15:49:17.796` — Android TTS completion callback
* `15:49:17.816` — OsmAnd `CarAppActivity` launch

The synthetic replay temporarily selected the line-in card to validate the
Mac-to-Pi signal path. It was reset afterward to automatic routing
(`persist.vendor.audio.pcm.card=-1`); the normal policy selects the standalone
`AK5370` USB microphone as capture card 0. No fatal exception, assistant crash,
or TTS completion timeout occurred in this replay.

The preceding `aa9a79d` Pi image was packaged with `RPI5_AUDIO=usb` and
`RPI5_IMAGE_SIZE_BYTES=250059350016`:

* Image: `RaspberryVanillaAOSP16-20260810-rpi5_car_zipformer_kokoro-aa9a79d.img.gz`
* Compressed size: 2,839,665,974 bytes
* Compressed SHA-256:
  `1ac4680db8aac6e62d98142e4b8bf83f96e4acd845b6dd27561ff910b8e51768`
* `gzip -t` passed locally; the local and build-host SHA-256 values match.
* Raw image logical size: 250,059,350,016 bytes; userdata is 229.4 GiB.
* The final product `system.img` SHA-256 is
  `9814348b5d307e37ce5490cf0bcc5bf3c6df88af8099bc5f423dcf3c7ba3ec0e`.
  Its platform-signed assistant APK matches the device-tested SHA above.

The context-cache follow-up is commit `0fc7ed6`. On the physical Pi, the
first boot after installing that APK completed the live catalog scan and
wrote a 54.6 KiB private cache containing 354 entities. After reboot, before
the first PTT request, the log reported:

* `16:06:24.687` — `Loaded 354 cached recognition context entities`
* `16:06:29.660` — first Zipformer graph ready with 445 context phrases

The subsequent live scan replaced the cache-backed snapshot and rebuilt the
graph to 1,024 phrases. A controlled line-in replay then opened the voice
session, selected the dynamic USB capture route, started Spotify from the
recognized `PLAY ERIC PRY` request, and completed Kokoro TTS without a crash,
timeout, or reboot. This replay used reused userdata; `READ_MEDIA_AUDIO` had
to be granted manually because default-permission exceptions are applied at
user creation. The product image continues to carry the default-permissions
entry for clean-userdata flashes.

The current full-size image for code commit `0fc7ed6` was packaged with
`RPI5_AUDIO=usb` and `RPI5_IMAGE_SIZE_BYTES=250059350016`:

* Image: `RaspberryVanillaAOSP16-20260810-rpi5_car_zipformer_kokoro-0fc7ed6.img.gz`
* Compressed size: 2,839,678,653 bytes
* Compressed SHA-256:
  `8b396f110f3e84c7c3f57a5295d036f7937d4bd17375368f39f54b3ae6a2c0c9`
* `gzip -t` passed locally; the local and build-host SHA-256 values match.
* Raw image logical size: 250,059,350,016 bytes; userdata is 229.4 GiB.
* Final product `system.img` SHA-256:
  `d845509ccd5604b8597e507fd106cea859476d97e2aa28fe60b21e7e6b253471`.
* Platform-signed assistant APK SHA-256:
  `8cba25f64b9f60901cb2e1f71829177a2eefbb162bfb0675229e9f97b5b38acb`.
