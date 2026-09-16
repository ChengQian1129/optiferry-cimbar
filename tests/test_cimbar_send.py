#!/usr/bin/env python3
import hashlib
import importlib.util
from pathlib import Path
import struct
import tempfile
import unittest


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

    def test_builds_verifiable_opm1_chunks(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "sample.zip"
            payload = bytes(range(251)) * 50
            source.write_bytes(payload)
            digest = hashlib.sha256(payload).hexdigest()
            name = cimbar_send.validate_name(source)
            transfer_id = cimbar_send.stable_transfer_id(
                len(payload), 10000, digest, name
            )
            output = root / "chunks"

            chunks, count = cimbar_send.build_chunks(
                source, output, 10000, transfer_id, digest, name
            )

            self.assertEqual(count, 2)
            self.assertEqual(len(chunks), count)
            for index, chunk in enumerate(chunks):
                path = output / str(chunk["file"])
                raw = path.read_bytes()
                header = struct.unpack("<4sBBH16sIIQQ32s32sHH", raw[:116])
                self.assertEqual(header[0], b"OPM1")
                self.assertEqual(header[1], 1)
                self.assertEqual(header[4], transfer_id)
                self.assertEqual(header[5], index)
                self.assertEqual(header[6], count)
                self.assertEqual(header[7], len(payload))
                self.assertEqual(header[8], 10000)
                self.assertEqual(header[9], bytes.fromhex(digest))
                filename_length = header[11]
                self.assertEqual(filename_length, len(name))
                self.assertEqual(raw[116 : 116 + filename_length], name)
                actual_payload = raw[header[3] :]
                self.assertEqual(hashlib.sha256(actual_payload).digest(), header[10])
                self.assertEqual(
                    len(actual_payload), int(chunk["bytes"])
                )

    def test_default_transfer_id_is_repeatable(self):
        name = b"archive.zip"
        digest = "00" * 32
        first = cimbar_send.stable_transfer_id(10, 10000, digest, name)
        second = cimbar_send.stable_transfer_id(10, 10000, digest, name)
        self.assertEqual(first, second)


if __name__ == "__main__":
    unittest.main()
