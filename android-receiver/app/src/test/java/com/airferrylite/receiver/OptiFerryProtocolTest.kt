package com.airferrylite.receiver
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.DataInputStream
import java.io.BufferedInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Random

class OptiFerryProtocolTest {
    private fun envelope(size: Int = 1): ByteArray {
        val name="test.bin".toByteArray(); val data=ByteArray(size){(it*31).toByte()}
        val out=ByteArray(132+name.size+size); val b=ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        b.put("BFB1".toByteArray());b.put(1.toByte());b.put(0.toByte());b.putShort((132+name.size).toShort())
        val whole=Bfb1.hash(data); b.put(Bfb1.batchId(size.toLong(),whole,"test.bin"))
        b.putInt(0);b.putInt(0);b.putInt(1);b.putInt(8388608);b.putLong(size.toLong());b.putLong(0);b.putInt(size);b.putInt(0)
        b.put(whole);b.put(whole);b.putShort(name.size.toShort());b.putShort(0);b.put(name);b.put(data)
        return out
    }
    @Test fun bfbRoundtripAndMalformed() {
        for(n in listOf(0,1,1048576)){val p=Bfb1.parse(envelope(n));assertEquals(n,p.dataSize);assertEquals("test.bin",p.name)}
        for(offset in listOf(0,4,5,6,28,32,39,40,48,56,60,64,96,128,130,132,140)) {
            val b=envelope();b[offset]=(b[offset].toInt() xor 0x40).toByte()
            try { Bfb1.parse(b);fail("Accepted corruption at $offset") } catch (_:IllegalArgumentException) {}
        }
        for(n in 0 until 140){try{Bfb1.parse(envelope().copyOf(n));fail("Accepted truncation $n")}catch(_:Exception){}}
    }
    @Test fun nativeFramesWithLossAndReordering() {
        val path=System.getenv("OPTIFERRY_FRAMES")
        org.junit.Assume.assumeNotNull(path)
        for(loss in listOf(0.0,0.05,0.10,0.20)) {
            val assembler=HighSpeedAssembler();val rng=Random(7153);val completed=mutableSetOf<Int>();val finalHash=MessageDigest.getInstance("SHA-256");var expected:ByteArray?=null;var count=0
            fun feed(frame:ByteArray){
                val result=assembler.accept(frame);assertNull(result.error)
                result.complete?.let { file ->
                    val s=Bfb1.parse(file.bytes)
                    if(completed.add(s.index)){assertEquals("Missing segment at loss $loss",count,s.index);count++;expected=s.wholeSha;finalHash.update(s.payload,s.headerSize,s.dataSize)}
                    assembler.reset()
                }
            }
            DataInputStream(BufferedInputStream(File(path!!).inputStream())).use { input ->
                val pending=mutableListOf<ByteArray>()
                fun flush(){pending.shuffle(rng);pending.forEach { feed(it);if(rng.nextInt(20)==0)feed(it) };pending.clear()}
                var previousKey=""
                while(input.available()>0){val prefix=ByteArray(4);input.readFully(prefix);val length=ByteBuffer.wrap(prefix).order(ByteOrder.LITTLE_ENDIAN).int;assertEquals(2068,length);val frame=ByteArray(length);input.readFully(frame)
                    val key=Bfb1.hex(frame.copyOfRange(0,4))+Bfb1.hex(frame.copyOfRange(8,20));if(key!=previousKey){flush();previousKey=key}
                    if(rng.nextDouble()>=loss)pending.add(frame)
                    if(pending.size>=32)flush()
                }
                flush()
            }
            assertNotNull("No recovered file at loss $loss",expected)
            assertArrayEquals("Whole-file hash at loss $loss",expected,finalHash.digest())
            assertTrue("Expected multi-segment fixture",count>1)
        }
    }
}


