# Software verification — 2026-09-09

## Executed and passed

- Locked BeamFerry commit `44d8c9b64c920183f5f250a6d9deb1978a385004`: eight npm test suites, unchanged Android APK build and upstream JVM tests.
- Native AFL2: all 136 official-sender golden frames match byte-for-byte, including single/quad, 2048/2933 blocks, zero through 1 MiB payloads, repair and systematic sequences.
- Installed `qsend --selftest`: embedded vectors, independent GF(2) LT repair recovery under erasure/reordering, SHA-256, BFB1, deterministic IDs, V33 binary QR and 4K geometry.
- CTest: two test executables, no failures. C++ Release build emits no warnings after formatting.
- Android full JVM suite: 57 tests, zero failures, zero skipped when both integration fixtures are provided.
- C++ frames → original Kotlin LT assembler → BFB1 parser: 100 MiB, 13 logical segments, exact whole hash at 0/5/10/20% erasure with duplicates and reordering; sufficient-repair fixture uses factor 2.5.
- Default factor 1.65: 100 MiB, rotating repair passes, 20% erasure, simulated receiver restart with durable bitmap/output; final SHA matches within the eight-pass fixture.
- Journal tests simulate death after temporary-file fsync and after atomic rename, verify old/new committed state recovery, reject oversized metadata, and retain eight independent journals.
- Actual WSLg full-screen framebuffer: 3840×2160; four V33 codes at 6 px/module. Independent jsQR decoding of the saved framebuffer matches all four native 2068-byte frames exactly. Every QR pixel is binary black/white and each 6×6 module is constant.
- WSLg 1 MiB finite transmission: native SDL X11/XWayland window, no browser. Windowed presentation near 60 Hz; full-screen measured approximately 57.4 Hz after text caching (p50 16.93 ms, p95 23.23 ms). These are app/compositor timings, not physical scanout or optical reception measurements.
- Installer executed successfully on the available Ubuntu 24.04 WSL2. A new login shell resolves `qsend` from `/root/.local/bin`, and selftest passes.
- APK signature verification passes; package is `org.optiferry.receiver`, minimum API 29, target API 35.

## Scope of remaining validation

No OPPO Find N5 or Android emulator/instrumentation execution was available. MediaStore Direct/fallback writes, collision behavior, camera runtime, Android OS process killing, device RSS, segment-boundary latency, 100/512 MiB zero-touch optical transfer and useful throughput have **not** been verified on-device.
Journal and restart tests use the real shared journal implementation plus a JVM random-access file fixture, not a mocked complete MediaStore provider. They do not establish device-provider correctness.

The original 32-bit session requirement conflicts with the 16-bit upstream wire field. The compatibility amendment and batch-count bound are explicit in UPSTREAM_COMPAT.md. This is a usable first-build delivery, not a declaration that every original P0 hardware acceptance item has passed.

Additional memory check: 512 MiB sparse source, 64 default 8 MiB segments, native frame export to /dev/null completed with maximum RSS 30,688 KiB. This measures sender preparation/encoding, not Android or GPU/window RSS.
