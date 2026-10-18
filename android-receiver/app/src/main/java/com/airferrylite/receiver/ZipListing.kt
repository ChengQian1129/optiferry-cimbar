package com.airferrylite.receiver

import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream
import java.util.zip.ZipFile

internal object ZipListing {
    fun names(bytes: ByteArray): List<String>? {
        if (bytes.size < 4 || bytes[0] != 0x50.toByte() || bytes[1] != 0x4b.toByte()) return null
        return try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                val names = ArrayList<String>()
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (!entry.isDirectory) {
                        val raw = entry.name.replace('\\', '/')
                        val name = raw.substringAfterLast('/').ifBlank { raw }
                        if (name.isNotBlank() && name != "." && name != "..") names += name
                    }
                    zip.closeEntry()
                }
                names.takeIf { it.isNotEmpty() }
            }
        } catch (_: Throwable) {
            null
        }
    }

    fun names(file: File): List<String>? {
        if (!file.isFile || file.length() < 4L) return null
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
}
