# ClipPulse

ClipPulse is a native Android AI short-form video clipping studio built with **Kotlin**, **Jetpack Compose (Material 3)**, **AndroidX Media3**, **Google ML Kit Face Detection**, **Room**, and **Firebase AI Logic**.

## Architecture Overview

1. **On-Device Audio Extraction (`AudioExtractor`)**
   - Original local videos stay strictly as `content://` URIs and are never uploaded.
   - Extracts only the audio track via `MediaExtractor` + `MediaCodec`, downmixes to mono, resamples to 16 kHz, computes a 50 ms RMS loudness envelope in the same pass, and encodes to compact 28 kbps AAC `.m4a` via `MediaMuxer` in app cache (temp files are always deleted in `finally`).
   - Keeps single-call uploads under 14 MB (~18.6 MB with base64 overhead, safely under Firebase AI Logic's 20 MB ceiling). Longer recordings automatically split into consecutive <= 45-minute chunks with 15 s overlap and merge/de-duplicate detected clips.
2. **Audio-Only Viral Hook & Caption Detection (`GeminiClipAnalyzer`)**
   - Uses **Firebase AI Logic** (`Firebase.ai`) with `gemini-3.8-flash` and strict JSON `responseSchema`.
   - **No `GEMINI_API_KEY` is needed**: authentication and attestation are handled automatically by **Firebase App Check** (`configureAppCheck(this, intent)` in `MainActivity.onCreate()`). Do not add `GEMINI_API_KEY` to `.env`, `local.properties`, or `BuildConfig`.
   - Post-processes timestamps by clamping to exact audio duration, snapping clip boundaries to the quietest loudness gap within ±1 s, dropping clips under 5 s, storing clip-relative captions, and distributing word timings proportionally by character count for karaoke-style highlighting.
3. **Smart 9:16 Framing & Local Media3 Export**
   - Runs **ML Kit Face Detection** locally on the original `content://` video URI (with safe center-crop fallback).
   - Uses **AndroidX Media3** (`ExoPlayer` + `TextureView` preview, `Transformer` + `InAppMuxer` MP4 export with color grading and burned-in word-highlighted captions).
   - Tracks MP4 export progress in a persistent **Foreground Service** notification with direct **Pause / Resume** and **Cancel** actions, saving finished videos to `Movies/ClipPulse` via Scoped Storage (`MediaStore.Video.Media`).
4. **Local Persistence (`Room`)**
   - Stores projects, generated viral clips, caption timings, and export history metadata + thumbnails locally on-device (`android:allowBackup="false"`).

## Build & Signing Configuration

> **IMPORTANT — Do NOT Modify `signingConfigs` or Keystore Files**
> Never delete, rename, or modify `debug.keystore`, `debug.keystore.base64`, or the `signingConfigs` (`release` and `debugConfig`) and `signingConfig` assignments in `app/build.gradle.kts`. They are required for building, installing, and updating the app reliably in AI Studio.

### Running Unit Tests

```bash
gradle :app:testDebugUnitTest
```
