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
- **Translation**: Google ML Kit on-device translation, ~59 target languages, plus **Khmer**
  through Gemini (online) or an offline NLLB model (see below).
- **Offline**: models are downloaded once on first use (speech 30–140 MB, translation ~30 MB);
  after that no audio or text leaves the phone (except caption text when using Gemini).

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

## Khmer

ML Kit has no Khmer model, so the app offers two Khmer engines under "Translate to":

| | Khmer (Gemini, online) | Khmer (offline) |
| --- | --- | --- |
| Engine | Google Gemini API | Meta NLLB-200 distilled 600M on ONNX Runtime |
| Needs | Internet and your own Gemini API key | One-time 910 MB download |
| Speed | About a second | A few seconds per sentence; shown after the sentence ends |
| Quality | Better | Lower |
| Privacy | Caption text (never audio) goes to Google | Nothing leaves the phone |

**Gemini key**: create one for free at <https://aistudio.google.com/apikey> and paste it into
the key field that appears in the app. Free keys are limited to a few requests per minute, so
the app translates finished sentences first and refreshes the sentence in progress at most
every 6 seconds.

**Offline model**: NLLB-200 is licensed CC-BY-NC 4.0 (non-commercial use only).

`NllbModelTest` runs the real model on a desktop JVM when `NLLB_DIR` points at a folder with
`tokenizer.json` and the two `.onnx` files; it is skipped otherwise.

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
| `TextTranslator` | ML Kit, Gemini and offline-Khmer translators |
| `NllbTranslator`, `NllbTokenizer` | Offline NLLB-200 inference and its tokenizer |
| `ModelManager` | Downloads and unpacks speech models |
| `CaptionLine` | Rolling subtitle text buffer |
