package com.airferrylite.receiver

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

data class CimbarScanStats(
    val capturedFrames: Long,
    val submittedFrames: Long,
    val droppedFrames: Long,
    val completedFiles: Long,
    val lastFrameAtMs: Long
)

/** CameraX adapter that feeds YUV frames to the native Cimbar decoder. */
class CimbarFrameAnalyzer(
    private val dataPath: String,
    private val onCompleted: (String) -> Unit,
    private val onStats: (CimbarScanStats) -> Unit = {}
) : ImageAnalysis.Analyzer {
    private val idle = AtomicBoolean(false)
    private val captured = AtomicLong(0)
    private val submitted = AtomicLong(0)
    private val dropped = AtomicLong(0)
    private val completed = AtomicLong(0)
    @Volatile private var lastFrameAt = 0L

    override fun analyze(image: ImageProxy) {
        captured.incrementAndGet()
        if (idle.get()) {
            image.close()
            return
        }

        val nv21 = try {
            yuv420ToNv21(image)
        } catch (_: Throwable) {
            image.close()
            dropped.incrementAndGet()
            publishStats()
            return
        }
        val width = image.width
        val height = image.height
        val rotation = image.imageInfo.rotationDegrees
        image.close()

        val path = try {
            CimbarNative.processNv21(nv21, width, height, rotation, dataPath)
        } catch (_: Throwable) {
            dropped.incrementAndGet()
            ""
        }
        val now = android.os.SystemClock.elapsedRealtime()
        lastFrameAt = now
        if (path == NATIVE_FRAME_DROPPED) {
            dropped.incrementAndGet()
        } else {
            submitted.incrementAndGet()
            if (path.isNotBlank()) {
                completed.incrementAndGet()
                onCompleted(path)
            }
        }
        onStats(CimbarScanStats(captured.get(), submitted.get(), dropped.get(), completed.get(), now))
    }

    fun setAnalysisIdle(value: Boolean) {
        idle.set(value)
    }

    fun reset() {
        idle.set(false)
        captured.set(0)
        submitted.set(0)
        dropped.set(0)
        completed.set(0)
        lastFrameAt = 0L
        CimbarNative.reset()
    }

    /** Drops native stream state without resetting camera statistics. */
    fun resetNativeDecoder() {
        CimbarNative.reset()
    }

    fun close() {
        idle.set(true)
        CimbarNative.shutdown()
    }

    private fun yuv420ToNv21(image: ImageProxy): ByteArray {
        val width = image.width
        val height = image.height
        require(width % 2 == 0 && height % 2 == 0) { "OpenCV NV21 只支持偶数宽高" }
        val chromaWidth = (width + 1) / 2
        val chromaHeight = (height + 1) / 2
        val output = ByteArray(width * height + width * chromaHeight)
        val planes = image.planes
        copyPlane(planes[0].buffer, planes[0].rowStride, planes[0].pixelStride, width, height, output, 0, 1)
        val chromaOffset = width * height
        val u = planes[1]
        val v = planes[2]
        packNv21Chroma(
            width = width,
            chromaHeight = chromaHeight,
            output = output,
            outputOffset = chromaOffset,
            uBuffer = u.buffer,
            uRowStride = u.rowStride,
            uPixelStride = u.pixelStride,
            vBuffer = v.buffer,
            vRowStride = v.rowStride,
            vPixelStride = v.pixelStride,
        )
        return output
    }

    private fun publishStats() {
        val now = android.os.SystemClock.elapsedRealtime()
        lastFrameAt = now
        onStats(CimbarScanStats(captured.get(), submitted.get(), dropped.get(), completed.get(), now))
    }

    private fun copyPlane(
        buffer: ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        width: Int,
        height: Int,
        output: ByteArray,
        outputOffset: Int,
        outputStride: Int
    ) {
        for (row in 0 until height) {
            for (col in 0 until width) {
                output[outputOffset + row * width * outputStride + col * outputStride] =
                    planeByte(buffer, rowStride, pixelStride, col, row)
            }
        }
    }

    private fun planeByte(buffer: ByteBuffer, rowStride: Int, pixelStride: Int, col: Int, row: Int): Byte {
        val position = buffer.position() + row * rowStride + col * pixelStride
        return if (position in 0 until buffer.limit()) buffer.get(position) else 0
    }
}

private const val NATIVE_FRAME_DROPPED = "__OPTIFERRY_CIMBAR_DROPPED__"

/** Copies interleaved VU chroma for the even-width NV21 frames accepted by OpenCV. */
internal fun packNv21Chroma(
    width: Int,
    chromaHeight: Int,
    output: ByteArray,
    outputOffset: Int,
    uBuffer: ByteBuffer,
    uRowStride: Int,
    uPixelStride: Int,
    vBuffer: ByteBuffer,
    vRowStride: Int,
    vPixelStride: Int,
) {
    require(width > 0 && width % 2 == 0 && chromaHeight >= 0)
    val chromaWidth = width / 2
    require(outputOffset >= 0 && outputOffset + width * chromaHeight <= output.size)
    for (row in 0 until chromaHeight) {
        val rowStart = outputOffset + row * width
        val rowEnd = rowStart + width
        for (col in 0 until chromaWidth) {
            val out = rowStart + col * 2
            if (out >= rowEnd) break
            output[out] = planeByteAt(vBuffer, vRowStride, vPixelStride, col, row)
            if (out + 1 < rowEnd) {
                output[out + 1] = planeByteAt(uBuffer, uRowStride, uPixelStride, col, row)
            }
        }
    }
}

private fun planeByteAt(buffer: ByteBuffer, rowStride: Int, pixelStride: Int, col: Int, row: Int): Byte {
    val position = buffer.position() + row * rowStride + col * pixelStride
    return if (position in 0 until buffer.limit()) buffer.get(position) else 0
}
