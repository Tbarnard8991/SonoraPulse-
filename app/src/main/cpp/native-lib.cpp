#include <jni.h>
#include <vector>
#include "AudioEngine.h"

static AudioEngine gEngine;
static std::vector<float> gScratch; // only touched on the Kotlin reader thread

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_sonorapulse_app_audio_NativeAudioBridge_startCapture(JNIEnv*, jobject) {
    return gEngine.start() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_sonorapulse_app_audio_NativeAudioBridge_stopCapture(JNIEnv*, jobject) {
    gEngine.stop();
}

JNIEXPORT jint JNICALL
Java_com_sonorapulse_app_audio_NativeAudioBridge_getSampleRate(JNIEnv*, jobject) {
    return gEngine.sampleRate();
}

JNIEXPORT jint JNICALL
Java_com_sonorapulse_app_audio_NativeAudioBridge_readSamples(
        JNIEnv* env, jobject, jfloatArray outArray) {
    jsize len = env->GetArrayLength(outArray);
    if (static_cast<jsize>(gScratch.size()) < len) gScratch.resize(len);
    int32_t count = gEngine.readSamples(gScratch.data(), len);
    if (count > 0) env->SetFloatArrayRegion(outArray, 0, count, gScratch.data());
    return count;
}

}
