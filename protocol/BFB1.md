# BFB1 v1

All integers are unsigned little-endian. The AFL2 recovered container is directly BFB1 (not wrapped in DCF2). Compression flags must be zero.

| Offset | Bytes | Field |
|---|---:|---|
| 0 | 4 | ASCII BFB1 |
| 4 | 1 | version = 1 |
| 5 | 1 | flags = 0 |
| 6 | 2 | header_length = 132 + filename_length |
| 8 | 16 | batch_id |
| 24 | 4 | session_salt |
| 28 | 4 | segment_index |
| 32 | 4 | segment_count |
| 36 | 4 | nominal_segment_size |
| 40 | 8 | original_file_size |
| 48 | 8 | segment_offset |
| 56 | 4 | segment_data_length |
| 60 | 4 | reserved = 0 |
| 64 | 32 | whole_file_sha256 |
| 96 | 32 | segment_sha256 |
| 128 | 2 | filename_length |
| 130 | 2 | reserved = 0 |
| 132 | N | valid UTF-8 basename, 1..240 bytes |
| 132+N | M | raw segment data |

Batch ID: first 16 SHA-256 bytes of ASCII `OptiFerry-BFB1-v1` || LE64(file_size) || whole SHA-256 || filename UTF-8.

Session material: ASCII `OptiFerry-AFL2-session-v1` || batch_id || LE32(salt) || LE32(segment_index).
The requested 32-bit session ID is LE32(first four SHA-256 bytes). Actual AFL2 serializes only LE16. This implementation checks the serialized IDs for nonzero/uniqueness, increasing salt from zero. The LT encoder is seeded with the serialized 16-bit ID to agree with the stock receiver.

The sender limits batches to 1536 segments and salt search to 4,000,000 attempts. Exceeding either limit fails explicitly and asks for larger logical segments. This is a documented V1 restriction arising from the actual AFL2 ID width.

Segments are 1..32 MiB nominally, default 8 MiB. Count = max(1, ceil(file_size/segment_size)). Offset = index × nominal size. Each segment length equals min(nominal_size, file_size-offset). Empty files use one empty-data, nonempty-header segment.

Receivers reject unknown version/flags, nonzero reserved fields, malformed UTF-8, control characters, slash/backslash, `..`, bad lengths, inconsistent metadata, invalid deterministic batch ID, overflow, and bad SHA-256. Sizes above signed 64-bit range are rejected.

AFL2 systematic sequences use bit 31 set. Repair sequences have bit 31 clear. Pass p (zero-based) starts repair sequences at p × (ceil(K×factor)-K), modulo 2^31; this changes later optical attempts without changing AFL2 wire behavior.

Durability order: segment bytes → fsync output → write journal temporary → fsync → atomic rename → fsync directory. The completion bitmap is never persisted before the corresponding segment data. Whole-file hash verification precedes MediaStore publication.
