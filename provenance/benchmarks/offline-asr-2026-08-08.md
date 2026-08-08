# Offline ASR corpus comparison — 2026-08-08

This is a benchmark record, not a product model import. The recordings used
for this run are private user material and are intentionally not redistributed
with the repository. The corpus contained twelve labeled mono, 16 kHz,
16-bit WAV files: three each for `what time is it`, `take me home`, `play Eric
Prydz Opus`, and the recorded coordinate command.

## Results

| Backend/profile | Corpus word edits | Reference words | WER | Notes |
| --- | ---: | ---: | ---: | --- |
| Zipformer streaming INT8, production Android decoder | 5 | 93 | 5.4% | Warm replay was 0.08–0.14× real time on the arm64 AVD; model load was 381 ms on that run. |
| Zipformer INT8 high-memory beam (`threads=6`, `max_active_paths=8`) | 5 | 93 | 5.4% | No corpus accuracy gain; warm replay was about 0.15–0.21× real time on the same AVD. |
| Vosk `vosk-model-small-en-us-0.15` | 11 | 93 | 11.8% | Compact fallback. |
| Vosk `vosk-model-en-us-0.22-lgraph` | 7 | 93 | 7.5% | Better raw artist spelling than Zipformer on this corpus; retained as the robustness-oriented alternative. |
| Whisper `base.en-q5_1` | — | — | — | About 0.21× real time on the Mac CPU, but misrecognized all three artist samples and corrupted one coordinate; rejected as a 4 GB fallback. |
| Whisper `small.en-q5_1` | — | — | — | Semantically correct intent on all twelve files; rendered `Prydz` as `Prid's` and emitted numeric coordinates. Raw word WER is not comparable without a number normalization policy. |

Zipformer produced exact text for all `what time is it` and `take me home`
recordings. Its three music hypotheses were `PRADE'S`/`PRIDE'S`; the generic
context resolver maps those and the Whisper `Prid's` spelling back to arbitrary
catalog metadata such as `Eric Prydz Opus` (see
`RecognitionContextIndexTest`). Two coordinate samples rendered `twenty one`
where the recording reference was `two one`; the command still retains the
complete coordinate phrase and the existing navigation parser accepts it.

On the running API 36 arm64 Caramel product AVD, the production assistant held
363 context phrases at approximately 287 MiB PSS / 383 MiB RSS after the
Zipformer model was warm. This is an emulator measurement, not a substitute
for the missing 4 GB Pi device run, but it is consistent with the earlier Pi
RSS measurements recorded in the README.

Whisper was run with `whisper.cpp` 1.9.2 on an Apple M4 Max. The q5_1 model
processed 39.76 seconds of audio in 24.47 seconds with CPU-only inference and
reached a 626,556,928-byte peak resident set on the Mac. The earlier Pi
measurement recorded in the README was approximately 0.98× real time. This is
not sufficient evidence to replace the always-on/push-to-talk 4 GB primary;
Whisper remains a candidate for an optional second pass or a higher-memory
variant.

## Benchmark-only provenance

The Whisper model was downloaded temporarily from:

`https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.en-q5_1.bin`

* SHA-256: `bfdff4894dcb76bbf647d56263ea2a96645423f1669176f4844a1bf8e478ad30`
* Size: 190,098,681 bytes
* Upstream OpenAI Whisper license: MIT —
  `https://github.com/openai/whisper/blob/main/LICENSE`
* Host `whisper.cpp` license: MIT —
  `https://github.com/ggml-org/whisper.cpp/blob/master/LICENSE`

The rejected `base.en-q5_1` comparison used the same upstream location with:

* SHA-256: `4baf70dd0d7c4247ba2b81fafd9c01005ac77c2f9ef064e00dcf195d0e2fdd2f`
* Size: 59,654,449 bytes

The model is not copied into the APK, AOSP product, or this repository. The
shipped Zipformer and Vosk sources, archives, patches, hashes, and licenses
remain recorded in `provenance/SOURCES.lock` and `LICENSES/`.
