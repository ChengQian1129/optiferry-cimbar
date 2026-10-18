#!/usr/bin/env python3
"""Prepare and play libcimbar streams accepted by CFC/OptiFerry.

Large files use the cimbar-bigfile manifest plus independent fountain streams.
The native sender advances its encode_id for every input, while the Android
receiver keeps each stream in its own decoder bucket.
"""

from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shlex
import shutil
import subprocess
import sys


DEFAULT_CHUNK_BYTES = 10 * 1024 * 1024
MAX_CHUNK_BYTES = 19 * 1024 * 1024
MAX_ORIGINAL_BYTES = 120 * MAX_CHUNK_BYTES
MAX_CHUNKS = 120
RECEIVER_MODE = "B"
# libcimbar starts at 109 and increments before the first input is encoded.
ENCODE_ID_BASE = 110
COPY_BYTES = 1024 * 1024
# Mode B carries 12 fountain blocks per frame. Its 625-byte fountain block
# includes a 6-byte header, leaving 619 bytes for stream data.
MODE_B_BLOCKS_PER_FRAME = 12
MODE_B_BLOCK_DATA_BYTES = 619
FOUNTAIN_BLOCK_ID_LIMIT = 1 << 16
MIN_BLOCK_REDUNDANCY = 2
ZSTD_STREAM_OVERHEAD_RESERVE = 256 * 1024
MANIFEST_SPACE_RESERVE = 64 * 1024
MANAGED_SENDER_VERSION = "OPTIFERRY_CIMBAR_CADENCE_V2"


def parse_size(value: str) -> int:
    match = re.fullmatch(r"\s*(\d+(?:\.\d+)?)\s*([kmgt]?i?b?)?\s*", value, re.I)
    if not match:
        raise argparse.ArgumentTypeError("size must look like 10M, 10MiB, or 10485760")
    number = float(match.group(1))
    suffix = (match.group(2) or "").lower()
    units = {
        "": 1, "b": 1,
        "k": 1024, "kb": 1024, "ki": 1024, "kib": 1024,
        "m": 1024**2, "mb": 1024**2, "mi": 1024**2, "mib": 1024**2,
        "g": 1024**3, "gb": 1024**3, "gi": 1024**3, "gib": 1024**3,
        "t": 1024**4, "tb": 1024**4, "ti": 1024**4, "tib": 1024**4,
    }
    result = int(number * units[suffix])
    if result <= 0:
        raise argparse.ArgumentTypeError("size must be positive")
    return result


def positive_int(value: str) -> int:
    try:
        number = int(value)
    except ValueError as error:
        raise argparse.ArgumentTypeError("must be an integer") from error
    if number <= 0:
        raise argparse.ArgumentTypeError("must be positive")
    return number


def parse_mode(value: str) -> str:
    modes = {"b": "B", "bm": "Bm", "bu": "Bu", "4c": "4C"}
    try:
        return modes[value.lower()]
    except KeyError as error:
        raise argparse.ArgumentTypeError("mode must be B, Bm, Bu, or 4C") from error


def validate_name(path: Path) -> bytes:
    name = path.name
    if not name or name in {".", ".."}:
        raise ValueError("input filename is empty or invalid")
    if name.casefold() == "manifest.json":
        raise ValueError("manifest.json is reserved by the Cimbar transfer format; rename the input file")
    if any(ord(char) < 0x20 or ord(char) == 0x7F for char in name):
        raise ValueError("input filename contains an unsupported control character")
    encoded = name.encode("utf-8")
    if len(encoded) > 255:
        raise ValueError("input filename is longer than 255 UTF-8 bytes")
    return encoded


def file_fingerprint(path: Path) -> tuple[int, int]:
    stat = path.stat()
    return stat.st_size, stat.st_mtime_ns


def ensure_unchanged(path: Path, before: tuple[int, int]) -> None:
    if file_fingerprint(path) != before:
        raise ValueError("input changed during transfer preparation; retry with a stable file")


def minimum_redundancy_frames(raw_chunk_bytes: int) -> int:
    encoded_upper_bound = raw_chunk_bytes + ZSTD_STREAM_OVERHEAD_RESERVE
    required_blocks = encoded_upper_bound // MODE_B_BLOCK_DATA_BYTES + 1
    return (
        MIN_BLOCK_REDUNDANCY * required_blocks + MODE_B_BLOCKS_PER_FRAME - 1
    ) // MODE_B_BLOCKS_PER_FRAME


