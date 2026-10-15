package com.bernardo.ereader

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableString
import android.text.style.AlignmentSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.net.Uri
import android.os.Bundle
import android.text.Spannable
import android.text.style.BackgroundColorSpan
import android.view.ActionMode
import android.view.GestureDetector
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Switch
import android.view.View
import android.content.res.ColorStateList
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.PopupWindow
import android.widget.Toast
import android.view.Gravity
import android.graphics.drawable.GradientDrawable
import java.io.File
import kotlin.concurrent.thread
import kotlin.math.abs

class MainActivity : Activity(), Narrator.Listener, NarrationService.Controller {

    companion object {
        private const val REQ_LIBRARY = 3
        private const val REQ_SETTINGS = 4
        // a "page" is a whole chapter; only books without chapters get cut into parts this big
        private const val MAX_SECTION = 60_000
        private const val PART_CHARS = 30_000
        private const val MENU_READ_HERE = 1001
        private const val MENU_PRONOUNCE = 1002
    }

    private lateinit var scroll: ScrollView
    private lateinit var pageView: TextView
    private lateinit var pageLabel: TextView
    private lateinit var playBtn: ImageButton
    private lateinit var busy: ProgressBar
    private lateinit var speedChip: TextView
    private lateinit var controls: LinearLayout
    private lateinit var jumpBack: ImageButton
    private lateinit var loadingOverlay: LinearLayout
    /** Auto-scroll with the voice; turned off when you scroll or change chapter yourself. */
    private var follow = true
    private var spoken = -1 to -1     // sentence currently being read
    private var th = Themes.all[0]

    private val prefs by lazy { getSharedPreferences("reader", MODE_PRIVATE) }
    private val booksDir by lazy { BookStore.dir(this) }
    private lateinit var narrator: Narrator

    private var bookId: String? = null
    private var bookLang: String? = null
    private var book = Book("", emptyList(), IntArray(0))
    private val text get() = book.text
    private var pages = IntArray(0)   // start offset of every page
    private var page = 0
    private var readPos = 0           // where narration resumes (char offset in the book)
    private var highlight = BackgroundColorSpan(0)
    private var tapMark = BackgroundColorSpan(0)
    private var jumpPopup: PopupWindow? = null
    private var fullscreen = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        scroll = findViewById(R.id.scroll)
        pageView = findViewById(R.id.page)
        pageLabel = findViewById(R.id.pageLabel)
        playBtn = findViewById(R.id.play)
        busy = findViewById(R.id.busy)
        speedChip = findViewById(R.id.speedChip)
        controls = findViewById(R.id.controls)
        jumpBack = findViewById(R.id.jumpBack)
        loadingOverlay = findViewById(R.id.loadingOverlay)
        jumpBack.setOnClickListener { returnToVoice() }
        scroll.setOnTouchListener { _, ev ->
            if (ev.actionMasked == MotionEvent.ACTION_MOVE && narrator.isPlaying && follow) {
                follow = false
                updateJumpBack()
            }
            false
        }

        narrator = Narrator(this, this).apply {
            val last = prefs.getString("last_book", null)
            voiceId = Voices.resolve(this@MainActivity, last, last?.let { prefs.getString("lang2_$it", null) }).id
            speed = prefs.getFloat("speed", 1.0f)
            pauseMs = prefs.getInt("pause", 400)
            commaPauses = prefs.getBoolean("comma_pauses", true)
            commaPauseMs = prefs.getInt("comma_pause", 180)
            maxGapMs = prefs.getInt("max_gap", 90)
            phrasing = prefs.getInt("phrasing", 2)
            // debug: adb shell am start -n com.bernardo.ereader/.MainActivity --ei threads 6
            intent.getIntExtra("threads", -1).takeIf { it > 0 }?.let { prefs.edit().putInt("threads", it).apply() }
            threads = prefs.getInt("threads", 4)
            init()
        }
        applyTheme()
        applyTypography()
        NarrationService.controller = this

        findViewById<View>(R.id.prev).setOnClickListener { userTurnPage(page - 1) }
        findViewById<View>(R.id.next).setOnClickListener { userTurnPage(page + 1) }
        findViewById<View>(R.id.back).setOnClickListener { skipSentence(-1) }
        findViewById<View>(R.id.fwd).setOnClickListener { skipSentence(+1) }
        speedChip.setOnClickListener { pickSpeed() }
        updateSpeedChip()
        // remember where you are inside a chapter as you scroll (debounced)
        scroll.setOnScrollChangeListener { _, _, _, _, _ ->
            pageView.removeCallbacks(saveScrollPos)
            pageView.postDelayed(saveScrollPos, 400)
            if (!follow) updateJumpBack()
        }
        playBtn.setOnClickListener { togglePlay() }

