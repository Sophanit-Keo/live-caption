# Live Caption

An Android app that shows real-time subtitles — and an optional live translation — in a
floating box on top of any app.

## How it works

```
device audio / microphone ─▶ Vosk speech-to-text ─▶ ML Kit translation ─▶ floating overlay
   (MediaProjection)            (on-device)            (on-device)        (draws over apps)
```

- **Audio**: "Device audio" captures what other apps are playing (Android 10+ playback
  capture). "Microphone" listens to the room or the phone's own speaker.
- **Speech-to-text**: [Vosk](https://alphacephei.com/vosk/) small models, 25 spoken languages.
- **Translation**: Google ML Kit on-device translation, ~59 target languages. **Khmer** is
  translated online instead (see below).
- **Offline**: models are downloaded once on first use (speech 30–140 MB, translation ~30 MB);
  after that no audio or text leaves the phone (except with an online language).

## Build

Requires JDK 17+ and the Android SDK (platform 36).

```
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest
```

Every push also builds the APK in GitHub Actions (see the run's **Artifacts**).

## Use

1. Pick the spoken language, the language to translate to, and the audio source.
2. Tap **Start captions** and grant: microphone/audio recording, "Display over other apps",
   and (for device audio) the screen-capture prompt.
3. Switch to any app. Drag the caption box up or down; tap ✕ or the notification to stop.

## Khmer (online translation)

ML Kit has no Khmer model, so choosing **Khmer (online)** sends the caption text (never the
audio) to the Google Cloud Translation API with your own key:

1. In [Google Cloud Console](https://console.cloud.google.com/) create a project, enable
   billing and the **Cloud Translation API**.
2. Create an API key under *APIs & Services → Credentials* (restrict it to Cloud Translation).
3. Paste it into the key field that appears in the app.

Usage is billed by Google per character translated. To keep that down, the sentence in
progress is re-translated at most every 1.2 seconds.

## Known limits

- Apps can opt out of audio capture. Phone/VoIP calls and some streaming and music apps
  cannot be captioned from device audio — use Microphone mode with the speaker on.
- The spoken language must be chosen up front; there is no automatic language detection.
- Small Vosk models favour speed over accuracy and output no punctuation.
- Languages are limited to what Vosk (speech) and ML Kit (translation) support.

## Code map

| File | Role |
| --- | --- |
| `MainActivity` | Settings screen, permission flow |
| `CaptionService` | Foreground service: capture → recognise → translate |
| `CaptionOverlay` | Floating, draggable subtitle window |
| `TextTranslator` | Offline (ML Kit) and online (Cloud Translation) translators |
| `ModelManager` | Downloads and unpacks speech models |
| `CaptionLine` | Rolling subtitle text buffer |
