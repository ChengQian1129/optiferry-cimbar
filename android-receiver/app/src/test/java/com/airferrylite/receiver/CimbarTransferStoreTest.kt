package com.airferrylite.receiver

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
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
            val store = newStore(root)
            assertTrue(manifest.isFile)
            assertEquals(root.canonicalFile, manifest.parentFile!!.canonicalFile)
            assertNull(store.acceptCompletedFile(manifest.path))
            store.error()?.let { throw AssertionError("store error: $it") }
            assertEquals(CimbarTransferProgress("archive.zip", 0, 2, 0), store.progress())
            val firstFile = File(root, "archive.part00.bin").also { it.writeBytes(first) }
            File(root, "other.part00.bin").writeBytes("old".toByteArray())
            assertNull(store.acceptCompletedFile(firstFile.path))
            val secondFile = File(root, "archive.part01.bin").also { it.writeBytes(second) }
            val complete = store.acceptCompletedFile(secondFile.path)
            if (complete == null) throw AssertionError("assembly error: ${store.error()}")

            assertNotNull(complete)
            assertArrayEquals(first + second, concat(complete!!.parts))
            assertTrue(complete.parts.all { it.parentFile!!.name.startsWith(".pending-") })
            assertTrue(!firstFile.exists())
            assertTrue(!secondFile.exists())
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
            val store = newStore(root)
            val oldComplete = store.acceptCompletedFile(oldManifest.path)
            assertNotNull(oldComplete)

            val newManifest = writeManifest(root, "same.bin", newBytes)
            assertNull(store.acceptCompletedFile(newManifest.path))
            assertEquals(CimbarTransferProgress("same.bin", 0, 1, 0), store.progress())
            assertNull(store.error())
            assertTrue(oldComplete!!.parts.all { it.isFile })

            part.writeBytes(newBytes)
            val complete = store.acceptCompletedFile(part.path)
            assertNotNull(complete)
            assertArrayEquals(newBytes, concat(complete!!.parts))
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
            val store = newStore(root)
            val manifest = writeManifest(root, "archive.zip", first + second)

            val firstResult = store.acceptCompletedFile(manifest.path)
            assertNotNull(firstResult)
            assertArrayEquals(first + second, concat(firstResult!!.parts))
            assertTrue(store.consumeManifestResetRequest())
            assertTrue(!store.consumeManifestResetRequest())
            assertEquals(CimbarTransferProgress("archive.zip", 2, 2, 8), store.progress())
            assertTrue(!firstFile.exists())
            assertTrue(!secondFile.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun completedFileIsRecoveredAfterStoreRestart() {
        val root = Files.createTempDirectory("cimbar-store-recover").toFile()
        try {
            val bytes = "recover this result".toByteArray()
            val manifest = writeManifest(root, "recover.bin", bytes)
            val split = bytes.size / 2
            File(root, "recover.part00.bin").writeBytes(bytes.copyOfRange(0, split))
            File(root, "recover.part01.bin").writeBytes(bytes.copyOfRange(split, bytes.size))
            val firstStore = newStore(root)
            val completed = firstStore.acceptCompletedFile(manifest.path)
                ?: throw AssertionError("assembly error: ${firstStore.error()}")

            val recovered = newStore(root).recoverPendingCompletedFile()
            assertNotNull(recovered)
            assertArrayEquals(bytes, concat(recovered!!.parts))
            assertEquals(sha256(bytes), recovered.sha256)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun savingRemovesLargePrivateStagingAndSuppressesReplay() {
        val root = Files.createTempDirectory("cimbar-store-saved").toFile()
        try {
            val bytes = "saved once".toByteArray()
            val manifest = writeManifest(root, "saved.bin", bytes)
            val split = bytes.size / 2
            val firstPart = File(root, "saved.part00.bin").also {
                it.writeBytes(bytes.copyOfRange(0, split))
            }
            val secondPart = File(root, "saved.part01.bin").also {
                it.writeBytes(bytes.copyOfRange(split, bytes.size))
            }
            val store = newStore(root)
            val complete = store.acceptCompletedFile(manifest.path)
                ?: throw AssertionError("assembly error: ${store.error()}")

            assertTrue(store.markSaved(complete.transferKey, complete.sha256))
            assertTrue(complete.parts.all { !it.exists() })
            assertTrue(!firstPart.exists())
            assertTrue(!secondPart.exists())
            assertTrue(manifest.isFile)
            assertNull(store.recoverPendingCompletedFile())
            assertEquals(CimbarTransferProgress("saved.bin", 2, 2, bytes.size.toLong()), store.progress())

            val lowSpaceStore = CimbarTransferStore(root) { 0L }
            assertNull(lowSpaceStore.acceptCompletedFile(manifest.path))
            assertNull(lowSpaceStore.error())
            assertEquals(CimbarTransferProgress("saved.bin", 2, 2, bytes.size.toLong()), lowSpaceStore.progress())

            firstPart.writeBytes(bytes.copyOfRange(0, split))
            assertNull(store.acceptCompletedFile(firstPart.path))
            assertTrue(!firstPart.exists())
            assertNull(newStore(root).recoverPendingCompletedFile())
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
            val store = newStore(root)
            assertNull(store.acceptCompletedFile(manifest.path))

            part.writeBytes("wrong".toByteArray())
            val second = File(root, "valid.part01.bin").also { it.writeBytes("lid".toByteArray()) }
            assertNull(store.acceptCompletedFile(part.path))
            assertNull(store.acceptCompletedFile(second.path))
            assertNotNull(store.error())

            part.writeBytes(expected.copyOf(2))
            val complete = store.acceptCompletedFile(part.path)
            assertNotNull(complete)
            assertArrayEquals(expected, concat(complete!!.parts))
            assertNull(store.error())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun manifestNameCannotBeUsedForOutputFile() {
        val root = Files.createTempDirectory("cimbar-store").toFile()
        try {
            val manifest = writeManifest(root, "Manifest.JSON", "payload".toByteArray())
            val store = newStore(root)
            assertNull(store.acceptCompletedFile(manifest.path))
            assertTrue(store.error()?.contains("控制文件名") == true)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun refusesManifestBeforeReceivingWhenPrivateStorageIsInsufficient() {
        val root = Files.createTempDirectory("cimbar-store-space").toFile()
        try {
            val manifest = writeManifest(root, "large.bin", "payload".toByteArray())
            val store = CimbarTransferStore(root) { 0L }
            assertNull(store.acceptCompletedFile(manifest.path))
            assertTrue("error=${store.error()}", store.error()?.startsWith("手机存储空间不足") == true)
            assertEquals(CimbarTransferProgress(null, 0, 0, 0), store.progress())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsOversizedManifestDuringStoreReload() {
        val root = Files.createTempDirectory("cimbar-store-manifest-limit").toFile()
        try {
            val manifest = File(root, "manifest.json")
            RandomAccessFile(manifest, "rw").use { output ->
                output.setLength(256L * 1024L + 1L)
                output.seek(0)
                output.writeByte('{'.code)
            }

            val store = newStore(root)
            assertTrue(store.error()?.contains("manifest 过大") == true)
            assertNull(store.progress().filename)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun refusesManifestWhenSpaceOnlyCoversReceiveNotExport() {
        val root = Files.createTempDirectory("cimbar-store-export-space").toFile()
        try {
            val bytes = "payload".toByteArray()
            val manifest = writeManifest(root, "large.bin", bytes)
            val receiveOnlyBytes = bytes.size.toLong() + 64L * 1024L * 1024L
            val store = CimbarTransferStore(root) { receiveOnlyBytes }

            assertNull(store.acceptCompletedFile(manifest.path))
            assertTrue(store.error()?.startsWith("手机存储空间不足") == true)
            assertEquals(CimbarTransferProgress(null, 0, 0, 0), store.progress())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun existingPartsLeaveOnlyExportSpaceToReserve() {
        val root = Files.createTempDirectory("cimbar-store-existing-space").toFile()
        try {
            val bytes = "payload".toByteArray()
            val manifest = writeManifest(root, "ready.bin", bytes)
            val split = bytes.size / 2
            val parts = listOf(
                File(root, "ready.part00.bin").also { it.writeBytes(bytes.copyOf(split)) },
                File(root, "ready.part01.bin").also { it.writeBytes(bytes.copyOfRange(split, bytes.size)) }
            )
            val freeBytes = bytes.size.toLong() + 64L * 1024L * 1024L
            val store = CimbarTransferStore(root) { freeBytes }

            val complete = store.acceptCompletedFile(manifest.path)
            assertNotNull(complete)
            assertArrayEquals(bytes, concat(complete!!.parts))
            assertTrue(parts.all { !it.exists() })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsChunksAboveTheModeBFountainBlockBudget() {
        val root = Files.createTempDirectory("cimbar-store-limit").toFile()
        try {
            val chunkSize = 19 * 1024 * 1024 + 1
            val manifest = File(root, "manifest.json").also {
                it.writeText(
                    """{"version":1,"tool":"cimbar-bigfile","filename":"large.bin","total_size":$chunkSize,"sha256":"${"0".repeat(64)}","chunk_count":1,"chunks":[{"index":0,"file":"large.part00.bin","size":$chunkSize,"sha256":"${"0".repeat(64)}"}]}"""
                )
            }
            val store = newStore(root)
            assertNull(store.acceptCompletedFile(manifest.path))
            assertTrue(store.error()?.contains("分片大小非法") == true)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun stagesMaximumSizeChunksWithStreamingHashes() {
        val root = Files.createTempDirectory("cimbar-store-large").toFile()
        try {
            val chunkSize = 19 * 1024 * 1024
            val chunkHashes = ArrayList<String>(2)
            val wholeDigest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(256 * 1024)
            val parts = (0..1).map { index ->
                val file = File(root, "large.part${index.toString().padStart(2, '0')}.bin")
                val chunkDigest = MessageDigest.getInstance("SHA-256")
                buffer.indices.forEach { buffer[it] = ((it + index * 17) % 251).toByte() }
                file.outputStream().use { output ->
                    var written = 0
                    while (written < chunkSize) {
                        val count = minOf(buffer.size, chunkSize - written)
                        output.write(buffer, 0, count)
                        chunkDigest.update(buffer, 0, count)
                        wholeDigest.update(buffer, 0, count)
                        written += count
                    }
                }
                chunkHashes += chunkDigest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
                file
            }
            val totalSize = chunkSize.toLong() * parts.size
            val wholeHash = wholeDigest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
            val chunks = chunkHashes.mapIndexed { index, hash ->
                """{"index":$index,"file":"large.part${index.toString().padStart(2, '0')}.bin","size":$chunkSize,"sha256":"$hash"}"""
            }.joinToString(",")
            val manifest = File(root, "manifest.json").also {
                it.writeText(
                    """{"version":1,"tool":"cimbar-bigfile","filename":"large.bin","total_size":$totalSize,"sha256":"$wholeHash","chunk_count":2,"chunks":[$chunks]}"""
                )
            }

            val store = newStore(root)
            val complete = store.acceptCompletedFile(manifest.path)
                ?: throw AssertionError("assembly error: ${store.error()}")

            assertEquals(totalSize, complete.size)
            assertEquals(wholeHash, complete.sha256)
            assertEquals(2, complete.parts.size)
            assertEquals(totalSize, complete.parts.sumOf { it.length() })
            assertTrue(parts.all { !it.exists() })
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

    private fun newStore(root: File) = CimbarTransferStore(root) { TEST_AVAILABLE_BYTES }

    private fun concat(parts: List<File>): ByteArray = ByteArrayOutputStream().use { output ->
        parts.forEach { part -> part.inputStream().use { it.copyTo(output) } }
        output.toByteArray()
    }

    companion object {
        private const val TEST_AVAILABLE_BYTES = 1L shl 40
    }
}
