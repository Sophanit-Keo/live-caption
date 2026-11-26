# Live Caption

An Android app that shows real-time subtitles — and an optional live translation — in a
floating box on top of any app.

## How it works

```
device audio / microphone ─▶ Vosk speech-to-text ─▶ translation ───────────▶ floating overlay
   (MediaProjection)            (on-device)          on the phone: ML Kit,  │  (draws over apps)
                                                     NLLB for Khmer;        └▶ spoken in headphones
                                                     or Gemini (online)        (text-to-speech, optional)
```

- **Audio**: "Device audio" captures what other apps are playing (Android 10+ playback
  capture). "Microphone" listens to the room or the phone's own speaker.
- **Speech-to-text**: [Vosk](https://alphacephei.com/vosk/) small models, 25 spoken languages.
- **Translation**: on the phone with Google ML Kit (~59 languages) or, for **Khmer**, Meta's
  NLLB model; or online with Gemini, the most accurate option, for every language (see
  [Translation engines](#translation-engines)).
- **Spoken translation**: optionally reads each translated sentence aloud in your headphones
  (see below).
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

1. Pick the spoken language, the language to translate to (and whether to translate on the
   phone or with Gemini), and the audio source.
2. Tap **Start captions** and grant: microphone/audio recording, "Display over other apps",
   and (for device audio) the screen-capture prompt.
3. Switch to any app. Drag the caption box up or down; tap ✕ or the notification to stop.

## Spoken translation in headphones

Turn on **Speak translation in headphones** and connect wired, USB or Bluetooth headphones:
each finished sentence is translated and then read aloud by the phone's text-to-speech voice.

- The voice only plays while headphones are connected, never on the phone speaker — there the
  microphone would hear it and caption it again. Plug headphones in or out at any time; the
  settings screen shows whether they are detected.
- **Voice**: the best installed voice for the language is used (voices that work offline
  first, then the highest quality). If the phone's default speech engine has no voice for the
  language (some phones' built-in engines have none for Khmer), the app uses another installed
  engine that does, preferring Google's. If none has one, the app says so; add a voice in
  Android Settings → Text-to-speech output.
- **No cut-offs**: one sentence plays at a time and is never interrupted. If the voice falls
  behind, it speaks up to 20% faster and drops the oldest waiting sentences instead.
- **Other sound**: the video or music is turned down while the voice speaks and stays down
  through the short gaps between sentences, instead of jumping up and down after every one.
  Turn off **Lower other sound while the voice speaks** to keep it at full volume (some
  podcast and audiobook apps pause instead of getting quieter).
- **Voice speed**: 70–150%.
- It speaks whole sentences, so the voice follows a sentence or so behind the speaker.
- It needs a translation language; captions-only mode has nothing to speak.

## Translation engines

Pick the language under **Translate to**, then the engine:

| | On this phone | Gemini (online) |
| --- | --- | --- |
| Engine | ML Kit; for Khmer, Meta NLLB-200 distilled 600M on ONNX Runtime | Google Gemini API (`gemini-flash-latest`) |
| Needs | One-time download: about 30 MB per language, 910 MB for Khmer | Internet and your own Gemini API key |
| Speed | Instant; Khmer takes several seconds per sentence and appears after the sentence ends | About a second |
| Accuracy | Each sentence on its own | Best: reads each sentence in the context of the previous ones |
| Privacy | Nothing leaves the phone | Caption text (never audio) goes to Google |

What the app does to keep translations accurate:

- **Cleaner input.** Small speech models return lowercase words without punctuation. Before
  translating, the app capitalizes the sentence and the English word "I", ends each finished
  sentence with a full stop (。 for Chinese and Japanese, । for Hindi), and removes the spaces
  the Chinese and Japanese models put between words. On the offline Khmer test the full stop
  made only a small difference (+0.3 chrF, within noise); it mainly keeps sentences apart when
  several are sent to Gemini together.
- **Context (Gemini).** Each request carries the last four sentences and their translations,
  so names, terms and pronouns stay consistent and misheard words can be worked out. Gemini is
  told never to answer a question in the captions, only to translate it.
- **Nothing skipped (Gemini, ML Kit).** Sentences that arrive while a translation is running
  are sent together next time instead of being dropped. If Gemini is busy, rate-limited or
  unreachable, the app waits as long as it asks (or 1, 2, 4 s) and tries again; the
  translation of the unfinished sentence pauses for a minute so that finished sentences get
  the requests that are left.
- **Beam search (offline Khmer).** NLLB now keeps two candidate translations and returns the
  more likely complete one, instead of always taking the single most likely next word. On 100
  caption-length English sentences from the FLORES-200 test set, given as speech-style input
  (lowercase, no punctuation), this raised chrF — a standard score of character overlap with
  professional Khmer translations — from 44.2 to 46.0, better in all of 1,000 resampled test
  sets. Each sentence takes about a quarter longer; four candidates scored no better.
- **Readable Khmer (offline).** NLLB puts a space between every Khmer word, which reads, and
  is spoken, like a list of separate words. The app joins them with zero-width spaces, as
  Khmer is normally written.
- **Current Gemini settings.** `temperature` is no longer sent: Google has deprecated it,
  current models ignore it, and earlier Gemini 3 models may do worse when it is lowered. Thinking
  is set to `low` to keep captions quick; if a model rejects that setting, the app retries
  without it.

**Gemini key**: create one for free at <https://aistudio.google.com/apikey> and paste it into
the key field that appears when you choose Gemini. Free keys allow only a few requests per
minute, so finished sentences come first and the sentence in progress is refreshed at most
every 6 seconds.

**Offline Khmer model**: NLLB-200 is licensed CC-BY-NC 4.0 (non-commercial use only).

`NllbModelTest` runs the real model on a desktop JVM when `NLLB_DIR` points at a folder with
`tokenizer.json` and the two `.onnx` files; it is skipped otherwise.

## Known limits

- Apps can opt out of audio capture. Phone/VoIP calls and some streaming and music apps
  cannot be captioned from device audio — use Microphone mode with the speaker on.
- The spoken language must be chosen up front; there is no automatic language detection.
- Small Vosk models favour speed over accuracy and output no punctuation. Recognition errors
  carry into the translation. Vosk's larger English model (`vosk-model-en-us-0.22-lgraph`,
  128 MB) made about a quarter fewer errors on LibriSpeech (7.6% vs 10.1% of words on clean
  speech, 17.1% vs 21.1% on harder speech) but needed 4.3 times the processing. That is more
  than most phones can keep up with live, so the app sticks with the small models.
- The offline Khmer model is slow (several seconds per sentence) and memory-hungry because the
  published file converts its whole 256,000-entry vocabulary table from 8-bit to 32-bit numbers
  for every word it writes. Fixing that needs a re-exported model file.
- Languages are limited to what Vosk (speech) and ML Kit, NLLB or Gemini (translation) support.

## Code map

| File | Role |
| --- | --- |
| `MainActivity` | Settings screen, permission flow |
| `CaptionService` | Foreground service: capture → recognise → translate |
| `CaptionOverlay` | Floating, draggable subtitle window and its fade / roll-up animations |
| `TranslationSpeaker` | Reads translations aloud: engine and voice choice, sentence queue, ducking; headphone detection |
| `TextTranslator` | ML Kit, Gemini and offline-Khmer translators |
| `Transcript` | Tidies recognizer output (casing, full stops, CJK spacing) before display and translation |
| `NllbTranslator`, `NllbTokenizer` | Offline NLLB-200 inference (greedy or beam search) and its tokenizer |
| `ModelManager` | Downloads and unpacks speech models |
| `CaptionLine` | Rolling subtitle text buffer |
