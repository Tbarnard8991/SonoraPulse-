package com.sonorapulse.app.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sonorapulse.app.audio.NativeAudioBridge
import com.sonorapulse.app.dsp.AudioTelemetry
import com.sonorapulse.app.dsp.DspTranslator
import com.sonorapulse.app.export.ExportResult
import com.sonorapulse.app.export.TelemetryExporter
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class SonoraPulseViewModel : ViewModel() {
    private val _telemetry = MutableStateFlow(AudioTelemetry())
    val telemetry: StateFlow<AudioTelemetry> = _telemetry.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _sampleRate = MutableStateFlow(0)
    val sampleRate: StateFlow<Int> = _sampleRate.asStateFlow()

    // ~64 s of history at 48 kHz with a 1024-sample hop (≈47 frames/s).
    private val maxHistory = 3000
    private val history = ArrayDeque<AudioTelemetry>(maxHistory)
    private var captureJob: Job? = null

    fun toggleCapture() {
        if (_isRunning.value) stop() else start()
    }

    fun stopIfRunning() {
        if (_isRunning.value) stop()
    }

    private fun start() {
        if (!NativeAudioBridge.startCapture()) return
        val rate = NativeAudioBridge.getSampleRate()
        _sampleRate.value = rate
        val translator = DspTranslator(sampleRate = rate.toFloat())
        val fftSize = translator.fftSize
        val hop = fftSize / 2

        _isRunning.value = true
        captureJob = viewModelScope.launch(Dispatchers.Default) {
            val frame = FloatArray(fftSize)
            val temp = FloatArray(hop)
            var filled = 0
            while (isActive) {
                val read = NativeAudioBridge.readSamples(temp)
                if (read <= 0) {
                    delay(5)
                    continue
                }
                var i = 0
                while (i < read) {
                    val n = minOf(read - i, fftSize - filled)
                    System.arraycopy(temp, i, frame, filled, n)
                    filled += n
                    i += n
                    if (filled == fftSize) {
                        val result = translator.analyze(frame)
                        _telemetry.value = result
                        synchronized(history) {
                            if (history.size >= maxHistory) history.removeFirst()
                            history.addLast(result)
                        }
                        // 50% overlap: keep the newest half as the start of the next frame.
                        System.arraycopy(frame, hop, frame, 0, fftSize - hop)
                        filled = fftSize - hop
                    }
                }
            }
        }
    }

    private fun stop() {
        captureJob?.cancel()
        captureJob = null
        NativeAudioBridge.stopCapture()
        _isRunning.value = false
    }

    fun exportHistory(context: Context, onResult: (ExportResult) -> Unit) {
        val snapshot = synchronized(history) { history.toList() }
        val appContext = context.applicationContext
        viewModelScope.launch {
            onResult(TelemetryExporter.exportCsv(appContext, snapshot))
        }
    }

    override fun onCleared() {
        stopIfRunning()
        super.onCleared()
    }
}
