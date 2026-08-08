# License inventory

The binaries and source snapshots in this repository are kept together with
their applicable notices so a Caramel Vanilla distribution can ship a complete
provenance record.

* `GPL-3.0-only.txt`: GNU GPL v3, applicable to the eSpeak NG program.
* `Apache-2.0.txt` and `BSD-2-Clause.txt`: notices used by eSpeak NG portions.
* `Unicode-DFS-2015.txt`: Unicode UCD data notice included by eSpeak NG.
* `Vosk-Apache-2.0.txt`: Vosk API/model notice.
* `JNA-LGPL-2.1-or-later.txt`, `LGPL-2.1.txt`, and `JNA-Apache-2.0.txt`: JNA's
  dual-license notice and texts. This build elects the Apache-2.0 option for
  the JNA dependency.
* `Apache-2.0.txt`: also applies to sherpa-onnx, Kotlin stdlib, the selected
  streaming Zipformer model, and their retained source snapshots.
* `MIT.txt`: applies to the ONNX Runtime 1.27.0 shared library carried in the
  sherpa-onnx Android AAR.

The exact source URLs, revisions, archive checksums, and binary checksums are
in `provenance/SOURCES.lock`. GPL source corresponding to the shipped eSpeak
APK is included as a pinned upstream source archive in
`provenance/sources/`.

The assistant itself adds no new copyleft ASR dependency: sherpa-onnx,
Zipformer, and Kotlin are Apache-2.0, while ONNX Runtime is MIT. Their exact
source revisions and binary/model hashes are nevertheless retained for
reproducibility. The product build copies this notice set and `SOURCES.lock`
to `/product/etc/caramel_voice/` so the installed image carries the same
inventory as the public source repository.
