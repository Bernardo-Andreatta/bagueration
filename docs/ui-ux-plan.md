# UI/UX refactor plan

Goal: best possible usability, a modern but **cozy** look (warm paper, soft corners, calm motion), without changing how narration works. Written for the session that will apply it; every phase ends in a commit and a compiling app.

## Ground rules

- **Stack stays as is**: plain `android.app.Activity`, programmatic Views, no Compose, no AndroidX/Material Components (zero AndroidX deps today; keep the APK and the mental model small). Everything is built with `LinearLayout`/`FrameLayout`/`GradientDrawable`/`RippleDrawable`, like `SettingsActivity` and `RendersActivity` already do.
- **Narration never stops because of a UI change.** Theme, font, bars, sheets: all runtime-applied (see `MainActivity.applyTheme`). Don't recreate the activity for a look change.
- **Themes drive every color.** No hard-coded colors in views; everything comes from `ReaderTheme` (`bg`, `bar`, `text`, `dim`, `accent`, `onAccent`, `heading`, `highlight`, `selection`, `light`) plus the new tokens below. `Themes.derive` must keep producing good custom themes.
- **UI strings stay in English** (the app is English today). Short, plain, no jargon.
- **Touch targets ≥ 48dp**, every tappable thing has a ripple and a `contentDescription`.
- **Working tree**: the uncommitted changes (Material Symbols icons in `Icons.kt` + `res/drawable/ic_*`, polish in Settings/Renders) compile. Commit them first as their own commit ("Material Symbols icons and settings polish") before starting phase 1, so the plan work is separable.
- One commit per phase, normal commit messages. Build with `./gradlew assemblePersonalDebug`; run `./gradlew testPersonalDebugUnitTest` before each commit. Install once at the end (`adb install -r`), then hand the user a test checklist. Don't drive the phone UI.

## What exists today (so you don't rediscover it)

| Screen | File | State |
|---|---|---|
| Reader | `MainActivity.kt` + `res/layout/activity_main.xml` | Stock `ActionBar` (title tinted via spans), 5 always-visible action icons, fixed bottom bar (~130dp: label + "Pre-load" chip + speed chip + 5 transport buttons), centered "Preparing voice…" overlay card, tap-a-sentence bubble, swipe to change chapter, fullscreen mode (double-tap exits). Dialogs: Contents (3 pill tabs), Search, Speed, Note, live-reading tip, voice-for-language offer. |
| Library | `LibraryActivity.kt` | `GridView` of covers (generated cover when none), "Reading" badge, progress line, headphones mark, "Add book" in the action bar, "Send debug log" in overflow, long-press → `AlertDialog` list. |
| Settings | `SettingsActivity.kt` | Already card-based with a shared vocabulary (`card`, `row`, `choiceRow`, `button`, `iconButton`, `slider`, `switch`, `chips`, `hintView`). Good shape; mostly needs to adopt the shared `Ui.kt` below. |
| Audio files | `RendersActivity.kt` | Also card-based, duplicates the same building blocks (`pill`, `iconButton`, `header`, `surface`). |
| Theming | `Themes.kt` | 5 presets (3 cool darks, Sepia, Paper) + custom (bg + accent → derived palette), paper texture bitmap, themed `AlertDialog.Builder`, `applyChrome` for bars. |
| Icons | `Icons.kt` | Material Symbols drawables tinted at runtime; `leading`/`trailing`/`inline` helpers. |

## Design direction: "warm paper"

