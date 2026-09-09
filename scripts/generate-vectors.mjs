import fs from 'node:fs';
import '../upstream/highspeed-protocol.js';
const H=globalThis.AirFerryHighSpeed;
const vectors=[];
for(const size of [0,1,2048,2049,1048576]) for(const layout of [1,4]) for(const blockLen of [2048,2933]) {
 const payload=Uint8Array.from({length:size},(_,i)=>(i*31+17)&255);
 const sessionId=0x1234, enc=new H.LTEncoder(payload,blockLen,sessionId);
 for(const seq of new Set([0,1,enc.k-1,enc.k,enc.k+1,37,1009,0x80000000,(0x80000000+enc.k-1)>>>0])) {
  const frame=H.packFrame({sessionId,seq,k:enc.k,blockLen,totalLen:size,payloadFnv:H.fnv1a(payload),layoutCodes:layout,systematic:true},enc.encode(seq));
  const name=`n${size}-l${layout}-b${blockLen}-s${seq}.bin`;
  fs.writeFileSync(new URL(`../protocol/test-vectors/afl2/${name}`,import.meta.url),frame);
  vectors.push({name,size,layout,blockLen,sessionId,seq,accepted:H.parseFrame(frame)!==null});
 }
}
fs.writeFileSync(new URL('../protocol/test-vectors/afl2/index.json',import.meta.url),JSON.stringify(vectors,null,2));
console.log(`${vectors.length} golden vectors generated from locked upstream`);
