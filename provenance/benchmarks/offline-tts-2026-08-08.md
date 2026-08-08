# Offline TTS AVD measurement — 2026-08-08

This is a benchmark record, not a model import. No private user recordings or
generated audio are redistributed with the repository. The test used the
Caramel AOSP 16 arm64 product AVD (`emulator-5570`), active user 10, with a
2,017,768 KiB guest-memory configuration.

## Kokoro result

The current local assistant build was `e862351` with the Zipformer INT8
profile. A debug-signed test copy of the already-pinned
`SherpaKokoroTts-1.13.4.apk` was installed as a disposable user package and
selected as the user's TTS engine. The product APK itself remains unsigned in
the source tree and is signed by the AOSP platform certificate when imported
by Soong.

The generated utterance `what time is it` was recognized exactly as
`WHAT TIME IS IT`; the command layer produced `It is 5:42 AM`, and Kokoro
initialized and completed the TTS request without a native crash. The AVD
audio-hardening policy muted the background-installed test engine, so this
headless run verifies binding, model initialization, and synthesis completion,
not audible speaker output.

| Process | Total PSS | Total RSS | Notes |
| --- | ---: | ---: | --- |
| `com.k2fsa.sherpa.onnx.tts.engine` | 465,551 KiB | 475,932 KiB | Kokoro model resident after synthesis |
| `com.radiosound.caramelvoice` | 294,324 KiB | 341,220 KiB | Zipformer and 368 context phrases |

The 2 GiB AVD reported 448,744 KiB available after the run and reclaimed the
small Caramel defaults helper. This is pressure evidence, not a 4 GB Pi
failure: the Kokoro profile remains appropriate as an optional natural-voice
variant, while eSpeak remains the low-memory fallback. A physical 4 GB Pi run
is still required before changing the 4 GB default or claiming hardware
thermal/audio stability.

## Provenance

The engine, Kokoro model, embedded eSpeak pronunciation data, ONNX Runtime,
and their notices are fully enumerated in `provenance/SOURCES.lock` and
`LICENSES/`. The tested unsigned APK hash is the hash recorded there:

* `SherpaKokoroTts-1.13.4.apk`
* SHA-256: `d08850d9248cda7e477be6a7354085132b5a0c086eb572542a3666eef09fdb03`

