#!/usr/bin/env python3
import hashlib
import importlib.util
import os
from pathlib import Path
import json
import tempfile
import unittest
from unittest import mock


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts" / "cimbar-send.py"
spec = importlib.util.spec_from_file_location("cimbar_send", SCRIPT)
if spec is None or spec.loader is None:
    raise RuntimeError(f"cannot load {SCRIPT}")
cimbar_send = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cimbar_send)


class CimbarSendTests(unittest.TestCase):
    def test_parse_size(self):
        self.assertEqual(cimbar_send.parse_size("24MiB"), 24 * 1024 * 1024)
        self.assertEqual(cimbar_send.parse_size("2M"), 2 * 1024 * 1024)
        self.assertEqual(cimbar_send.parse_size("1024"), 1024)

    def test_max_chunk_preserves_two_fountain_block_passes(self):
        frames = cimbar_send.minimum_redundancy_frames(cimbar_send.MAX_CHUNK_BYTES)
        self.assertLessEqual(
            frames * cimbar_send.MODE_B_BLOCKS_PER_FRAME,
            cimbar_send.FOUNTAIN_BLOCK_ID_LIMIT,
        )
        self.assertGreater(
            cimbar_send.minimum_redundancy_frames(cimbar_send.MAX_CHUNK_BYTES + 1024 * 1024)
            * cimbar_send.MODE_B_BLOCKS_PER_FRAME,
            cimbar_send.FOUNTAIN_BLOCK_ID_LIMIT,
        )

    def test_preparation_checks_free_space_before_writing_chunks(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "sample.bin"
            source.write_bytes(b"payload")
            output = root / "chunks"
            with mock.patch.object(cimbar_send.shutil, "disk_usage", return_value=mock.Mock(free=0)):
                with self.assertRaisesRegex(OSError, "insufficient disk space"):
                    cimbar_send.build_chunks(
                        source,
                        output,
                        4,
                        hashlib.sha256(source.read_bytes()).hexdigest(),
                        cimbar_send.validate_name(source),
                    )
            self.assertFalse(any(output.glob("*.part*.bin")))

    def test_builds_cfc_compatible_chunks(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "sample.zip"
            payload = bytes(range(251)) * 50
            source.write_bytes(payload)
            digest = hashlib.sha256(payload).hexdigest()
            name = cimbar_send.validate_name(source)
            output = root / "chunks"

            chunks, count = cimbar_send.build_chunks(
                source, output, 10000, digest, name
            )

            self.assertEqual(count, 2)
            self.assertEqual(len(chunks), count)
            for index, chunk in enumerate(chunks):
                path = output / str(chunk["file"])
                raw = path.read_bytes()
                self.assertEqual(raw, payload[index * 10000 : (index + 1) * 10000])
                self.assertEqual(hashlib.sha256(raw).hexdigest(), chunk["sha256"])
                self.assertEqual(len(raw), int(chunk["size"]))
                self.assertEqual(path.name, f"sample.part{index:02d}.bin")

    def test_manifest_shape(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "sample.tar.gz"
            payload = b"cimbar" * 4000
            source.write_bytes(payload)
            output = root / "chunks"
            digest = hashlib.sha256(payload).hexdigest()
            name = cimbar_send.validate_name(source)
            chunks, count = cimbar_send.build_chunks(source, output, 4096, digest, name)
            manifest = {
                "version": 1,
                "tool": "cimbar-bigfile",
                "filename": source.name,
                "total_size": len(payload),
                "sha256": digest,
                "chunk_size": 4096,
                "chunk_count": count,
                "encode_id_base": cimbar_send.ENCODE_ID_BASE,
                "chunks": [{k: c[k] for k in ("index", "size", "sha256")} for c in chunks],
            }
            parsed = json.loads(json.dumps(manifest))
            self.assertEqual(parsed["tool"], "cimbar-bigfile")
            self.assertEqual(parsed["chunks"][0]["index"], 0)
            self.assertTrue((output / "sample.tar.part00.bin").is_file())

    def test_default_transfer_id_is_repeatable(self):
        name = b"archive.zip"
        digest = "00" * 32
        first = cimbar_send.stable_transfer_id(10, 10000, digest, name)
        second = cimbar_send.stable_transfer_id(10, 10000, digest, name)
        self.assertEqual(first, second)

    def test_find_sender_prefers_managed_binary_over_path_binary(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            managed = root / ".local/libexec/optiferry-cimbar/cimbar_send"
            system = root / "usr/local/bin/cimbar_send"
            managed.parent.mkdir(parents=True)
            system.parent.mkdir(parents=True)
            managed.write_bytes(b"managed")
            system.write_bytes(b"system")

            def which(candidate):
                return str(system) if candidate == "cimbar_send" else None

            with (
                mock.patch.object(cimbar_send.Path, "home", return_value=root),
                mock.patch.dict(os.environ, {"CIMBAR_SEND": ""}),
                mock.patch.object(cimbar_send.shutil, "which", side_effect=which),
                mock.patch.object(cimbar_send.os, "access", return_value=True),
            ):
                self.assertEqual(str(managed), cimbar_send.find_sender(None))

    def test_multichunk_send_refuses_unmanaged_path_binary(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            system = root / "usr/local/bin/cimbar_send"
            system.parent.mkdir(parents=True)
            system.write_bytes(b"old-system-sender")

            def which(candidate):
                return str(system) if candidate == "cimbar_send" else None

            with (
                mock.patch.object(cimbar_send.Path, "home", return_value=root),
                mock.patch.dict(os.environ, {"CIMBAR_SEND": ""}),
                mock.patch.object(cimbar_send.shutil, "which", side_effect=which),
                mock.patch.object(cimbar_send.os, "access", return_value=True),
            ):
                with self.assertRaisesRegex(RuntimeError, "requires a cadence-v2 cimbar_send"):
                    cimbar_send.find_sender(None, require_managed=True)

    def test_multichunk_send_verifies_managed_binary_hash(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            managed = root / ".local/libexec/optiferry-cimbar/cimbar_send"
            managed.parent.mkdir(parents=True)
            managed.write_bytes(b"patched-sender")
            managed.chmod(0o755)
            record = managed.with_name("cimbar_send.sha256")
            record.write_text(hashlib.sha256(b"patched-sender").hexdigest() + "  cimbar_send\n")

            with (
                mock.patch.object(cimbar_send.Path, "home", return_value=root),
                mock.patch.dict(os.environ, {"CIMBAR_SEND": ""}),
                mock.patch.object(cimbar_send.shutil, "which", return_value=None),
                mock.patch.object(
                    cimbar_send.subprocess,
                    "run",
                    return_value=mock.Mock(
                        returncode=0,
                        stdout=f"{cimbar_send.MANAGED_SENDER_VERSION}\n",
                        stderr="",
                    ),
                ),
            ):
                self.assertEqual(
                    str(managed), cimbar_send.find_sender(None, require_managed=True)
                )
                with mock.patch.object(
                    cimbar_send.subprocess,
                    "run",
                    return_value=mock.Mock(returncode=0, stdout="stale-cadence\n", stderr=""),
                ):
                    with self.assertRaisesRegex(RuntimeError, "install record or cadence version"):
                        cimbar_send.find_sender(None, require_managed=True)
                managed.write_bytes(b"old-sender")
                with self.assertRaisesRegex(RuntimeError, "does not match its install record"):
                    cimbar_send.find_sender(None, require_managed=True)

    def test_explicit_sender_must_report_cadence_version_for_multichunk_send(self):
        with tempfile.TemporaryDirectory() as directory:
            explicit = Path(directory) / "custom-cimbar_send"
            explicit.write_bytes(b"custom-sender")

            with (
                mock.patch.object(cimbar_send.os, "access", return_value=True),
                mock.patch.object(
                    cimbar_send.subprocess,
                    "run",
                    return_value=mock.Mock(
                        returncode=0,
                        stdout=f"{cimbar_send.MANAGED_SENDER_VERSION}\n",
                        stderr="",
                    ),
                ),
            ):
                self.assertEqual(
                    str(explicit),
                    cimbar_send.find_sender(str(explicit), require_managed=True),
                )

            with (
                mock.patch.object(cimbar_send.os, "access", return_value=True),
                mock.patch.object(
                    cimbar_send.subprocess,
                    "run",
                    return_value=mock.Mock(returncode=0, stdout="stale-cadence\n", stderr=""),
                ),
            ):
                with self.assertRaisesRegex(RuntimeError, "does not report OPTIFERRY_CIMBAR_CADENCE_V2"):
                    cimbar_send.find_sender(str(explicit), require_managed=True)

    def test_prepare_only_does_not_need_a_sender_or_display(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "sample.bin"
            source.write_bytes(b"payload")
            output = root / "chunks"
            self.assertEqual(cimbar_send.main([str(source), "--output", str(output), "--prepare-only"]), 0)
            self.assertTrue((output / "manifest.json").is_file())

    def test_single_chunk_transfer_requires_the_cadence_managed_sender(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "sample.bin"
            source.write_bytes(b"payload")
            output = root / "chunks"
            with (
                mock.patch.object(cimbar_send, "find_sender", return_value="/managed/cimbar_send") as find_sender,
                mock.patch.object(cimbar_send, "require_wslg"),
                mock.patch.object(cimbar_send.subprocess, "run", return_value=mock.Mock(returncode=0)),
            ):
                self.assertEqual(
                    cimbar_send.main([str(source), "--output", str(output)]),
                    0,
                )
            self.assertTrue(find_sender.call_args.kwargs["require_managed"])

    def test_empty_input_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "empty.bin"
            source.write_bytes(b"")
            with self.assertRaisesRegex(ValueError, "empty"):
                cimbar_send.main([str(source), "--prepare-only"])

    def test_manifest_filename_is_reserved(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "Manifest.JSON"
            source.write_bytes(b"payload")
            with self.assertRaisesRegex(ValueError, "reserved"):
                cimbar_send.main([str(source), "--prepare-only"])

    def test_receiver_rejects_modes_not_supported_by_apk(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "sample.bin"
            source.write_bytes(b"payload")
            with self.assertRaisesRegex(ValueError, "固定使用 Mode B"):
                cimbar_send.main([str(source), "--mode", "4C"])


if __name__ == "__main__":
    unittest.main()
