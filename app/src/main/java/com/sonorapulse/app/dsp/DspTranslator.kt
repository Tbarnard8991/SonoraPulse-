package com.sonorapulse.app.dsp

import kotlin.math.*

/**
 * 2048-point FFT at the stream's real sample rate (≈23.4 Hz per bin at 48 kHz).
 * Feed it overlapping frames (hop = fftSize / 2) for ~47 updates per second.
 */
class DspTranslator(
    private val sampleRate: Float,
    val fftSize: Int = 2048,
    private val barCount: Int = 32,
    private val minHz: Float = 20f,
    private val maxHz: Float = 20_000f
) {
    private val half = fftSize / 2
    private val binHz = sampleRate / fftSize
    private val hann = FloatArray(fftSize) { i ->
        (0.5 * (1.0 - cos(2.0 * PI * i / (fftSize - 1)))).toFloat()
    }
    // Hann coherent gain is 0.5, so a full-scale sine peaks at N/4 after the FFT.
    private val ampScale = 4f / fftSize
    private val real = FloatArray(fftSize)
    private val imag = FloatArray(fftSize)
    private val magnitudes = FloatArray(half)

    // Log-spaced bar edges, in bins, clamped to Nyquist.
    private val barEdges = IntArray(barCount + 1) { i ->
        val top = min(maxHz, sampleRate / 2f)
        val hz = minHz * (top / minHz).pow(i.toFloat() / barCount)
        (hz / binHz).roundToInt().coerceIn(1, half - 1)
    }

    fun analyze(pcm: FloatArray): AudioTelemetry {
        var sumSq = 0.0
        for (i in 0 until fftSize) {
            val s = pcm[i]
            sumSq += s * s
            real[i] = s * hann[i]
            imag[i] = 0f
        }
        val rms = sqrt(sumSq / fftSize).toFloat().coerceAtLeast(1e-6f)
        val rmsDbfs = (20f * log10(rms)).coerceIn(-96f, 0f)

        fft(real, imag)

        var peakBin = 1
        var weightedFreq = 0.0
        var magSum = 0.0
        magnitudes[0] = 0f
        for (i in 1 until half) {
            val mag = hypot(real[i], imag[i]) * ampScale
            magnitudes[i] = mag
            if (mag > magnitudes[peakBin]) peakBin = i
            weightedFreq += i * binHz * mag
            magSum += mag
        }

        // Parabolic interpolation on log magnitude for sub-bin peak accuracy.
        var peakHz = peakBin * binHz
        if (peakBin in 2 until half - 1) {
            val a = ln(magnitudes[peakBin - 1] + 1e-12f)
            val b = ln(magnitudes[peakBin] + 1e-12f)
            val c = ln(magnitudes[peakBin + 1] + 1e-12f)
            val denom = a - 2f * b + c
            if (denom != 0f) {
                val offset = (0.5f * (a - c) / denom).coerceIn(-0.5f, 0.5f)
                peakHz = (peakBin + offset) * binHz
            }
        }

        val bars = FloatArray(barCount) { b ->
            val lo = barEdges[b]
            val hi = max(lo + 1, barEdges[b + 1])
            var peak = 0f
            for (k in lo until min(hi, half)) if (magnitudes[k] > peak) peak = magnitudes[k]
            val db = (20f * log10(peak.coerceAtLeast(1e-5f))).coerceIn(-80f, 0f)
            (db + 80f) / 80f
        }

        return AudioTelemetry(
            timestampMs = System.currentTimeMillis(),
            rmsDbfs = rmsDbfs,
            peakFreqHz = if (magnitudes[peakBin] > 1e-5f) peakHz else 0f,
            spectralCentroidHz = if (magSum > 1e-6) (weightedFreq / magSum).toFloat() else 0f,
            spectrumBars = bars,
            subBassDb = bandDb(20f, 60f),
            bassDb = bandDb(60f, 250f),
            midDb = bandDb(250f, 2000f),
            presenceDb = bandDb(2000f, 6000f),
            brillianceDb = bandDb(6000f, 20000f)
        )
    }

    /** Band energy in dB: sum of power across the band's bins. */
    private fun bandDb(lowHz: Float, highHz: Float): Float {
        val lo = (lowHz / binHz).roundToInt().coerceIn(1, half - 1)
        val hi = (highHz / binHz).roundToInt().coerceIn(lo, half - 1)
        var power = 0.0
        for (k in lo..hi) power += magnitudes[k].toDouble() * magnitudes[k]
        return (10.0 * log10(power + 1e-12)).toFloat().coerceIn(-96f, 0f)
    }

    private fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wLenRe = cos(ang).toFloat()
            val wLenIm = sin(ang).toFloat()
            val halfLen = len / 2
            for (i in 0 until n step len) {
                var wRe = 1f
                var wIm = 0f
                for (k in 0 until halfLen) {
                    val uRe = re[i + k]
                    val uIm = im[i + k]
                    val vRe = re[i + k + halfLen] * wRe - im[i + k + halfLen] * wIm
                    val vIm = re[i + k + halfLen] * wIm + im[i + k + halfLen] * wRe
                    re[i + k] = uRe + vRe
                    im[i + k] = uIm + vIm
                    re[i + k + halfLen] = uRe - vRe
                    im[i + k + halfLen] = uIm - vIm
                    val nextWRe = wRe * wLenRe - wIm * wLenIm
                    wIm = wRe * wLenIm + wIm * wLenRe
                    wRe = nextWRe
                }
            }
            len = len shl 1
        }
    }
}
