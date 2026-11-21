package com.sonorapulse.app.dsp

data class AudioTelemetry(
    val timestampMs: Long = 0L,
    val rmsDbfs: Float = -96f,
    val peakFreqHz: Float = 0f,
    val spectralCentroidHz: Float = 0f,
    val spectrumBars: FloatArray = FloatArray(32), // Log-spaced 20 Hz–20 kHz, normalized 0f..1f
    val subBassDb: Float = -96f,     // 20–60 Hz
    val bassDb: Float = -96f,        // 60–250 Hz
    val midDb: Float = -96f,         // 250–2000 Hz
    val presenceDb: Float = -96f,    // 2000–6000 Hz
    val brillianceDb: Float = -96f   // 6000–20000 Hz
)
