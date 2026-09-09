package com.airferrylite.receiver

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

/** BFB1 is parsed before the legacy DCF2 container, after AFL2 FNV validation. */
data class BatchSegment(
    val batch: String, val salt: Long, val index: Int, val count: Int,
    val segmentSize: Int, val fileSize: Long, val offset: Long,
    val name: String, val wholeSha: ByteArray, val segmentSha: ByteArray,
    val payload: ByteArray, val headerSize: Int
) {
    val dataSize get() = payload.size - headerSize
    fun sameBatch(other: BatchSegment) = batch == other.batch && salt == other.salt &&
        count == other.count && segmentSize == other.segmentSize && fileSize == other.fileSize &&
        name == other.name && wholeSha.contentEquals(other.wholeSha)
}

object Bfb1 {
    fun isBatch(bytes: ByteArray) = bytes.size >= 4 && bytes[0] == 66.toByte() &&
        bytes[1] == 70.toByte() && bytes[2] == 66.toByte() && bytes[3] == 49.toByte()
    fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
    fun batchId(size: Long, hash: ByteArray, name: String): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        md.update("OptiFerry-BFB1-v1".toByteArray(Charsets.US_ASCII))
        md.update(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(size).array())
        md.update(hash); md.update(name.toByteArray(Charsets.UTF_8))
        return md.digest().copyOf(16)
    }
    fun parse(bytes: ByteArray): BatchSegment {
        fun bad(ok: Boolean) { require(ok) { "BAD_BATCH_METADATA" } }
        bad(bytes.size >= 132 && isBatch(bytes))
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun u16(p: Int) = b.getShort(p).toInt() and 65535
        fun u32(p: Int) = b.getInt(p).toLong() and 0xffffffffL
        bad(bytes[4] == 1.toByte() && bytes[5] == 0.toByte() && u32(60) == 0L && u16(130) == 0)
        val header = u16(6); val nameSize = u16(128)
        bad(nameSize in 1..240 && header == 132 + nameSize && header <= bytes.size)
        val name = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes, 132, nameSize)).toString()
        bad(name != "." && !name.contains("..") && name.none { it == '/' || it == '\\' || it.isISOControl() })
        val count = u32(32); val index = u32(28); val nominal = u32(36)
        val size = b.getLong(40); val offset = b.getLong(48); val length = u32(56)
        bad(count in 1..65535 && index < count && nominal in 1048576..33554432)
        bad(size >= 0 && offset >= 0 && offset <= size && length == (bytes.size - header).toLong())
        bad(count == (if (size == 0L) 1L else 1L + (size - 1) / nominal))
        bad(offset == index * nominal && length == minOf(nominal, size - offset))
        val whole = bytes.copyOfRange(64,96); val segment = bytes.copyOfRange(96,128)
        val id = bytes.copyOfRange(8,24)
        bad(id.contentEquals(batchId(size, whole, name)))
        val md = MessageDigest.getInstance("SHA-256"); md.update(bytes, header, bytes.size - header)
        require(md.digest().contentEquals(segment)) { "BAD_SEGMENT_HASH" }
        return BatchSegment(hex(id),u32(24),index.toInt(),count.toInt(),nominal.toInt(),size,offset,name,whole,segment,bytes,header)
    }
}
