package com.sonorapulse.app.audio

object NativeAudioBridge {
    init {
        System.loadLibrary("sonorapulse")
    }

    external fun startCapture(): Boolean
    external fun stopCapture()
    external fun getSampleRate(): Int
    external fun readSamples(outBuffer: FloatArray): Int
}
