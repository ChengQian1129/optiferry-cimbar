package com.airferrylite.receiver

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.system.Os
import android.system.OsConstants

import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.BitSet

/** All calls run on the protocol executor. A segment is durable before its bitmap bit. */
class BatchStore(private val context: Context) {
    enum class State { IDLE, SCANNING, RECEIVING_SEGMENT, COMMITTING_SEGMENT, VERIFYING_WHOLE_FILE,
        COMPLETE, STORAGE_FULL, BAD_SEGMENT_HASH, BAD_BATCH_METADATA, WHOLE_HASH_FAILED, CAMERA_ERROR }
    data class Progress(val name: String, val size: Long, val received: Long, val count: Int,
        val done: Int, val state: State, val storage: String, val elapsedMs: Long)
    private val root = File(context.filesDir,"optiferry-batches").apply { mkdirs() }
    private val resolver = context.contentResolver
    private val known = HashSet<String>()
    private var loaded = false
    var state = State.IDLE; private set
    private fun stateFile(id: String) = AtomicBatchJournal(File(root,"$id.json"))
    private fun read(id: String): JSONObject? = try {
        JSONObject(stateFile(id).read().toString(Charsets.UTF_8))
    } catch (_: java.io.FileNotFoundException) { null }
    private fun persist(id: String, j: JSONObject) {
        j.put("updated",System.currentTimeMillis())
        stateFile(id).write(j.toString().toByteArray(Charsets.UTF_8))
    }
    fun completedFrame(bytes: ByteArray): Boolean {
        if (!loaded) {
            root.listFiles()?.filter { it.name.endsWith(".json") }?.forEach { file ->
                try { val j=read(file.name.removeSuffix(".json")) ?: return@forEach
                    val sessions=j.optJSONObject("sessions") ?: return@forEach
                    val bitmap=BitSet.valueOf(android.util.Base64.decode(j.getString("bitmap"),android.util.Base64.DEFAULT))
                    // A crash during verification/publication must permit one segment to
                    // return and restart finalization instead of dropping every frame.
                    if (bitmap.cardinality() < j.getInt("count") || j.getString("status") == State.COMPLETE.name)
                        sessions.keys().forEach { known.add(it) }
                } catch (_: Exception) { /* Corrupt state is not trusted for skipping. */ }
            }
            loaded=true
        }
        return frameKey(bytes)?.let { known.contains(it) } ?: false
    }
    // Full AFL2 identity avoids false skips when unrelated 16-bit session IDs collide.
    fun frameKey(b: ByteArray): String? = if (HighSpeedAssembler.looksLikeFrame(b))
        Bfb1.hex(b.copyOfRange(0,4)) + Bfb1.hex(b.copyOfRange(8,20)) else null
    fun incompleteTransfers(): List<Progress> = root.listFiles().orEmpty()
        .filter { it.name.endsWith(".json") }.mapNotNull { file ->
            try {
                val j=read(file.name.removeSuffix(".json")) ?: return@mapNotNull null
                if(j.getString("status")==State.COMPLETE.name)return@mapNotNull null
                val bits=BitSet.valueOf(android.util.Base64.decode(j.getString("bitmap"),android.util.Base64.DEFAULT))
                val size=j.getLong("size");val nominal=j.getInt("segmentSize");var received=0L;var bit=bits.nextSetBit(0)
                while(bit>=0){received+=minOf(nominal.toLong(),size-bit.toLong()*nominal);bit=bits.nextSetBit(bit+1)}
                Progress(j.getString("name"),size,received,j.getInt("count"),bits.cardinality(),State.SCANNING,j.getString("mode"),System.currentTimeMillis()-j.getLong("created"))
            } catch (_:Exception) { null }
        }
    private fun initial(s: BatchSegment): JSONObject {
        val available=StatFs(context.filesDir.absolutePath).availableBytes
        if(s.fileSize > available - 16L*1024*1024) {
            state=State.STORAGE_FULL
            throw IOException("STORAGE_FULL: Required ${s.fileSize}, available $available")
        }
        val values=ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME,s.name)
            put(MediaStore.Downloads.MIME_TYPE,"application/octet-stream")
            put(MediaStore.Downloads.RELATIVE_PATH,"Download/OptiFerry")
            put(MediaStore.Downloads.IS_PENDING,1)
        }
        val uri=resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values)
            ?: throw IOException("Cannot create Download/OptiFerry output")
        var mode="Direct"
        try { resolver.openFileDescriptor(uri,"rw")!!.use { p ->
            Os.lseek(p.fileDescriptor,0,OsConstants.SEEK_SET)
            Os.ftruncate(p.fileDescriptor,s.fileSize)
            Os.fsync(p.fileDescriptor)
        } } catch (_: Exception) { mode="Staging fallback" }
        val j=JSONObject().put("version",1).put("batch",s.batch).put("salt",s.salt)
            .put("name",s.name).put("size",s.fileSize).put("whole",Bfb1.hex(s.wholeSha))
            .put("count",s.count).put("segmentSize",s.segmentSize).put("uri",uri.toString())
            .put("mode",mode).put("bitmap","").put("sessions",JSONObject())
            .put("created",System.currentTimeMillis()).put("status",State.SCANNING.name)
        try {
            if(mode!="Direct") RandomAccessFile(File(root,"${s.batch}.staging"),"rw").use { it.setLength(s.fileSize); it.fd.sync() }
            persist(s.batch,j)
        } catch(e:Exception) { resolver.delete(uri,null,null); throw e }
        return j
    }
    private fun metadataMatches(j: JSONObject,s: BatchSegment) = j.getInt("version")==1 &&
        j.getString("batch")==s.batch && j.getLong("salt")==s.salt && j.getString("name")==s.name &&
        j.getLong("size")==s.fileSize && j.getString("whole")==Bfb1.hex(s.wholeSha) &&
        j.getInt("count")==s.count && j.getInt("segmentSize")==s.segmentSize
    fun commit(s: BatchSegment, frameKey: String): Progress {
        state=State.COMMITTING_SEGMENT
        val j=read(s.batch) ?: initial(s)
        require(metadataMatches(j,s)) { "BAD_BATCH_METADATA: inconsistent batch" }
        val bits=BitSet.valueOf(android.util.Base64.decode(j.getString("bitmap"),android.util.Base64.DEFAULT))
        val uri=Uri.parse(j.getString("uri")); val direct=j.getString("mode")=="Direct"
        if(!bits[s.index]) {
            if(direct) resolver.openFileDescriptor(uri,"rw")!!.use { p ->
                var written=0
                while(written<s.dataSize) {
                    val n=Os.pwrite(p.fileDescriptor,s.payload,s.headerSize+written,s.dataSize-written,s.offset+written)
                    if(n<=0)throw IOException("Output write made no progress")
                    written+=n
                }
                Os.fsync(p.fileDescriptor)
            } else RandomAccessFile(File(root,"${s.batch}.staging"),"rw").use { f ->
                f.seek(s.offset); f.write(s.payload,s.headerSize,s.dataSize); f.fd.sync()
            }
            bits.set(s.index)
            j.put("bitmap",android.util.Base64.encodeToString(bits.toByteArray(),android.util.Base64.NO_WRAP))
            j.getJSONObject("sessions").put(frameKey,s.index)
            persist(s.batch,j)
            known.add(frameKey)
        }
        if(bits.cardinality()==s.count && j.getString("status")!=State.COMPLETE.name) {
            state=State.VERIFYING_WHOLE_FILE; j.put("status",state.name); persist(s.batch,j)
            val digest=MessageDigest.getInstance("SHA-256")
            val source=if(direct) resolver.openInputStream(uri)!! else FileInputStream(File(root,"${s.batch}.staging"))
            var total=0L
            source.use { input -> val buffer=ByteArray(256*1024); while(true){val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n);total+=n} }
            if(total!=s.fileSize || !digest.digest().contentEquals(s.wholeSha)) {
                state=State.WHOLE_HASH_FAILED; j.put("status",state.name)
                // Allow a later optical pass to overwrite and recheck all segments.
                j.put("bitmap","");j.getJSONObject("sessions").keys().forEach { known.remove(it) };j.put("sessions",JSONObject())
                persist(s.batch,j);throw IOException("WHOLE_HASH_FAILED: file remains unpublished")
            }
            if(!direct) {
                FileInputStream(File(root,"${s.batch}.staging")).use { input ->
                    android.os.ParcelFileDescriptor.AutoCloseOutputStream(resolver.openFileDescriptor(uri,"rwt")!!).use { output ->
                        input.copyTo(output,256*1024);output.flush();output.fd.sync()
                    }
                }
                // Verify the destination as well, before publication.
                val copied=MessageDigest.getInstance("SHA-256")
                resolver.openInputStream(uri)!!.use { input -> val b=ByteArray(256*1024);while(true){val n=input.read(b);if(n<0)break;copied.update(b,0,n)} }
                check(copied.digest().contentEquals(s.wholeSha)) { "WHOLE_HASH_FAILED: staging copy" }
            }
            val published=resolver.update(uri,ContentValues().apply { put(MediaStore.Downloads.IS_PENDING,0) },null,null)
            check(published==1) { "Cannot publish verified file" }
            state=State.COMPLETE;j.put("status",state.name);persist(s.batch,j)
            if(!direct)File(root,"${s.batch}.staging").delete()
        } else state=if(j.getString("status")==State.COMPLETE.name)State.COMPLETE else State.SCANNING
        var received=0L;var bit=bits.nextSetBit(0)
        while(bit>=0){received+=minOf(s.segmentSize.toLong(),s.fileSize-bit.toLong()*s.segmentSize);bit=bits.nextSetBit(bit+1)}
        return Progress(s.name,s.fileSize,received,s.count,bits.cardinality(),state,j.getString("mode"),System.currentTimeMillis()-j.getLong("created"))
    }
}






