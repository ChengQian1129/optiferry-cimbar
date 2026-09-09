package com.airferrylite.receiver

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/** Durable private-app journal, shared by Android and JVM crash-recovery tests. */
class AtomicBatchJournal(private val file: File) {
    enum class Point { TEMP_SYNCED, RENAMED }
    fun read(): ByteArray = FileInputStream(file).use { stream ->
        require(file.length() <= 1024 * 1024) { "BAD_BATCH_METADATA: oversized journal" }
        stream.readBytes()
    }
    fun write(bytes: ByteArray, checkpoint: (Point) -> Unit = {}) {
        require(bytes.size <= 1024 * 1024) { "BAD_BATCH_METADATA: oversized journal" }
        file.parentFile!!.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(temp).use { output -> output.write(bytes);output.fd.sync() }
        checkpoint(Point.TEMP_SYNCED)
        Files.move(temp.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
        checkpoint(Point.RENAMED)
        FileChannel.open(file.parentFile!!.toPath(),StandardOpenOption.READ).use { it.force(true) }
    }
}
