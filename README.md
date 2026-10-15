# bagueration

An Android e-reader that reads your books aloud with natural-sounding voices, entirely on the phone. No account, no cloud, no internet needed after install.

## What it does

- **Reads aloud from anywhere**: tap a sentence (or long-press a word) and it starts there. The sentence being read is highlighted and the page follows along. Scroll away and a button brings you back.
- **Keeps your place** in every book, and keeps reading with the screen off. Controls show up in the notification shade, lock screen and on headphones.
- **Formats**: EPUB, PDF, MOBI / AZW / AZW3, FB2, DOCX, HTML, Markdown and TXT. Covers, titles and chapters are picked up automatically.
- **Voices**: 41 built-in Kokoro voices (English US/UK, Portuguese, Spanish, French, Italian, Hindi). Optional downloads (Wi-Fi only) add German, Dutch, Polish, Russian, Ukrainian, Brazilian Portuguese and more.
- **Detects each book's language** and suggests voices in that language first.
- **Tuning**: speed, pauses between sentences and at commas, trimming of mid-sentence hesitations, phrasing length, voice blends, per-voice recommended settings and your own saved presets.
- **Pronunciation fixes** for names the voice gets wrong, per book or for all books.
- **Reading**: chapter-per-page scrolling, chapter list, search (chapter or whole book, accent-insensitive), five themes plus custom colors, font, size and spacing.

## Building

The voice model (~330 MB) and speech engine are too large for git, so they're downloaded on setup.

```bash
tools/setup.sh          # downloads sherpa-onnx + Kokoro v1.0, bakes the voice blends
./gradlew assembleDebug  # app/build/outputs/apk/debug/app-debug.apk
```

Requirements: JDK 17, Android SDK 35, Python 3 (for the blend step), about 1 GB of free disk space. The phone needs Android 8+ (arm64) and about 1 GB of free memory while reading aloud.

## How it's put together

| File | Role |
|---|---|
| `MainActivity.kt` | Reader: pages, highlighting, player controls, search, gestures |
| `Narrator.kt` | Speech pipeline: sentence splitting, phrasing, comma pauses, hesitation trimming, audio cache, playback |
| `Voices.kt` | Voice catalog, language detection, per-voice presets, blends |
| `BookParser.kt`, `PdfParser.kt`, `MobiReader.kt` | Turning each format into text + chapters + styles |
| `SettingsActivity.kt`, `LibraryActivity.kt` | Settings and the cover library |
| `NarrationService.kt` | Media controls, background playback |
| `tools/` | Model setup and the voice-blend baking script |

## Credits

- [Kokoro-82M](https://huggingface.co/hexgrad/Kokoro-82M) by hexgrad, Apache-2.0.
- [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) by k2-fsa, Apache-2.0. It runs the voices on the device and hosts the downloadable [Piper](https://github.com/rhasspy/piper) voices, each under its own license.
- [PdfBox-Android](https://github.com/TomRoush/PdfBox-Android) and [Apache Commons Compress](https://commons.apache.org/proper/commons-compress/), Apache-2.0.