        // Long-press a word -> "Read from here" in the selection menu
        pageView.customSelectionActionModeCallback = object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                menu.add(Menu.NONE, MENU_PRONOUNCE, 1, "Fix pronunciation")
                menu.add(Menu.NONE, MENU_READ_HERE, 0, "Read from here")
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                return true
            }
            override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false
            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                if (item.itemId == MENU_PRONOUNCE) {
                    val a = minOf(pageView.selectionStart, pageView.selectionEnd).coerceAtLeast(0)
                    val b = maxOf(pageView.selectionStart, pageView.selectionEnd).coerceAtMost(pageView.text.length)
                    val word = pageView.text.subSequence(a, b).toString()
                    mode.finish()
                    Pronunciations.edit(this@MainActivity, bookId, word)
                    return true
                }
                if (item.itemId != MENU_READ_HERE) return false
                val local = minOf(pageView.selectionStart, pageView.selectionEnd).coerceAtLeast(0)
                mode.finish()
                startReading(wordStart(pages[page] + local))
                return true
            }
            override fun onDestroyActionMode(mode: ActionMode) {}
        }

        val handled = handleViewIntent(intent)
        if (!handled) prefs.getString("last_book", null)?.let { openBook(it) } ?: run { showEmpty(); showLibrary() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleViewIntent(intent)
    }

    private fun handleViewIntent(intent: Intent?): Boolean {
        val uri = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data ?: return false
        importBook(uri)
        return true
    }

    // ---------- swipe to turn pages ----------
    private val gestures by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (!fullscreen) return false
                // a double-tap on text would also select a word: swallow the rest of this gesture
                swallowGesture = true
                setFullscreen(false)
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (fullscreen) { setFullscreen(false); return true }   // tap anywhere brings the controls back
                if (bookId == null || jumpPopup != null) return false
                showJumpPopup(e.rawX, e.rawY)
                return true
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                val dx = e2.x - (e1?.x ?: return false)
                val dy = e2.y - e1.y
                if (abs(dx) > 150 && abs(dx) > abs(dy) * 2) {
                    userTurnPage(if (dx < 0) page + 1 else page - 1)
                    return true
                }
                return false
            }
        })
    }

    /** While narrating: tap a phrase -> small "Read from here" bubble that jumps narration to that sentence. */
    private fun showJumpPopup(rawX: Float, rawY: Float) {
        val loc = IntArray(2)
        pageView.getLocationOnScreen(loc)
        val sLoc = IntArray(2)
        scroll.getLocationOnScreen(sLoc)
        if (rawY < sLoc[1] || rawY > sLoc[1] + scroll.height) return   // tap was on the bottom bar
        val local = pageView.getOffsetForPosition(rawX - loc[0], rawY - loc[1])
        if (local < 0) return
        val (s, e) = sentenceAround(pages[page] + local)
        if (e <= s) return
        val sp = pageView.text as? Spannable ?: return
        sp.setSpan(tapMark, (s - pages[page]).coerceIn(0, sp.length), (e - pages[page]).coerceIn(0, sp.length),
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)

        val d = resources.displayMetrics.density
        val label = TextView(this).apply {
            text = "▶  Read from here"
            textSize = 15f
            setTextColor(th.onAccent)
            setPadding((16 * d).toInt(), (10 * d).toInt(), (16 * d).toInt(), (10 * d).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 20 * d
                setColor(th.accent)
            }
            elevation = 6 * d
        }
        val popup = PopupWindow(label, android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            isOutsideTouchable = true
            setOnDismissListener {
                (pageView.text as? Spannable)?.removeSpan(tapMark)
                jumpPopup = null
            }
        }
        label.setOnClickListener {
            popup.dismiss()
            startReading(s)
        }
        label.measure(0, 0)
        val x = (rawX - label.measuredWidth / 2).toInt().coerceAtLeast((8 * d).toInt())
        val y = (rawY - label.measuredHeight - 28 * d).toInt().coerceAtLeast(sLoc[1])
        jumpPopup = popup
        popup.showAtLocation(scroll, Gravity.NO_GRAVITY, x, y)
        pageView.postDelayed({ if (jumpPopup === popup) popup.dismiss() }, 4000)
    }

    /** Sentence containing [offset], clamped to the current chapter. */
    private fun sentenceAround(offset: Int): Pair<Int, Int> {
        val lo = pages[page]
        val hi = pageEnd(page)
        fun isEnd(i: Int) = text[i] == '\n' ||
            (text[i] in ".!?…" && (i + 1 >= text.length || text[i + 1].isWhitespace() || text[i + 1] in "\"'”’»)"))
        var s = offset.coerceIn(lo, hi - 1)
        while (s > lo && !isEnd(s - 1)) s--
        while (s < hi && (text[s].isWhitespace() || text[s] in "\"'”’»)")) s++
        var e = offset.coerceIn(lo, hi - 1)
        while (e < hi && !isEnd(e)) e++
        while (e < hi && text[e] != '\n' && !text[e].isWhitespace()) e++  // include closing quotes
        return s to e
    }

    private var swallowGesture = false

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (swallowGesture) {
            gestures.onTouchEvent(ev)
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                swallowGesture = false
                (pageView.text as? Spannable)?.let { android.text.Selection.removeSelection(it) }
            }
            return true
        }
        if (gestures.onTouchEvent(ev)) return true
        return super.dispatchTouchEvent(ev)
    }

    // ---------- books ----------
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        fun icon(id: Int, order: Int, title: String, res: Int) {
            val item = menu.add(0, id, order, title).setIcon(res)
            item.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            item.icon?.mutate()?.setTint(th.text)
        }
        icon(2, 0, "Library", R.drawable.ic_library)
        icon(12, 1, "Search", R.drawable.ic_search)
        icon(5, 2, "Chapters", R.drawable.ic_toc)
        icon(7, 3, "Full screen", R.drawable.ic_fullscreen)
        icon(11, 4, "Settings", R.drawable.ic_settings)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            2 -> showLibrary()
            5 -> showChapters()
            7 -> setFullscreen(true)
            8 -> openSettings(SettingsActivity.SECTION_APPEARANCE)
            11 -> openSettings(null)
            12 -> showSearch()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_SETTINGS) {
            if (looksSnapshot() != looksBeforeSettings) { applyTheme(); applyTypography() }
            updateSpeedChip()
            if (narrator.isPreviewing) narrator.stop()
            // changes were applied live; only resume if a voice test interrupted the book
            if (wasReadingBeforeSettings && !narrator.isPlaying) startReading(readPos) else onPlaybackChanged()
        }
        if (requestCode == REQ_LIBRARY) {
            // Back pressed in a library that was opened with Back: leave the app (narration keeps its controls)
            if (data?.getBooleanExtra(LibraryActivity.EXTRA_EXIT, false) == true) { moveTaskToBack(true); return }
            val current = bookId
            if (current != null && !File(booksDir, "$current.txt").exists()) {
                // the open book was removed from the library
                narrator.stop()
                onPlaybackChanged()
                bookId = null
                showEmpty()
            }
            val id = data?.getStringExtra(LibraryActivity.EXTRA_OPEN)
            if (resultCode == RESULT_OK && id != null && id != bookId) openBook(id)
        }
    }

    /** "Open with" from a file manager: import into the library and open. */
    private fun importBook(uri: Uri) {
        actionBar?.subtitle = Themes.tint("Importing…", th.dim)
        thread {
            try {
                val id = BookStore.import(this, uri)
                runOnUiThread { openBook(id) }
            } catch (t: Throwable) {
                runOnUiThread {
                    Toast.makeText(this, "Could not open book: ${t.message}", Toast.LENGTH_LONG).show()
                    updateHeader()
                }
            }
        }
    }

    private fun openBook(id: String) {
        val loaded = BookParser.load(booksDir, id) ?: return showEmpty()
        narrator.stop()
        saveProgress()
        bookId = id
        book = loaded
        search = null
        pages = paginate()
        readPos = prefs.getInt("pos_$id", 0).coerceIn(0, text.length)
        prefs.edit().putString("last_book", id).apply()
        BookStore.markOpened(this, id)
        // language: detected at import; older books get detected now
        // "lang2_" = detected by the current detector (older "lang_" values could be wrong, e.g. Portuguese -> Magyar)
        bookLang = prefs.getString("lang2_$id", null)
            ?: LangDetect.detect(text, prefs.getString("langtag_$id", null)).also { prefs.edit().putString("lang2_$id", it).apply() }
        narrator.voiceId = Voices.resolve(this, id, bookLang).id
        narrator.pronunciations = Pronunciations.rules(this, id)
        showPage(pageOf(readPos))
        scrollToOffset(readPos, force = true)
        updateHeader()
        onPlaybackChanged()
    }

    private fun showEmpty() {
        title = Themes.tint("bagueration2.0", th.text)
        actionBar?.subtitle = null
        pageView.text = "No book open.\n\nOpen the Library and tap + to add one.\n\nTip: tap any sentence to start reading aloud from there."
        pageLabel.text = ""
    }

    private var settingsSnapshot: List<Any> = emptyList()
    private var wasReadingBeforeSettings = false
    private var looksBeforeSettings: List<Any?> = emptyList()
    private fun looksSnapshot(): List<Any?> = listOf("theme", "custom_bg", "custom_accent", "font_size", "font", "spacing", "justify")
        .map { prefs.all[it] }

    private fun openSettings(section: String?) {
        wasReadingBeforeSettings = narrator.isPlaying && !narrator.isPreviewing
        settingsSnapshot = listOf(narrator.voiceId, narrator.speed, narrator.maxGapMs, narrator.commaPauses)
        looksBeforeSettings = looksSnapshot()
        startActivityForResult(Intent(this, SettingsActivity::class.java)
            .putExtra(SettingsActivity.EXTRA_SECTION, section)
            .putExtra(SettingsActivity.EXTRA_BOOK_ID, bookId)
            .putExtra(SettingsActivity.EXTRA_BOOK_LANG, bookLang), REQ_SETTINGS)
    }

    private fun showLibrary(exitOnBack: Boolean = false) {
        startActivityForResult(Intent(this, LibraryActivity::class.java)
            .putExtra(LibraryActivity.EXTRA_CURRENT, bookId)
            .putExtra(LibraryActivity.EXTRA_EXIT_ON_BACK, exitOnBack), REQ_LIBRARY)
    }

    // ---------- paging ----------
    /** One page per chapter (a long vertical strip). Chapterless books are cut into ~30k-char parts. */
    private fun paginate(): IntArray {
        val t = text
        val bounds = (book.chapters.map { it.start } + 0 + t.length).filter { it in 0..t.length }.distinct().sorted()
        val starts = ArrayList<Int>()
        for (k in 0 until bounds.size - 1) {
            val segEnd = bounds[k + 1]
            var pos = bounds[k]
            starts += pos
            if (segEnd - pos <= MAX_SECTION) continue
            while (segEnd - pos > MAX_SECTION) {
                val target = pos + PART_CHARS
                pos = t.lastIndexOf("\n\n", target).takeIf { it > pos + PART_CHARS / 2 }?.plus(2) ?: target
                starts += pos
            }
        }
        return if (starts.isEmpty()) intArrayOf(0) else starts.toIntArray()
    }

    private fun chapterAt(offset: Int): Int {
        var idx = -1
        for ((i, c) in book.chapters.withIndex()) if (c.start <= offset) idx = i else break
        return idx
    }

    private fun labelFor(p: Int): String {
        val pct = pages[p] * 100L / text.length.coerceAtLeast(1)
        val unit = if (book.chapters.isNotEmpty()) "Chapter" else "Part"
        return "$unit ${p + 1} of ${pages.size}  ·  $pct%"
    }

    private fun showChapters() {
        if (bookId == null) return
        if (book.chapters.isEmpty()) return Toast.makeText(this, "No chapters found in this book", Toast.LENGTH_SHORT).show()
        val titles = book.chapters.mapIndexed { i, c -> "${i + 1}.  ${c.title}" }.toTypedArray()
        Themes.dialog(this).setTitle("Chapters")
            .setSingleChoiceItems(titles, chapterAt(pages[page])) { d, i ->
                d.dismiss()
                userTurnPage(pageOf(book.chapters[i].start))
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun pageOf(offset: Int): Int {
        val i = pages.binarySearch(offset)
        return (if (i >= 0) i else -i - 2).coerceIn(0, pages.size - 1)
    }

    private fun pageEnd(p: Int) = if (p + 1 < pages.size) pages[p + 1] else text.length

    private fun showPage(p: Int) {
        if (pages.isEmpty()) return
        page = p.coerceIn(0, pages.size - 1)
        pageView.setText(styledPage(page), TextView.BufferType.SPANNABLE)
        scroll.scrollTo(0, 0)
        pageLabel.text = labelFor(page)
        updateHeader()
        for ((id, enabled) in listOf(R.id.prev to (page > 0), R.id.next to (page < pages.size - 1))) {
            findViewById<View>(id).apply { isEnabled = enabled; alpha = if (enabled) 1f else 0.3f }
        }
    }

    private fun styledPage(p: Int): SpannableString {
        val start = pages[p]
        val end = pageEnd(p)
        val sp = SpannableString(text.substring(start, end))
        val st = book.styles
        for (i in st.indices step 3) {
            val s = maxOf(st[i], start)
            val e = minOf(st[i + 1], end)
            if (e > s) sp.setSpan(StyleSpan(st[i + 2]), s - start, e - start, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        for (c in book.chapters) {
            if (c.start < start || c.start >= end) continue
            val s = c.start - start
            val e = (c.headEnd - start).coerceAtMost(sp.length)
            if (e <= s) continue
            // paragraph spans must end after the newline
            val pe = if (e < sp.length && sp[e] == '\n') e + 1 else e
            sp.setSpan(RelativeSizeSpan(1.45f), s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            sp.setSpan(StyleSpan(Typeface.BOLD), s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            sp.setSpan(ForegroundColorSpan(th.heading), s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            sp.setSpan(AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER), s, pe, Spannable.SPAN_PARAGRAPH)
        }
        return sp
    }

    /** Manual chapter change, with a sideways slide. While narrating, the user is browsing; don't fight them. */
    private fun userTurnPage(p: Int) {
        if (bookId == null || p !in pages.indices || p == page) return
        val dir = if (p > page) 1 else -1
        val w = scroll.width.toFloat()
        if (narrator.isPlaying) follow = false
        scroll.animate().translationX(-dir * w * 0.3f).alpha(0f).setDuration(120).withEndAction {
            showPage(p)
            if (!narrator.isPlaying) {
                readPos = pages[page]
                saveProgress()
            }
            scroll.translationX = dir * w * 0.3f
            scroll.animate().translationX(0f).alpha(1f).setDuration(160).start()
            if (narrator.isPlaying) { if (spoken.first in pages[page] until pageEnd(page)) highlightSpoken(); updateJumpBack() }
        }.start()
    }

    /** Char offset (in the book) of the first line visible at the top of the screen. */
    private fun topVisibleOffset(): Int {
        val layout = pageView.layout ?: return pages[page]
        val line = layout.getLineForVertical((scroll.scrollY - pageView.paddingTop).coerceAtLeast(0))
        return pages[page] + layout.getLineStart(line)
    }

    private fun visibleRange(): IntRange {
        val layout = pageView.layout ?: return pages[page] until pageEnd(page)
        val top = layout.getLineForVertical((scroll.scrollY - pageView.paddingTop).coerceAtLeast(0))
        val bottom = layout.getLineForVertical(scroll.scrollY + scroll.height - pageView.paddingTop)
        return (pages[page] + layout.getLineStart(top)) until (pages[page] + layout.getLineEnd(bottom))
    }

    private val saveScrollPos = Runnable {
        if (!follow && narrator.isPlaying && spoken.first in visibleRange() && pageOf(spoken.first) == page) {
            follow = true
            updateJumpBack()
        }
        // keep the exact sentence position if it's still on screen, otherwise follow the scroll
        if (bookId != null && !narrator.isPlaying && readPos !in visibleRange()) {
            readPos = topVisibleOffset()
            saveProgress()
        }
    }

    private fun scrollToOffset(offset: Int, force: Boolean = false) {
        pageView.post {
            val layout = pageView.layout ?: return@post
            val local = (offset - pages[page]).coerceIn(0, pageView.text.length)
            val y = layout.getLineTop(layout.getLineForOffset(local)) + pageView.paddingTop
            if (force) scroll.scrollTo(0, (y - pageView.paddingTop).coerceAtLeast(0))
            else if (y < scroll.scrollY || y > scroll.scrollY + scroll.height * 0.75) {
                scroll.smoothScrollTo(0, (y - scroll.height / 4).coerceAtLeast(0))
            }
        }
    }

    private fun wordStart(offset: Int): Int {
        var i = offset.coerceIn(0, text.length)
        while (i > 0 && !text[i - 1].isWhitespace()) i--
        return i
    }

    // ---------- narration ----------
    private fun togglePlay() {
        if (bookId == null) return showLibrary()
        if (narrator.isPlaying) {
            narrator.stop()
            onPlaybackChanged()
            return
        }
        // resume from the saved spot if it's on screen, otherwise from the top of what you're looking at
        val from = if (readPos in visibleRange()) readPos else topVisibleOffset()
        startReading(from)
    }

    private fun startReading(from: Int) {
        if (bookId == null) return
        if (offerVoiceForBookLanguage { startReading(from) }) return
        follow = true
        readPos = from
        saveProgress()
        narrator.start(text, from)
        onPlaybackChanged()
        onBuffering(true)
    }

    private fun onPlaybackChanged() {
        val playing = narrator.isPlaying
        syncMedia()
        if (playing && !narrator.isPreviewing) grabAudio() else releaseAudio()
        playBtn.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        playBtn.contentDescription = if (playing) "Stop reading" else "Read aloud"
        if (!playing) { onBuffering(false); follow = true; spoken = -1 to -1 }
        updateJumpBack()
        if (playing) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            (pageView.text as? Spannable)?.removeSpan(highlight)
        }
    }

    override fun onBuffering(waiting: Boolean) {
        val show = waiting && narrator.isPlaying
        busy.visibility = if (show) android.view.View.VISIBLE else android.view.View.GONE
        val msg = if (narrator.isReady) "Preparing voice…" else "Loading voice model…"
        if (show) {
            findViewById<TextView>(R.id.loadingText).text = msg
            if (loadingOverlay.visibility != View.VISIBLE) {
                loadingOverlay.alpha = 0f
                loadingOverlay.visibility = View.VISIBLE
                loadingOverlay.animate().alpha(1f).setDuration(150).start()
            }
        } else loadingOverlay.visibility = View.GONE
        if (show) pageLabel.text = msg
        else if (pages.isNotEmpty() && bookId != null) pageLabel.text = labelFor(page)
    }

    override fun onSentence(start: Int, end: Int) {
        onBuffering(false)
        readPos = start
        saveProgress()
        spoken = start to end
        if (mediaActive && chapterAt(start) != mediaChapter) syncMedia()
        if (!follow) {
            // you're browsing: keep the highlight in sync if it's on this chapter, but don't move the view
            if (start in pages[page] until pageEnd(page)) highlightSpoken() else (pageView.text as? Spannable)?.removeSpan(highlight)
            updateJumpBack()
            return
        }
        if (start !in pages[page] until pageEnd(page)) showPage(pageOf(start))
        highlightSpoken()
        scrollToOffset(start)
    }

    private fun highlightSpoken() {
        val (start, end) = spoken
        if (start < 0) return
        val pStart = pages[page]
        val sp = pageView.text as? Spannable ?: return
        val hs = (start - pStart).coerceIn(0, sp.length)
        val he = (minOf(end, pageEnd(page)) - pStart).coerceIn(hs, sp.length)
        sp.setSpan(highlight, hs, he, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    /** Chevron pointing toward the sentence being read (up/down, or toward its chapter). */
    private fun updateJumpBack() {
        val show = narrator.isPlaying && !follow && spoken.first >= 0
        jumpBack.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) return
        val target = pageOf(spoken.first)
        val up = if (target != page) target < page else {
            val layout = pageView.layout
            val y = layout?.getLineTop(layout.getLineForOffset((spoken.first - pages[page]).coerceIn(0, pageView.text.length))) ?: 0
            y + pageView.paddingTop < scroll.scrollY
        }
        jumpBack.setImageResource(if (up) R.drawable.ic_expand_less else R.drawable.ic_expand_more)
    }

    private fun returnToVoice() {
        follow = true
        if (spoken.first < 0) return updateJumpBack()
        if (pageOf(spoken.first) != page) showPage(pageOf(spoken.first))
        highlightSpoken()
        scrollToOffset(spoken.first)
        updateJumpBack()
    }

    override fun onFinished() = onPlaybackChanged()

    override fun onReady() = updateHeader()

    override fun onError(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        narrator.stop()
        onPlaybackChanged()
    }

    /** Title = book, subtitle = current chapter. */
    private fun updateHeader() {
        val id = bookId ?: return
        title = Themes.tint(prefs.getString("title_$id", id), th.text)
        val ch = if (pages.isNotEmpty()) book.chapters.getOrNull(chapterAt(pages[page]))?.title else null
        actionBar?.subtitle = Themes.tint(ch, th.dim)
    }

    private fun setSpeed(v: Float) {
        narrator.speed = v
        prefs.edit().putFloat("speed", v).apply()
        updateSpeedChip()
        restartIfPlaying()
    }

    private fun updateSpeedChip() {
        speedChip.text = "${"%.2f".format(java.util.Locale.US, narrator.speed).trimEnd('0').trimEnd('.')}×"
    }

    /** Speed sheet: big readout, 0.5x-2.0x slider in 0.05 steps, presets. Applied once on close. */
    private fun pickSpeed() {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        fun fmt(v: Float) = "${"%.2f".format(java.util.Locale.US, v).trimEnd('0').trimEnd('.')}×"
        var chosen = narrator.speed
        val readout = TextView(this).apply {
            textSize = 34f; gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD
            setTextColor(th.accent); text = fmt(chosen)
        }
        val slider = SeekBar(this).apply {
            max = 30                                    // (2.0 - 0.5) / 0.05
            progress = ((chosen - 0.5f) / 0.05f).toInt().coerceIn(0, 30)
            progressTintList = ColorStateList.valueOf(th.accent)
            thumbTintList = ColorStateList.valueOf(th.accent)
        }
        val presets = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        fun select(v: Float) {
            chosen = (Math.round(v * 20) / 20f).coerceIn(0.5f, 2.0f)
            readout.text = fmt(chosen)
            for (i in 0 until presets.childCount) (presets.getChildAt(i) as TextView).let { chip ->
                val on = chip.tag == chosen
                chip.setTextColor(if (on) th.onAccent else readout.currentTextColor.let { if (th.light) 0xFF333333.toInt() else 0xFFDDDDDD.toInt() })
                chip.background = GradientDrawable().apply {
                    cornerRadius = dp(16).toFloat()
                    if (on) setColor(th.accent) else setStroke(dp(1), if (th.light) 0xFFBBBBBB.toInt() else 0xFF555B66.toInt())
                }
            }
        }
        for (v in floatArrayOf(0.8f, 1.0f, 1.25f, 1.5f, 1.75f)) presets.addView(TextView(this).apply {
            text = fmt(v); tag = v; textSize = 14f; gravity = Gravity.CENTER
            setPadding(dp(12), dp(7), dp(12), dp(7))
            setOnClickListener { select(v); slider.progress = ((v - 0.5f) / 0.05f).toInt() }
        }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(4); marginEnd = dp(4) })
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) { if (fromUser) select(0.5f + p * 0.05f) }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
        select(chosen)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
            addView(readout)
            addView(slider, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                val dim = if (th.light) 0xFF777777.toInt() else 0xFF8A94A6.toInt()
                addView(TextView(this@MainActivity).apply { text = "0.5×"; textSize = 11f; setTextColor(dim) }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(TextView(this@MainActivity).apply { text = "2×"; textSize = 11f; setTextColor(dim); gravity = Gravity.END }, LinearLayout.LayoutParams(0, -2, 1f))
            }, LinearLayout.LayoutParams(-1, -2).apply { marginStart = dp(16); marginEnd = dp(16) })
            addView(presets, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        }
        Themes.dialog(this).setTitle("Reading speed").setView(box)
            .setPositiveButton("Done") { _, _ -> if (chosen != narrator.speed) setSpeed(chosen) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Jump narration one sentence back / forward (starts reading if stopped). */
    private fun skipSentence(dir: Int, fromSaved: Boolean = false) {
        if (bookId == null || pages.isEmpty()) return
        val cur = if (fromSaved || narrator.isPlaying || readPos in visibleRange()) readPos else topVisibleOffset()
        if (pageOf(cur) != page) showPage(pageOf(cur))
        val (s, e) = sentenceAround(cur.coerceIn(pages[page], pageEnd(page) - 1))
        val target = if (dir > 0) {
            var k = e
            while (k < text.length && text[k].isWhitespace()) k++
            k
        } else previousSentenceStart(s)
        if (target >= text.length) return
        if (target !in pages[page] until pageEnd(page)) showPage(pageOf(target))
        startReading(target)
    }

    /** Start of the sentence before the one starting at [s] (may be in the previous chapter). */
    private fun previousSentenceStart(s: Int): Int {
        fun isEnd(i: Int) = text[i] == '\n' ||
            (text[i] in ".!?…" && (i + 1 >= text.length || text[i + 1].isWhitespace() || text[i + 1] in "\"'”’»)"))
        // step back over the whitespace / closing quotes / punctuation that end the previous sentence
        var k = s - 1
        while (k > 0 && (text[k].isWhitespace() || text[k] in "\"'”’»)")) k--
        while (k > 0 && text[k] in ".!?…") k--
        if (k <= 0) return 0
        // then back to that sentence's start
        var start = k
        while (start > 0 && !isEnd(start - 1)) start--
        while (start < s && (text[start].isWhitespace() || text[start] in "\"'”’»)")) start++
        return start
    }

    // ---------- look & feel ----------

    private fun applyTheme() {
        th = Themes.current(this)
        Themes.applyChrome(this, th)
        findViewById<View>(R.id.root).setBackgroundColor(th.bg)
        pageView.setTextColor(th.text)
        pageView.highlightColor = th.selection
        controls.background = GradientDrawable().apply {
            setColor(th.bar)
            val r = 22 * resources.displayMetrics.density
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        }
        pageLabel.setTextColor(th.dim)
        speedChip.setTextColor(th.accent)
        speedChip.background = GradientDrawable().apply {
            cornerRadius = 50f
            setStroke((1.2f * resources.displayMetrics.density).toInt(), th.accent)
        }
        for (id in intArrayOf(R.id.prev, R.id.next, R.id.back, R.id.fwd)) {
            findViewById<ImageButton>(id).imageTintList = ColorStateList.valueOf(th.text)
        }
        playBtn.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(th.accent) }
        playBtn.imageTintList = ColorStateList.valueOf(th.onAccent)
        busy.indeterminateTintList = ColorStateList.valueOf(th.accent)
        jumpBack.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(th.accent) }
        jumpBack.imageTintList = ColorStateList.valueOf(th.onAccent)
        loadingOverlay.background = GradientDrawable().apply { cornerRadius = 20 * resources.displayMetrics.density; setColor(th.bar) }
        findViewById<ProgressBar>(R.id.loadingSpinner).indeterminateTintList = ColorStateList.valueOf(th.accent)
        findViewById<TextView>(R.id.loadingText).setTextColor(th.text)
        // spans carry their own color: rebuild them and re-render the page
        (pageView.text as? Spannable)?.let { it.removeSpan(highlight); it.removeSpan(tapMark) }
        highlight = BackgroundColorSpan(th.highlight)
        tapMark = BackgroundColorSpan(th.selection)
        if (bookId != null && pages.isNotEmpty()) {
            val y = scroll.scrollY
            pageView.setText(styledPage(page), TextView.BufferType.SPANNABLE)
            if (narrator.isPlaying && spoken.first in pages[page] until pageEnd(page)) highlightSpoken()
            scroll.post { scroll.scrollTo(0, y) }
            updateHeader()
        } else title = Themes.tint(title, th.text)
        invalidateOptionsMenu()
    }

    private fun applyTypography() {
        pageView.textSize = prefs.getInt("font_size", 19).toFloat()
        pageView.typeface = if (prefs.getString("font", "serif") == "sans") Typeface.SANS_SERIF else Typeface.SERIF
        pageView.setLineSpacing(0f, prefs.getFloat("spacing", 1.4f))
        pageView.justificationMode = if (prefs.getBoolean("justify", true))
            android.text.Layout.JUSTIFICATION_MODE_INTER_WORD else android.text.Layout.JUSTIFICATION_MODE_NONE
        if (narrator.isPlaying) scrollToOffset(readPos)
    }

    /**
     * If the book's language has no installed voice (and you haven't picked one anyway), suggest
     * downloading one. Returns true if a dialog took over; [then] continues reading afterwards.
     */
    private fun offerVoiceForBookLanguage(then: () -> Unit): Boolean {
        val id = bookId ?: return false
        val lang = bookLang ?: return false
        val current = Voices.byId(narrator.voiceId)
        if (current?.lang == lang || Voices.defaultFor(this, lang) != null) return false
        if (prefs.getBoolean("skip_voice_offer_$id", false)) return false
        val suggestion = Voices.all.firstOrNull { it.lang == lang } ?: return false
        val langName = Voices.LANGUAGES[lang] ?: lang
        Themes.dialog(this)
            .setTitle("This book is in $langName")
            .setMessage("You don't have a $langName voice yet. Download ${suggestion.name}? It's ${suggestion.downloadMb} MB and needs Wi-Fi.")
            .setPositiveButton("Download") { _, _ ->
                VoiceDownloader.withDialog(this, suggestion) {
                    Voices.remember(this, id, suggestion)
                    narrator.voiceId = suggestion.id
                    then()
                }
            }
            .setNegativeButton("Use current voice") { _, _ ->
                prefs.edit().putBoolean("skip_voice_offer_$id", true).apply()
                then()
            }
            .setNeutralButton("Cancel", null)
            .show()
        return true
    }

    // ---------- search ----------

    private var search: BookSearch? = null
    private var lastQuery = ""
    private var searchWholeBook = false
    private var searchMark = BackgroundColorSpan(0)

    /** Search this chapter or the whole book; tap a result to jump there. */
    private fun showSearch() {
        if (bookId == null) return
        val engine = search ?: BookSearch(text).also { search = it }
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        lateinit var dialog: android.app.AlertDialog
        var hits: List<BookSearch.Hit> = emptyList()

        val input = android.widget.EditText(this).apply {
            setText(lastQuery); setSelection(lastQuery.length)
            hint = "Search"; setSingleLine(); textSize = 16f
            setTextColor(th.text); setHintTextColor(th.dim)
            backgroundTintList = ColorStateList.valueOf(th.accent)
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
        }
        val count = TextView(this).apply { textSize = 12f; setTextColor(th.dim); setPadding(dp(4), dp(6), dp(4), dp(2)) }
        val adapter = object : android.widget.BaseAdapter() {
            override fun getCount() = hits.size
            override fun getItem(i: Int) = hits[i]
            override fun getItemId(i: Int) = i.toLong()
            override fun getView(i: Int, convert: View?, parent: android.view.ViewGroup): View {
                val hit = hits[i]
                val (snip, a, b) = engine.snippet(hit)
                val sp = SpannableString(snip).apply {
                    setSpan(StyleSpan(Typeface.BOLD), a, b, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    setSpan(ForegroundColorSpan(th.accent), a, b, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                return LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(6), dp(10), dp(6), dp(10))
                    if (searchWholeBook) book.chapters.getOrNull(chapterAt(hit.start))?.let { ch ->
                        addView(TextView(this@MainActivity).apply { text = ch.title; textSize = 11f; setTextColor(th.dim); maxLines = 1 })
                    }
                    addView(TextView(this@MainActivity).apply { text = sp; textSize = 14f; setTextColor(th.text); maxLines = 3 })
                }
            }
        }
        val results = android.widget.ListView(this).apply {
            this.adapter = adapter; divider = null
            setOnItemClickListener { _, _, i, _ -> dialog.dismiss(); jumpToMatch(hits[i]) }
        }
        val run = Runnable {
            val q = input.text.toString()
            lastQuery = q
            if (q.trim().length < 2) { hits = emptyList(); adapter.notifyDataSetChanged(); count.text = "Type at least 2 letters"; return@Runnable }
            val (from, to) = if (searchWholeBook) 0 to text.length else pages[page] to pageEnd(page)
            thread {
                val found = engine.find(q, from, to)
                runOnUiThread {
                    if (input.text.toString() != q) return@runOnUiThread
                    hits = found; adapter.notifyDataSetChanged()
                    count.text = when {
                        found.isEmpty() -> "No matches " + if (searchWholeBook) "in the book" else "in this chapter"
                        found.size >= 500 -> "500+ matches — showing the first 500"
                        else -> "${found.size} match" + (if (found.size == 1) "" else "es") + if (searchWholeBook) " in the book" else " in this chapter"
                    }
                }
            }
        }
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(e: android.text.Editable?) { input.removeCallbacks(run); input.postDelayed(run, 250) }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        // scope toggle
        val scope = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
        fun paintScope() {
            for (i in 0 until scope.childCount) (scope.getChildAt(i) as TextView).let { c ->
                val on = (i == 1) == searchWholeBook
                c.setTextColor(if (on) th.onAccent else th.text)
                c.background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); if (on) setColor(th.accent) else setStroke(dp(1), th.dim) }
            }
        }
        for ((i, label) in listOf("This chapter", "Whole book").withIndex()) scope.addView(TextView(this).apply {
            text = label; textSize = 13f; gravity = Gravity.CENTER; setPadding(dp(12), dp(7), dp(12), dp(7))
            setOnClickListener { searchWholeBook = i == 1; paintScope(); run.run() }
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
        paintScope()

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input); addView(scope); addView(count)
            addView(results, LinearLayout.LayoutParams(-1, (resources.displayMetrics.heightPixels * 0.5).toInt()))
        }
        dialog = Themes.dialog(this).setTitle("Search").setView(box).setNegativeButton("Close", null).create()
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        input.requestFocus()
        if (lastQuery.isNotBlank()) run.run()
    }

    private fun jumpToMatch(hit: BookSearch.Hit) {
        if (narrator.isPlaying) follow = false   // keep reading; the chevron brings you back
        val p = pageOf(hit.start)
        if (p != page) showPage(p)
        (pageView.text as? Spannable)?.let { sp ->
            sp.removeSpan(searchMark)
            searchMark = BackgroundColorSpan(th.selection)
            val a = (hit.start - pages[page]).coerceIn(0, sp.length)
            val b = (hit.end - pages[page]).coerceIn(a, sp.length)
            sp.setSpan(searchMark, a, b, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            val mark = searchMark
            pageView.postDelayed({ (pageView.text as? Spannable)?.removeSpan(mark) }, 5000)
        }
        pageView.post {
            val layout = pageView.layout ?: return@post
            val y = layout.getLineTop(layout.getLineForOffset((hit.start - pages[page]).coerceIn(0, pageView.text.length)))
            scroll.smoothScrollTo(0, (y - scroll.height / 3).coerceAtLeast(0))
        }
        updateJumpBack()
    }

    private fun restartIfPlaying() {
        if (narrator.isPlaying) startReading(readPos)
    }

    private fun saveProgress() {
        bookId?.let { prefs.edit().putInt("pos_$it", readPos).apply() }
    }

    /** Hides action bar, bottom controls and system bars. Only views change, so narration keeps going. */
    @Suppress("DEPRECATION")
    private fun setFullscreen(on: Boolean) {
        fullscreen = on
        findViewById<android.view.View>(R.id.controls).visibility = if (on) android.view.View.GONE else android.view.View.VISIBLE
        if (on) actionBar?.hide() else actionBar?.show()
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let { c ->
                if (on) {
                    c.hide(android.view.WindowInsets.Type.systemBars())
                    c.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                } else c.show(android.view.WindowInsets.Type.systemBars())
            }
        } else {
            window.decorView.systemUiVisibility = if (on)
                android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
                    android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            else 0
        }
        if (on) Toast.makeText(this, "Tap anywhere to show the controls", Toast.LENGTH_SHORT).show()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            fullscreen -> setFullscreen(false)
            bookId != null -> showLibrary(exitOnBack = true)   // back from a book goes to the library
            else -> super.onBackPressed()
        }
    }

    /** Foreground service + wake lock while narrating, so locking the phone doesn't stop the voice. */
    // ---------- system media controls (notification / quick settings / lock screen / headset) ----------

    private var mediaActive = false      // controls stay up while paused, until swiped away
    private var askedNotifications = false
    private var mediaChapter = -2

    /** Push play state + book / chapter / cover to the media session; starts or stops the service as needed. */
    private fun syncMedia() {
        val id = bookId
        val playing = narrator.isPlaying && !narrator.isPreviewing
        if (playing) mediaActive = true
        if (!mediaActive || id == null) {
            if (NarrationService.instance != null) stopService(Intent(this, NarrationService::class.java))
            return
        }
        if (playing && !askedNotifications && android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            askedNotifications = true
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 2)
        }
        val ci = chapterAt(readPos)
        mediaChapter = ci
        val bookTitle = prefs.getString("title_$id", id) ?: id
        val chapterTitle = book.chapters.getOrNull(ci)?.title ?: ""
        val cover = File(booksDir, "$id.jpg").takeIf { it.exists() }?.absolutePath
        val svc = NarrationService.instance
        if (svc != null) svc.update(playing, bookTitle, chapterTitle, cover)
        else if (playing) startForegroundService(Intent(this, NarrationService::class.java)
            .putExtra(NarrationService.EXTRA_PLAYING, true)
            .putExtra(NarrationService.EXTRA_TITLE, bookTitle)
            .putExtra(NarrationService.EXTRA_CHAPTER, chapterTitle)
            .putExtra(NarrationService.EXTRA_COVER, cover))
    }

    override fun remotePlay() = runOnUiThread {
        if (bookId != null && !narrator.isPlaying) startReading(readPos)
    }

    override fun remotePause() = runOnUiThread {
        if (narrator.isPlaying) {
            narrator.stop()
            onPlaybackChanged()
        }
    }

    override fun remoteSkip(dir: Int) = runOnUiThread { skipSentence(dir, fromSaved = true) }

    override fun remoteDismissed() = runOnUiThread { mediaActive = false }

    // pause for calls / other apps' audio, and when headphones are unplugged
    private val audioManager by lazy { getSystemService(android.media.AudioManager::class.java) }
    private var focusRequest: android.media.AudioFocusRequest? = null
    private val noisy = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context, i: Intent) = remotePause()
    }
    private var noisyRegistered = false

    private fun grabAudio() {
        if (focusRequest == null) {
            focusRequest = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setOnAudioFocusChangeListener { change ->
                    if (change == android.media.AudioManager.AUDIOFOCUS_LOSS ||
                        change == android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) remotePause()
                }
                .build()
            audioManager.requestAudioFocus(focusRequest!!)
        }
        if (!noisyRegistered) {
            val f = android.content.IntentFilter(android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(noisy, f, RECEIVER_NOT_EXPORTED) else registerReceiver(noisy, f)
            noisyRegistered = true
        }
    }

    private fun releaseAudio() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
        if (noisyRegistered) { unregisterReceiver(noisy); noisyRegistered = false }
    }

    override fun onPause() {
        super.onPause()
        saveProgress()
    }

    override fun onDestroy() {
        if (NarrationService.controller === this) NarrationService.controller = null
        releaseAudio()
        stopService(Intent(this, NarrationService::class.java))
        narrator.release()
        super.onDestroy()
    }
}
