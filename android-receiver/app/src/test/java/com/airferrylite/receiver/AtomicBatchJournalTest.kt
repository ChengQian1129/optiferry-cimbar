package com.airferrylite.receiver
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.io.File
import java.io.RandomAccessFile

class AtomicBatchJournalTest {
    @Test fun processDeathBeforeAndAfterRename() {
        val directory=Files.createTempDirectory("optiferry-journal").toFile()
        try {
            val file=File(directory,"batch.json")
            val first="completed=40/128".toByteArray()
            AtomicBatchJournal(file).write(first)
            try { AtomicBatchJournal(file).write("completed=41/128".toByteArray()) {
                if(it==AtomicBatchJournal.Point.TEMP_SYNCED)throw IllegalStateException("simulated process death")
            };fail() } catch(_:IllegalStateException){}
            assertArrayEquals(first,AtomicBatchJournal(file).read())
            val second="completed=42/128".toByteArray()
            try { AtomicBatchJournal(file).write(second) {
                if(it==AtomicBatchJournal.Point.RENAMED)throw IllegalStateException("simulated process death")
            };fail() } catch(_:IllegalStateException){}
            assertArrayEquals(second,AtomicBatchJournal(file).read())
            // Uncommitted partial writes must not invalidate the last committed state.
            File(directory,"batch.json.tmp").writeBytes(byteArrayOf(1,2,3))
            assertArrayEquals(second,AtomicBatchJournal(file).read())
        } finally { directory.deleteRecursively() }
    }
    @Test fun boundedMetadataAndMultipleBatches() {
        val directory=Files.createTempDirectory("optiferry-journal").toFile()
        try {
            for(i in 0..7)AtomicBatchJournal(File(directory,"$i.json")).write("segment=$i".toByteArray())
            for(i in 0..7)assertEquals("segment=$i",AtomicBatchJournal(File(directory,"$i.json")).read().toString(Charsets.UTF_8))
            val f=File(directory,"oversize.json");RandomAccessFile(f,"rw").use { it.setLength(2*1024*1024) }
            try{AtomicBatchJournal(f).read();fail()}catch(_:IllegalArgumentException){}
        } finally { directory.deleteRecursively() }
    }
}
