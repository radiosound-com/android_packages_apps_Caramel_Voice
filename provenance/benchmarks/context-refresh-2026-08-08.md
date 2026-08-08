# Dynamic context refresh AVD test — 2026-08-08

This test validates the generic Android context path; it does not redistribute
private media or navigation databases. The target was the disposable Caramel
AOSP 16 arm64 AVD (`emulator-5570`), active user 10, running the assistant
prebuilt with SHA-256
`3f2278fc332d72ade8c5e4c8935e9d6d16494931cd700a2f323f4960a94e3aa3`.

`READ_MEDIA_AUDIO` was granted manually on this disposable AVD because its
pre-created user 10 does not exercise the product's fresh-user default-
permission provisioning. The physical Pi verification remains required.

## Procedure and result

1. Started the assistant without restarting after catalog changes. It logged
   registration of the standard MediaStore audio and playlist observer.
2. Inserted one synthetic MediaStore audio row for `Eric Prydz Opus` by
   `Eric Prydz`. The observer debounced the edit and logged
   `Indexed 1 MediaStore audio entities`; Zipformer rebuilt its context graph
   from the built-in set to the catalog-inclusive set.
3. Played generated speech for `play Eric Prydz Opus` through the AVD host
   audio input. Zipformer emitted a noisy final hypothesis (`PLAY ERIC PRYDZ
   OPAS`), but the generic token/phonetic resolver selected the catalog's
   canonical `Eric Prydz Opus` value. The media controller received exactly
   that string and returned `PLAYER_NOT_FOUND`, as expected because the AVD
   has no compatible user media player.
4. Deleted only the synthetic row. The observer re-indexed zero MediaStore
   audio entities, and the query confirmed that no test row remained.

The test uses only standard MediaStore metadata and the existing generic
`MediaController`/`MediaBrowserService` interfaces. No Spotify, artist, title,
playlist, OsmAnd, or other catalog-specific code was added.
