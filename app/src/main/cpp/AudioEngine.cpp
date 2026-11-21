#include "AudioEngine.h"
#include <android/log.h>
#include <algorithm>

#define LOG_TAG "SonoraPulse"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

bool AudioEngine::start() {
    std::lock_guard<std::mutex> guard(lock_);
    wantRunning_.store(true);
    // Fresh session: discard any samples left over from a previous run.
    writeIndex_.store(0, std::memory_order_relaxed);
    readIndex_.store(0, std::memory_order_relaxed);
    return openAndStartLocked();
}

bool AudioEngine::openAndStartLocked() {
    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Input)
           ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
           ->setSharingMode(oboe::SharingMode::Exclusive)
           ->setInputPreset(oboe::InputPreset::Unprocessed)
           ->setFormat(oboe::AudioFormat::Float)
           ->setChannelCount(oboe::ChannelCount::Mono)
           ->setSampleRate(kTargetSampleRate)
           // If the mic runs at 44.1 kHz natively, Oboe resamples to 48 kHz.
           ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium)
           ->setDataCallback(this)
           ->setErrorCallback(this);

    oboe::Result result = builder.openStream(stream_);
    if (result != oboe::Result::OK || !stream_) {
        LOGW("openStream failed: %s", oboe::convertToText(result));
        stream_.reset();
        return false;
    }
    // Report the rate actually delivered so the DSP never assumes it.
    sampleRate_.store(stream_->getSampleRate(), std::memory_order_release);
    return stream_->requestStart() == oboe::Result::OK;
}

void AudioEngine::stop() {
    std::lock_guard<std::mutex> guard(lock_);
    wantRunning_.store(false);
    if (stream_) {
        stream_->stop();
        stream_->close();
        stream_.reset();
    }
}

oboe::DataCallbackResult AudioEngine::onAudioReady(
        oboe::AudioStream*, void* audioData, int32_t numFrames) {
    const auto* samples = static_cast<const float*>(audioData);
    size_t w = writeIndex_.load(std::memory_order_relaxed);
    for (int32_t i = 0; i < numFrames; ++i) {
        ringBuffer_[w & (kBufferCapacity - 1)] = samples[i];
        ++w;
    }
    writeIndex_.store(w, std::memory_order_release);
    return oboe::DataCallbackResult::Continue;
}

void AudioEngine::onErrorAfterClose(oboe::AudioStream* closed, oboe::Result error) {
    LOGW("Stream closed (%s), reopening", oboe::convertToText(error));
    std::lock_guard<std::mutex> guard(lock_);
    // Ignore errors from an old stream that stop()/start() already replaced.
    if (!stream_ || stream_.get() != closed) return;
    stream_.reset();
    if (wantRunning_.load()) openAndStartLocked();
}

int32_t AudioEngine::readSamples(float* dest, int32_t maxSamples) {
    size_t w = writeIndex_.load(std::memory_order_acquire);
    size_t r = readIndex_.load(std::memory_order_relaxed);
    if (r > w) r = w; // indices were reset by start()
    size_t available = w - r;
    if (available > kBufferCapacity) {
        r = w - kBufferCapacity; // Reader fell behind: drop oldest samples
        available = kBufferCapacity;
    }
    int32_t toRead = std::min<int32_t>(maxSamples, static_cast<int32_t>(available));
    for (int32_t i = 0; i < toRead; ++i) {
        dest[i] = ringBuffer_[(r + i) & (kBufferCapacity - 1)];
    }
    readIndex_.store(r + toRead, std::memory_order_release);
    return toRead;
}
