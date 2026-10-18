package com.airferrylite.receiver

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.StandardCopyOption

data class CimbarTransferProgress(
    val filename: String?,
    val received: Int,
    val total: Int,
    val bytes: Long
)

data class CimbarCompletedFile(
    val name: String,
    val file: File,
    val sha256: String
)

/** Persists CFC-compatible manifest/part files and assembles them in order. */
class CimbarTransferStore(private val root: File) {
    private data class Part(
        val index: Int,
        val filename: String,
        val size: Long,
        val sha256: String
    )

    private data class Manifest(
        val filename: String,
        val totalSize: Long,
        val sha256: String,
        val chunkCount: Int,
        val parts: List<Part>,
        val identity: String
    )

    private val partFiles = linkedMapOf<String, File>()
    private var manifest: Manifest? = null
    private var deliveredIdentity: String? = null
    private var manifestResetRequested = false
    private var lastError: String? = null

    init {
        require(root.mkdirs() || root.isDirectory) { "无法创建彩码存储目录" }
        reloadExisting()
    }

    @Synchronized
    fun acceptCompletedFile(path: String): CimbarCompletedFile? {
        val file = File(path)
        if (!isDirectFile(file)) {
            lastError = "彩码文件路径非法: ${file.path}"
            return null
        }
        lastError = null
        try {
            if (file.name.equals(MANIFEST_NAME, ignoreCase = true)) {
                val parsed = parseManifest(file.readText(Charsets.UTF_8))
                if (manifest?.identity != parsed.identity) {
                    // A manifest may arrive after one or more parts. Preserve
                    // those files when there was no prior manifest; the
                    // hashes below still decide whether they can be used.
                    // Once a prior manifest is known, equal filenames belong
                    // to the old transfer and must be discarded.
                    if (manifest != null) clearPartFiles()
                    deliveredIdentity = null
                    manifestResetRequested = true
                }
                manifest = parsed
                indexExistingParts()
            } else {
                val match = PART_PATTERN.matchEntire(file.name) ?: return null
                val index = match.groupValues[1].toIntOrNull()
                    ?: throw IllegalArgumentException("彩码分片序号非法")
                if (index >= MAX_CHUNKS) throw IllegalArgumentException("彩码分片序号过大")
                partFiles[file.name] = file
                manifest?.parts?.firstOrNull { it.filename == file.name }?.let {
                    require(it.index == index) { "彩码分片文件名与 manifest 不匹配" }
                }
            }
            return assembleIfComplete()
        } catch (error: Throwable) {
            lastError = error.message ?: error.javaClass.simpleName
            return null
        }
    }

    @Synchronized
    fun progress(): CimbarTransferProgress {
        val current = manifest
        val expected = current?.parts.orEmpty()
        val present = expected.filter { partFiles[it.filename]?.isFile == true }
        return CimbarTransferProgress(
            current?.filename,
            present.size,
            current?.chunkCount ?: 0,
            present.sumOf { partFiles[it.filename]?.length() ?: 0L }
        )
    }

    @Synchronized
    fun error(): String? = lastError

    /** Returns and clears the native-decoder reset request for a new manifest. */
    @Synchronized
    fun consumeManifestResetRequest(): Boolean {
        val requested = manifestResetRequested
        manifestResetRequested = false
        return requested
    }

    @Synchronized
    fun reset(deleteFiles: Boolean = false) {
        manifest = null
        partFiles.clear()
        deliveredIdentity = null
        manifestResetRequested = false
        lastError = null
        if (deleteFiles) {
            root.listFiles()?.forEach { it.delete() }
        } else {
            reloadExisting()
        }
    }

    private fun reloadExisting() {
        partFiles.clear()
        root.listFiles()?.filter { it.name.startsWith(".assembled-") && it.name.endsWith(".tmp") }
            ?.forEach { it.delete() }
        val manifestFile = File(root, MANIFEST_NAME)
        if (manifestFile.isFile) {
            runCatching { manifest = parseManifest(manifestFile.readText(Charsets.UTF_8)) }
                .onFailure { lastError = it.message ?: it.javaClass.simpleName }
        }
        indexExistingParts()
    }

