import fs from 'node:fs';
const f=fs.openSync(process.argv[2],'w');const b=Buffer.alloc(1024*1024);let x=7153;for(let i=0;i<b.length;i++){x^=x<<13;x^=x>>>17;x^=x<<5;b[i]=x&255;}for(let i=0;i<100;i++)fs.writeSync(f,b);fs.closeSync(f);
