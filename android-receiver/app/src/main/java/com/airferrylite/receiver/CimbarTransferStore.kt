package com.airferrylite.receiver

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import java.io.BufferedInputStream
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
    val parts: List<File>,
    val size: Long,
    val sha256: String,
    val transferKey: String
)

/** Persists CFC-compatible manifest/part files and assembles them in order. */
class CimbarTransferStore(
    private val root: File,
    private val availableBytes: () -> Long = { root.usableSpace }
) {
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
                require(file.length() <= MAX_MANIFEST_BYTES) { "彩码 manifest 过大" }
                val parsed = parseManifest(file.readText(Charsets.UTF_8))
                if (manifest?.identity == parsed.identity && deliveredIdentity == parsed.identity) {
                    return null
                }
                val isNewIdentity = manifest?.identity != parsed.identity
                val oldPartFiles = if (isNewIdentity && manifest != null) {
                    root.listFiles()?.filter { it.isFile && PART_PATTERN.matches(it.name) }.orEmpty()
                } else emptyList()
                val reclaimableBytes = oldPartFiles.sumOf { it.length() }
                val pending = pendingDirectory(identityKey(parsed))
                val alreadyPresentBytes = parsed.parts.sumOf { part ->
                    listOf(File(root, part.filename), File(pending, part.filename))
                        .firstOrNull { it.isFile && it.length() == part.size }
                        ?.length() ?: 0L
                }
                val additionalReceiveBytes = (parsed.totalSize - alreadyPresentBytes).coerceAtLeast(0L)
                val requiredBytes = Math.addExact(
                    Math.addExact(additionalReceiveBytes, parsed.totalSize),
                    STORAGE_RESERVE_BYTES
                )
                val availableWithReclaim = Math.addExact(availableBytes().coerceAtLeast(0L), reclaimableBytes)
                if (availableWithReclaim < requiredBytes) {
                    lastError = "$STORAGE_SPACE_ERROR_PREFIX：接收并保存前至少需要 $requiredBytes 字节可用，清理空间后点继续"
                    return null
                }
                if (isNewIdentity) {
                    // A manifest may arrive after one or more parts. Preserve
                    // those files when there was no prior manifest; the
                    // hashes below still decide whether they can be used.
                    // Once a prior manifest is known, equal filenames belong
                    // to the old transfer and must be discarded.
                    if (manifest != null) clearPartFiles()
                    deliveredIdentity = null
                    File(root, SAVED_TRANSFER_NAME).delete()
                    manifestResetRequested = true
                }
                manifest = parsed
                indexExistingParts()
            } else {
                val match = PART_PATTERN.matchEntire(file.name) ?: return null
                val index = match.groupValues[1].toIntOrNull()
                    ?: throw IllegalArgumentException("彩码分片序号非法")
                if (index >= MAX_CHUNKS) throw IllegalArgumentException("彩码分片序号过大")
                val expectedPart = manifest?.parts?.firstOrNull { it.filename == file.name }
                expectedPart?.let {
                    require(it.index == index) { "彩码分片文件名与 manifest 不匹配" }
                }
                if (manifest != null && manifest?.identity == deliveredIdentity) {
                    partFiles.remove(file.name)
                    file.delete()
                    return null
                }
                partFiles[file.name] = file
            }
            return assembleIfComplete()
        } catch (error: Throwable) {
            lastError = error.message ?: error.javaClass.simpleName
            return null
        }
    }

    @Synchronized
    fun progress(): CimbarTransferProgress {
        if (lastError?.startsWith(STORAGE_SPACE_ERROR_PREFIX) == true) {
            return CimbarTransferProgress(null, 0, 0, 0)
        }
        val current = manifest
        val expected = current?.parts.orEmpty()
        if (current != null && deliveredIdentity == current.identity) {
            return CimbarTransferProgress(current.filename, current.chunkCount, current.chunkCount, current.totalSize)
        }
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

    /** Recovers a verified result left in private storage if the process died before saving it. */
    @Synchronized
    fun recoverPendingCompletedFile(): CimbarCompletedFile? {
        val pendingDirectories = root.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith(PENDING_PREFIX) }
            ?.sortedBy { it.lastModified() }
            .orEmpty()
        for (directory in pendingDirectories) {
            val key = directory.name.removePrefix(PENDING_PREFIX)
            if (!SHA_PATTERN.matches(key)) continue
            val manifestFile = File(directory, MANIFEST_NAME)
            if (!manifestFile.isFile || manifestFile.length() > MAX_MANIFEST_BYTES) continue
            val parsed = runCatching {
                parseManifest(manifestFile.readText(Charsets.UTF_8))
            }
            val savedManifest = parsed.getOrNull()
            if (savedManifest == null) {
                parsed.exceptionOrNull()?.let { lastError = it.message ?: it.javaClass.simpleName }
                continue
            }
            if (identityKey(savedManifest) != key) continue
            val completed = runCatching { stageVerified(savedManifest, directory) }
                .onFailure { lastError = it.message ?: it.javaClass.simpleName }
                .getOrNull()
            if (completed != null) return completed
        }

        val current = manifest ?: return null
        if (deliveredIdentity == current.identity) return null
        return runCatching { stageVerified(current, pendingDirectory(identityKey(current))) }
            .onFailure { lastError = it.message ?: it.javaClass.simpleName }
            .getOrNull()
    }

    /** Records a public save before removing the large private staging files. */
    @Synchronized
    fun markSaved(transferKey: String, sha256: String): Boolean {
        val key = transferKey.lowercase()
        val digest = sha256.lowercase()
        if (!SHA_PATTERN.matches(key) || !SHA_PATTERN.matches(digest)) return false

        val current = manifest
        val isCurrent = current != null && identityKey(current) == key && current.sha256 == digest
        if (isCurrent) {
            val marker = File(root, SAVED_TRANSFER_NAME)
            val temporary = File(root, "$SAVED_TRANSFER_NAME.tmp")
            try {
                FileOutputStream(temporary).use { output ->
                    output.write(key.toByteArray(Charsets.US_ASCII))
                    output.fd.sync()
                }
                Files.move(temporary.toPath(), marker.toPath(), StandardCopyOption.REPLACE_EXISTING)
                deliveredIdentity = current!!.identity
            } catch (error: Throwable) {
                temporary.delete()
                lastError = error.message ?: error.javaClass.simpleName
                return false
            }
            clearPartFiles()
        }

        val pending = pendingDirectory(key)
        val removed = !pending.exists() || pending.deleteRecursively()
        lastError = if (removed) null else "已保存，但无法清理彩码临时文件"
        return removed
    }

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
            root.listFiles()?.forEach { it.deleteRecursively() }
        } else {
            reloadExisting()
        }
    }

    private fun reloadExisting() {
        partFiles.clear()
        File(root, "$SAVED_TRANSFER_NAME.tmp").delete()
        val manifestFile = File(root, MANIFEST_NAME)
        if (manifestFile.isFile) {
            runCatching {
                require(manifestFile.length() <= MAX_MANIFEST_BYTES) { "彩码 manifest 过大" }
                manifest = parseManifest(manifestFile.readText(Charsets.UTF_8))
            }
                .onFailure { lastError = it.message ?: it.javaClass.simpleName }
        }
        val marker = File(root, SAVED_TRANSFER_NAME)
        val savedKey = if (marker.isFile) {
            runCatching { marker.readText(Charsets.US_ASCII).trim() }.getOrNull()
        } else null
        val current = manifest
        if (current != null && savedKey == identityKey(current)) {
            deliveredIdentity = current.identity
            clearPartFiles()
            pendingDirectory(identityKey(current)).deleteRecursively()
        } else {
            deliveredIdentity = null
            if (marker.exists()) marker.delete()
            indexExistingParts()
        }
    }

    private fun indexExistingParts() {
        val current = manifest
        if (current != null && deliveredIdentity == current.identity) {
            clearPartFiles()
            return
        }
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
        require(totalSize in 1..MAX_TOTAL_BYTES) { "manifest 文件大小非法" }
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
            require(size in 1..MAX_CHUNK_BYTES) { "manifest 分片大小非法" }
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
        return stageVerified(current, pendingDirectory(identityKey(current)))
    }

    private fun stageVerified(current: Manifest, directory: File): CimbarCompletedFile? {
        val files = current.parts.map { part ->
            val staged = File(directory, part.filename)
            val incoming = partFiles[part.filename]?.takeIf { it.isFile }
                ?: File(root, part.filename).takeIf { it.isFile }
            part to (staged.takeIf { it.isFile } ?: incoming)
        }
        if (files.any { it.second == null }) return null

        try {
            val fullDigest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            for ((part, source) in files) {
                val file = requireNotNull(source)
                if (file.length() != part.size) {
                    if (file.parentFile?.canonicalFile == directory.canonicalFile) file.delete()
                    throw IllegalArgumentException("分片 ${part.index} 大小不匹配")
                }
                val digest = MessageDigest.getInstance("SHA-256")
                var copied = 0L
                BufferedInputStream(FileInputStream(file)).use { input ->
                    while (copied < part.size) {
                        val requested = minOf(buffer.size.toLong(), part.size - copied).toInt()
                        val read = input.read(buffer, 0, requested)
                        require(read > 0) { "分片 ${part.index} 读取不完整" }
                        digest.update(buffer, 0, read)
                        fullDigest.update(buffer, 0, read)
                        copied += read
                    }
                }
                if (digest.digest().toHex() != part.sha256) {
                    if (file.parentFile?.canonicalFile == directory.canonicalFile) file.delete()
                    throw IllegalArgumentException("分片 ${part.index} SHA-256 校验失败")
                }
            }
            val digest = fullDigest.digest().toHex()
            require(digest == current.sha256) { "完整文件 SHA-256 校验失败" }

            require(directory.mkdirs() || directory.isDirectory) { "无法创建待保存目录" }
            val savedManifest = File(directory, MANIFEST_NAME)
            if (!savedManifest.isFile) {
                val sourceManifest = File(root, MANIFEST_NAME)
                require(sourceManifest.isFile) { "彩码 manifest 文件不存在" }
                val temporary = File(directory, "$MANIFEST_NAME.tmp")
                Files.copy(sourceManifest.toPath(), temporary.toPath(), StandardCopyOption.REPLACE_EXISTING)
                Files.move(temporary.toPath(), savedManifest.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }

            val stagedParts = ArrayList<File>(current.chunkCount)
            for ((part, source) in files) {
                val target = File(directory, part.filename)
                val file = requireNotNull(source)
                if (file.canonicalFile != target.canonicalFile) {
                    Files.move(file.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
                stagedParts += target
            }
            if (manifest?.identity == current.identity) {
                deliveredIdentity = current.identity
                partFiles.clear()
            }
            return CimbarCompletedFile(current.filename, stagedParts, current.totalSize, digest, identityKey(current))
        } catch (error: Throwable) {
            lastError = error.message ?: error.javaClass.simpleName
            throw error
        }
    }

    private fun clearPartFiles() {
        partFiles.clear()
        root.listFiles()?.forEach { file ->
            if (file.isFile && PART_PATTERN.matches(file.name)) file.delete()
        }
    }

    private fun pendingDirectory(key: String): File = File(root, "$PENDING_PREFIX$key")

    private fun identityKey(current: Manifest): String =
        MessageDigest.getInstance("SHA-256")
            .digest(current.identity.toByteArray(Charsets.UTF_8)).toHex()

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
        private const val SAVED_TRANSFER_NAME = ".saved-transfer"
        private const val PENDING_PREFIX = ".pending-"
        private const val MAX_MANIFEST_BYTES = 256 * 1024L
        private const val MAX_CHUNKS = 120
        private const val MAX_NAME_BYTES = 255
        private const val MAX_CHUNK_BYTES = 19L * 1024L * 1024L
        private const val MAX_TOTAL_BYTES = MAX_CHUNKS.toLong() * MAX_CHUNK_BYTES
        private const val STORAGE_RESERVE_BYTES = 64L * 1024L * 1024L
        private const val STORAGE_SPACE_ERROR_PREFIX = "手机存储空间不足"
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
