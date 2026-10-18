package com.airferrylite.receiver

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZipListingTest {
    @Test
    fun listsFileNamesAndSkipsFolders() {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("docs/"))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("docs/a.txt"))
            zip.write("hello".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("b.txt"))
            zip.write("world".toByteArray())
            zip.closeEntry()
        }
        assertEquals(listOf("a.txt", "b.txt"), ZipListing.names(output.toByteArray()))
    }

    @Test
    fun rejectsNonZipBytes() {
        assertNull(ZipListing.names("not a zip".toByteArray()))
        assertNull(ZipListing.names(ByteArray(0)))
    }

    @Test
    fun skipsArchiveDirectoryScanAboveSizeLimit() {
        val root = Files.createTempDirectory("zip-listing").toFile()
        try {
            val file = File(root, "large.zip")
            RandomAccessFile(file, "rw").use { output ->
                output.setLength(ZipListing.MAX_ARCHIVE_BYTES + 1)
                output.seek(0)
                output.write(byteArrayOf(0x50, 0x4b, 0x03, 0x04))
            }
            assertNull(ZipListing.names(file))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun stopsInflatingEntriesAboveExpandedScanLimit() {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("large.txt"))
            zip.write(ByteArray((ZipListing.MAX_EXPANDED_SCAN_BYTES + 1).toInt()) { 'A'.code.toByte() })
            zip.closeEntry()
        }
        val bytes = output.toByteArray()
        assertTrue(bytes.size.toLong() < ZipListing.MAX_ARCHIVE_BYTES)
        assertNull(ZipListing.names(bytes))
    }
}
