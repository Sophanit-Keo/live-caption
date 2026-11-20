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

## Subtitles

![Subtitle styles](docs/screenshots/subtitle-styles.png)

Tap the floating subtitles for a toolbar; it hides again after a few seconds:

![Subtitle toolbar](docs/screenshots/overlay-controls.png)

| Button | Does |
| --- | --- |
| ⏸ / ▶ | Pause or resume (pausing finishes the sentence in progress first) |
| 🔊 | Voice on / off (a headphone icon means it is waiting for headphones) |
| A− / A+ | Smaller / bigger text |
| Aa | Next style: Box, Outline (film-style, no box), High contrast (yellow on black) |
| ▭ | Show or hide the original text above the translation |
| 📄 | Open the transcript of this session (read it, summarize what was said so far) |
| 🔒 | Lock: touches go through to the app underneath, e.g. a video's controls. Unlock from the notification or by opening Live Caption |
| ⚙ | Open Live Caption settings |
| ✕ | Stop captions |

- **Moving:** drag the subtitles up or down; the position is remembered.
- **Readability:** a fixed number of lines (1–4) keeps the box from jumping, and the newest
  words stay visible at the bottom.
- **Dimmed words:** words that may still change (speech still being recognised) are drawn
  dimmer.
- **Status dot:** grey when listening, pulsing green when the translation is being spoken,
  amber when connecting, dark when paused.
- **Notification:** has Pause/Resume, Unlock (when locked) and Stop.

In settings, the **Subtitles** section has a live preview with style, text size, background,
lines and alignment. These settings, and the voice settings, apply to running captions
immediately.

![Subtitle settings](docs/screenshots/subtitle-settings.png)

## Transcripts and summaries

While captions run, every finished line (original, translation and time) is saved on the phone,
one file per session. Turn this off under **Transcripts and summaries** in settings.

| Saved sessions | A transcript with its summary |
| --- | --- |
| ![Saved transcripts](docs/screenshots/transcripts.png) | ![Transcript and summary](docs/screenshots/transcript-summary.png) |

- **Summary**: written by Gemini with your key: a title, a short overview, key points, and
  action items when there are decisions, tasks or questions. It is written in the translation
  language by default; you can pick the spoken language, the phone's language or English
  instead. While captions run, "Summarize so far" covers everything said up to that point.
- **Share / Copy** the transcript, with its summary, as text.
- **Save as file**: a text file (`.txt`), or a subtitle file (`.srt`) with the translation and
  original for each line, timed from when captions started.
- **Delete**: one transcript, or all of them (the one being recorded is kept).

Files are written as captions arrive, so a transcript survives the app being closed. Sessions in
which nothing was said are not kept. Transcripts are excluded from backups.

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
- **Gemini Live voice**:
  - **Start buffer**: each phrase starts after a ~0.25 s buffer, so network hiccups don't make
    it stutter.
  - **Catching up**: when it falls behind the video it speeds up in steps (×1.1, ×1.25, ×1.4)
    and returns to normal speed gradually. Beyond 12 s behind, the oldest speech is skipped.
  - **Subtitles in sync**: translated subtitles appear when the voice says the words, not
    seconds earlier. This can be switched off.
  - **Louder voice**: optional, so it stands out over the original audio.
- **Phone voice**:
  - **No gaps**: the next sentence is queued while the current one plays.
  - **Voice choice**: an installed, high-quality voice is preferred over a network voice,
    which adds a delay before each sentence.
  - **Keeping up**: if sentences pile up, older ones are skipped and the voice talks a little
    faster.

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
- **Topic hint**: an optional "What are you watching?" field ("football match", "physics
  lecture") is passed to both Gemini engines, to get names and specialist terms right.
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

`./gradlew testDebugUnitTest -Pscreenshots` also renders the subtitle UI to
`app/build/screenshots/` with Robolectric (it downloads Robolectric's Android runtime, ~200 MB,
so it is off by default).

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
| `CaptionOverlay` | Floating, draggable subtitle window and its toolbar (pause, voice, size, style, lock…) |
| `SubtitleView` | The caption box: styles, outlined text, newest lines at the bottom, status dot |
| `CaptionScheduler` | Shows Gemini Live's text when its voice speaks it, in order |
| `TranslationPipeline` | What to translate when: merging, context, rate-limit back-off |
| `TextTranslator` | ML Kit, Gemini and offline-Khmer translators |
| `LiveTranslate` | Gemini Live Translate WebSocket session and reconnecting manager |
| `VoiceOutput` | Text-to-speech voice, PCM player, ducking, headphone detection |
| `SpeechQueue` | Which sentences to speak and how fast (`PlaybackPacing` for Gemini's voice) |
| `Transcript` | Saving sessions (`TranscriptStore`), and text/.srt export (`TranscriptFormat`) |
| `Gemini` | Shared Gemini API calls and model fallback; `Summarizer` |
| `TranscriptsActivity`, `TranscriptActivity` | Saved sessions; one transcript with summary, share and save |
| `Languages`, `TextTidy` | Target language catalog per engine; text clean-up |
| `NllbTranslator`, `NllbTokenizer` | Offline NLLB-200 inference and its tokenizer |
| `ModelManager` | Downloads and unpacks speech models |
| `CaptionLine` | Rolling subtitle text buffers |
