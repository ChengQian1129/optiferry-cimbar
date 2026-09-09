package com.airferrylite.receiver
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test
import java.io.File
import java.io.DataInputStream
import java.io.BufferedInputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.MessageDigest
import java.util.BitSet
import java.util.Random

class BatchResumeIntegrationTest {
    @Test fun defaultAttemptLoopsRecoverAcrossRestart() {
        val fixture=System.getenv("OPTIFERRY_DEFAULT_FRAMES")
        assumeNotNull(fixture)
        val directory=Files.createTempDirectory("optiferry-resume").toFile()
        try {
            val output=File(directory,"pending.bin");val journalFile=File(directory,"state")
            var bitmap=BitSet();var assembler=HighSpeedAssembler();val known=mutableSetOf<String>()
            val rng=Random(4351);var expected:ByteArray?=null;var count=Int.MAX_VALUE;var restarted=false
            for(pass in 0 until 1){
                DataInputStream(BufferedInputStream(File(fixture!!).inputStream())).use { input ->
                    while(input.available()>0){val prefix=ByteArray(4);input.readFully(prefix);val length=ByteBuffer.wrap(prefix).order(ByteOrder.LITTLE_ENDIAN).int
                        val frame=ByteArray(length);input.readFully(frame)
                        val key=Bfb1.hex(frame.copyOfRange(0,4))+Bfb1.hex(frame.copyOfRange(8,20))
                        if(key in known || rng.nextDouble()<0.2)continue
                        val update=assembler.accept(frame);assertNull(update.error)
                        val bytes=update.complete?.bytes ?: continue
                        val segment=Bfb1.parse(bytes);count=segment.count;expected=segment.wholeSha
                        if(!bitmap[segment.index]) {
                            RandomAccessFile(output,"rw").use { f -> f.setLength(segment.fileSize);f.seek(segment.offset);f.write(bytes,segment.headerSize,segment.dataSize);f.fd.sync() }
                            bitmap.set(segment.index);AtomicBatchJournal(journalFile).write(bitmap.toByteArray())
                        }
                        known.add(key);assembler.reset()
                        if(bitmap.cardinality()>=5&&!restarted){
                            val before=bitmap.cardinality()
                            // Fresh in-memory receiver, retaining only durable output and journal.
                            assembler=HighSpeedAssembler();bitmap=BitSet.valueOf(AtomicBatchJournal(journalFile).read());known.clear();restarted=true
                            assertEquals(before,bitmap.cardinality())
                        }
                    }
                }
                if(bitmap.cardinality()==count){println("Default factor 1.65, 20% loss: completed across rotating repair passes with restart");break}
            }
            assertTrue(restarted);assertEquals(count,bitmap.cardinality())
            val digest=MessageDigest.getInstance("SHA-256")
            output.inputStream().use { input -> val buffer=ByteArray(262144);while(true){val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n)} }
            assertArrayEquals(expected,digest.digest())
        } finally { directory.deleteRecursively() }
    }
}


