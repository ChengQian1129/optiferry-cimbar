import fs from "node:fs";
import vm from "node:vm";

const [exportPath, expectedLayoutText = "2", sourcePath] = process.argv.slice(2);
if (!exportPath) {
  console.error(
    "Usage: node verify-export.mjs EXPORT.frames [layoutCodes] [SOURCE]",
  );
  process.exit(2);
}

const expectedLayout = Number(expectedLayoutText);
if (![1, 2, 4].includes(expectedLayout))
  throw new Error("layoutCodes must be 1, 2 or 4");

const context = { TextEncoder, TextDecoder };
const protocolPath = new URL(
  "../../upstream/desktop-receiver/highspeed-protocol.js",
  import.meta.url,
);
vm.runInNewContext(fs.readFileSync(protocolPath, "utf8"), context);
const highSpeed = context.AirFerryHighSpeed;
const raw = fs.readFileSync(exportPath);
const frames = [];
let offset = 0;
while (offset < raw.length) {
  if (offset + 4 > raw.length) throw new Error("truncated frame length");
  const length = raw.readUInt32LE(offset);
  offset += 4;
  if (length < highSpeed.HEADER_LEN + 1 || offset + length > raw.length)
    throw new Error("invalid exported frame length");
  frames.push(
    new Uint8Array(raw.buffer, raw.byteOffset + offset, length),
  );
  offset += length;
}
if (frames.length === 0 || offset !== raw.length)
  throw new Error("export does not contain complete frames");

const parsed = frames.map((frame) => highSpeed.parseFrame(frame));
if (
  parsed.some(
    (item) =>
      !item ||
      item.header.layoutCodes !== expectedLayout ||
      item.header.blockLen !== 2048 ||
      item.header.k === 0,
  )
)
  throw new Error("exported frame header/layout mismatch");

const first = parsed[0].header;
const identity = highSpeed.streamIdentity(first);
if (parsed.some((item) => highSpeed.streamIdentity(item.header) !== identity))
  throw new Error("exported frames contain multiple streams");

const decoder = new highSpeed.LTDecoder(
  first.k,
  first.blockLen,
  first.sessionId,
  first.totalLen,
);
for (const item of parsed) decoder.addFrame(item.header.seq, item.block);
const recovered = decoder.assemble();
if (!recovered || !decoder.isComplete)
  throw new Error(
    `LT recovery incomplete: ${decoder.solvedCount}/${first.k} blocks`,
  );
if (
  recovered[0] !== 0x42 ||
  recovered[1] !== 0x46 ||
  recovered[2] !== 0x42 ||
  recovered[3] !== 0x31
)
  throw new Error("recovered payload is not a BFB1 envelope");

if (sourcePath) {
  const headerLength = recovered[6] | (recovered[7] << 8);
  const dataLength =
    recovered[56] |
    (recovered[57] << 8) |
    (recovered[58] << 16) |
    (recovered[59] << 24);
  const source = fs.readFileSync(sourcePath);
  if (dataLength !== source.length)
    throw new Error(`BFB1 data length mismatch: ${dataLength}/${source.length}`);
  const data = recovered.subarray(headerLength, headerLength + dataLength);
  if (data.length !== source.length || !data.every((value, i) => value === source[i]))
    throw new Error("LT-recovered BFB1 data differs from source");
}

console.log(
  `PASS export: ${frames.length} frames, layout=${first.layoutCodes}, ` +
    `block=${first.blockLen}B, k=${first.k}, ` +
    `LT solved=${decoder.solvedCount}/${first.k}, recovered=${recovered.length}B`,
);
