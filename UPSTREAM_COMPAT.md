# Upstream compatibility decisions

Source: locked upstream commit in UPSTREAM_BASELINE.md.

| Topic | Actual upstream behavior | Decision |
|---|---|---|
| Session ID | packFrame writes uint16 at offset 2; Kotlin reads uint16. The requested 32-bit ID cannot fit. | Requires specification amendment before session mapping implementation. Preserve AFL2 bytes. |
| Systematic sequence | High bit 0x80000000 selects source block; ordinary 0.. are repair sequences. | Preserve actual sequence behavior, including systematic flag magic 0x0f for quad. |
| Empty payload | LT encoder and packFrame produce bytes, but parseFrame rejects totalLen=0; packFile rejects empty files. | Include rejected zero-length raw vectors; empty BFB1 transfer will have a nonempty envelope. |
| AFL2 recovered container | Stock Android unpacks DCF2 immediately after FNV validation. | BFB1 interception must happen before DCF2 parsing, not only in Activity completion UI. |
| Non-word-aligned blocks | LTEncoder pads each block to ceil(blockLen/4) words internally, transmits blockLen bytes. | Preserve byte result for 2933-byte test vectors. |

Golden vector payload pattern: byte[i] = (i*31+17)&255; session=0x1234.
The index includes parser acceptance, so zero-payload rejection is explicit.

## Implemented ID compatibility amendment

The 32-bit hash derivation is retained internally. AFL2 serialization and the LT seed use its low 16 bits, exactly as required by the actual header. Salt selection now checks **wire IDs** as well as nonzero values. To bound preparation, maximum batch count is 1536 and maximum search attempts is 4,000,000. This differs from the original 32-bit-only specification and is surfaced in README and CLI errors.

Completed-frame skipping uses the full AFL2 identity (including container size and FNV), not the 16-bit ID alone. This avoids false skips when different batches share a wire ID. Durable records retain the identity for every completed segment.

Later batch passes rotate the repair sequence range. Individual frame encoding is unchanged, and all upstream vectors remain byte-for-byte identical.

## Milestone sequencing

Upstream JS tests ran before native implementation. The untouched upstream Android APK and JVM tests passed before OptiFerry artifact verification. Native protocol work overlapped Android dependency download/troubleshooting; it did not wait for that external build to finish. No upstream source was changed or pulled again.
