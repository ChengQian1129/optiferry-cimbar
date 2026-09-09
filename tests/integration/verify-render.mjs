import fs from 'node:fs';
import vm from 'node:vm';
const module={exports:{}};
vm.runInNewContext(fs.readFileSync(new URL('../../upstream/vendor/jsQR.js',import.meta.url),'utf8'),{module,exports:module.exports});
const decode=module.exports;
const bmp=fs.readFileSync(process.argv[2]),expected=fs.readFileSync(process.argv[3]);
const start=bmp.readUInt32LE(10),width=bmp.readInt32LE(18),rawHeight=bmp.readInt32LE(22),height=Math.abs(rawHeight),depth=bmp.readUInt16LE(28);
if(width!==3840||height!==2160||depth!==32)throw Error('Expected 3840x2160 32-bit framebuffer');
const stride=width*4,scale=6,side=157*scale,gap=20,left=(width-2*side-gap)/2,top=100+(height-100-2*side-gap)/2;
let frameOffset=0;
for(let slot=0;slot<4;slot++){
 const pixels=new Uint8ClampedArray(side*side*4),x0=left+(slot%2)*(side+gap),y0=top+Math.floor(slot/2)*(side+gap);
 for(let y=0;y<side;y++)for(let x=0;x<side;x++){
  const row=rawHeight>0?height-1-(y+y0):y+y0,p=start+row*stride+(x+x0)*4,i=(y*side+x)*4;
  pixels[i]=bmp[p+2];pixels[i+1]=bmp[p+1];pixels[i+2]=bmp[p];pixels[i+3]=255;
  if(pixels[i]!==0&&pixels[i]!==255)throw Error('Non-binary QR pixel');
  const base=(Math.floor(y/scale)*scale*side+Math.floor(x/scale)*scale)*4;
  if(pixels[i]!==pixels[base])throw Error('Non-integer module edges');
 }
 const result=decode(pixels,side,side,{inversionAttempts:'dontInvert'});
 if(!result||result.version!==33||result.binaryData.length!==2068)throw Error(`QR ${slot} did not decode as V33 / 2068 bytes`);
 const length=expected.readUInt32LE(frameOffset);frameOffset+=4;
 if(!Buffer.from(result.binaryData).equals(expected.subarray(frameOffset,frameOffset+length)))throw Error(`QR ${slot} payload differs from native frame`);
 frameOffset+=length;
}
console.log('PASS: 4K framebuffer; all four V33 codes decode to exact native 2068-byte frames; 6px modules, binary pixels');
