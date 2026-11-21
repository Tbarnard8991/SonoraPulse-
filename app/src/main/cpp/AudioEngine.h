#pragma once
#include <oboe/Oboe.h>
#include <atomic>
#include <array>
#include <memory>
#include <mutex>

class AudioEngine : public oboe::AudioStreamDataCallback,
                    public oboe::AudioStreamErrorCallback {
public:
    bool start();
    void stop();
    int32_t readSamples(float* dest, int32_t maxSamples);
    int32_t sampleRate() const { return sampleRate_.load(std::memory_order_acquire); }

    // Real-time audio thread: no allocation, no locks.
    oboe::DataCallbackResult onAudioReady(
        oboe::AudioStream* stream,
        void* audioData,
        int32_t numFrames) override;

    // Called when the device route changes or the stream is disconnected.
    void onErrorAfterClose(oboe::AudioStream* stream, oboe::Result error) override;

private:
    bool openAndStartLocked();

    static constexpr int32_t kTargetSampleRate = 48000;
    static constexpr size_t kBufferCapacity = 16384; // Power of 2
    std::array<float, kBufferCapacity> ringBuffer_{};
    std::atomic<size_t> writeIndex_{0};
    std::atomic<size_t> readIndex_{0};
    std::atomic<int32_t> sampleRate_{kTargetSampleRate};
    std::atomic<bool> wantRunning_{false};

    std::mutex lock_; // guards stream_ open/close only, never touched by onAudioReady
    std::shared_ptr<oboe::AudioStream> stream_;
};
