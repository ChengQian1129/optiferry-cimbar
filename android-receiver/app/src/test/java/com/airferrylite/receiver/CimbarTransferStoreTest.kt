package com.airferrylite.receiver

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

class CimbarTransferStoreTest {
    @Test
    fun assemblesManifestPartsAndIgnoresUnrelatedFiles() {
        val root = Files.createTempDirectory("cimbar-store").toFile()
        try {
            val first = "abcd".toByteArray()
            val second = "efgh".toByteArray()
            val manifest = writeManifest(root, "archive.zip", first + second)
            val store = CimbarTransferStore(root)
            assertTrue(manifest.isFile)
            assertEquals(root.canonicalFile, manifest.parentFile!!.canonicalFile)
            assertNull(store.acceptCompletedFile(manifest.path))
            store.error()?.let { throw AssertionError("store error: $it") }
            assertEquals(CimbarTransferProgress("archive.zip", 0, 2, 0), store.progress())
            val firstFile = File(root, "archive.part00.bin").also { it.writeBytes(first) }
            val secondFile = File(root, "archive.part01.bin").also { it.writeBytes(second) }
            File(root, "other.part00.bin").writeBytes("old".toByteArray())
            assertNull(store.acceptCompletedFile(firstFile.path))
            val complete = store.acceptCompletedFile(secondFile.path)
            if (complete == null) throw AssertionError("assembly error: ${store.error()}")

            assertNotNull(complete)
            assertArrayEquals(first + second, complete!!.bytes)
            assertEquals("archive.zip", complete.name)
            assertEquals(CimbarTransferProgress("archive.zip", 2, 2, 8), store.progress())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun newManifestDropsOldPartsAndClearsOldError() {
        val root = Files.createTempDirectory("cimbar-store").toFile()
        try {
            val oldBytes = "o".toByteArray()
            val newBytes = "n".toByteArray()
            val oldManifest = writeManifest(root, "same.bin", oldBytes)
            val part = File(root, "same.part00.bin")
            part.writeBytes(oldBytes)
            val store = CimbarTransferStore(root)
            assertNotNull(store.acceptCompletedFile(oldManifest.path))

            val newManifest = writeManifest(root, "same.bin", newBytes)
            assertNull(store.acceptCompletedFile(newManifest.path))
            assertEquals(CimbarTransferProgress("same.bin", 0, 1, 0), store.progress())
            assertNull(store.error())

            part.writeBytes(newBytes)
            val complete = store.acceptCompletedFile(part.path)
            assertNotNull(complete)
            assertArrayEquals(newBytes, complete!!.bytes)
            assertNull(store.error())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun partsReceivedBeforeManifestAreRetained() {
        val root = Files.createTempDirectory("cimbar-store").toFile()
        try {
            val first = "abcd".toByteArray()
            val second = "efgh".toByteArray()
            val firstFile = File(root, "archive.part00.bin").also { it.writeBytes(first) }
            val secondFile = File(root, "archive.part01.bin").also { it.writeBytes(second) }
            val store = CimbarTransferStore(root)
            val manifest = writeManifest(root, "archive.zip", first + second)

            val firstResult = store.acceptCompletedFile(manifest.path)
            assertNotNull(firstResult)
            assertArrayEquals(first + second, firstResult!!.bytes)
            assertTrue(store.consumeManifestResetRequest())
            assertTrue(!store.consumeManifestResetRequest())
            assertEquals(CimbarTransferProgress("archive.zip", 2, 2, 8), store.progress())
            assertTrue(firstFile.isFile)
            assertTrue(secondFile.isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun badPartCanBeReplacedWithoutStickingErrorState() {
        val root = Files.createTempDirectory("cimbar-store").toFile()
        try {
            val expected = "valid".toByteArray()
            val manifest = writeManifest(root, "valid.bin", expected)
            val part = File(root, "valid.part00.bin")
            val store = CimbarTransferStore(root)
            assertNull(store.acceptCompletedFile(manifest.path))

            part.writeBytes("wrong".toByteArray())
            val second = File(root, "valid.part01.bin").also { it.writeBytes("lid".toByteArray()) }
            assertNull(store.acceptCompletedFile(part.path))
            assertNull(store.acceptCompletedFile(second.path))
            assertNotNull(store.error())

            part.writeBytes(expected.copyOf(2))
            val complete = store.acceptCompletedFile(part.path)
            assertNotNull(complete)
            assertArrayEquals(expected, complete!!.bytes)
            assertNull(store.error())
        } finally {
            root.deleteRecursively()
        }
    }

    private fun writeManifest(root: File, name: String, bytes: ByteArray): File {
        val split = bytes.size / 2
        val parts = listOf(bytes.copyOf(split), bytes.copyOfRange(split, bytes.size))
            .filter { it.isNotEmpty() }
        val stem = name.substringBeforeLast('.')
        val chunks = parts.mapIndexed { index, part ->
            "{\"index\":$index,\"file\":\"$stem.part${index.toString().padStart(2, '0')}.bin\",\"size\":${part.size},\"sha256\":\"${sha256(part)}\"}"
        }.joinToString(",")
        val json = """
            {"version":1,"tool":"cimbar-bigfile","filename":"$name","total_size":${bytes.size},"sha256":"${sha256(bytes)}","chunk_count":${parts.size},"chunks":[$chunks]}
        """.trimIndent()
        return File(root, "manifest.json").also { it.writeText(json) }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
