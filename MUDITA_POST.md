# Mudita forum post — draft

**Where to post:** [Mudita Products](https://forum.mudita.com/c/mudita-products/) on
forum.mudita.com — that's where the sideloading threads live and where an app
announcement belongs. There's also a newer
[Community Apps](https://forum.mudita.com/c/sideloaded-apps-ranking/28) section, but that
one is a *ranking of user reviews* — the natural place for other people to review CalmLib
once they've tried it, rather than for you to announce it yourself. Announce in Mudita
Products, then link to it if someone reviews it.

There is no app store on the Kompakt; sideloading is the supported route, and Mudita
Center can copy the APK across if people prefer that to `adb`.

---

**Title:** CalmLib — an offline, typography-first e-reader built for the Kompakt (open source)

---

I've been building an e-reader specifically for the Kompakt, because I wanted something
that felt like the device does: quiet, offline, and about reading rather than features.

It's called **CalmLib**, it's free and open source (AGPL), and it has **no internet
permission at all** — the app literally cannot phone home, because Android never grants it
the ability to. No accounts, no sync, no store, no telemetry, no browser.

*(Attach these four when you post — Discourse takes drag-and-drop: `docs/screenshots/library.png`,
`reading.png`, `dictionary.png`, `settings.png`. Put the library and reading shots near the top,
the dictionary one beside the dictionary bullet.)*

**What it does**

- Reads EPUB, PDF, TXT and FB2, with a built-in PDF→EPUB converter for PDFs that won't reflow
- Built for e-ink: tap zones and volume-key page turns, a full-screen refresh on every page
  turn so nothing ghosts, and an automatic full refresh on pages with images so pictures
  always paint cleanly
- Remembers exactly where you were, to the line, without bookmarking anything
- Bundled offline dictionary (WordNet, ~150,000 entries) — long-press a word, tap Look up.
  Works in airplane mode, handles inflected words ("running" → run)
- Highlights, bookmarks and a vocabulary list, exportable as Markdown
- Files you open from outside the app are *previewed*, not silently added — your library
  only holds what you chose to put there

**Why I made it**

I read a lot of long, dense books on the Kompakt and wanted a reader that treats typography
as the point rather than an afterthought — and one where I know exactly what the software is
doing, since the whole reason I'm on this device is to not be farmed for attention.

**Getting it**

Source and APK: https://github.com/Colalolly1/calmlib

```
adb install CalmLib-0.16.0.apk
```

Grant "All files access" when asked — it only scans dedicated book folders (Books/, Ebooks/,
Reading/, and KOReader/FBReader/Kindle folders), never your whole storage. Put your books in
`/sdcard/Books`.

**Fair warning**

It's early. I've been using it daily on my own Kompakt and it's had a fairly brutal round of
bug-hunting, but you'll be among the first people other than me to run it. If something
breaks, open an issue on GitHub and I'll fix it — I'm actively working on this.

Happy to hear what you'd want from a reader on this device.
