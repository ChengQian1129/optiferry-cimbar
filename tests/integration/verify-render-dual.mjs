import fs from 'node:fs';
import vm from 'node:vm';

const module = { exports: {} };
vm.runInNewContext(
  fs.readFileSync(new URL('../../upstream/vendor/jsQR.js', import.meta.url), 'utf8'),
  { module, exports: module.exports }
);
const decode = module.exports;
const bmp = fs.readFileSync(process.argv[2]);
const expected = fs.readFileSync(process.argv[3]);
const start = bmp.readUInt32LE(10);
const width = bmp.readInt32LE(18);
const rawHeight = bmp.readInt32LE(22);
const height = Math.abs(rawHeight);
const depth = bmp.readUInt16LE(28);
if (width !== 3840 || height !== 2160 || depth !== 32) {
  throw Error('Expected 3840x2160 32-bit framebuffer');
}

const stride = width * 4;
const scale = 11;
const side = 157 * scale;
const gap = 20;
const left = (width - 2 * side - gap) / 2;
const top = 100 + Math.floor((height - 100 - side) / 2);
let frameOffset = 0;
for (let slot = 0; slot < 2; slot++) {
  const pixels = new Uint8ClampedArray(side * side * 4);
  const x0 = left + slot * (side + gap);
  const y0 = top;
  for (let y = 0; y < side; y++) {
    for (let x = 0; x < side; x++) {
      const row = rawHeight > 0 ? height - 1 - (y + y0) : y + y0;
      const p = start + row * stride + (x + x0) * 4;
      const i = (y * side + x) * 4;
      pixels[i] = bmp[p + 2];
      pixels[i + 1] = bmp[p + 1];
      pixels[i + 2] = bmp[p];
      pixels[i + 3] = 255;
      if (pixels[i] !== 0 && pixels[i] !== 255) throw Error('Non-binary QR pixel');
      const base = (Math.floor(y / scale) * scale * side + Math.floor(x / scale) * scale) * 4;
      if (pixels[i] !== pixels[base]) throw Error('Non-integer module edges');
    }
  }
  const result = decode(pixels, side, side, { inversionAttempts: 'dontInvert' });
  if (!result || result.version !== 33 || result.binaryData.length !== 2068) {
    throw Error(`QR ${slot} did not decode as V33 / 2068 bytes`);
  }
  const length = expected.readUInt32LE(frameOffset);
  frameOffset += 4;
  if (!Buffer.from(result.binaryData).equals(expected.subarray(frameOffset, frameOffset + length))) {
    throw Error(`QR ${slot} payload differs from native frame`);
  }
  frameOffset += length;
}
console.log('PASS: dual 4K framebuffer; both enlarged horizontal V33 codes decode to exact native 2068-byte frames');
