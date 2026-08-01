# CalmLib

A calm, typography-led e-reader for the **Mudita Kompakt** (and other Android e-ink devices).

CalmLib is built around one idea: reading should feel like paper. No accounts, no
sync, no store, no browser, no network access at all — the app doesn't even hold
the INTERNET permission. Your books and your reading life stay on your device.

## Features

- **Formats**: EPUB, PDF, TXT, FB2 — plus a built-in PDF→EPUB converter for
  reflowing stubborn PDFs.
- **E-ink first**: high-contrast typography, tap zones and volume-key page
  turns, configurable full-refresh cadence, automatic full refresh on pages
  with images so pictures always paint cleanly.
- **A real library**: your books on wooden-shelf rows, continue-reading up
  front, collections, search across every book you own, mass select/remove,
  per-book reading settings that never bleed between books.
- **Reading that stays put**: your position is saved continuously and
  restored to the exact line — no manual bookmarking, resilient even when
  Android kills the renderer mid-read.
- **Offline dictionary**: bundled WordNet (~150,000 entries). Long-press a
  word → Look up. Handles inflections ("running" → run) and messy selections.
  Import your own StarDict dictionaries if you want more.
- **Highlights, bookmarks, vocabulary**: long-press to highlight or save
  words; export highlights as Markdown.
- **Guest preview**: files opened from outside the app are *previewed*, not
  silently imported — your library only contains what you put there.

## Installing on a Mudita Kompakt

1. Download the APK from [Releases](../../releases) (or build it yourself, below).
2. Enable USB debugging on the Kompakt (Settings → About → tap Build number 7×,
   then Developer options → USB debugging).
3. `adb install CalmLib.apk`
4. On first launch, grant **All files access** when asked — CalmLib needs it to
   find your books. It scans only dedicated book folders (`Books/`, `Ebooks/`,
   `Reading/`, and KOReader/FBReader/Kindle folders), never your whole storage.
5. Put your books in `/sdcard/Books` (create it if needed) and pull down to scan.

## Building

```bash
JAVA_HOME=$(path to a JDK 17) ./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

No Google services, no proprietary dependencies.

## Privacy

- **No network permission.** CalmLib cannot transmit anything, ever.
- No analytics, no telemetry, no crash reporting.
- The only permission it asks for is storage access, to find and open your books.

## License

CalmLib is free software under the **GNU AGPL v3** — see [LICENSE](LICENSE).

### Bundled third-party work

| Component | License |
|---|---|
| [WordNet](https://wordnet.princeton.edu/) dictionary (via dict.org StarDict packaging) | WordNet License (Princeton) |
| [Literata](https://github.com/googlefonts/literata) typeface | SIL OFL 1.1 |
| [Atkinson Hyperlegible](https://brailleinstitute.org/freefont) typeface | SIL OFL 1.1 |
| [JetBrains Mono](https://github.com/JetBrains/JetBrainsMono) typeface | SIL OFL 1.1 |
| [PdfBox-Android](https://github.com/TomRoush/PdfBox-Android) | Apache 2.0 |

WordNet: "WordNet 3.0 Copyright 2006 by Princeton University. All rights reserved."
Used under the WordNet license; THIS SOFTWARE AND DATABASE IS PROVIDED "AS IS".
