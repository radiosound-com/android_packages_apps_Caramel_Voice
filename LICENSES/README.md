# License inventory

The binaries and source snapshots in this repository are kept together with
their applicable notices so a Caramel Vanilla distribution can ship a complete
provenance record.

* `GPL-3.0-only.txt`: GNU GPL v3, applicable to the eSpeak NG program.
* `Apache-2.0.txt` and `BSD-2-Clause.txt`: notices used by eSpeak NG portions.
* `Vosk-Apache-2.0.txt`: Vosk API/model notice.
* `JNA-LGPL-2.1-or-later.txt`, `LGPL-2.1.txt`, and `JNA-Apache-2.0.txt`: JNA's
  dual-license notice and texts. This build elects the Apache-2.0 option for
  the JNA dependency.

The exact source URLs, revisions, archive checksums, and binary checksums are
in `provenance/SOURCES.lock`. GPL source corresponding to the shipped eSpeak
APK is included as a pinned upstream source archive in
`provenance/sources/`.