    private fun indexExistingParts() {
        root.listFiles()?.forEach { file ->
            if (file.isFile && PART_PATTERN.matches(file.name)) partFiles[file.name] = file
        }
    }

    private fun parseManifest(text: String): Manifest {
        val json = JsonParser.parseString(text).asJsonObject
        require(json.requiredInt("version") == 1) { "不支持的 Cimbar manifest 版本" }
        require(json.requiredString("tool") == "cimbar-bigfile") { "不是 cimbar-bigfile manifest" }
        val originalFilename = json.requiredString("filename")
            .substringAfterLast('/')
            .substringAfterLast('\\')
        require(originalFilename.isNotBlank() && originalFilename.toByteArray(Charsets.UTF_8).size <= MAX_NAME_BYTES) {
            "manifest 文件名非法"
        }
        require(!originalFilename.equals(MANIFEST_NAME, ignoreCase = true)) {
            "manifest.json 是彩码控制文件名，不能作为输出文件名"
        }
        val totalSize = json.requiredLong("total_size")
        val chunkCount = json.requiredInt("chunk_count")
        require(totalSize >= 0 && totalSize <= MAX_TOTAL_BYTES) { "manifest 文件大小非法" }
        require(chunkCount in 1..MAX_CHUNKS) { "manifest 分片数量非法" }
        val wholeSha = normalizedSha(json.requiredString("sha256"), "完整文件 SHA-256")
        val chunks = json.requiredArray("chunks")
        require(chunks.size() == chunkCount) { "manifest 分片数量不匹配" }

        val parts = ArrayList<Part>(chunkCount)
        var summedSize = 0L
        for (index in 0 until chunkCount) {
            val chunk = chunks[index].asJsonObject
            require(chunk.requiredInt("index") == index) { "manifest 分片顺序非法" }
            val size = chunk.requiredLong("size")
            require(size >= 0 && size <= MAX_CHUNK_BYTES) { "manifest 分片大小非法" }
            val digest = normalizedSha(chunk.requiredString("sha256"), "分片 SHA-256")
            val chunkFilename = chunk.optionalString("file").orEmpty().ifBlank {
                defaultPartFilename(originalFilename, chunkCount, index)
            }
            require(isSafePartFilename(chunkFilename)) { "manifest 分片文件名非法" }
            parts += Part(index, chunkFilename, size, digest)
            summedSize = Math.addExact(summedSize, size)
        }
        require(summedSize == totalSize) { "manifest 分片大小总和不匹配" }
        require(parts.map { it.filename }.toSet().size == parts.size) { "manifest 分片文件名重复" }
        val identity = buildString {
            append(originalFilename).append('\u0000')
            append(totalSize).append('\u0000').append(wholeSha).append('\u0000')
            parts.forEach {
                append(it.index).append(':').append(it.filename).append(':')
                    .append(it.size).append(':').append(it.sha256).append(';')
            }
        }
        return Manifest(originalFilename, totalSize, wholeSha, chunkCount, parts, identity)
    }

