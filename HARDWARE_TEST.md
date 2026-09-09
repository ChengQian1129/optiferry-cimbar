# Target hardware acceptance

Real-device optical performance remains to be validated on the target OPPO Find N5 and 27" 4K 60Hz monitor.

## Setup

1. Keep the stock BeamFerry app installed; install `dist/OptiFerry-debug.apk` separately.
2. Set the monitor to 3840×2160, 60 Hz. Run `qsend --diagnose`; then full-screen transmission must report 6 px/module.
3. Grant the phone camera permission and press Receive once. Keep all four QR codes visible, avoid reflections, and use stable framing.

## Files and integrity

Generate random 1, 20, 100, and 512 MiB files, for example:

```bash
dd if=/dev/urandom of=512m.bin bs=1M count=512 status=progress
sha256sum 512m.bin
qsend 512m.bin
```

Record the source hash. Confirm the receiver reports SHA-256 verified and the saved file is in Download/OptiFerry. Independently hash the saved file after testing if possible.

For 100/512 MiB, after Start there must be zero Save / Continue / Next / Confirm / Choose directory actions. Any such action is a P0 failure.

## Recovery and storage

- Kill the app after several completed segments. Reopen and start scanning the same sender. Completed progress must survive and the final hash must match.
- Stop and restart qsend with the same source and segment size. Receiver must resume the same batch.
- Hide the camera during one segment, then reveal it during a later segment. Later segments must continue; a subsequent pass must fill the missing one.
- Repeat a completed batch: no duplicate final file.
- Receive a different file with the same basename: an existing unrelated file must not be overwritten.
- Test at least eight incomplete batches and switching among them.
- Test insufficient storage: an actionable storage error must appear, with no corrupted published file.
- Verify the displayed Direct storage mode. Staging fallback requires extra temporary space and needs separate device/provider testing.
- Observe segment boundaries: no Activity/CameraX restart, permission prompt, or geometry reset caused by a normal commit. Record stalls, target <500 ms.

## Performance

Use 20 MiB, same framing, four codes, 2068-byte frames, 30 symbol sets/s. Run stock sender + stock receiver three times, then qsend + OptiFerry three times.
Compare median **completed useful file bytes / elapsed time**, not QR bytes emitted. Target is at least 90% of stock median; 180 KiB/s is a stretch goal.
Record camera FPS, QR/frame, calibrated slots, app RSS, elapsed time, useful rate, retry passes and sender presentation metrics.

The current development environment has measured approximately 54–60 app presents/s depending on window size/driver; this does not establish target-phone throughput or physical scanout synchronization.