- **Shape**: 20dp cards, 14dp rows/inner surfaces, 999dp pills, 10dp covers. Circles only for the play button and avatars.
- **Type**: body in a bundled **Literata** (OFL, Google Play Books' reading face) as the default serif, with system Serif/Sans still selectable; UI stays `sans-serif` / `sans-serif-medium`. Scale: 11.5 caption · 13 secondary · 15 body · 17 title · 22 screen title. Headings in the book: `1.45×` bold, in `heading`, as today.
- **Color**: add two warm presets so the cozy option exists in both modes, and make the first-launch default follow the system: light → **Linen**, dark → **Dusk**.
  - `Linen` (light): bg `#F4EDE1`, bar `#EBE2D2`, text `#2B2520`, dim `#7A6E62`, accent `#B0633A` (terracotta), onAccent `#FFF6EC`, heading `#6E3B21`, highlight `#F0D6B0`, selection `#E2C08E`.
  - `Dusk` (dark, warm): bg `#211C18`, bar `#1A1613`, text `#E6DCCB`, dim `#9A8E7E`, accent `#D9A866` (amber), onAccent `#1F1710`, heading `#EFC98C`, highlight `#4A3A22`, selection `#6A5230`.
  - Keep Midnight, Graphite, Black, Sepia, Paper. Paper texture available on every theme (it already handles dark).
- **New theme tokens** (add to `ReaderTheme`, derive them in `Themes.derive` and set them on presets): `surface` (card on page: light → `mix(bg, white, 0.5)`, dark → `mix(bg, white, 0.06)`), `outline` (`mix(bar, text, 0.14)`), `scrim` (`bg` at 60% alpha). Replace the ad-hoc `Themes.mix(th.bar, th.text, 0.12f)` scattered in Settings/Renders with these.
- **Motion**: 150–200ms, decelerate; bars slide, sheets slide up with the scrim fading; page turn keeps its slide. No bounce.
- **Highlight of the spoken sentence**: a rounded, padded highlight (custom `LineBackgroundSpan`-style span that draws a rounded rect behind each line of the sentence, 4dp radius, 2dp vertical padding) instead of the hard `BackgroundColorSpan`. Same for bookmarks/notes/search marks. This is the single biggest "cozy" win on the page.

## Phase 1 — Design system (`Ui.kt`) + chrome

New file `Ui.kt` (object `Ui`), extracted from `SettingsActivity`/`RendersActivity` and used by all four activities:

- `card(ctx, th, title?, icon?) { … }`, `header(ctx, th, text)`, `row(...)`, `choiceRow(...)`, `pill(text, icon, filled, wide)`, `iconButton(icon, desc, outlined)`, `chips(options, selected, onPick)`, `slider(...)`, `switchRow(...)`, `hint(text)`, `divider()`, `pressable(fill?)`, `ripple(color)`, `surface(th, stroke?)`, `progressBar(th, color)`.
- `Ui.topBar(activity, th, title, subtitle?, navIcon?, actions: List<Action>)`: a 56dp custom top bar (`LinearLayout`) replacing the stock `ActionBar` in every activity. Reason: full theme control (no `Themes.tint` span hack), it sits on the page background/paper, and it can hide/show with animation. Set `android:windowActionBar=false` / `windowNoTitle=true` in `styles.xml` and remove the `actionBar?.` calls. Title 17sp medium, subtitle 13sp dim, both ellipsized; nav icon on the left (back chevron, or nothing); up to 3 action icons 48dp on the right, more go into an overflow `PopupMenu`-like sheet (`Ui.menuSheet`).
- `Ui.sheet(activity, th, title?) { content }`: bottom sheet built on a plain `Dialog` (`Gravity.BOTTOM`, `match_parent` width, rounded 24dp top corners in `bar`, 4×32dp drag handle, scrim, slide-up/down animation via `window.attributes.windowAnimations` or manual `translationY` animation, dismiss on scrim tap and on swipe-down of the handle). Max height 80% of the screen; content scrolls inside. Returns the `Dialog` so callers can dismiss. Keep `Themes.dialog` for confirmations only (Remove book? Delete file? Reset?).
- **Edge-to-edge**: `window.setDecorFitsSystemWindows(false)` (API 30+) / `SYSTEM_UI_FLAG_LAYOUT_*` below; status + nav bars transparent; the top bar pads by the status inset and the bottom player pads by the nav inset (`setOnApplyWindowInsetsListener` on the root). `Themes.applyChrome` becomes: transparent bars + light/dark icon appearance only.
- Add `Linen` and `Dusk` to `Themes.all`, the new tokens, and the system-following default (`Themes.current`: when no `theme` pref, pick by `Configuration.UI_MODE_NIGHT_MASK`).
- Bundle **Literata** (variable TTF from google/fonts, OFL; put `app/src/main/assets/fonts/Literata.ttf` + `OFL.txt`; load once with `Typeface.createFromAsset`, cache in `Themes`/`Ui`). Font pref values: `"literata"` (default for new installs), `"serif"`, `"sans"`. Existing users keep whatever they had.

Commit: "Shared Ui building blocks, custom top bar, bottom sheets, warm themes".

## Phase 2 — Reader (`MainActivity`)

Top bar
- Left: back chevron → library (Back already does this). Title = book, subtitle = chapter. Right: **Contents**, **Search**, **More**. "More" sheet: Full screen, Audio files, Appearance (opens Settings at the appearance section; action id 8 already exists), Settings, Library.
- The bar hides when you scroll down and comes back on scroll up or tap (animate `translationY`), same for the bottom player. While narrating and following the voice, bars stay. Fullscreen mode keeps working: everything hidden, tap or double-tap brings bars back; drop the "Tap anywhere…" toast after the first time (pref).

Page
- Max text column **680dp**, centered (tablets/landscape); horizontal padding 24dp phone / 32dp when the column is capped.
- `pageView.textLocale = Locale.forLanguageTag(bookLang)` so Android's hyphenation and line breaking use the book's language (today they use the device locale).
- Rounded highlight spans (see direction). Spoken sentence: `highlight`; bookmarks: accent tint; notes: warm tint; tapped sentence: `selection`; search hit: `selection`, as today.
- "Left off here" stays an underline, but dotted and in `accent` at 60% (custom span drawing a dotted line) instead of the solid system underline.
- Thin **chapter progress line** (2dp, `accent`) at the very top of the page area, showing position inside the chapter (from `scrollY / contentHeight`); hidden in fullscreen? No: keep it, it's the only hint left.
- Empty state (no book): replace the plain text with a centered card: book icon in a soft circle, "No book open", one line of help, a filled pill "Open library". Same vocabulary as `RendersActivity.emptyState`.

Bottom player (replaces the `controls` block in `activity_main.xml`)
- One compact bar, ~88dp + nav inset, `bar` color, 22dp top corners (as today), subtle top outline.
- Row 1 (28dp): chapter label left ("Chapter 3 of 12 · 14 min left" — time left at the current speed from chars: `chars / (14.5 × speed) + sentences × pauseMs`, the same estimate `RendersActivity.newFile` uses; move that formula to `Narrator` or a small `Estimate` helper and reuse), speed chip right ("1.25×", opens the speed sheet), audio chip ("Audio" / "Making 40%" / "Pre-load", icon only when narrow) — both chips 36dp tall, pill background in `surface`, ripple.
- Row 2 (56dp): ‹ chapter · ⏮ sentence · **Play** 56dp filled circle with the busy ring around it · ⏭ sentence · › chapter. 48dp targets, `text` tint, disabled at 30% alpha as today.
- **Loading**: remove the centered `loadingOverlay` card. The busy ring on the play button + the row-1 label ("Preparing voice…" / "Loading voice model…") is enough; add a 2s-delayed small pill above the player ("Preparing voice…") only if still buffering, so short waits don't flash anything.
- **Jump-back chevron**: keep, but as a 44dp pill with the chevron and "Back to the voice" text, above the player, `accent` filled; it already handles up/down.

Tap bubble ("Read from here")
- Keep behaviour and the narrow-screen squeeze logic. Look: `surface`-colored card with 16dp radius, outline, soft elevation; first action filled accent pill ("Read from here"), the others icon buttons with labels in `text`. Notes keep the quote preview on top.

Sheets (replace dialogs)
- **Contents** → sheet with a segmented control (Chapters / Bookmarks / Notes) in a `surface` track with a sliding selected pill; list rows 56dp; the current chapter row shows a small "Reading" dot and the sheet opens scrolled to it; each chapter row shows its length ("12 min") in `dim`. Empty states as today. Bookmark/note rows keep long-press actions but also get a trailing overflow icon (TalkBack can't long-press reliably).
- **Search** → sheet with the field at the top (auto-focus, IME search action), scope chips, results list, count line. Same engine.
- **Speed** → sheet: big readout, `−`/`+` steppers either side (0.05), slider, presets; **applies live** on release (narrator keeps reading, `applyToPlaying` path), no Done/Cancel.
- Live-reading tip, voice-for-language offer, Note editor, Remove confirmations: stay as `Themes.dialog` (they are decisions).

Commit: "Reader: custom top bar, compact player, rounded highlights, sheets".

## Phase 3 — Library (`LibraryActivity`)

- Top bar: "Library" title; actions: Search (only when > 8 books; filters the grid as you type, field drops down under the bar), Sort (sheet: Recent · Title · Author · Progress), More (Send debug log, Settings).
- **Continue reading** hero at the top when there is a current/last book: cover 96×144, title, author, chapter + progress bar, two pills: **Resume** (opens the book and returns `EXTRA_PLAY=true` so `MainActivity` starts narration where it left off) and **Open**. Hidden when the library is empty.
- Grid: keep `GridView` and the column math; covers 10dp radius, 2dp elevation in light themes / outline in dark; title 13sp two lines; meta line 11.5sp. Move the headphones "has audio" mark onto the cover (small 20dp circle, bottom-left) and the "Reading" badge stays top-right. Progress line stays.
- **Add book** = floating 56dp filled circle `+` bottom-right (above the nav inset), ripple; the action-bar item goes away. Keep the "Open with" intent path.
- Empty state: card with a stacked-books icon, "Your library is empty", formats in a `dim` line, filled pill "Add a book".
- Long-press → `Ui.sheet` with the book header (cover, title, author) and rows: Open · Resume reading aloud · Audio files · Remove from library. Remove still confirms.
- Importing overlay: same, but inside a `surface` card with the new progress bar; keep it blocking.

Commit: "Library: continue-reading card, add button, cover polish".

## Phase 4 — Settings and Audio files adopt `Ui.kt`

- Replace the local building blocks in `SettingsActivity` and `RendersActivity` with `Ui.*` (behaviour identical; `keepInPlace`, `refreshPresets`, `touched` stay).
- Settings: custom top bar; sections stay. **Appearance** gains: Font chips (Literata · Serif · Sans), Margins chips (Narrow · Normal · Wide → 16/24/32dp page padding, pref `margins`), theme row shows the 7 presets + Custom as 44dp circles with a scrolling row (they no longer fit as equal-weight columns). The preview block uses the rounded highlight so what you see matches the page.
- Audio files: top bar with the "+" action; cards unchanged otherwise. "New audio file" becomes a `Ui.sheet` (the two pickers stay `Themes.dialog` single-choice lists).
- Voice picker becomes a `Ui.sheet` (long list; same rows, preview buttons, download card).

Commit: "Settings and audio files on the shared Ui kit; font and margin options".

## Phase 5 — Finish, install, checklist

- `./gradlew assemblePersonalDebug testPersonalDebugUnitTest`, fix warnings you introduced.
- Walk every theme (7 presets + a custom light + a custom dark) through: reader with/without paper, player, sheets, library, settings. Check contrast of `dim` on `bar` ≥ 4.5:1 in the two new themes (adjust `dim` if not).
- Install once: `adb install -r app/build/outputs/apk/personal/debug/app-personal-debug.apk`.
- Give the user a short checklist to test on the phone (bars hide/show while scrolling, narration survives theme/font/speed changes, sheets dismiss by swipe, library resume starts the voice, fullscreen in/out, paper texture on Dusk/Linen, large font + narrow screen bubble, TalkBack on the player).

Bump `versionName` to `5.0`, `versionCode` 31 in `app/build.gradle.kts` in the last commit.

## Out of scope (don't do now)

Compose migration, Readium, new TTS engines, Android Auto/Wear, tablet two-pane. See the technology notes below for the follow-ups the user may pick later.

---

# Technology notes: more natural reading, any language

What the app runs today: sherpa-onnx 1.13.8 with Kokoro-82M (built in; espeak-ng G2P, a US-English lexicon file) and downloadable Piper voices; sentence splitting, phrasing, pauses and hesitation trimming in `Narrator.kt`; `SpokenText.kt` rewrites numerals/years/money/initials (English fully, other languages partly); `LangDetect` is a hand-written frequent-word scorer for ~23 languages.

Ranked by value for effort. All open source unless noted.

## Cheap, built into Android (no new dependency)

1. ~~**`android.icu.text.RuleBasedNumberFormat` (SPELLOUT)**~~ — would spell numbers and ordinals in any language, but it turned out not to be part of the public Android SDK (only ICU's `NumberFormat`, `BreakIterator`, `DateFormat`... are). Done instead with a small hand-written `Ordinals.kt` (1–100, gendered, pt/es/fr/it/de); plain cardinals are left as digits, which the voices already read in their own language.
2. **`android.icu.text.BreakIterator.getSentenceInstance(locale)`** — locale-aware sentence boundaries (handles "Sr.", "Dr.", "etc.", "…", CJK punctuation, Devanagari danda). Use it as the first pass in `Narrator`'s sentence splitter and in `MainActivity.sentenceAround`, keeping the current quote/paragraph tweaks on top.
3. **`TextView.textLocale`** — hyphenation and line breaking in the book's language (phase 2).
4. **`AudioTrack.setPlaybackParams(PlaybackParams().setSpeed(x))`** — time-stretch without pitch change. Lets a pre-made audio file play at any speed instead of baking the speed in, and lets speed changes apply instantly on cached audio without re-rendering. Worth a setting "speed changes re-render (best quality) / stretch (instant)".
5. **`android.view.textclassifier.TextClassificationManager` language detection** (API 29+, on-device) — a second opinion for `LangDetect` on short or mixed texts. Not open source, but free and offline.

## Text front end (biggest naturalness gains per language)

6. **misaki** (Apache-2.0, hexgrad) — Kokoro's own G2P. Kokoro was trained on misaki phonemes; sherpa-onnx feeds it espeak-ng phonemes plus a lexicon, which is the main reason English names, homographs ("read", "lead") and stress sometimes come out off. misaki's English is a dictionary (`us_gold`/`us_silver` JSON) + rules; the dictionaries can be converted into the lexicon format sherpa-onnx already loads (`kokoro/lexicon-us-en.txt`), so most of the gain needs no engine change. Same idea for its pt/es/fr/it data where it exists.
7. **ipa-dict** (MIT) / **CMUdict** (BSD) / **Wiktionary IPA dumps** — per-language word → IPA lists, same lexicon route, for names and rare words in pt/es/fr/it/de/…
8. **Lingua** (Apache-2.0, JVM) — language detection that is accurate on single sentences; pick the 20 languages you ship to keep it ~10 MB. Enables **per-sentence voice switching** inside one book (French dialogue in an English novel, Latin epigraphs): detect per paragraph, switch Kokoro `espeakLang`/voice when a run of ≥2 sentences is in another language. The engine cache (`engineFor`) already keeps one engine per language.
9. **Unicode CLDR data via ICU** (`MeasureFormat`, `DateFormat`, currency names) — units, dates and currencies spoken in the book's language; same API family as item 1.
10. **Abbreviation tables per language** (own list, or from LibreOffice/Hunspell autocorrect data, MPL/LGPL): "Mr.", "Sra.", "z.B.", "etc." expanded before synthesis. Keep the user's `Pronunciations` on top.

## Voices and engines (sherpa-onnx already supports these; just new model files)

11. **Supertonic 2/3** (Supertone; sherpa-onnx 1.13.2+ ships Android examples) — 66–99M params, very fast on CPU, multilingual (en/ko/es/pt/fr in v2, more announced in v3). Natural prosody, often rated above Kokoro for non-English. License is OpenRAIL (use restrictions, not OSI) — fine for a personal app, check before publishing.
12. **Kokoro v1.1-zh** — Chinese voices for the same engine config.
13. **MeloTTS** (MIT; VITS family in sherpa-onnx) — en/es/fr/zh/ja/ko, decent quality, good for languages Kokoro lacks.
14. **MMS-TTS** (Meta; VITS; CC-BY-NC) — 1,100+ languages; the "any language" fallback. Non-commercial.
15. **Matcha-TTS** (MIT) — icefall-trained models in sherpa-onnx; quality close to Kokoro for en/zh, light on CPU.
16. **ZipVoice** (k2-fsa, Apache-2.0; sherpa-onnx 1.12.11+) and **Pocket TTS** (Kyutai) — zero-shot voice cloning from a few seconds of audio: "narrate in my own / a friend's voice". Heavier; worth a try as an optional download.
17. **Kitten TTS** (Apache-2.0, 14–80M) — tiny English fallback for low-memory phones.
18. Too heavy for a phone today, keep an eye on: Chatterbox (MIT, 23 languages), CosyVoice 2 (Apache-2.0), Qwen3-TTS, Orpheus.

## Reading experience around the voice

19. **Word-level highlight without timestamps** — Kokoro gives no alignments, but each sentence's duration is known once rendered: distribute it over words by character count (weighted by punctuation pauses). Cheap "karaoke" that feels much more alive than sentence-level. Exact timings later via a small forced aligner (sherpa-onnx has `OfflineSpeechDenoiser`/ASR models; a CTC aligner would be the proper tool).
20. **EBU R128 loudness normalization** (libebur128, MIT, or a 20-line K-weighted RMS in Kotlin) — equal loudness across voices, blends and pre-made files; no more volume jumps when switching voice.
21. **Readium Kotlin Toolkit** (BSD-3) — if the app ever wants real EPUB layout (images, tables, footnotes, publisher CSS) instead of flattened text, this is the one; it has a TTS navigator and a Decoration API for highlights. Big change, separate project.
22. **pdfium** (Apache-2.0, via `pdfium-android`) — better text order and column handling than PdfBox for PDFs; **Tesseract 4 / Tesseract4Android** (Apache-2.0) for scanned PDFs.
23. **Fonts (OFL)**: Literata (reading default), Atkinson Hyperlegible (low vision), OpenDyslexic (dyslexia) as options in Appearance.
24. **Bergamot / firefox-translations** (MPL) — offline sentence translation on long-press, for reading in a second language. Models ~15 MB per pair.
