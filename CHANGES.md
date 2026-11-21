# SonoraPulse: corrected starter code

Drop the app/ folder over your Android Studio project's app/ folder.

## What changed
1. minSdk 26 -> 29: MediaStore.Downloads does not exist before Android 10 (crash fix).
2. Audio engine reports its real sample rate; Oboe resamples to 48 kHz if the mic runs at 44.1 kHz. DSP uses the real rate, so Hz readings stay correct.
3. Edge-to-edge (mandatory at target API 36): enableEdgeToEdge() + safeDrawingPadding().
4. 16 KB page alignment linker flag; build with NDK r28+ and check in Build > Analyze APK.
5. Spectrum bars are 32 log-spaced bands from 20 Hz to 20 kHz (was linear, 47 Hz-12 kHz).
6. Hann window gain corrected: a full-scale sine now reads 0 dB, not -6 dB.
7. Peak frequency uses parabolic interpolation (sub-bin accuracy).
8. FFT 1024 -> 2048 with 50% overlap: 23.4 Hz bins, still ~47 updates/s.
9. Five named bands now report dB (summed power) and show on screen.
10. Error callback reopens the stream after headphone/route changes.
11. Ring buffer reset on start (no stale audio); capacity 16384.
12. CSV export runs on Dispatchers.IO, uses IS_PENDING, deletes partial files, and reports real errors.
13. Capture stops when the app goes to the background (ON_STOP).
14. History holds ~64 s (3000 frames) instead of ~13 s.
15. kotlinOptions -> kotlin { compilerOptions }; JNI uses SetFloatArrayRegion.

## Test on a real phone
- Play a 440 Hz tone: PEAK should read 440 +/- 1 Hz.
- Full-volume tone should read near 0 dB on its bar; silence near the floor.
- Plug/unplug headphones while capturing: readouts should resume within a second.
- Background the app, return, tap Start: capture restarts cleanly.
- Export CSV: file appears in Downloads/SonoraPulse.
