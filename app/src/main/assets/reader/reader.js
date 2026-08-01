/**
 * Pagination strategy: TRANSLATEY (vertical scroll-equivalent).
 *
 * We let body have its natural content height (a single tall flow) and use
 * `body.style.transform = translateY(-N * pageHeight)` to advance pages.
 *
 * Why not CSS multi-column?
 *   Multi-column works in theory but is *extremely* fragile to publisher CSS:
 *   forced `page-break-after: always` on headings, `column-span: all`, `widows`
 *   and `orphans` settings, `column-count: 1` overrides, or any positioned
 *   element will break it. Every fix we tried still left chapters showing one
 *   line of text + a sea of white per page. TranslateY is dead simple —
 *   browser doesn't try to do anything clever, content flows top-to-bottom,
 *   we translate the body up by viewport height for each page turn.
 *
 * Trade-off: text may be cut mid-line at page boundaries. We mitigate this
 * by snapping pageHeight to a whole multiple of line-height, so the most
 * common case (uniform body text) breaks cleanly between lines.
 */

// Commit beacon: this script executes while the document is still PARSING —
// long before images finish. On this device onPageStarted never fires for
// loadDataWithBaseURL, so this is the only reliable "the renderer took our
// load" signal. Without it the native watchdog cannot tell a live slow load
// from a dropped one and re-issues right over it, aborting it every 2.5s.
try {
    var __calmG = /[?&#]g=(\d+)/.exec(location.href);
    CalmBridge.onDocStarted(__calmG ? parseInt(__calmG[1], 10) : -1);
} catch (e) {}

var CalmReader = {
    currentPage: 0,
    totalPages: 1,
    pageHeight: 0,
    // Bumped by every EXPLICIT navigation (page turn, slider, restore-position,
    // TOC/bookmark jump). Pending anchor-restore timers compare against the value
    // they saw at capture time and stand down if navigation happened since —
    // regardless of how the async Kotlin->JS calls interleaved.
    _navSeq: 0,

    // Pagination isn't valid until images have settled — committing the page
    // grid before then is what caused the "page shows for a second, then skips"
    // bug: an image finished decoding, all text below shifted down, and the page
    // indices no longer matched what the reader was looking at.
    _paginated: false,
    _pendingPage: null,

    init: function() {
        // Guard against the double-init the app performs (onPageFinished calls
        // init() AND the DOMContentLoaded handler does) — a second registration
        // would stack duplicate resize listeners.
        if (this._inited) return;
        this._inited = true;
        var self = this;
        this._whenImagesSettled(function() {
            self.calculatePages();
        });
        window.addEventListener('resize', function() {
            // Debounced: immersive-mode transitions fire several resize events.
            clearTimeout(self._resizeDebounce);
            self._resizeDebounce = setTimeout(function() {
                self.calculatePages();   // offset-converts position on height change
            }, 150);
        });
    },

    /**
     * Run cb once every <img> in the document has loaded (or errored), with a
     * 1.5s cap so one broken image can't stall the reader. Immediate when there
     * are no pending images.
     */
    _whenImagesSettled: function(cb) {
        var pending = [];
        var imgs = document.images;
        for (var i = 0; i < imgs.length; i++) {
            if (!imgs[i].complete) pending.push(imgs[i]);
        }
        if (pending.length === 0) { cb(); return; }
        var done = false;
        var left = pending.length;
        var finish = function() { if (!done) { done = true; cb(); } };
        var tick = function() { if (--left <= 0) finish(); };
        for (var j = 0; j < pending.length; j++) {
            pending[j].addEventListener('load', tick);
            pending[j].addEventListener('error', tick);
        }
        setTimeout(finish, 1500);
    },

    /**
     * pageHeight = viewport height, snapped down to the nearest multiple of
     * the body's computed line-height. That way pages end on a line boundary
     * and most lines aren't cut.
     */
    refreshPageHeight: function() {
        var raw = window.innerHeight || document.documentElement.clientHeight;
        // Layout not ready (WebView reports ~0 before first layout). Committing
        // a page height now would collapse pages to one line-height and poison
        // every downstream calculation — the proven root cause of the image-page
        // skip. Refuse, and let calculatePages retry.
        if (!raw || raw < 100) return false;
        var lh = parseFloat(window.getComputedStyle(document.body).lineHeight);
        if (isNaN(lh) || lh < 8) lh = 24;
        // Subtract a tiny bit so the last line isn't clipped by sub-pixel rounding.
        var usable = raw - 2;
        var snapped = Math.floor(usable / lh) * lh;
        this.pageHeight = Math.max(lh, snapped);
        return true;
    },

    calculatePages: function() {
        // ALWAYS recompute metrics here so page totals can never be built from a
        // stale pageHeight epoch (setDisplayMode/setTextOptions used to call this
        // without any refresh — mixing epochs was half the skip bug).
        var oldPH = this.pageHeight;
        var oldOffset = this._currentTranslate();
        if (!this.refreshPageHeight()) {
            // Viewport not laid out yet — retry shortly; stay unpaginated so page
            // turns are swallowed and navigations queue as pending.
            var self = this;
            this._layoutRetryCount = (this._layoutRetryCount || 0) + 1;
            // This loop used to spin in total silence — a stuck zero-height
            // WebView looked identical to "no logs at all" from outside.
            if (this._layoutRetryCount === 1 || this._layoutRetryCount % 10 === 0) {
                this._dbg('layout not ready (innerHeight=' + (window.innerHeight || 0) +
                          ') retry #' + this._layoutRetryCount);
            }
            if (!this._layoutRetry) {
                this._layoutRetry = setTimeout(function() {
                    self._layoutRetry = null;
                    self.calculatePages();
                }, 100);
            }
            return;
        }
        this._layoutRetryCount = 0;
        var h = this._contentHeight();
        this.totalPages = Math.max(1, Math.ceil(h / this.pageHeight));
        var wasPaginated = this._paginated;
        this._paginated = true;
        // A navigation requested before the grid existed (e.g. the saved-position
        // restore racing image loads) is applied now, against the final layout.
        // Character anchors take precedence over page indices — they're exact.
        if (this._pendingChar != null) {
            var c = this._pendingChar;
            this._pendingChar = null;
            this._pendingPage = null;
            this._resolveCharOffset(c);
            return;
        }
        if (this._pendingPage != null) {
            var p = this._pendingPage;
            this._pendingPage = null;
            this.goToPage(p, true, 'pending');
            return;
        }
        // If the page height CHANGED (system bars settled, metrics shifted after
        // an image decode…), convert the reader's position by pixel offset so the
        // SAME CONTENT stays on screen. Re-asserting the old index against the new
        // height was the visible "shows for a second, then skips" jump.
        if (wasPaginated && oldPH > 0 && Math.abs(oldPH - this.pageHeight) > 0.5) {
            this.currentPage = Math.min(
                this.totalPages - 1,
                Math.max(0, Math.round(oldOffset / this.pageHeight))
            );
        }
        if (this.currentPage >= this.totalPages) this.currentPage = this.totalPages - 1;
        // Re-align the transform to the (possibly new) grid.
        this.goToPage(this.currentPage, true, 'grid-sync');
    },

    /**
     * Height of the actual CONTENT, for page counting. body.scrollHeight is
     * floored at the viewport (body has min-height:100vh), so a chapter
     * shorter than the screen — cover pages, title pages — measures as a
     * full viewport and ceil()s into a phantom blank second page. goToEnd
     * and saved positions then land on that blank page, which is what "the
     * picture shows for a second then it skips" looks like on device.
     */
    _contentHeight: function() {
        var scrollH = document.body.scrollHeight;
        var viewH = window.innerHeight || 0;
        // Taller than the viewport = genuinely multi-page; scrollHeight is
        // trustworthy there (it includes absolutely-everything).
        if (scrollH > viewH + 1) return scrollH;
        var bottom = 0;
        for (var el = document.body.firstElementChild; el; el = el.nextElementSibling) {
            var b = el.offsetTop + el.offsetHeight;
            if (b > bottom) bottom = b;
        }
        if (bottom <= 0) return scrollH;
        var pad = parseFloat(window.getComputedStyle(document.body).paddingBottom) || 0;
        return Math.min(scrollH, bottom + pad);
    },

    /** Diagnostic tap into logcat via the bridge — invaluable for E-Ink-device
     *  debugging where there is no visible console. */
    _dbg: function(msg) {
        try { CalmBridge.onDebug(msg); } catch (e) {}
    },

    /** _internal=true marks re-assertions from reflow/restore timers; only real
     *  navigation bumps _navSeq so it can invalidate pending anchor restores.
     *  src labels the caller for diagnostics. */
    goToPage: function(n, _internal, src) {
        this._dbg('goToPage n=' + n + ' internal=' + !!_internal + ' src=' + (src || '?') +
                  ' cur=' + this.currentPage + '/' + this.totalPages + ' paginated=' + this._paginated);
        if (!_internal) this._navSeq++;
        // Grid not computed yet (images still settling): remember the request and
        // honor it when calculatePages runs. Clamping against a totalPages of 1
        // here would silently discard a saved-position restore.
        if (!this._paginated) { this._pendingPage = n; return; }
        if (n < 0) n = 0;
        if (n >= this.totalPages) n = this.totalPages - 1;
        this.currentPage = n;
        var offset = n * this.pageHeight;
        var b = document.body.style;
        b.setProperty('transform', 'translateY(-' + offset + 'px)', 'important');
        b.setProperty('-webkit-transform', 'translateY(-' + offset + 'px)', 'important');
        this.notifyPageChange();
    },

    /** Jump to the chapter's final page — safe to call before pagination exists
     *  (the huge index is clamped once the real grid is computed). */
    goToEndOfChapter: function() {
        this.goToPage(this._paginated ? this.totalPages - 1 : 1000000000, false, 'goToEnd');
    },

    nextPage: function() {
        // Pretend we handled taps that land before pagination exists — returning
        // false here would make the Kotlin side advance a whole CHAPTER.
        if (!this._paginated) return true;
        if (this.currentPage < this.totalPages - 1) {
            this.goToPage(this.currentPage + 1, false, 'nextPage');
            return true;
        }
        return false;
    },

    prevPage: function() {
        if (!this._paginated) return true;
        if (this.currentPage > 0) {
            this.goToPage(this.currentPage - 1, false, 'prevPage');
            return true;
        }
        return false;
    },

    getProgress: function() {
        return JSON.stringify({ page: this.currentPage, total: this.totalPages });
    },

    /**
     * Position anchoring — capture the first visible text BEFORE a reflow
     * (font size, spacing, margins, justify…) and return to that exact text
     * AFTER, so changing settings never loses the reader's place.
     * caretRangeFromPoint resolves a viewport point to a text node + offset;
     * after the reflow we ask where that node landed and jump to whichever
     * page now contains it.
     */
    _captureAnchor: function() {
        if (typeof document.caretRangeFromPoint !== 'function') return null;
        try {
            var maxY = Math.min(this.pageHeight || window.innerHeight, window.innerHeight);
            var xs = [Math.floor(window.innerWidth * 0.5), Math.floor(window.innerWidth * 0.25)];
            // Probe downward from the top of the visible page until we hit real text.
            for (var y = 6; y < maxY; y += 14) {
                for (var i = 0; i < xs.length; i++) {
                    var r = document.caretRangeFromPoint(xs[i], y);
                    if (r && r.startContainer && r.startContainer.nodeType === 3 &&
                        r.startContainer.nodeValue && r.startContainer.nodeValue.trim().length > 0) {
                        return { node: r.startContainer, offset: r.startOffset };
                    }
                }
            }
        } catch (e) {}
        return null;
    },

    /** Read the live translateY off the body — the only trustworthy source when
     *  pageHeight has just changed and currentPage*pageHeight would mix old/new. */
    _currentTranslate: function() {
        var m = /translateY\((-?[0-9.]+)px\)/.exec(document.body.style.transform || '');
        if (!m) return this.currentPage * this.pageHeight;
        return Math.abs(parseFloat(m[1]));
    },

    _restoreAnchor: function(anchor) {
        if (!anchor || !anchor.node || !document.body.contains(anchor.node)) {
            // Anchor died in the reflow (e.g. bionic mode rebuilt the text nodes) —
            // fall back to keeping the same page index.
            this.goToPage(this.currentPage, true, 'restore-dead');
            return;
        }
        try {
            var len = anchor.node.nodeValue ? anchor.node.nodeValue.length : 0;
            if (len === 0) { this.goToPage(this.currentPage, true, 'restore-empty'); return; }
            var off = Math.min(anchor.offset, len - 1);
            var range = document.createRange();
            range.setStart(anchor.node, off);
            range.setEnd(anchor.node, Math.min(off + 1, len));
            var rect = range.getBoundingClientRect();
            var documentY = rect.top + this._currentTranslate();
            var page = Math.floor(documentY / this.pageHeight);
            this.goToPage(Math.max(0, page), true, 'restore-anchor');
        } catch (e) {
            this.goToPage(this.currentPage, true, 'restore-error');
        }
    },

    setStyle: function(fontSize, lineSpacing, marginH, marginV, fontFamily) {
        // Anchor-restore is ONLY for genuine settings changes mid-read. The
        // chapter-load pipeline re-applies the same values the HTML was rendered
        // with; anchoring there chased text that shifted under late-loading
        // images and produced the show-page-then-skip bug.
        var sig = fontSize + '|' + lineSpacing + '|' + marginH + '|' + marginV + '|' + fontFamily;
        var isFirstApply = !this._styleAppliedOnce;
        this._styleAppliedOnce = true;
        var shouldAnchor = !isFirstApply && this._styleSig !== sig;
        this._styleSig = sig;
        var anchor = shouldAnchor ? this._captureAnchor() : null;
        var navSeqAtCapture = this._navSeq;
        // Cap images to the PAGE GRID, not the raw viewport. pageHeight is
        // snapped DOWN to a line-height multiple, so a 100vh-based cap makes
        // every full-height image overflow the page boundary by a few px —
        // manufacturing a phantom mostly-blank page after each big picture.
        // Mirror refreshPageHeight's math with the values being applied now.
        var lhPx = fontSize * lineSpacing;
        var usableH = (window.innerHeight || document.documentElement.clientHeight || 0) - 2;
        var gridPH = Math.floor(usableH / lhPx) * lhPx;
        var imgMax = (usableH > 100 && gridPH > lhPx)
            ? Math.max(lhPx, gridPH - 2 * marginV - lhPx) + 'px'
            : 'calc(100vh - ' + (2 * marginV + 20) + 'px)';
        // Inject a stylesheet (covers nodes added later) AND set inline styles
        // (beats publisher class-based !important rules).
        var styleEl = document.getElementById('calm-overrides');
        if (!styleEl) {
            styleEl = document.createElement('style');
            styleEl.id = 'calm-overrides';
            document.head.appendChild(styleEl);
        }
        styleEl.textContent =
            'html { height: 100vh !important; overflow: hidden !important; margin: 0 !important; padding: 0 !important; } ' +
            'body { font-size: ' + fontSize + 'px !important; ' +
                  'line-height: ' + lineSpacing + ' !important; ' +
                  'font-family: ' + fontFamily + ' !important; ' +
                  'padding: ' + marginV + 'px ' + marginH + 'px !important; ' +
                  'margin: 0 !important; ' +
                  'height: auto !important; ' +
                  'min-height: 100vh !important; ' +
                  'overflow: visible !important; ' +
                  'box-sizing: border-box !important; ' +
                  'will-change: transform !important; ' +
                  '-webkit-column-count: 1 !important; column-count: 1 !important; ' +
                  '-webkit-column-width: auto !important; column-width: auto !important; ' +
                  '} ' +
            'body *, body *::before, body *::after { ' +
                  'font-size: inherit !important; line-height: inherit !important; font-family: inherit !important; ' +
                  // Defeat publisher-forced page/column breaks (the common cause of
                  // one-line-per-page rendering).
                  'break-before: auto !important; break-after: auto !important; break-inside: auto !important; ' +
                  'page-break-before: auto !important; page-break-after: auto !important; page-break-inside: auto !important; ' +
                  '-webkit-column-break-before: auto !important; -webkit-column-break-after: auto !important; -webkit-column-break-inside: auto !important; ' +
                  'column-span: none !important; -webkit-column-span: none !important; ' +
                  '} ' +
            'body img, body svg, body video, body canvas { ' +
                  'max-width: 100% !important; ' +
                  'max-height: ' + imgMax + ' !important; ' +
                  'height: auto !important; display: block !important; margin: 0.4em auto !important; ' +
                  '} ' +
            'body h1 { font-size: 1.7em !important; } body h2 { font-size: 1.45em !important; } ' +
            'body h3 { font-size: 1.25em !important; } body h4, body h5, body h6 { font-size: 1.1em !important; }';

        // Apply inline on body so publisher inline-style rules don't win.
        var b = document.body.style;
        b.setProperty('font-size', fontSize + 'px', 'important');
        b.setProperty('line-height', '' + lineSpacing, 'important');
        b.setProperty('font-family', fontFamily, 'important');
        b.setProperty('padding-left', marginH + 'px', 'important');
        b.setProperty('padding-right', marginH + 'px', 'important');
        b.setProperty('padding-top', marginV + 'px', 'important');
        b.setProperty('padding-bottom', marginV + 'px', 'important');
        b.setProperty('margin', '0', 'important');
        b.setProperty('height', 'auto', 'important');
        b.setProperty('min-height', '100vh', 'important');
        b.setProperty('overflow', 'visible', 'important');
        b.setProperty('box-sizing', 'border-box', 'important');
        b.setProperty('display', 'block', 'important');
        b.setProperty('column-count', '1', 'important');
        b.setProperty('-webkit-column-count', '1', 'important');
        b.setProperty('column-width', 'auto', 'important');
        b.setProperty('-webkit-column-width', 'auto', 'important');

        // Force inline on every descendant — inline !important beats class !important.
        var all = document.body.querySelectorAll('*');
        for (var i = 0; i < all.length; i++) {
            var el = all[i];
            var tag = el.tagName;
            var s = el.style;
            s.setProperty('line-height', 'inherit', 'important');
            s.setProperty('font-family', 'inherit', 'important');
            s.setProperty('break-before', 'auto', 'important');
            s.setProperty('break-after', 'auto', 'important');
            s.setProperty('break-inside', 'auto', 'important');
            s.setProperty('page-break-before', 'auto', 'important');
            s.setProperty('page-break-after', 'auto', 'important');
            s.setProperty('page-break-inside', 'auto', 'important');
            s.setProperty('column-span', 'none', 'important');
            s.setProperty('-webkit-column-span', 'none', 'important');
            if (tag === 'H1' || tag === 'H2' || tag === 'H3' || tag === 'H4' || tag === 'H5' || tag === 'H6') {
                s.setProperty('white-space', 'normal', 'important');
                s.setProperty('overflow-wrap', 'break-word', 'important');
                s.setProperty('word-wrap', 'break-word', 'important');
            }
            if (tag === 'H1') s.setProperty('font-size', '1.7em', 'important');
            else if (tag === 'H2') s.setProperty('font-size', '1.45em', 'important');
            else if (tag === 'H3') s.setProperty('font-size', '1.25em', 'important');
            else if (tag === 'H4' || tag === 'H5' || tag === 'H6') s.setProperty('font-size', '1.1em', 'important');
            else s.setProperty('font-size', 'inherit', 'important');
        }

        // Trigger reflow, wait for image layout to settle, recompute the grid,
        // then either return to the anchored text (genuine settings change) or
        // simply re-assert the current page index.
        var _ = document.body.offsetHeight;
        var self = this;
        setTimeout(function() {
            self._whenImagesSettled(function() {
                self.calculatePages();
                if (shouldAnchor && self._navSeq === navSeqAtCapture) {
                    self._restoreAnchor(anchor);
                }
            });
        }, 80);
    },

    setDisplayMode: function(contrastBoost, boldMode, noAntialias) {
        var body = document.body;
        body.classList.toggle('contrast-boost', contrastBoost);
        body.classList.toggle('bold-mode', boldMode);
        body.classList.toggle('no-antialias', noAntialias);
        this.calculatePages();
    },

    setTextOptions: function(justify, hyphenation, bionic) {
        // Same first-apply guard as setStyle: the chapter-load pipeline calls this
        // with the already-rendered values; only genuine toggles should anchor.
        var sig = !!justify + '|' + !!hyphenation + '|' + !!bionic;
        var isFirstApply = !this._textOptsAppliedOnce;
        this._textOptsAppliedOnce = true;
        var shouldAnchor = !isFirstApply && this._textOptsSig !== sig;
        this._textOptsSig = sig;
        var anchor = shouldAnchor ? this._captureAnchor() : null;
        var navSeqAtCapture = this._navSeq;
        var body = document.body;
        body.classList.toggle('justify', !!justify);
        body.classList.toggle('no-hyphens', !hyphenation);
        if (bionic && !body.classList.contains('bionic')) {
            this.applyBionic();
            body.classList.add('bionic');
        } else if (!bionic && body.classList.contains('bionic')) {
            this.removeBionic();
            body.classList.remove('bionic');
        }
        var self = this;
        setTimeout(function() {
            self.calculatePages();
            if (shouldAnchor && self._navSeq === navSeqAtCapture) {
                self._restoreAnchor(anchor);
            }
        }, 80);
    },

    applyBionic: function() {
        var skip = { SCRIPT:1, STYLE:1, B:1, STRONG:1 };
        var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, {
            acceptNode: function(n) {
                if (!n.nodeValue || !n.nodeValue.trim()) return NodeFilter.FILTER_REJECT;
                var p = n.parentNode;
                while (p && p !== document.body) {
                    if (skip[p.tagName]) return NodeFilter.FILTER_REJECT;
                    if (p.classList && p.classList.contains('bf')) return NodeFilter.FILTER_REJECT;
                    p = p.parentNode;
                }
                return NodeFilter.FILTER_ACCEPT;
            }
        }, false);
        var nodes = [];
        var n;
        while ((n = walker.nextNode())) nodes.push(n);
        for (var i = 0; i < nodes.length; i++) {
            var node = nodes[i];
            var frag = document.createDocumentFragment();
            var parts = node.nodeValue.split(/(\s+)/);
            for (var j = 0; j < parts.length; j++) {
                var w = parts[j];
                if (!w) continue;
                if (/^\s+$/.test(w)) {
                    frag.appendChild(document.createTextNode(w));
                } else {
                    var lead = 0;
                    while (lead < w.length && !/[a-zA-Z0-9]/.test(w[lead])) lead++;
                    var letters = w.length - lead;
                    var boldUntil = lead + Math.ceil(letters / 2);
                    if (boldUntil <= lead) {
                        frag.appendChild(document.createTextNode(w));
                    } else {
                        if (lead > 0) frag.appendChild(document.createTextNode(w.substring(0, lead)));
                        var b = document.createElement('b');
                        b.className = 'bf';
                        b.textContent = w.substring(lead, boldUntil);
                        frag.appendChild(b);
                        if (boldUntil < w.length) frag.appendChild(document.createTextNode(w.substring(boldUntil)));
                    }
                }
            }
            node.parentNode.replaceChild(frag, node);
        }
    },

    removeBionic: function() {
        var bs = document.querySelectorAll('b.bf');
        for (var i = 0; i < bs.length; i++) {
            var b = bs[i];
            b.parentNode.replaceChild(document.createTextNode(b.textContent), b);
        }
        document.body.normalize();
    },

    scrollToPercent: function(percent) {
        var page = Math.floor(percent * this.totalPages);
        this.goToPage(page, false, 'seek');
    },

    /**
     * Search uses Range.getBoundingClientRect() to find the actual Y-pixel
     * position of each match. With translateY pagination we map Y → page by
     * dividing by pageHeight. Earlier versions used a character-position
     * heuristic — that broke under variable line-heights and images, so a
     * match showing as "page 5" might land you on page 10. This is exact.
     */
    searchText: function(query) {
        if (!query) return '[]';
        var pages = this._collectMatches(query, Number.MAX_SAFE_INTEGER).map(function(m) { return m.page; });
        // De-dupe while preserving order
        var seen = {}; var out = [];
        for (var i = 0; i < pages.length; i++) {
            if (!seen[pages[i]]) { seen[pages[i]] = true; out.push(pages[i]); }
        }
        return JSON.stringify(out);
    },

    searchTextWithSnippets: function(query, limit) {
        if (!query) return '[]';
        return JSON.stringify(this._collectMatches(query, limit || 30));
    },

    _collectMatches: function(query, limit) {
        var ph = this.pageHeight || window.innerHeight || 800;
        var results = [];
        var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null, false);
        var node;
        var q = query.toLowerCase();
        while ((node = walker.nextNode())) {
            var text = node.nodeValue;
            if (!text) continue;
            var lower = text.toLowerCase();
            var idx = 0;
            while ((idx = lower.indexOf(q, idx)) !== -1) {
                try {
                    var range = document.createRange();
                    range.setStart(node, idx);
                    range.setEnd(node, idx + q.length);
                    var rect = range.getBoundingClientRect();
                    // Map viewport-y to document-y by adding current translation.
                    var translateY = this.currentPage * this.pageHeight;
                    var documentY = rect.top + translateY;
                    var page = Math.floor(documentY / ph);
                    var snippetStart = Math.max(0, idx - 35);
                    var snippetEnd = Math.min(text.length, idx + q.length + 35);
                    var snippet = (snippetStart > 0 ? '…' : '') +
                        text.substring(snippetStart, snippetEnd).replace(/\s+/g, ' ').trim() +
                        (snippetEnd < text.length ? '…' : '');
                    results.push({ page: Math.max(0, page), snippet: snippet });
                    if (results.length >= limit) return results;
                } catch (e) { /* skip this match */ }
                idx += q.length;
            }
        }
        return results;
    },

    getSelectedText: function() {
        var sel = window.getSelection();
        return sel ? sel.toString().trim() : '';
    },

    clearSelection: function() {
        if (window.getSelection) window.getSelection().removeAllRanges();
    },

    markHighlight: function(text) {
        if (!text || text.length < 2) return false;
        var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null, false);
        var node;
        while ((node = walker.nextNode())) {
            var idx = node.nodeValue.indexOf(text);
            if (idx >= 0) {
                var before = node.nodeValue.substring(0, idx);
                var match = node.nodeValue.substring(idx, idx + text.length);
                var after = node.nodeValue.substring(idx + text.length);
                var span = document.createElement('span');
                span.className = 'highlight';
                span.textContent = match;
                var afterNode = document.createTextNode(after);
                node.nodeValue = before;
                node.parentNode.insertBefore(span, node.nextSibling);
                node.parentNode.insertBefore(afterNode, span.nextSibling);
                return true;
            }
        }
        return false;
    },

    applyAllHighlights: function(jsonList) {
        try {
            var arr = JSON.parse(jsonList);
            for (var i = 0; i < arr.length; i++) this.markHighlight(arr[i]);
        } catch (e) {}
    },

    /**
     * Chapter-local character offset of the first visible text — a coordinate
     * that survives ANY reflow (font size, viewport, late images), unlike a page
     * index which is only meaningful for one particular page grid. This is what
     * gets persisted as the reading position.
     */
    _charOffsetOfViewportTop: function() {
        if (typeof document.caretRangeFromPoint !== 'function') return -1;
        var r = null;
        var xs = [Math.floor(window.innerWidth * 0.5), Math.floor(window.innerWidth * 0.25), Math.floor(window.innerWidth * 0.75)];
        outer:
        for (var y = 4; y < Math.min(this.pageHeight || 800, window.innerHeight); y += 12) {
            for (var i = 0; i < xs.length; i++) {
                var c = document.caretRangeFromPoint(xs[i], y);
                if (c && c.startContainer && c.startContainer.nodeType === 3 &&
                    c.startContainer.nodeValue && c.startContainer.nodeValue.trim().length > 0) {
                    r = c;
                    break outer;
                }
            }
        }
        if (!r) return -1;
        var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null, false);
        var count = 0, node;
        while ((node = walker.nextNode())) {
            if (node === r.startContainer) return count + r.startOffset;
            count += node.nodeValue.length;
        }
        return -1;
    },

    /** Navigate to a chapter-local character offset. Queued if pagination isn't
     *  ready yet and resolved against the final grid — the durable way to restore
     *  a saved reading position. */
    goToCharOffset: function(n) {
        if (n == null || n < 0) return;
        this._navSeq++;   // counts as explicit navigation
        if (!this._paginated) { this._pendingChar = n; return; }
        this._resolveCharOffset(n);
    },

    /** Rect of the single character at (node, off); null if unmeasurable. */
    _rectOfChar: function(node, off) {
        try {
            var len = node.nodeValue ? node.nodeValue.length : 0;
            if (!len) return null;
            off = Math.max(0, Math.min(len - 1, off));
            var range = document.createRange();
            range.setStart(node, off);
            range.setEnd(node, Math.min(off + 1, len));
            return range.getBoundingClientRect();
        } catch (e) { return null; }
    },

    _resolveCharOffset: function(n) {
        try {
            var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null, false);
            var count = 0, node;
            while ((node = walker.nextNode())) {
                var len = node.nodeValue.length;
                if (count + len > n) {
                    var rect = this._rectOfChar(node, n - count);
                    // The saved offset can land in INVISIBLE text — script
                    // nodes, display:none footnotes, whitespace collapsed
                    // between blocks. Those measure as a zero rect, and a zero
                    // rect computes page 0: the restore silently threw the
                    // reader back to the chapter start. Walk forward to the
                    // next visibly-rendered character instead.
                    while ((!rect || rect.height < 1) && (node = walker.nextNode())) {
                        if (node.nodeValue.trim().length === 0) continue;
                        rect = this._rectOfChar(node, 0);
                    }
                    if (!rect || rect.height < 1) return;   // nothing visible after target
                    var docY = rect.top + this._currentTranslate();
                    var page = Math.floor(docY / this.pageHeight);
                    this.goToPage(Math.max(0, page), true, 'char-restore');
                    return;
                }
                count += len;
            }
            this.goToPage(this.totalPages - 1, true, 'char-restore-end');
        } catch (e) { /* keep current position */ }
    },

    notifyPageChange: function() {
        try {
            var ch = this._paginated ? this._charOffsetOfViewportTop() : -1;
            CalmBridge.onPageChanged(this.currentPage, this.totalPages, ch);
        } catch(e) {}
        // Pages carrying a picture get a forced full e-ink refresh regardless
        // of the auto-refresh setting: partial updates leave big images badly
        // painted, and the paint delay invites "did it turn?" double-taps.
        try {
            if (this._paginated && this.currentPage !== this._lastImgNotifyPage) {
                var self = this;
                setTimeout(function() {
                    if (self._viewportHasImage()) {
                        self._lastImgNotifyPage = self.currentPage;
                        try { CalmBridge.onImageVisible(); } catch (e) {}
                    }
                }, 250);
            }
        } catch(e) {}
    },

    /** True when a meaningfully-sized image intersects the current viewport. */
    _viewportHasImage: function() {
        var imgs = document.images;
        for (var i = 0; i < imgs.length; i++) {
            var r = imgs[i].getBoundingClientRect();
            if (r.height > 40 && r.bottom > 0 && r.top < window.innerHeight) return true;
        }
        return false;
    },

    notifySelection: function() {
        var sel = this.getSelectedText();
        if (sel.length > 0) {
            try { CalmBridge.onTextSelected(sel); } catch (e) {}
        }
    },

    /**
     * Many EPUBs wrap images (especially covers) as
     *   <svg …><image xlink:href="pic.jpg"/></svg>
     * which text/html WebView rendering shows as NOTHING. Replace each such
     * wrapper with a plain <img> pointing at the same file.
     */
    _unwrapSvgImages: function() {
        var svgs = document.querySelectorAll('svg');
        for (var i = svgs.length - 1; i >= 0; i--) {
            var svg = svgs[i];
            var im = svg.querySelector('image');
            if (!im) continue;
            var href = im.getAttribute('xlink:href') || im.getAttribute('href') ||
                       im.getAttributeNS('http://www.w3.org/1999/xlink', 'href');
            if (!href) continue;
            var img = document.createElement('img');
            img.src = href;
            if (svg.parentNode) svg.parentNode.replaceChild(img, svg);
        }
    },

    attachImageHandlers: function() {
        this._unwrapSvgImages();
        var imgs = document.body.getElementsByTagName('img');
        for (var i = 0; i < imgs.length; i++) {
            (function(img) {
                img.style.cursor = 'pointer';
                img.addEventListener('click', function(e) {
                    e.preventDefault(); e.stopPropagation();
                    try { CalmBridge.onImageTapped(img.src || img.getAttribute('data-src') || ''); } catch(ex) {}
                });
                // Images load AFTER pagination is computed; each arrival shifts all
                // content below it and stales the page grid — around pictures that
                // read as skipped or repeated pages. Recalculate (debounced) and
                // re-assert the current page whenever an image finishes loading.
                if (!img.complete) {
                    var onSettle = function() {
                        clearTimeout(CalmReader._imgDebounce);
                        CalmReader._imgDebounce = setTimeout(function() {
                            // A late image arrival must re-arm the image-page
                            // refresh: the flash that fired on page arrival
                            // repainted a page the image wasn't on yet.
                            CalmReader._lastImgNotifyPage = -1;
                            CalmReader.calculatePages();
                        }, 150);
                    };
                    img.addEventListener('load', onSettle);
                    img.addEventListener('error', onSettle);
                }
            })(imgs[i]);
        }
    },

    wordCount: function() {
        var t = (document.body.innerText || document.body.textContent || '').trim();
        if (!t) return 0;
        return t.split(/\s+/).length;
    },

    /**
     * Returns the text currently visible on screen. Works with translateY
     * pagination by checking each text node's viewport-relative bounding rect.
     * Used by TTS read-aloud so we narrate what the user is actually looking at.
     */
    getCurrentPageText: function() {
        var pageH = this.pageHeight || window.innerHeight;
        var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, {
            acceptNode: function(n) {
                if (!n.nodeValue || !n.nodeValue.trim()) return NodeFilter.FILTER_REJECT;
                return NodeFilter.FILTER_ACCEPT;
            }
        }, false);
        var parts = [];
        var node;
        while ((node = walker.nextNode())) {
            var range = document.createRange();
            range.selectNode(node);
            var rect = range.getBoundingClientRect();
            // Visible if any part overlaps [0, pageHeight] vertically.
            if (rect.bottom > 0 && rect.top < pageH) {
                parts.push(node.nodeValue);
            }
        }
        return parts.join(' ').replace(/\s+/g, ' ').trim();
    }
};

document.addEventListener('DOMContentLoaded', function() {
    setTimeout(function() {
        CalmReader.init();
        CalmReader.attachImageHandlers();
    }, 100);

    document.addEventListener('selectionchange', function() {
        clearTimeout(CalmReader._selDebounce);
        CalmReader._selDebounce = setTimeout(function() {
            CalmReader.notifySelection();
        }, 250);
    });
});
