package com.airferrylite.receiver

import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class CimbarFrameAnalyzerTest {
    @Test
    fun evenWidthChromaRespectsPlanePixelAndRowStrides() {
        val output = ByteArray(8)
        val u = ByteBuffer.wrap(byteArrayOf(99, 20, 99, 21, 99, 99, 99, 22, 99, 23))
            .apply { position(1) }
        val v = ByteBuffer.wrap(byteArrayOf(99, 10, 99, 11, 99, 99, 99, 12, 99, 13))
            .apply { position(1) }
        packNv21Chroma(
            width = 4,
            chromaHeight = 2,
            output = output,
            outputOffset = 0,
            uBuffer = u,
            uRowStride = 6,
            uPixelStride = 2,
            vBuffer = v,
            vRowStride = 6,
            vPixelStride = 2,
        )

        assertArrayEquals(
            byteArrayOf(10, 20, 11, 21, 12, 22, 13, 23),
            output,
        )
    }
}