    private fun assembleIfComplete(): CimbarCompletedFile? {
        val current = manifest ?: return null
        if (deliveredIdentity == current.identity) return null
        if (current.parts.any { partFiles[it.filename]?.isFile != true }) return null

        val identityKey = MessageDigest.getInstance("SHA-256")
            .digest(current.identity.toByteArray(Charsets.UTF_8)).toHex()
        val temporary = File.createTempFile(".assembled-", ".tmp", root)
        val completed = File(root, ".complete-$identityKey.bin")
        try {
            val fullDigest = MessageDigest.getInstance("SHA-256")
            BufferedOutputStream(FileOutputStream(temporary)).use { output ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                for (part in current.parts) {
                    val file = partFiles[part.filename]
                        ?: throw IllegalStateException("缺少彩码分片 ${part.index}")
                    require(file.length() == part.size) { "分片 ${part.index} 大小不匹配" }
                    val digest = MessageDigest.getInstance("SHA-256")
                    var copied = 0L
                    BufferedInputStream(FileInputStream(file)).use { input ->
                        while (copied < part.size) {
                            val requested = minOf(buffer.size.toLong(), part.size - copied).toInt()
                            val read = input.read(buffer, 0, requested)
                            require(read > 0) { "分片 ${part.index} 读取不完整" }
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            fullDigest.update(buffer, 0, read)
                            copied += read
                        }
                    }
                    require(digest.digest().toHex() == part.sha256) {
                        "分片 ${part.index} SHA-256 校验失败"
                    }
                }
            }
            require(temporary.length() == current.totalSize) { "拼接后大小不匹配" }
            val digest = fullDigest.digest().toHex()
            require(digest == current.sha256) { "完整文件 SHA-256 校验失败" }
            Files.move(temporary.toPath(), completed.toPath(), StandardCopyOption.REPLACE_EXISTING)
            deliveredIdentity = current.identity
            return CimbarCompletedFile(current.filename, completed, digest)
        } finally {
            temporary.delete()
        }
    }

    private fun clearPartFiles() {
        partFiles.clear()
        root.listFiles()?.forEach { file ->
            if (file.isFile && PART_PATTERN.matches(file.name)) file.delete()
        }
    }

    private fun isDirectFile(file: File): Boolean = runCatching {
        file.isFile && file.parentFile?.canonicalFile == root.canonicalFile
    }.getOrDefault(false)

    private fun normalizedSha(value: String, label: String): String {
        val normalized = value.lowercase()
        require(SHA_PATTERN.matches(normalized)) { "$label 非法" }
        return normalized
    }

    private fun isSafePartFilename(value: String): Boolean {
        if (value.isBlank() || value != File(value).name) return false
        return PART_PATTERN.matches(value)
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun JsonElement?.asRequired(label: String): JsonElement =
        requireNotNull(this) { "manifest 缺少 $label" }.also {
            require(!it.isJsonNull) { "manifest 字段 $label 为空" }
        }

    private fun com.google.gson.JsonObject.requiredString(key: String): String =
        get(key).asRequired(key).asString

    private fun com.google.gson.JsonObject.requiredInt(key: String): Int =
        get(key).asRequired(key).asInt

    private fun com.google.gson.JsonObject.requiredLong(key: String): Long =
        get(key).asRequired(key).asLong

    private fun com.google.gson.JsonObject.requiredArray(key: String) =
        get(key).asRequired(key).asJsonArray

    private fun com.google.gson.JsonObject.optionalString(key: String): String? =
        get(key)?.takeUnless { it.isJsonNull }?.asString

    companion object {
        private const val MANIFEST_NAME = "manifest.json"
        private const val MAX_CHUNKS = 120
        private const val MAX_NAME_BYTES = 255
        private const val MAX_CHUNK_BYTES = 24L * 1024L * 1024L
        private const val MAX_TOTAL_BYTES = MAX_CHUNKS.toLong() * MAX_CHUNK_BYTES
        private const val COPY_BUFFER_BYTES = 256 * 1024
        private val PART_PATTERN = Regex(".+\\.part(\\d+)\\.bin", RegexOption.IGNORE_CASE)
        private val SHA_PATTERN = Regex("[0-9a-f]{64}")

        private fun defaultPartFilename(name: String, count: Int, index: Int): String {
            val dot = name.lastIndexOf('.')
            val stem = if (dot > 0) name.substring(0, dot) else name
            val width = if (count < 100) 2 else 3
            return "$stem.part${index.toString().padStart(width, '0')}.bin"
        }
    }
}
