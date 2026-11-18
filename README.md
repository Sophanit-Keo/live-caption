# Live Caption

An Android app that shows real-time subtitles in a floating box on top of any app, translates
them, and can speak the translation into your headphones.

## Translation engines

Pick one under **Translation engine**:

| | Offline | Gemini | Gemini Live |
| --- | --- | --- | --- |
| How | Vosk speech-to-text → ML Kit (or NLLB for Khmer) | Vosk speech-to-text → Gemini text translation | Audio → Gemini 3.5 Live Translate → translated speech + captions |
| Spoken languages | 25 (chosen up front) | 25 (chosen up front) | 70+, detected automatically (Khmer included) |
| Target languages | ~59 + Khmer | ~59 + Khmer + Live languages | 78 (Khmer included) |
| Voice in headphones | Phone's text-to-speech voice | Phone's text-to-speech voice | Gemini's natural voice, close to real time |
| Needs | One-time model downloads | Internet + Gemini API key | Internet + Gemini API key |
| What leaves the phone | Nothing | Caption text | The audio |

```
Offline / Gemini:  audio ─▶ Vosk ─▶ TranslationPipeline ─▶ ML Kit / NLLB / Gemini ─▶ overlay
                                                                              └──▶ text-to-speech ─▶ headphones
Gemini Live:       audio ─▶ LiveTranslator (WebSocket) ─▶ translated speech ─▶ headphones
                                                       └─▶ both transcripts ─▶ overlay
```

**Gemini API key**: create one for free at <https://aistudio.google.com/apikey> and paste it
into the key field. Both Gemini engines work on the free tier; Google limits free keys per
minute and per day. Live Translate on a paid key costs about $0.037 per minute of audio
(Google's pricing page, November 2026).

## Spoken translation in headphones

Turn on **Speak the translation in my headphones**. Then:

- **Headphones only by default**: wired, USB, Bluetooth or hearing aids. Without them the
  voice waits (headphone icon on the captions) unless "Use the phone speaker" is on.
- **Mute button**: the speaker button on the floating captions mutes or unmutes the voice at
  any time.
- **Ducking**: other apps' audio is lowered while the translation speaks. Some apps pause
  instead of lowering; turn off "Lower other audio" if that happens.
- **No feedback**: the app's own voice is excluded from audio capture, so it is never
  captioned again. In Microphone mode without headphones, the microphone is muted while the
  phone speaks.
- **Keeping up**: if speech piles up, the phone voice skips older sentences and talks a little
  faster. Gemini Live's voice speeds up, and skips ahead when it falls more than 15 s behind.

For the Offline and Gemini engines, the settings screen checks that your phone has a
text-to-speech voice for the target language, offers to download it, and has a **Test voice**
button. Speech Services by Google includes Khmer.

## Translation quality

- **Context**: Gemini receives the last three captions and their translations, so names,
  pronouns and terms stay consistent. It is told the text comes from speech recognition, so it
  can fix misheard words.
- **Model**: Gemini uses `gemini-3.5-flash-lite` (low latency, tuned for translation) and falls
  back to `gemini-flash-latest` if a key can't use it.
- **No dropped sentences**: if sentences pile up, or Google's rate limit is hit, they are
  merged and translated together after the pause.
- **Sentence clean-up**: finished sentences get a capital letter and end punctuation before
  translation.
- **Chinese and Japanese**: the word spaces that speech recognition inserts are removed.
- **Offline Khmer**: the model's space after every word is replaced by invisible joiners, so it
  reads like normal Khmer. Decoding loops are cut.

## Build

Requires JDK 17+ and the Android SDK (platform 36).

```
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest
```

Every push also builds the APK in GitHub Actions (see the run's **Artifacts**).

`NllbModelTest` runs the real offline Khmer model on a desktop JVM when `NLLB_DIR` points at a
folder with `tokenizer.json` and the two `.onnx` files; it is skipped otherwise. The Gemini
and Live Translate tests run against local fake servers; they do not call Google.

## Use

1. Pick the engine, the language to translate to, and the audio source.
2. Tap **Start captions** and grant: microphone/audio recording, "Display over other apps",
   and (for device audio) the screen-capture prompt.
3. Switch to any app. Drag the caption box up or down; tap ✕ or the notification to stop.

## Known limits

- **Blocked apps**: apps can opt out of audio capture. Phone/VoIP calls and some streaming and
  music apps cannot be captioned from device audio; use Microphone mode.
- **Spoken language (Offline and Gemini)**: it must be chosen up front, and small Vosk models
  favour speed over accuracy.
- **Gemini Live is a preview model**: Google may change it. The app reconnects about every 10
  minutes when Google ends the connection, which can cost a moment of speech.
- **Offline Khmer** needs a 910 MB download and takes a few seconds per sentence. NLLB-200 is
  licensed CC-BY-NC 4.0 (non-commercial use only).

## Code map

| File | Role |
| --- | --- |
| `MainActivity` | Settings screen, permission flow, voice check and preview |
| `CaptionService` | Foreground service: capture → recognise/translate → overlay and voice |
| `CaptionOverlay` | Floating, draggable subtitle window with close and voice buttons |
| `TranslationPipeline` | What to translate when: merging, context, rate-limit back-off |
| `TextTranslator` | ML Kit, Gemini and offline-Khmer translators |
| `LiveTranslate` | Gemini Live Translate WebSocket session and reconnecting manager |
| `VoiceOutput` | Text-to-speech voice, PCM player, ducking, headphone detection |
| `Languages`, `TextTidy` | Target language catalog per engine; text clean-up |
| `NllbTranslator`, `NllbTokenizer` | Offline NLLB-200 inference and its tokenizer |
| `ModelManager` | Downloads and unpacks speech models |
| `CaptionLine` | Rolling subtitle text buffers |
