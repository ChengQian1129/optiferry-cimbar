package com.airferrylite.receiver

/** JNI bridge for the vendored CFC/libcimbar decoder. */
object CimbarNative {
    init {
        System.loadLibrary("cimbar-native")
    }

    external fun processNv21(
        nv21: ByteArray,
        width: Int,
        height: Int,
        rotationDegrees: Int,
        dataPath: String,
        mode: Int = 68
    ): String

    external fun reset()

    external fun shutdown()
}
