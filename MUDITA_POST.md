# Mudita community post — draft

*(Paste-ready. Adjust the tone to fit wherever you post it — forum thread, Discord, Reddit.)*

---

**Title:** CalmLib — an offline, typography-first e-reader built for the Kompakt (open source)

---

I've been building an e-reader specifically for the Mudita Kompakt, because I wanted
something that felt like the device does: quiet, offline, and focused on reading rather
than on features.

It's called **CalmLib**, it's free and open source (AGPL), and it has **no internet
permission at all** — the app literally cannot phone home, because Android never grants
it the ability to. No accounts, no sync, no store, no telemetry, no browser.

**What it does**

- Reads EPUB, PDF, TXT and FB2, with a built-in PDF→EPUB converter for PDFs that won't reflow
- Built for e-ink: tap zones and volume-key page turns, full-screen refresh on every page
  turn so nothing ghosts, and an automatic full refresh on pages with images so pictures
  always paint cleanly
- Remembers exactly where you were, to the line, without you bookmarking anything
- Bundled offline dictionary (WordNet, ~150,000 entries) — long-press a word, tap Look up.
  Works in airplane mode, handles inflected words ("running" → run)
- Highlights, bookmarks and a vocabulary list, exportable as Markdown
- Files you open from outside the app are *previewed*, not silently added — your library
  only holds what you chose to put there

**Why I made it**

I read a lot of long, dense books on the Kompakt and wanted a reader that treats
typography as the point rather than an afterthought — and one where I actually know what
the software is doing, since the whole reason I'm on this device is to not be farmed for
attention.

**Getting it**

Source and APK: https://github.com/Colalolly1/calmlib

Install with `adb install CalmLib-0.16.0.apk`, grant "All files access" when asked (it
only scans dedicated book folders — Books/, Ebooks/, Reading/, and KOReader/FBReader/
Kindle folders — never your whole storage), and put your books in `/sdcard/Books`.

**Fair warning**

It's early. I've been using it daily on my own Kompakt, and it's had a fairly brutal
round of bug-hunting, but you'll be among the first people other than me to run it. If
something breaks, open an issue on GitHub and I'll fix it — I'm actively working on this.

Happy to hear what you'd want from a reader on this device.
