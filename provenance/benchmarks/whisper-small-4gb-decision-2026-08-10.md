# Whisper small.en decision for the 4 GB Pi — 2026-08-10

This is a deployment decision record, not a model import. The benchmark-only
Whisper `small.en-q5_1` artifact, its SHA-256, and MIT licensing are recorded
in `offline-asr-2026-08-08.md`; the model is not copied into the APK or AOSP
image.

The current physical Pi was measured after the context-cache reboot and
Kokoro/Spotify/OsmAnd activity:

| Measurement | Value |
| --- | ---: |
| Total RAM | 3,966 MiB |
| Available RAM reported by `free` | 96 MiB |
| Caramel Voice RSS | 441 MiB |
| Kokoro TTS RSS | 624 MiB |
| OsmAnd RSS | 396 MiB |
| Spotify RSS | 256 MiB |
| Swap | 0 MiB |

The private corpus comparison found Whisper small.en semantically correct on
all twelve recordings and the streaming INT8 Zipformer suitable for the
responsive primary path. However, the benchmarked Whisper q5_1 run reached a
626,556,928-byte peak resident set on the Mac and was approximately 0.98×
real time on the earlier Pi measurement. Adding that model and a second
decoder while Kokoro and navigation are resident would leave no safe memory
margin on this Pi and risks the lockups already observed during high-memory
voice experiments.

Decision:

* Keep context-biased INT8 Zipformer as the 4 GB default.
* Do not load Whisper small.en concurrently or make it an automatic second
  pass on the current 4 GB product.
* Reserve Whisper small.en (or a smaller Whisper distillation) for a future
  16 GB product variant, with an explicit memory and latency gate before it
  becomes selectable.

This preserves the model choice and licensing record without shipping a
known memory-risk feature. The current high-memory Zipformer profile remains
available for 16 GB boards, but its larger beam produced no corpus accuracy
gain in the existing comparison.
