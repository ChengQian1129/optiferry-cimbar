package com.airferrylite.receiver

import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream
import java.util.zip.ZipFile

internal object ZipListing {
    fun names(bytes: ByteArray): List<String>? {
        if (bytes.size < 4 || bytes.size.toLong() > MAX_ARCHIVE_BYTES ||
            bytes[0] != 0x50.toByte() || bytes[1] != 0x4b.toByte()) return null
        return try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                val names = ArrayList<String>()
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                var expandedBytes = 0L
                var truncated = false
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (!entry.isDirectory) {
                        val raw = entry.name.replace('\\', '/')
                        val name = raw.substringAfterLast('/').ifBlank { raw }
                        if (name.isNotBlank() && name != "." && name != "..") {
                            if (names.size < MAX_NAMES) names += name else truncated = true
                        }
                    }
                    while (true) {
                        val read = zip.read(buffer)
                        if (read < 0) break
                        expandedBytes += read
                        if (expandedBytes > MAX_EXPANDED_SCAN_BYTES) return null
                    }
                }
                if (truncated) names += "..."
                names.takeIf { it.isNotEmpty() }
            }
        } catch (_: Throwable) {
            null
        }
    }

    fun names(file: File): List<String>? {
        if (!file.isFile || file.length() < 4L || file.length() > MAX_ARCHIVE_BYTES) return null
        return try {
            ZipFile(file).use { zip ->
                val entries = zip.entries()
                val names = ArrayList<String>()
                while (entries.hasMoreElements() && names.size < MAX_NAMES) {
                    val entry = entries.nextElement()
                    if (!entry.isDirectory) {
                        val raw = entry.name.replace('\\', '/')
                        val name = raw.substringAfterLast('/').ifBlank { raw }
                        if (name.isNotBlank() && name != "." && name != "..") names += name
                    }
                }
                if (entries.hasMoreElements()) names += "..."
                names.takeIf { it.isNotEmpty() }
            }
        } catch (_: Throwable) {
            null
        }
    }

    private const val MAX_NAMES = 500
    private const val COPY_BUFFER_BYTES = 16 * 1024
    const val MAX_EXPANDED_SCAN_BYTES = 32L * 1024L * 1024L
    const val MAX_ARCHIVE_BYTES = 8L * 1024L * 1024L
}