def select_chunk_size(file_size: int, requested_chunk_size: int | None = None) -> int:
    if requested_chunk_size is not None:
        if requested_chunk_size <= 0:
            raise ValueError("chunk size must be positive")
        if requested_chunk_size > MAX_CHUNK_BYTES:
            raise ValueError("chunk size must be <= 19MiB to preserve two fountain-block passes")
        return requested_chunk_size
    required = (file_size + MAX_CHUNKS - 1) // MAX_CHUNKS
    return max(DEFAULT_CHUNK_BYTES, min(required, MAX_CHUNK_BYTES))


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        while True:
            block = source.read(COPY_BYTES)
            if not block:
                break
            digest.update(block)
    return digest.hexdigest()


def stable_transfer_id(size: int, capacity: int, digest_hex: str, name: bytes) -> bytes:
    material = (
        b"cimbar-bigfile-v1\0"
        + size.to_bytes(8, "little")
        + capacity.to_bytes(8, "little")
        + bytes.fromhex(digest_hex)
        + name
    )
    return hashlib.sha256(material).digest()[:16]


def strip_extension(name: str) -> str:
    stem, dot, _ = name.rpartition(".")
    return stem if dot and stem else name


def build_chunks(
    source: Path,
    output_dir: Path,
    capacity: int,
    file_digest_hex: str,
    name: bytes,
) -> tuple[list[dict[str, object]], int]:
    size = source.stat().st_size
    count = max(1, (size + capacity - 1) // capacity)
    if size > MAX_ORIGINAL_BYTES or count > MAX_CHUNKS:
        raise ValueError("input exceeds the Cimbar 120-stream safety limit")

    output_dir.mkdir(parents=True, exist_ok=True)
    basename = strip_extension(name.decode("utf-8"))
    width = 2 if count < 100 else 3
    missing_bytes = 0
    existing_rewrite_bytes = 0
    for index in range(count):
        length = min(capacity, size - index * capacity) if size else 0
        filename = f"{basename}.part{index:0{width}d}.bin"
        output = output_dir / filename
        if output.is_file() and output.stat().st_size == length:
            existing_rewrite_bytes = max(existing_rewrite_bytes, length)
        else:
            missing_bytes += length
    required_space = missing_bytes + existing_rewrite_bytes + MANIFEST_SPACE_RESERVE
    free_space = shutil.disk_usage(output_dir).free
    if free_space < required_space:
        raise OSError(
            "insufficient disk space for Cimbar preparation: "
            f"need about {required_space} bytes, have {free_space} bytes free"
        )

    chunks: list[dict[str, object]] = []
    with source.open("rb") as source_file:
        for index in range(count):
            offset = index * capacity
            length = min(capacity, size - offset) if size else 0
            filename = f"{basename}.part{index:0{width}d}.bin"
            output = output_dir / filename
            temporary = output.with_name(output.name + ".part")
            digest = hashlib.sha256()
            copied = 0
            try:
                source_file.seek(offset)
                with temporary.open("wb") as target:
                    while copied < length:
                        block = source_file.read(min(COPY_BYTES, length - copied))
                        if not block:
                            raise OSError("input changed while writing chunk")
                        target.write(block)
                        digest.update(block)
                        copied += len(block)
                    target.flush()
                    os.fsync(target.fileno())
                os.replace(temporary, output)
            except Exception:
                try:
                    temporary.unlink()
                except FileNotFoundError:
                    pass
                raise
            chunks.append({
                "index": index,
                "file": filename,
                "size": length,
                "sha256": digest.hexdigest(),
            })
            print(f"prepared {index + 1}/{count}: {output}")

    if sha256_file(source) != file_digest_hex:
        raise ValueError("input changed while chunks were being written; retry with a stable file")
    return chunks, count


def safe_stem(name: str) -> str:
    value = re.sub(r"[^A-Za-z0-9._-]+", "_", name).strip("._")
    return value[:64] or "file"


def default_output(source: Path, transfer_id: bytes) -> Path:
    root = Path(
        os.environ.get(
            "CIMBAR_OUTPUT_ROOT",
            str(Path.home() / ".cache" / "optiferry-cimbar" / "transfers"),
        )
    ).expanduser()
    return root / f"{safe_stem(source.name)}-{transfer_id.hex()[:12]}"


def managed_sender_is_verified(sender: Path) -> bool:
    record = sender.with_name(sender.name + ".sha256")
    try:
        expected = record.read_text(encoding="ascii").strip().split()[0]
    except (OSError, IndexError):
        return False
    if re.fullmatch(r"[0-9a-f]{64}", expected) is None or sha256_file(sender) != expected:
        return False
    return sender_supports_cadence_v2(sender)


def sender_supports_cadence_v2(sender: Path) -> bool:
    try:
        version = subprocess.run(
            [str(sender), "--optiferry-version"],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            timeout=5,
            check=False,
        )
    except (OSError, subprocess.SubprocessError):
        return False
    return version.returncode == 0 and version.stdout.strip() == MANAGED_SENDER_VERSION


def find_sender(explicit: str | None, *, require_managed: bool = False) -> str:
    candidates: list[str] = []
    if explicit:
        candidates.append(explicit)
    if os.environ.get("CIMBAR_SEND"):
        candidates.append(os.environ["CIMBAR_SEND"])
    managed = Path.home() / ".local" / "libexec" / "optiferry-cimbar" / "cimbar_send"
    candidates.append(str(managed))
    manual_override = bool(explicit or os.environ.get("CIMBAR_SEND"))
    if not require_managed or manual_override:
        candidates.append(str(Path.home() / ".local" / "bin" / "cimbar_send"))
        from_path = shutil.which("cimbar_send")
        if from_path:
            candidates.append(from_path)
        candidates.append("/usr/local/bin/cimbar_send")

    seen: set[str] = set()
    for candidate in candidates:
        if not candidate or candidate in seen:
            continue
        seen.add(candidate)
        resolved = shutil.which(candidate) or candidate
        path = Path(resolved).expanduser()
        if path.is_file() and os.access(path, os.X_OK):
            if require_managed:
                is_managed = path.resolve() == managed.resolve()
                compatible = managed_sender_is_verified(managed) if is_managed else sender_supports_cadence_v2(path)
                if not compatible:
                    if is_managed:
                        raise RuntimeError("The managed cimbar_send does not match its install record or cadence version. Run scripts/install-cimbar-wsl.sh to rebuild it.")
                    raise RuntimeError(f"{path} does not report {MANAGED_SENDER_VERSION}; provide a compatible sender or run scripts/install-cimbar-wsl.sh.")
            return str(path)

    if require_managed and not manual_override:
        raise RuntimeError("Cimbar transfer requires a cadence-v2 cimbar_send. Run scripts/install-cimbar-wsl.sh, or provide --sender /path/to/cadence-v2/cimbar_send.")
    raise RuntimeError("cimbar_send was not found. Run scripts/install-cimbar-wsl.sh once, or set CIMBAR_SEND=/path/to/cimbar_send.")


def require_wslg() -> None:
    if not Path("/mnt/wslg").is_dir() or not (os.environ.get("DISPLAY") or os.environ.get("WAYLAND_DISPLAY")):
        raise RuntimeError("WSLg display was not detected. Run cimbar-send inside WSL2 with WSLg enabled. Use --prepare-only to prepare chunks without a display.")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Prepare and play CFC-compatible Cimbar streams.")
    parser.add_argument("input", type=Path, help="file to send")
    parser.add_argument("--chunk-size", type=parse_size, help="raw bytes per Cimbar stream (selected automatically; maximum: 19MiB)")
    parser.add_argument("--output", type=Path, help="chunk directory (default: ~/.cache/optiferry-cimbar/transfers/...)")
    parser.add_argument("--sender", help="cadence-v2 cimbar_send executable; otherwise auto-detected or CIMBAR_SEND is used")
    parser.add_argument("--fps", type=positive_int, default=15, help="sender FPS (default: 15)")
    parser.add_argument("--mode", type=parse_mode, default="B", help="B, Bm, Bu, or 4C")
    parser.add_argument("--padding", type=int, help="forward black padding to cimbar_send")
    parser.add_argument("--compression", type=int, help="forward zstd compression level")
    parser.add_argument("--new-transfer", action="store_true", help="use a new preparation directory instead of the repeatable one")
    parser.add_argument("--prepare-only", action="store_true", help="create and verify streams but do not open a sender window")
    parser.add_argument(
        "--confirm-cimbar-receiver",
        action="store_true",
        help="deprecated compatibility flag; OptiFerry handles Cimbar directly",
    )
    parser.add_argument("--cleanup", action="store_true", help="remove generated streams after sender exits successfully")
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    source = args.input.expanduser().resolve()
    if not source.is_file():
        raise ValueError(f"input is not a regular file: {source}")
    if args.padding is not None and args.padding < 0:
        raise ValueError("padding must be non-negative")
    if args.compression is not None and args.compression < 0:
        raise ValueError("compression must be non-negative")
    if not args.prepare_only and args.mode != RECEIVER_MODE:
        raise ValueError(
            f"OptiFerry 彩码接收端当前固定使用 Mode {RECEIVER_MODE}; "
            f"--mode {args.mode} 不能直接接收，请改用 --mode {RECEIVER_MODE}"
        )

    before = file_fingerprint(source)
    size = before[0]
    if size == 0:
        raise ValueError("input file is empty; libcimbar cannot transmit an empty input")
    if size > MAX_ORIGINAL_BYTES:
        raise ValueError("彩码单次最多支持约 2.2 GiB；这个文件超过当前接收端上限")
    chunk_size = select_chunk_size(size, args.chunk_size)
    chunk_count = (size + chunk_size - 1) // chunk_size
    if chunk_count > MAX_CHUNKS:
        raise ValueError("当前分片设置超过接收端 120 片上限；请去掉 --chunk-size 使用自动设置")
    name = validate_name(source)
    print(f"hashing {source} ({size} bytes)…")
    digest = sha256_file(source)
    ensure_unchanged(source, before)

    transfer_id = secrets.token_bytes(16) if args.new_transfer else stable_transfer_id(size, chunk_size, digest, name)
    output_dir = args.output.expanduser().resolve() if args.output else default_output(source, transfer_id)
    chunks, count = build_chunks(source, output_dir, chunk_size, digest, name)
    ensure_unchanged(source, before)

    manifest_path = output_dir / "manifest.json"
    manifest = {
        "version": 1,
        "tool": "cimbar-bigfile",
        "filename": source.name,
        "total_size": size,
        "sha256": digest,
        "chunk_size": chunk_size,
        "chunk_count": count,
        "encode_id_base": ENCODE_ID_BASE,
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "chunks": [
            {key: chunk[key] for key in ("index", "file", "size", "sha256")}
            for chunk in chunks
        ],
    }
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    print(f"transfer id: {transfer_id.hex()}")
    print(f"sha256: {digest}")
    print(f"chunk size: {chunk_size / (1024 * 1024):g} MiB ({'automatic' if args.chunk_size is None else 'manual'})")
    print(f"streams: manifest + {count} chunks")
    print(f"manifest: {manifest_path}")
    if args.prepare_only:
        print("prepared only; no sender window was opened")
        return 0

    sender = find_sender(args.sender, require_managed=True)
    require_wslg()
    command = [sender, "--fps", str(args.fps), "--mode", args.mode]
    if args.padding is not None:
        command.extend(["--padding", str(args.padding)])
    if args.compression is not None:
        command.extend(["--compression", str(args.compression)])
    command.append(str(manifest_path))
    command.extend(str(output_dir / str(chunk["file"])) for chunk in chunks)

    print("starting: " + shlex.join(command))
    print("cimbar_send loops manifest and all chunks; stop after OptiFerry reports SHA-256 verified")
    try:
        result = subprocess.run(command, check=False)
    except KeyboardInterrupt:
        return 130

    if result.returncode == 0 and args.cleanup:
        for path in [manifest_path, *(output_dir / str(chunk["file"]) for chunk in chunks)]:
            try:
                path.unlink()
            except FileNotFoundError:
                pass
    return result.returncode


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, RuntimeError, ValueError, KeyError) as error:
        print(f"cimbar-send: error: {error}", file=sys.stderr)
        raise SystemExit(2)
