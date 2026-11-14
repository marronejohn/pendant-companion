# Continuing development of Pendant Companion (latest version: v0.14.1)

You are picking up an existing, working Android app. Read this brief in full before changing anything, and keep every locked decision below intact.

## What the app is
Pendant Companion (package `com.pendantcompanion`) is a privacy-first Android companion app for the Limitless/Omi LS-PT-01 pendant (user's device serial the owner's device). It talks to the pendant directly over BLE, pulls recordings off it non-destructively, decrypts and transcribes everything on-device, and produces daily/periodic AI reflection cards. The user is the user (Australia). Nothing leaves the phone except transcripts the user chooses to mirror to their own Google Drive, and AI requests that use the user's own API key.

## Stack and build
- Kotlin + Jetpack Compose, single module. minSdk 26, target/compileSdk 35, arm64-v8a only, NDK 27.2.12479018, Java 17, Gradle 8.9. Debug-signed APKs.
- Repo: github.com/marronejohn/pendant-companion (branch `main`, commits as "the user" / the owner's email).
- The release APK must stay under ~20 MB (it is delivered via Google Drive), so jniLibs use `useLegacyPackaging` and native libs are stripped carefully.
- Version history lives in `app/build.gradle.kts` (current: versionCode 30, versionName "0.14.1").

## Core pipeline
1. **BLE sync**: direct flash-drain from the pendant, non-destructive by default (the pendant's own copy is never touched). Protocol references to verify against: github.com/MAkcanca/pendant-cli and github.com/aldrinjenson/pendant-sync (tested against pendant firmware v1.1.20).
2. **Decryption**: ECIES P-256 / HKDF-SHA256 / AES-128-GCM on the pulled audio frames.
3. **Audio**: Ogg Opus pipeline.
4. **Transcription**: on-device Whisper (whisper.cpp via JNI, class `WhisperBridge`). Models are ggml `.bin` files: tiny.en-q8_0 (~43 MB, default) or base.en-q5_1 (~60 MB). Models arrive either by in-app HTTPS download (validated by a native init load before being marked ready) or by SAF file pick. Batch transcription runs multiple workers (3 for tiny, 2 for base), skips already-transcribed sessions in `onlyNew` mode, and reports progress through a `BatchProgress` state (terminal note "finished"; batch-level failures clear after 30 s so nothing wedges).
5. **Voice scoring**: ECAPA-TDNN ONNX (84,251,116 B model, ONNX Runtime 1.20.0), enrollment via 3 s windows / 1.5 s hop with an energy gate, mean-pooled 192-dim embedding saved as `enroll.bin` + `speaker_enroll_version`. Score threshold default 0.75.
6. **Drive mirroring**: transcripts mirror to the user's chosen Drive folder (SAF tree URI pref `drive_tree_uri`) as `YYYY-MM-DD_session-N.txt`, pruned after 14 days; reflection/review JSONs persist there. Dedupe by session number + content.
7. **AI insights (bring-your-own-key only)**: presets for Gemini (default, free key), Groq, OpenRouter, plus a generic OpenAI-compatible "Other". The phone calls the provider on schedule: a daily reflection card at the user's chosen evening hour (default 9 pm), and weekly/monthly/quarterly/half-yearly/yearly cards generated overnight (~3 am via `AiReflectionBatchReceiver`, request code 9402). `maybeCatchUp` writes the day's card if the hour passed with none. Skip short or empty sessions.

## Guided setup wizard (`Onboarding.kt`)
First-run flow, shown once (`setup_done` pref): WELCOME (permissions) -> CONNECT (scan + pair, auto-advance) -> SYNC (auto-sync with ETA) -> MODEL (in-app download or file pick; a model already on the phone is validated on entry so returning users skip this) -> TRANSCRIBE (auto-kicks the batch, `onlyNew`) -> VOICE (30 s mic enrollment via `MicRecorder`, or learn from a pendant session, skippable) -> INSIGHTS (provider/key/Drive folder/card hour + test button) -> FINISH. The header reads "step N of 5" (welcome/connect share 1, model+transcribe share 3, insights/finish share 5).
As of v0.13.40 the wizard renders a **status trail**: the last six timestamped internal events (version tag, permissions, connected, sync finished, model validated, download tapped, batch state, errors) in small monospace at the bottom of every step, so a stuck screen diagnoses itself from a screenshot.

## Current state and open work
- v0.13.40 (commit 1550397) shipped the wizard status trail. After that: v0.14.0 (commit cdfc503) briefly replaced the wizard with a one-screen first run; the user asked for the wizard back. v0.14.1 (commit a93350b, versionCode 30) is the latest build: the wizard is the first-run screen again, and the long-standing voice bug is fixed - enrollFromMic called the busy-guarded scoreSessions() from inside the still-active enroll job, so mic-enrolled voiceprints never scored any session; it now calls rescoreAfterEnrollChange(), and unenrollSession clears displayed scores when no enrollment remains. The next release ladder (Setup Doctor, Home 3-state, local Day recap, one-tap AI, voice auto-learn, speed pass) is specified in docs/v0.14-brief.md; flagged decisions NOT to build without the user: hosted AI insights, bundled on-device LLM.
- Known open bug: after "Clear enrollment", voice scores stay at 0% until a working re-enrollment path runs; the wizard's `enrollFromMic` path (negative embedding keys `vec_-1.bin`) is the intended fix, still needs device validation.
- The app is offline except the AI feature. Existing user data (model file, enrollment, transcripts) must survive every upgrade: the wizard treats already-done steps as done and shows itself only once.

## Working rules
- The developer cannot test Bluetooth; sideload each APK and reports results. Keep changes small, one concern per version, and bump versionCode/versionName every build.
- Never break the existing data flow or rename prefs/files the app already reads.
- Deliver the debug-signed APK; do not add services, telemetry, or accounts. No analytics, no network calls beyond the model download and the user-configured AI provider.
- UI voice: plain, warm, second person; the app speaks to the user directly ("The pendant's own copy is untouched").
