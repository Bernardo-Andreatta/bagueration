package com.bernardo.ereader

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableString
import android.text.style.AlignmentSpan
import android.text.style.CharacterStyle
import android.text.style.ParagraphStyle
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.net.Uri
import android.os.Bundle
import android.text.Spannable
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
        private const val MENU_BOOKMARK = 1003
        private const val MENU_NOTE = 1004
    }

    private lateinit var scroll: ScrollView
    private lateinit var pageView: TextView
    private lateinit var pageLabel: TextView
    private lateinit var playBtn: ImageButton
    private lateinit var busy: ProgressBar
    private lateinit var speedChip: TextView
    private lateinit var audioChip: TextView
    private lateinit var controls: LinearLayout
    private lateinit var jumpBack: TextView
    private lateinit var dock: LinearLayout
    private lateinit var bufferPill: TextView
    private lateinit var emptyHolder: android.widget.FrameLayout
    private lateinit var progressLine: View
    /** Auto-scroll with the voice; turned off when you scroll or change chapter yourself. */
    private var follow = true
        set(v) { field = v; applyWordMark() }   // the word mark only shows while following
    private var spoken = -1 to -1     // sentence currently being read
    private var word = -1 to -1       // word currently being heard (estimated), inside [spoken]
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
    /** The sentence the voice was on when you paused: play continues there, wherever you've scrolled, until you pick other text. -1 = none. */
    private var heardPos = -1
    /** Paused in a pre-made audio file: which one and where (ms), to continue at that exact spot. */
    private var heardFile: Pair<String, Long>? = null
    private var pausedWhileHidden = false
    private var highlight = Mark(0, 2)
    private var wordMark = Mark(0, 3)   // the word being heard, drawn over the sentence highlight
    private var tapMark = Mark(0, 1)
    private var jumpPopup: PopupWindow? = null
    private var fullscreen = false
    private lateinit var topBar: Ui.TopBar
    private var bars = Ui.Bars(0, 0, 0, 0)

    private fun padForBars() {
        val d = resources.displayMetrics.density
        topBar.view.setPadding(bars.left, bars.top, bars.right, 0)
        controls.setPadding((12 * d).toInt() + bars.left, (6 * d).toInt(), (12 * d).toInt() + bars.right, (4 * d).toInt() + bars.bottom)
        emptyHolder.setPadding((24 * d).toInt() + bars.left, bars.top, (24 * d).toInt() + bars.right, bars.bottom)
        // full screen hides the status bar: keep the text where it is
        if (!fullscreen) layoutPage()
    }

    /**
     * The text column: at most 680dp wide and centered (tablets, landscape), with the margins setting at the sides,
     * and clear of the top bar and the player that float over it.
     */
    private fun layoutPage() {
        val d = resources.displayMetrics.density
        val margin = (prefs.getInt("margins", 24) * d).toInt()
        val avail = (scroll.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels) - bars.left - bars.right
        val maxCol = (680 * d).toInt()
        val capped = avail - 2 * margin > maxCol
        val side = if (capped) maxOf(margin, (32 * d).toInt()) else margin
        val lp = pageView.layoutParams as android.widget.FrameLayout.LayoutParams
        val width = if (capped) maxCol + 2 * side else -1
        if (lp.width != width || lp.leftMargin != bars.left || lp.rightMargin != bars.right) {
            lp.width = width; lp.gravity = Gravity.CENTER_HORIZONTAL
            lp.leftMargin = bars.left; lp.rightMargin = bars.right
            pageView.layoutParams = lp
        }
        // top bar: status bar + 56dp + the progress line; player: ~114dp + navigation bar
        pageView.setPadding(side, bars.top + (58 * d).toInt() + (14 * d).toInt(), side, bars.bottom + (114 * d).toInt() + (36 * d).toInt())
    }

    /** The top bar, (re)made in the current theme: back to the library, title = book, subtitle = chapter. */
    private fun buildTopBar() {
        val root = findViewById<android.widget.FrameLayout>(R.id.root)
        if (::topBar.isInitialized) root.removeView(topBar.view)
        topBar = Ui(this, th).topBar(null, nav = Ui.Action(R.drawable.ic_chevron_left, "Library") { showLibrary() },
            actions = listOf(
                Ui.Action(R.drawable.ic_toc, "Contents") { showChapters() },
                Ui.Action(R.drawable.ic_search, "Search") { showSearch() },
                Ui.Action(R.drawable.ic_more, "More") { showMore() },
            ), background = Themes.pageBackground(this, th))
        // where you are in the chapter: a thin line along the bar's bottom edge (stays when the bar slides away)
        progressLine = View(this).apply {
            background = android.graphics.drawable.LayerDrawable(arrayOf(
                android.graphics.drawable.ColorDrawable(th.outline),
                android.graphics.drawable.ClipDrawable(android.graphics.drawable.ColorDrawable(th.accent), Gravity.START,
                    android.graphics.drawable.ClipDrawable.HORIZONTAL)))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        topBar.view.addView(progressLine, LinearLayout.LayoutParams(-1, maxOf(2, (2 * resources.displayMetrics.density).toInt())))
        root.addView(topBar.view, root.indexOfChild(dock), android.widget.FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        if (barsHidden) {
            topBar.row.alpha = 0f; topBar.row.visibility = View.INVISIBLE
            topBar.view.post { topBar.view.translationY = -topBar.row.height.toFloat() }
        }
        padForBars()
        updateProgressLine()
    }

    private fun updateProgressLine() {
        if (!::progressLine.isInitialized) return
        val child = scroll.getChildAt(0) ?: return
        val range = (child.height - scroll.height).coerceAtLeast(1)
        val f = if (bookId == null) 0f else (scroll.scrollY.toFloat() / range).coerceIn(0f, 1f)
        (progressLine.background as android.graphics.drawable.LayerDrawable).getDrawable(1).level = (f * 10000).toInt()
    }

    private fun showMore() {
        Ui(this, th).menuSheet(null, listOfNotNull(
            Ui.Action(R.drawable.ic_fullscreen, "Full screen") { setFullscreen(true) },
            if (bookId != null) Ui.Action(R.drawable.ic_headphones, "Prepared audio") { openRenders() } else null,
            Ui.Action(R.drawable.ic_text_format, "Appearance") { openSettings(SettingsActivity.SECTION_APPEARANCE) },
            Ui.Action(R.drawable.ic_settings, "Settings") { openSettings(null) },
            Ui.Action(R.drawable.ic_library, "Library") { showLibrary() },
        ))
    }

    // ---------- bars that slide away while you read ----------

    private var barsHidden = false
    private var scrollRun = 0
    private var scrollTouching = false
    private var lastScrollTouch = 0L

    /** Scrolling down hides the bars, scrolling up (or reaching the end) brings them back. Not while following the voice. */
    private fun autoHideBars(dy: Int, y: Int) {
        if (fullscreen || bookId == null) return
        // only your own scrolling (a drag, or the fling after it): opening a book at your place, jumping to a search
        // hit or following the voice scrolls too, and used to hide the bars as the book opened
        if (!scrollTouching && android.os.SystemClock.uptimeMillis() - lastScrollTouch > 1500) return
        if (narrator.isPlaying && follow) { scrollRun = 0; return }
        scrollRun = if (dy != 0 && (dy > 0) == (scrollRun > 0)) scrollRun + dy else dy
        val d = resources.displayMetrics.density
        val atEnd = y + scroll.height >= (scroll.getChildAt(0)?.height ?: 0) - 8 * d
        when {
            atEnd || y < 48 * d -> showBars()
            scrollRun > 24 * d -> hideBars()
            scrollRun < -24 * d -> showBars()
        }
    }

    private fun showBars() {
        if (!barsHidden || fullscreen) return
        barsHidden = false
        slideBars(false)
    }

    private fun hideBars() {
        if (barsHidden) return
        barsHidden = true
        slideBars(true)
    }

    /** Top bar up (leaving the status-bar strip and the progress line), player down. Views only: narration carries on. */
    private fun slideBars(hide: Boolean) {
        val interp = android.view.animation.DecelerateInterpolator()
        val dur = if (hide) 200L else 180L
        val row = topBar.row
        if (!hide) row.visibility = View.VISIBLE
        topBar.view.animate().translationY(if (hide) -row.height.toFloat() else 0f).setDuration(dur).setInterpolator(interp).start()
        row.animate().alpha(if (hide) 0f else 1f).setDuration(dur)
            .withEndAction { if (barsHidden) row.visibility = View.INVISIBLE }.start()
        dock.animate().translationY(if (hide) controls.height.toFloat() else 0f).setDuration(dur).setInterpolator(interp).start()
        // the pills above the player ride down with it; keep them clear of the navigation bar
        for (pill in listOf(jumpBack, bufferPill)) {
            pill.animate().translationY(if (hide) -bars.bottom.toFloat() else 0f).setDuration(dur).setInterpolator(interp).start()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.edgeToEdge(this)
        setContentView(R.layout.activity_main)
        scroll = findViewById(R.id.scroll)
        pageView = findViewById(R.id.page)
        pageLabel = findViewById(R.id.pageLabel)
        playBtn = findViewById(R.id.play)
        busy = findViewById(R.id.busy)
        speedChip = findViewById(R.id.speedChip)
        audioChip = findViewById(R.id.audioChip)
        // nothing prepared in this book yet: straight to "Prepare chapters"
        audioChip.setOnClickListener { bookId?.let { openRenders(prepare = Renders.list(this, it).isEmpty()) } }
        controls = findViewById(R.id.controls)
        jumpBack = findViewById(R.id.jumpBack)
        dock = findViewById(R.id.dock)
        bufferPill = findViewById(R.id.bufferPill)
        emptyHolder = findViewById(R.id.emptyHolder)
        jumpBack.setOnClickListener { returnToVoice() }
        scroll.setOnTouchListener { _, ev ->
            scrollTouching = ev.actionMasked != MotionEvent.ACTION_UP && ev.actionMasked != MotionEvent.ACTION_CANCEL
            lastScrollTouch = android.os.SystemClock.uptimeMillis()
            if (ev.actionMasked == MotionEvent.ACTION_MOVE && narrator.isPlaying && follow) {
                follow = false
                stopGliding()
                updateJumpBack()
            }
            false
        }

        // reuse the engine if it's still alive (an audio file was being made while the reader was closed)
        val alive = Narrator.instance?.takeIf { it.isRendering }
        narrator = alive?.also { it.listener = this } ?: Narrator(this, this).apply {
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
        Ui.onInsets(findViewById(R.id.root)) { b -> bars = b; padForBars() }
        NarrationService.controller = this

        findViewById<View>(R.id.prev).setOnClickListener { userTurnPage(page - 1) }
        findViewById<View>(R.id.next).setOnClickListener { userTurnPage(page + 1) }
        findViewById<View>(R.id.back).setOnClickListener { skipSentence(-1) }
        findViewById<View>(R.id.fwd).setOnClickListener { skipSentence(+1) }
        speedChip.setOnClickListener { pickSpeed() }
        updateSpeedChip()
        // remember where you are inside a chapter as you scroll (debounced)
        scroll.setOnScrollChangeListener { _, _, y, _, oldY ->
            if ((pageView as? PageView)?.needsRedraw() == true) pageView.invalidate()   // the page only draws the lines near the screen (see PageView.onDraw)
            pageView.removeCallbacks(saveScrollPos)
            pageView.postDelayed(saveScrollPos, 400)
            if (!follow) updateJumpBack()
            updateProgressLine()
            autoHideBars(y - oldY, y)
        }
        playBtn.setOnClickListener { togglePlay() }

        // Long-press a word -> "Read from here" in the selection menu
        pageView.customSelectionActionModeCallback = object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                menu.add(Menu.NONE, MENU_NOTE, 1, "Note")
                menu.add(Menu.NONE, MENU_BOOKMARK, 2, "Bookmark")
                menu.add(Menu.NONE, MENU_PRONOUNCE, 3, "Fix pronunciation")
                menu.add(Menu.NONE, MENU_READ_HERE, 0, "Read from here")
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                return true
            }
            override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false
            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                if (item.itemId == MENU_NOTE || item.itemId == MENU_BOOKMARK) {
                    val a = minOf(pageView.selectionStart, pageView.selectionEnd).coerceAtLeast(0)
                    val b = maxOf(pageView.selectionStart, pageView.selectionEnd).coerceAtMost(pageView.text.length)
                    mode.finish()
                    if (b > a) {
                        if (item.itemId == MENU_NOTE) editNote(null, pages[page] + a, pages[page] + b)
                        else addBookmark(pages[page] + a, pages[page] + b)
                    }
                    return true
                }
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

        AppLog.offerAfterCrash(this)
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
                if (downOnChrome) return false
                if (fullscreen) { setFullscreen(false); return true }   // tap anywhere brings the controls back
                showBars()
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
    /** Is the touch on [v] (only if it's showing)? */
    private fun isOver(v: View, e: MotionEvent): Boolean {
        if (v.visibility != View.VISIBLE || !v.isShown) return false
        val loc = IntArray(2).also { v.getLocationOnScreen(it) }
        return e.rawX >= loc[0] && e.rawX <= loc[0] + v.width && e.rawY >= loc[1] && e.rawY <= loc[1] + v.height
    }

    private fun showJumpPopup(rawX: Float, rawY: Float) {
        val loc = IntArray(2)
        pageView.getLocationOnScreen(loc)
        val sLoc = IntArray(2)
        scroll.getLocationOnScreen(sLoc)
        if (rawY < sLoc[1] || rawY > sLoc[1] + scroll.height) return   // tap was on the bottom bar
        val local = pageView.getOffsetForPosition(rawX - loc[0], rawY - loc[1])
        if (local < 0) return
        val at = pages[page] + local
        val note = annotations.firstOrNull { it.isNote && at >= it.start && at < it.end }
        val (s, e) = if (note != null) note.start to note.end else sentenceAround(at)
        if (e <= s) return
        val sp = pageView.text as? Spannable ?: return
        sp.setSpan(tapMark, (s - pages[page]).coerceIn(0, sp.length), (e - pages[page]).coerceIn(0, sp.length),
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)

        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val ui = Ui(this, th)
        val bubble = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ui.rounded(th.surface, 16, th.outline)
            elevation = 8 * d
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        lateinit var popup: PopupWindow
        // the first action is the main one: a filled pill; the others are quiet, with their icon in the accent
        fun action(label: String, icon: Int? = null, onClick: () -> Unit) = TextView(this).apply {
            val primary = (bubble.tag as? Int ?: 0) == 0
            bubble.tag = (bubble.tag as? Int ?: 0) + 1
            text = label; textSize = 14f; gravity = Gravity.CENTER_VERTICAL
            setTextColor(if (primary) th.onAccent else th.text)
            if (primary) typeface = ui.medium
            isSingleLine = true
            minHeight = dp(44)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            if (icon != null) Icons.leading(this, icon, if (primary) th.onAccent else th.accent, sizeDp = 18f, gapDp = 6f)
            background = if (primary) android.graphics.drawable.RippleDrawable(ui.ripple(th.onAccent), ui.rounded(th.accent, 22), null)
                else ui.pressable(radiusDp = 22)
            contentDescription = label
            setOnClickListener { popup.dismiss(); onClick() }
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        if (note != null) {
            // tapped a note: show it
            bubble.addView(TextView(this).apply {
                text = note.note; textSize = 14f; setTextColor(th.text); maxLines = 6
                ellipsize = android.text.TextUtils.TruncateAt.END
                maxWidth = (resources.displayMetrics.widthPixels * 0.8).toInt()
                setPadding(dp(10), dp(8), dp(10), dp(2))
            })
            row.addView(action("Read", R.drawable.ic_play) { startReading(s) })
            row.addView(action("Edit") { editNote(note) })
            row.addView(action("Delete") { removeAnnotation(note) })
        } else {
            val marked = annotations.firstOrNull { !it.isNote && it.start == s }
            row.addView(action("Read from here", R.drawable.ic_play) { startReading(s) })
            row.addView(action(if (marked != null) "Remove" else "Bookmark",
                if (marked != null) R.drawable.ic_bookmark_remove else R.drawable.ic_bookmark) {
                if (marked != null) removeAnnotation(marked) else addBookmark(s, e)
            })
            row.addView(action("Note", R.drawable.ic_note) { editNote(null, s, e) })
        }
        // keep the buttons on one line: on a narrow screen or with large text, tighten them, then drop the labels of
        // the icon buttons after the first (a squeezed "Note" used to wrap letter by letter)
        val avail = resources.displayMetrics.widthPixels - dp(32)
        fun rowWidth(): Int { row.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED); return row.measuredWidth + dp(12) }
        val buttons = (0 until row.childCount).map { row.getChildAt(it) as TextView }
        if (rowWidth() > avail) buttons.forEach { it.setPadding(dp(8), dp(8), dp(8), dp(8)); it.textSize = 13f }
        if (rowWidth() > avail) buttons.drop(1).filter { it.compoundDrawablesRelative[0] != null }.forEach {
            it.text = ""; it.compoundDrawablePadding = 0; it.setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        bubble.addView(row)
        bubble.measure(0, 0)
        // an explicit size: a WRAP_CONTENT popup is first measured at the system's dialog width (320dp), which
        // squeezed the last button ("Note" showed as "N")
        popup = PopupWindow(bubble, bubble.measuredWidth, bubble.measuredHeight, true).apply {
            isOutsideTouchable = true
            setOnDismissListener {
                (pageView.text as? Spannable)?.removeSpan(tapMark)
                jumpPopup = null
            }
        }
        val x = (rawX - bubble.measuredWidth / 2).toInt().coerceIn(dp(8), (resources.displayMetrics.widthPixels - bubble.measuredWidth - dp(8)).coerceAtLeast(dp(8)))
        // above the sentence (not just the tapped line, so it doesn't cover the sentence's start); below it if there's
        // no room under the top bar
        val layout = pageView.layout
        val y = if (layout != null) {
            val top = loc[1] + pageView.totalPaddingTop + layout.getLineTop(layout.getLineForOffset((s - pages[page]).coerceIn(0, sp.length)))
            val bottom = loc[1] + pageView.totalPaddingTop + layout.getLineBottom(layout.getLineForOffset((e - pages[page]).coerceIn(0, sp.length)))
            val above = top - bubble.measuredHeight - dp(6)
            val barBottom = if (barsHidden) bars.top else topBar.view.height
            val below = bottom + dp(6)
            val dockTop = resources.displayMetrics.heightPixels - (if (barsHidden) 0 else controls.height) - bars.bottom
            when {
                above >= barBottom + dp(4) -> above
                below + bubble.measuredHeight <= dockTop -> below
                else -> (rawY - bubble.measuredHeight - 28 * d).toInt().coerceAtLeast(barBottom + dp(4))   // a long sentence
            }
        } else (rawY - bubble.measuredHeight - 28 * d).toInt().coerceAtLeast(bars.top + dp(8))
        jumpPopup = popup
        popup.showAtLocation(scroll, Gravity.NO_GRAVITY, x, y)
        pageView.postDelayed({ if (jumpPopup === popup) popup.dismiss() }, if (note != null) 8000 else 5000)
    }

    // ---------- bookmarks & notes ----------

    private var annotations: List<Annotation> = emptyList()
    private var wasPlayingForMarks = false

    private fun reloadAnnotations() {
        annotations = bookId?.let { Annotations.list(this, it) } ?: emptyList()
        refreshPageSpans()
    }

    private fun addBookmark(s: Int, e: Int) {
        val id = bookId ?: return
        Annotations.add(this, id, s, e, null)
        Toast.makeText(this, "Bookmarked", Toast.LENGTH_SHORT).show()
        reloadAnnotations()
    }

    private fun removeAnnotation(a: Annotation) {
        val id = bookId ?: return
        Annotations.remove(this, id, a)
        reloadAnnotations()
    }

    /** New note on [s, e) (existing == null) or edit an existing one. */
    private fun editNote(existing: Annotation?, s: Int = existing?.start ?: 0, e: Int = existing?.end ?: 0) {
        val id = bookId ?: return
        val d = resources.displayMetrics.density
        val quote = text.substring(s, e).trim().let { if (it.length > 160) it.take(160) + "…" else it }
        val ui = Ui(this, th)
        // a rounded field (not a bare underline floating under three empty lines), lined up with the dialog's title
        val input = android.widget.EditText(this).apply {
            setText(existing?.note.orEmpty()); setSelection(text.length)
            hint = "Your note"; minLines = 3; maxLines = 8; gravity = Gravity.TOP
            textSize = 16f; setTextColor(th.text); setHintTextColor(th.dim)
            background = ui.rounded(th.surface, 12, th.outline)
            setPadding((14 * d).toInt(), (12 * d).toInt(), (14 * d).toInt(), (12 * d).toInt())
            highlightColor = th.selection
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * d).toInt(), 0, (24 * d).toInt(), (4 * d).toInt())   // 24dp = the title's own inset
            addView(TextView(this@MainActivity).apply {
                text = "“$quote”"; textSize = 14f; setTextColor(th.dim); typeface = Typeface.SERIF
                setLineSpacing(0f, 1.2f)
                setPadding(0, 0, 0, (14 * d).toInt())
            })
            addView(input)
        }
        Themes.dialog(this).setTitle(if (existing == null) "Add note" else "Edit note").setView(box)
            .setPositiveButton("Save") { _, _ ->
                val t = input.text.toString().trim()
                when {
                    t.isEmpty() && existing != null -> Annotations.remove(this, id, existing)
                    t.isEmpty() -> {}
                    existing != null -> Annotations.update(this, id, existing.copy(note = t))
                    else -> Annotations.add(this, id, s, e, t)
                }
                reloadAnnotations()
            }
            .setNegativeButton("Cancel", null)
            .apply { if (existing != null) setNeutralButton("Delete") { _, _ -> removeAnnotation(existing) } }
            .show()
        input.requestFocus()
    }

    /** Re-draw the page's marks (bookmarks, notes, left-off) without moving it. */
    private fun refreshPageSpans() {
        if (bookId == null || pages.isEmpty()) return
        val fresh = styledPage(page)
        val cur = pageView.text as? Spannable
        if (cur != null && cur.length == fresh.length) {
            // swap the marks on the text already shown: setText on a selectable TextView puts the cursor back at the
            // start and scrolls the page to the top (pausing did that, to add the left-off underline)
            for (s in cur.getSpans(0, cur.length, Any::class.java)) {
                if ((s is CharacterStyle || s is ParagraphStyle) && s !== highlight && s !== wordMark && s !== tapMark) cur.removeSpan(s)
            }
            for (s in fresh.getSpans(0, fresh.length, Any::class.java)) {
                cur.setSpan(s, fresh.getSpanStart(s), fresh.getSpanEnd(s), fresh.getSpanFlags(s))
            }
        } else {
            val y = scroll.scrollY
            pageView.setText(fresh, TextView.BufferType.SPANNABLE)
            scroll.post { scroll.scrollTo(0, y) }
        }
        if (narrator.isPlaying && spoken.first in pages[page] until pageEnd(page)) highlightSpoken()
    }

    private fun sentenceAroundIn(offset: Int, lo: Int, hi: Int): Pair<Int, Int> {
        fun isEnd(i: Int) = text[i] == '\n' ||
            (text[i] in ".!?…" && (i + 1 >= text.length || text[i + 1].isWhitespace() || text[i + 1] in "\"'”’»)"))
        var s = offset.coerceIn(lo, hi - 1)
        while (s > lo && !isEnd(s - 1)) s--
        while (s < hi && (text[s].isWhitespace() || text[s] in "\"'”’»)")) s++
        var e = offset.coerceIn(lo, hi - 1)
        while (e < hi && !isEnd(e)) e++
        while (e < hi && text[e] != '\n' && !text[e].isWhitespace()) e++
        return s to e
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

    /** The touch started on a button or bar, not the page: a tap there never opens the sentence bubble. */
    private var downOnChrome = false

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // decided when the finger lands: by the time a single tap is confirmed, "Back to the voice" has already
        // hidden itself, and the tap used to fall through to the sentence under it
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            downOnChrome = isOver(jumpBack, ev) || isOver(bufferPill, ev) || isOver(controls, ev) || isOver(topBar.view, ev)
        }
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
            // "Resume" in the library: read aloud from where you left off
            if (resultCode == RESULT_OK && id != null && id == bookId && data.getBooleanExtra(LibraryActivity.EXTRA_PLAY, false) &&
                !narrator.isPlaying) pageView.post { if (bookId == id) resumeReading() }
        }
    }

    /** "Open with" from a file manager: import into the library and open. */
    private fun importBook(uri: Uri) {
        topBar.setTitle(topBar.title.text, "Importing…")
        thread {
            try {
                val id = BookStore.import(this, uri)
                runOnUiThread { openBook(id) }
            } catch (t: Throwable) {
                runOnUiThread {
                    AppLog.e("Import", "open-with import failed", t)
                    Toast.makeText(this, "Could not open book: ${t.message}", Toast.LENGTH_LONG).show()
                    updateHeader()
                }
            }
        }
    }

    private fun openBook(id: String) {
        AppLog.i("Reader", "open book $id")
        val loaded = BookParser.load(booksDir, id) ?: return showEmpty().also { AppLog.e("Reader", "book $id missing") }
        narrator.stop()
        saveProgress()
        bookId = id
        book = loaded
        search = null
        pages = paginate()
        readPos = prefs.getInt("pos_$id", 0).coerceIn(0, text.length)
        heardPos = prefs.getInt("heard_$id", -1).coerceAtMost(text.length - 1)
        heardFile = prefs.getString("heardfile_$id", null)?.split(':')?.takeIf { it.size == 2 }
            ?.let { (f, ms) -> ms.toLongOrNull()?.let { f to it } }
        prefs.edit().putString("last_book", id).apply()
        BookStore.markOpened(this, id)
        // language: detected at import; older books get detected now
        // "lang2_" = detected by the current detector (older "lang_" values could be wrong, e.g. Portuguese -> Magyar)
        bookLang = prefs.getString("lang2_$id", null)
            ?: LangDetect.detect(text, prefs.getString("langtag_$id", null)).also { prefs.edit().putString("lang2_$id", it).apply() }
        narrator.voiceId = Voices.resolve(this, id, bookLang).id
        narrator.pronunciations = Pronunciations.rules(this, id)
        // hyphenation and line breaks in the book's language, not the phone's
        bookLang?.let { lang -> runCatching { pageView.textLocale = java.util.Locale.forLanguageTag(lang) } }
        emptyHolder.visibility = View.GONE
        dock.visibility = View.VISIBLE
        bookActions(true)
        annotations = Annotations.list(this, id)
        AppLog.i("Reader", "book $id: ${text.length} chars, ${book.chapters.size} chapters, lang $bookLang, voice ${narrator.voiceId}")
        showPage(pageOf(readPos))
        scrollToOffset(readPos, force = true)
        updateHeader()
        onPlaybackChanged()
        prefetchHere()
    }

    /** Contents and Search only mean something with a book open. */
    private fun bookActions(on: Boolean) {
        for (i in 0 until minOf(2, topBar.actions.childCount)) topBar.actions.getChildAt(i).visibility = if (on) View.VISIBLE else View.GONE
    }

    private fun showEmpty() {
        topBar.setTitle("bagueration2.0")
        pageView.text = ""
        pageLabel.text = ""
        emptyHolder.removeAllViews()
        emptyHolder.addView(Ui(this, th).emptyState(R.drawable.ic_library, "No book open",
            "Add a book in the library, or pick one you've added.\nTip: tap any sentence to start reading aloud from there.",
            "Open library", R.drawable.ic_library) { showLibrary() },
            android.widget.FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        emptyHolder.visibility = View.VISIBLE
        dock.visibility = View.GONE
        bookActions(false)
        updateProgressLine()
    }

    private var settingsSnapshot: List<Any> = emptyList()
    private var wasReadingBeforeSettings = false
    private var looksBeforeSettings: List<Any?> = emptyList()
    private fun looksSnapshot(): List<Any?> = listOf("theme", "custom_bg", "custom_accent", "font_size", "font", "spacing", "justify", "paper_texture", "margins")
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

    private var startAfterBatteryPrompt: Int? = null

    /** First live reading in a book: mention heat/battery and point at preparing chapters. Nothing plays until a choice. */
    private fun showLiveTip(from: Int) {
        val id = bookId ?: return
        fun seen() = prefs.edit().putBoolean("live_tip_$id", true).apply()
        val restricted = !Background.unrestricted(this)
        Themes.dialog(this).setTitle("Reading aloud, live")
            .setMessage("The voice is made on your phone as it reads. Over long listens that warms the phone and uses battery.\n\n" +
                "Or prepare this chapter first: it's made once, ahead of time, and plays with no loading. " +
                "You can start listening after a couple of minutes." +
                if (restricted) "\n\nWith the screen off your phone slows this app down. Allowing unrestricted battery use helps." else "")
            .setPositiveButton("Read live") { _, _ -> seen(); startReading(from) }
            .setNegativeButton("Prepare chapters") { _, _ -> seen(); openRenders(prepare = true) }
            .apply {
                if (restricted) setNeutralButton("Allow") { _, _ ->
                    seen()
                    startAfterBatteryPrompt = from   // start once you're back from the system prompt
                    Background.request(this@MainActivity)
                }
            }
            .show()
    }

    /** Prepared audio for this book, about the chapter on screen; [prepare] opens straight into "Prepare chapters". */
    private fun openRenders(prepare: Boolean = false) {
        val id = bookId ?: return
        val pos = if (pages.isNotEmpty()) pages[page] else 0
        startActivity(Intent(this, RendersActivity::class.java)
            .putExtra(RendersActivity.EXTRA_BOOK, id)
            .putExtra(RendersActivity.EXTRA_CHAPTER, chapterAt(pos).coerceAtLeast(0))
            .putExtra(RendersActivity.EXTRA_POS, if (heardPos >= 0 && chapterAt(heardPos) == chapterAt(pos)) heardPos else pos)
            .putExtra(RendersActivity.EXTRA_NEW, prepare))
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

    /** "Chapter 3 of 12 · 14 min left": what's left of the chapter from where the voice is (or would start), at this speed. */
    private fun labelFor(p: Int): String {
        val unit = if (book.chapters.isNotEmpty()) "Chapter" else "Part"
        val from = when {
            narrator.isPlaying && spoken.first in pages[p] until pageEnd(p) -> spoken.first
            !narrator.isPlaying && heardPos in pages[p] until pageEnd(p) -> heardPos
            p == page && pageView.layout != null -> topVisibleOffset()
            else -> pages[p]
        }
        val min = ((RenderManager.estimateMs(pageEnd(p) - from, narrator.speed, narrator.pauseMs) + 30_000) / 60_000).toInt()
        val left = when {
            min < 1 -> "under a minute left"
            min < 60 -> "$min min left"
            else -> "${min / 60} h ${min % 60} min left"
        }
        // count the book's chapters, not pages: front matter before chapter 1 and long chapters cut into parts used to
        // make this disagree with the chapter title in the top bar ("Chapter 272" under "270: Agreement")
        val (n, total) = if (book.chapters.isNotEmpty()) chapterAt(pages[p]) + 1 to book.chapters.size else p + 1 to pages.size
        if (n == 0) return "Start  ·  $left"
        // long books: the short form, so the minutes left aren't cut off by the chips
        return if (total >= 100) "$n / $total  ·  $left" else "$unit $n of $total  ·  $left"
    }

    /** Spoken length of a chapter, for the contents list. */
    private fun chapterLength(i: Int): String {
        val start = book.chapters[i].start
        val end = book.chapters.getOrNull(i + 1)?.start ?: text.length
        val min = ((RenderManager.estimateMs(end - start, narrator.speed, narrator.pauseMs) + 30_000) / 60_000).toInt()
        return if (min < 1) "< 1 min" else if (min < 60) "$min min" else "${min / 60} h ${min % 60} min"
    }

    /** Contents: Chapters / Bookmarks / Notes in one sheet, tap to jump. Opens scrolled to the chapter you're in. */
    private fun showChapters(tab: Int = 0) {
        if (bookId == null) return
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val ui = Ui(this, th)
        lateinit var sheet: Ui.Sheet
        val listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val listScroll = ScrollView(this).apply { addView(listBox); overScrollMode = View.OVER_SCROLL_NEVER }
        var current = tab

        fun row(title: CharSequence, sub: String?, trailing: String?, selected: Boolean, more: (() -> Unit)?, onClick: (() -> Unit)?) =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(56)
                setPadding(dp(12), dp(8), dp(if (more != null) 0 else 12), dp(8))
                background = ui.pressable(if (selected) ui.tinted(0.16f, th.bar) else null)
                if (selected) addView(View(this@MainActivity).apply { background = ui.oval(th.accent) },
                    LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(10) })
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(this@MainActivity).apply {
                        text = title; textSize = 15f; setTextColor(th.text); maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
                        if (selected) typeface = ui.medium
                    })
                    if (sub != null) addView(TextView(this@MainActivity).apply {
                        text = sub; textSize = 12.5f; setTextColor(th.dim); maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
                        setPadding(0, dp(2), 0, 0)
                    })
                }, LinearLayout.LayoutParams(0, -2, 1f))
                if (trailing != null) addView(TextView(this@MainActivity).apply { text = trailing; textSize = 12.5f; setTextColor(th.dim) },
                    LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(12) })
                if (more != null) addView(ui.iconButton(R.drawable.ic_more, "More options", tint = th.dim) { more() })
                contentDescription = listOfNotNull(title, sub, trailing, if (selected) "reading now" else null).joinToString(", ")
                if (onClick != null) setOnClickListener { onClick() }
            }
        fun jump(pos: Int) {
            sheet.dismiss()
            if (narrator.isPlaying) follow = false
            pickPosition(pos)
            val p = pageOf(pos)
            if (p != page) showPage(p)
            pageView.post {
                val layout = pageView.layout ?: return@post
                val y = layout.getLineTop(layout.getLineForOffset((pos - pages[page]).coerceIn(0, pageView.text.length)))
                scroll.smoothScrollTo(0, (y - scroll.height / 3).coerceAtLeast(0))
            }
            updateJumpBack()
        }
        fun snippet(a: Annotation) = text.substring(a.start, a.end).trim().replace('\n', ' ').let { if (it.length > 140) it.take(140) + "…" else it }
        fun chapterOf(pos: Int) = book.chapters.getOrNull(chapterAt(pos))?.title

        // segmented control: a sliding pill behind three labels
        val seg = android.widget.FrameLayout(this).apply {
            background = ui.rounded(th.surface, 22, th.outline)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val segPill = View(this).apply { background = ui.rounded(th.accent, 18) }
        val segLabels = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        // fixed heights: a match_parent child of a wrap_content FrameLayout takes all the height it's offered, and the
        // control filled the whole sheet
        seg.addView(segPill, android.widget.FrameLayout.LayoutParams(0, dp(40)))
        seg.addView(segLabels, android.widget.FrameLayout.LayoutParams(-1, dp(40)))
        fun segText(i: Int): String {
            val n = when (i) { 1 -> annotations.count { !it.isNote }; 2 -> annotations.count { it.isNote }; else -> 0 }
            return listOf("Chapters", "Bookmarks", "Notes")[i] + if (n > 0) " $n" else ""
        }
        lateinit var fill: () -> Unit
        fun select(animate: Boolean) {
            val w = segLabels.width / 3
            if (w > 0) {
                if (segPill.layoutParams.width != w) segPill.layoutParams = android.widget.FrameLayout.LayoutParams(w, dp(40))
                if (animate) segPill.animate().translationX((current * w).toFloat()).setDuration(180)
                    .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
                else segPill.translationX = (current * w).toFloat()
            }
            for (i in 0 until segLabels.childCount) (segLabels.getChildAt(i) as TextView).let { c ->
                c.text = segText(i)
                c.setTextColor(if (i == current) th.onAccent else th.text)
                c.isSelected = i == current
            }
            fill()
        }
        for (i in 0..2) segLabels.addView(TextView(this).apply {
            gravity = Gravity.CENTER; textSize = 13.5f; typeface = ui.medium; isSingleLine = true
            setAutoSizeTextTypeUniformWithConfiguration(10, 14, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
            background = ui.pressable(radiusDp = 18)
            setOnClickListener { if (current != i) { current = i; select(true) } }
        }, LinearLayout.LayoutParams(0, -1, 1f))

        fun annotationActions(a: Annotation) {
            val go = Ui.Action(R.drawable.ic_chevron_right, "Go to it") { jump(a.start) }
            Ui(this, th).menuSheet(if (a.isNote) "Note" else "Bookmark", if (a.isNote) listOf(go,
                Ui.Action(R.drawable.ic_note, "Edit note") { sheet.dismiss(); editNote(a) },
                Ui.Action(R.drawable.ic_delete, "Delete note") { removeAnnotation(a); select(false) },
            ) else listOf(go,
                Ui.Action(R.drawable.ic_bookmark_remove, "Remove bookmark") { removeAnnotation(a); select(false) },
            ))
        }

        fill = {
            listBox.removeAllViews()
            var currentRow: View? = null
            when (current) {
                0 -> if (book.chapters.isEmpty()) listBox.addView(row("No chapters found in this book", null, null, false, null, null))
                     else {
                         val here = chapterAt(pages[page])
                         book.chapters.forEachIndexed { i, c ->
                             val r = row(c.displayName(i), null, chapterLength(i), i == here, null) { jump(c.start) }
                             if (i == here) currentRow = r
                             listBox.addView(r)
                         }
                     }
                1 -> {
                    val marks = annotations.filter { !it.isNote }
                    if (marks.isEmpty()) listBox.addView(row("No bookmarks yet", "Tap a sentence and choose Bookmark.", null, false, null, null))
                    marks.forEach { a -> listBox.addView(row(snippet(a), chapterOf(a.start), null, false, { annotationActions(a) }) { jump(a.start) }.apply {
                        setOnLongClickListener { annotationActions(a); true }
                    }) }
                }
                else -> {
                    val notes = annotations.filter { it.isNote }
                    if (notes.isEmpty()) listBox.addView(row("No notes yet", "Tap a sentence (or select text) and choose Note.", null, false, null, null))
                    notes.forEach { a ->
                        val title = SpannableString(a.note!!).apply { setSpan(StyleSpan(Typeface.BOLD), 0, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE) }
                        listBox.addView(row(title, "“${snippet(a)}”" + (chapterOf(a.start)?.let { " · $it" } ?: ""), null, false,
                            { annotationActions(a) }) { jump(a.start) }.apply {
                            setOnLongClickListener { annotationActions(a); true }
                        })
                    }
                }
            }
            listScroll.scrollTo(0, 0)
            currentRow?.let { r -> listScroll.post { listScroll.scrollTo(0, (r.top - listScroll.height / 3).coerceAtLeast(0)) } }
        }

        sheet = ui.sheet("Contents", scroll = false, tall = true) {
            addView(seg, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(4), 0, dp(4), dp(8)) })
            addView(listScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        select(false)
        segLabels.post { select(false) }
        sheet.show()
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
        scroll.post { updateProgressLine() }
        updateHeader()
        updateAudioChip()
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
        // bookmarks (accent tint), notes (warm tint)
        val bookmarkTint = Themes.mix(th.bg, th.accent, if (th.light) 0.22f else 0.25f)
        val noteTint = Themes.mix(th.bg, 0xFFE8C36A.toInt(), if (th.light) 0.45f else 0.3f)
        for (a in annotations) {
            val s0 = maxOf(a.start, start); val e0 = minOf(a.end, end)
            if (e0 <= s0) continue
            sp.setSpan(Mark(if (a.isNote) noteTint else bookmarkTint), s0 - start, e0 - start, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        // where you left off (where play continues): the exact sentence, with a dotted underline, while not reading
        val leftOff = if (heardPos >= 0) heardPos else readPos
        // not at the very start of a chapter (that's just where you turned to it: no line under its heading)
        if (!narrator.isPlaying && leftOff in start until end && leftOff > start) {
            val (ls, le) = sentenceAroundIn(leftOff, start, end)
            if (le > ls) sp.setSpan(Mark((th.accent and 0xFFFFFF) or (0x99 shl 24), 3, dotted = true), ls - start, le - start, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
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
            // centered only when the heading is a paragraph of its own: a PDF heading can run into the text after it
            // (or start mid-line), and a paragraph span that doesn't cover whole paragraphs crashes
            val wholeParagraph = (s == 0 || sp[s - 1] == '\n') && (pe == sp.length || sp[pe - 1] == '\n')
            if (wholeParagraph) sp.setSpan(AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER), s, pe, Spannable.SPAN_PARAGRAPH)
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

    /**
     * How much of the scroll view's top and bottom is covered: the top bar (status bar + row, or just the status
     * strip once it slid away) and the player (or the navigation bar once it slid away). The text runs under both.
     */
    private fun coverTop(): Int {
        val d = resources.displayMetrics.density
        return if (fullscreen) 0 else bars.top + (if (barsHidden) 2 * d else 58 * d).toInt()
    }

    private fun coverBottom(): Int = when {
        fullscreen -> 0
        barsHidden -> bars.bottom
        else -> controls.height.takeIf { it > 0 } ?: (bars.bottom + (114 * resources.displayMetrics.density).toInt())
    }

    /** The part of the page you can actually see, in scroll coordinates: [first, last]. */
    private fun visibleWindow(): IntRange = (scroll.scrollY + coverTop())..(scroll.scrollY + scroll.height - coverBottom())

    private fun visibleRange(): IntRange {
        val layout = pageView.layout ?: return pages[page] until pageEnd(page)
        val win = visibleWindow()
        val top = layout.getLineForVertical((win.first - pageView.paddingTop).coerceAtLeast(0))
        val bottom = layout.getLineForVertical((win.last - pageView.paddingTop).coerceAtLeast(0))
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
        if (bookId != null && pages.isNotEmpty() && busy.visibility != View.VISIBLE) pageLabel.text = labelFor(page)
    }

    private fun scrollToOffset(offset: Int, force: Boolean = false) {
        pageView.post {
            val layout = pageView.layout ?: return@post
            val local = (offset - pages[page]).coerceIn(0, pageView.text.length)
            val y = layout.getLineTop(layout.getLineForOffset(local)) + pageView.paddingTop
            if (force) scroll.scrollTo(0, (y - pageView.paddingTop).coerceAtLeast(0))
            else {
                val win = visibleWindow()
                val vh = win.last - win.first
                if (y < win.first || y > win.first + vh * 0.75) {
                    scroll.smoothScrollTo(0, (y - coverTop() - vh / 4).coerceAtLeast(0))
                }
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
        if (narrator.isPlaying) return pauseReading()
        resumeReading()
    }

    private fun pauseReading() {
        if (!narrator.isPlaying) return
        heardFile = narrator.filePosition
        narrator.stop()
        saveProgress()
        AppLog.i("Reader", "paused at $heardPos" + (heardFile?.let { " (audio file ${it.first} at ${it.second}ms)" } ?: ""))
        onPlaybackChanged()
        prefetchHere()
    }

    /**
     * Play: continue from the last sentence heard (and the exact spot in an audio file), wherever you've scrolled
     * since. Only when you picked other text (Read from here, contents, bookmarks, search) or nothing was heard yet:
     * the saved spot if it's on screen, otherwise the top of what you're looking at.
     */
    private fun resumeReading() {
        if (heardPos in 0 until text.length) return startReading(heardPos, resume = true)
        val from = if (readPos in visibleRange()) readPos else topVisibleOffset()
        startReading(from)
    }

    /** You chose where to read from while paused (not just scrolled): play starts there, not where the voice stopped. */
    private fun pickPosition(pos: Int) {
        if (narrator.isPlaying) return
        heardPos = -1
        heardFile = null
        readPos = pos
        saveProgress()
        refreshPageSpans()   // the left-off underline moves to what you picked
    }

    private fun startReading(from: Int, resume: Boolean = false) {
        if (bookId == null) return
        AppLog.i("Reader", "read from $from${if (resume) " (resume)" else ""} (voice ${narrator.voiceId}, speed ${narrator.speed}, phrasing ${narrator.phrasing})")
        if (offerVoiceForBookLanguage { startReading(from, resume) }) return
        follow = true
        readPos = from
        val file = Renders.playableAt(this, bookId!!, from)
        val atMs = heardFile?.takeIf { resume && it.first == file?.id }?.second
        heardPos = from
        heardFile = null
        saveProgress()
        // first live reading in this book: ask first, play only after a choice
        if (file == null && !prefs.getBoolean("live_tip_$bookId", false)) { showLiveTip(from); return }
        if (file != null) narrator.playRender(file, text, from, atMs) else narrator.start(text, from)
        onPlaybackChanged()
        onBuffering(true)
    }

    private fun onPlaybackChanged() {
        val playing = narrator.isPlaying
        if (playing && !narrator.isPreviewing) showBars()   // following the voice: the bars stay
        syncMedia()
        if (playing && !narrator.isPreviewing) grabAudio() else releaseAudio()
        playBtn.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        playBtn.contentDescription = if (playing) "Stop reading" else "Read aloud"
        if (!playing) { onBuffering(false); follow = true; spoken = -1 to -1; stopGliding() }
        if (playing != wasPlayingForMarks) { wasPlayingForMarks = playing; refreshPageSpans() }   // left-off underline
        updateJumpBack()
        if (playing) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            (pageView.text as? Spannable)?.let { it.removeSpan(highlight); it.removeSpan(wordMark) }
            word = -1 to -1
        }
    }

    /** A small "Preparing voice…" pill above the player, only if the wait goes on (short waits show just the ring). */
    private val showBufferPill = Runnable {
        if (busy.visibility == View.VISIBLE && narrator.isPlaying) {
            bufferPill.alpha = 0f
            bufferPill.visibility = View.VISIBLE
            bufferPill.animate().alpha(1f).setDuration(180).start()
        }
    }

    override fun onBuffering(waiting: Boolean) {
        val show = waiting && narrator.isPlaying
        busy.visibility = if (show) View.VISIBLE else View.GONE
        val msg = if (narrator.isReady) "Preparing voice…" else "Loading voice model…"
        bufferPill.removeCallbacks(showBufferPill)
        if (show) {
            bufferPill.text = msg
            // together with the player's own "Preparing voice…" label, not two seconds after it
            if (bufferPill.visibility != View.VISIBLE) bufferPill.postDelayed(showBufferPill, 250)
        } else bufferPill.visibility = View.GONE
        playBtn.contentDescription = if (show) "$msg Stop reading" else if (narrator.isPlaying) "Stop reading" else "Read aloud"
        if (show) pageLabel.text = msg
        else if (pages.isNotEmpty() && bookId != null) pageLabel.text = labelFor(page)
    }

    override fun onSentence(start: Int, end: Int) {
        onBuffering(false)
        readPos = start
        heardPos = start
        saveProgress()
        spoken = start to end
        if (mediaActive && chapterAt(start) != mediaChapter) syncMedia() else tickMediaProgress()
        // screen off / app in the background: nothing is drawn (smooth scrolls never run), so just note the
        // position and catch up in onResume
        if (!visible) return
        if (!follow) {
            // you're browsing: keep the highlight in sync if it's on this chapter, but don't move the view
            if (start in pages[page] until pageEnd(page)) highlightSpoken() else (pageView.text as? Spannable)?.removeSpan(highlight)
            updateJumpBack()
            return
        }
        if (start !in pages[page] until pageEnd(page)) showPage(pageOf(start))
        highlightSpoken()
        keepSpokenVisible()
    }

    // ---------- following the voice: the page moves only in the pauses between sentences ----------

    private var followAnim: android.animation.ValueAnimator? = null
    private val glideTarget get() = if (followAnim?.isRunning == true) 0 else -1

    /** Scroll position (px) that puts the middle of [start, end) a bit above the middle of the visible part. */
    private fun centeredOn(start: Int, end: Int): Int? {
        val layout = pageView.layout ?: return null
        if (start < 0 || start !in pages[page] until pageEnd(page)) return null
        val a = layout.getLineForOffset((start - pages[page]).coerceIn(0, pageView.text.length))
        val b = layout.getLineForOffset((maxOf(end - 1, start) - pages[page]).coerceIn(0, pageView.text.length))
        val vh = scroll.height - coverTop() - coverBottom()
        val top = layout.getLineTop(a) + pageView.paddingTop
        val bottom = layout.getLineBottom(b) + pageView.paddingTop
        // a sentence taller than the visible part: its start near the top instead of its middle
        val anchor = if (bottom - top > vh * 0.8) top + (vh * 0.15f).toInt() else (top + bottom) / 2
        val max = ((scroll.getChildAt(0)?.height ?: 0) - scroll.height).coerceAtLeast(0)
        return (anchor - coverTop() - (vh * 0.45f).toInt()).coerceIn(0, max)
    }

    /**
     * Glide the page to [target] over [ms]: the scroll position is animated, one small step a frame. The page only
     * redraws when the scroll leaves the band it last drew (see PageView), so a frame costs almost nothing. Tiny
     * moves are skipped so the page doesn't twitch; a move of more than a screen is done at once.
     */
    private fun glideTo(target: Int, ms: Long) {
        stopGliding()
        val diff = target - scroll.scrollY
        if (abs(diff) < 10 * resources.displayMetrics.density) return
        if (abs(diff) > scroll.height) { scroll.scrollTo(0, target); return }
        followAnim = android.animation.ValueAnimator.ofInt(scroll.scrollY, target).apply {
            duration = ms
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            addUpdateListener { scroll.scrollTo(0, it.animatedValue as Int) }
            start()
        }
    }

    private fun stopGliding() { followAnim?.cancel(); followAnim = null }

    /** The pause after a sentence began: bring the next sentence to the middle while nothing is being said. */
    override fun onGap(nextStart: Int, ms: Int) {
        if (!follow || !visible || !narrator.isPlaying || pages.isEmpty() || bookId == null) return
        var k = nextStart
        while (k < text.length && text[k].isWhitespace()) k++
        if (k >= text.length || k !in pages[page] until pageEnd(page)) return   // next chapter: its page opens at the top
        val (s, e) = sentenceAround(k)
        val target = centeredOn(s, e) ?: return
        // use the whole pause, within reason: long enough to be gentle, short enough to be done before the voice resumes
        glideTo(target, ms.toLong().coerceIn(220, 650))
    }

    /** The sentence is being read but isn't on screen (a jump, a sentence taller than the screen): bring it in. */
    private fun keepSpokenVisible() {
        if (!follow || !visible || !narrator.isPlaying || pages.isEmpty()) return
        if (glideTarget >= 0) return
        val layout = pageView.layout ?: return
        val at = if (word.first >= spoken.first && word.first < spoken.second) word.first else spoken.first
        if (at < 0 || at !in pages[page] until pageEnd(page)) return
        val line = layout.getLineForOffset((at - pages[page]).coerceIn(0, pageView.text.length))
        val top = layout.getLineTop(line) + pageView.paddingTop
        val bottom = layout.getLineBottom(line) + pageView.paddingTop
        val win = visibleWindow()
        if (top >= win.first && bottom <= win.last) return
        val target = centeredOn(spoken.first, spoken.second) ?: return
        glideTo(target, 320)
    }

    private fun highlightSpoken() {
        val (start, end) = spoken
        if (start < 0) return
        val pStart = pages[page]
        val sp = pageView.text as? Spannable ?: return
        val hs = (start - pStart).coerceIn(0, sp.length)
        val he = (minOf(end, pageEnd(page)) - pStart).coerceIn(hs, sp.length)
        sp.setSpan(highlight, hs, he, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        applyWordMark()
    }

    override fun onWord(start: Int, end: Int) {
        word = start to end
        applyWordMark()
        keepSpokenVisible()
    }

    /** The word being heard gets a stronger mark inside the sentence's, while following the voice on this page. */
    private fun applyWordMark() {
        if (!::pageView.isInitialized || !::narrator.isInitialized) return
        val sp = pageView.text as? Spannable ?: return
        val (a, b) = word
        val pStart = pages.getOrNull(page) ?: return
        // off while browsing, hidden, stopped, on another page, or outside the sentence lit now (the sentence mark moves
        // a moment before the voice gets there; the word mark waits for it)
        if (a < 0 || !follow || !visible || !narrator.isPlaying || a !in pStart until pageEnd(page) ||
            a < spoken.first || a >= spoken.second) { sp.removeSpan(wordMark); return }
        val ws = (a - pStart).coerceIn(0, sp.length)
        val we = (minOf(b, pageEnd(page)) - pStart).coerceIn(ws, sp.length)
        if (sp.getSpanStart(wordMark) == ws && sp.getSpanEnd(wordMark) == we) return
        sp.setSpan(wordMark, ws, we, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    /** Word marks are only asked for while the reader is on screen and the setting is on. */
    private fun syncWordHighlight() {
        narrator.wordHighlight = visible && prefs.getBoolean("word_highlight", true)
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
        Icons.leading(jumpBack, if (up) R.drawable.ic_expand_less else R.drawable.ic_expand_more, th.onAccent, sizeDp = 22f, gapDp = 6f)
    }

    private fun returnToVoice() {
        follow = true
        showBars()
        if (spoken.first < 0) return updateJumpBack()
        if (pageOf(spoken.first) != page) showPage(pageOf(spoken.first))
        highlightSpoken()
        scrollToOffset(spoken.first)
        updateJumpBack()
    }

    override fun onFinished() = onPlaybackChanged()

    override fun onReady() = updateHeader()

    override fun onError(msg: String) {
        AppLog.e("Reader", msg)
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        narrator.stop()
        onPlaybackChanged()
    }

    /** Title = book, subtitle = current chapter. */
    private fun updateHeader() {
        val id = bookId ?: return
        val ch = if (pages.isNotEmpty()) book.chapters.getOrNull(chapterAt(pages[page]))?.title else null
        topBar.setTitle(prefs.getString("title_$id", id), ch)
    }

    /** Re-voices from the sentence being read so the new speed is heard right away (quick taps make one restart). */
    /** Live reading re-renders from the sentence being heard; a pre-made file is stretched in place. */
    private val applySpeed = Runnable {
        if (narrator.isPlaying && !narrator.isPreviewing) narrator.applyToPlaying()
    }

    private fun setSpeed(v: Float) {
        if (v == narrator.speed) return
        narrator.speed = v
        prefs.edit().putFloat("speed", v).apply()
        updateSpeedChip()
        if (pages.isNotEmpty() && bookId != null && busy.visibility != View.VISIBLE) pageLabel.text = labelFor(page)
        pageView.removeCallbacks(applySpeed)
        pageView.postDelayed(applySpeed, 350)
    }

    /** Player-bar shortcut to prepared audio, showing what's happening for this book. */
    private val audioChipTick = object : Runnable {
        override fun run() { updateAudioChip(); if (RenderManager.isBusy) audioChip.postDelayed(this, 1000) }
    }

    private fun updateAudioChip() {
        if (!::audioChip.isInitialized) return
        val id = bookId
        audioChip.visibility = if (id == null) View.GONE else View.VISIBLE
        if (id == null) return
        val making = RenderManager.current?.takeIf { it.render.bookId == id }
        val here = if (pages.isNotEmpty()) Renders.playableAt(this, id, pages[page]) else null
        val (label, icon) = when {
            making != null -> "Preparing ${(making.fraction * 100).toInt()}%" to R.drawable.ic_graphic_eq
            here != null -> "Prepared" to R.drawable.ic_headphones
            else -> "Prepare" to R.drawable.ic_download
        }
        // a narrow screen shows just the icon
        val narrow = resources.configuration.screenWidthDp < 360
        audioChip.text = if (narrow) "" else label
        Icons.leading(audioChip, icon, th.accent, sizeDp = 16f, gapDp = if (narrow) 0f else 6f)
        audioChip.contentDescription = when {
            making != null -> "Preparing audio, ${(making.fraction * 100).toInt()} percent. Open prepared audio"
            here != null -> "This page plays prepared audio. Open prepared audio"
            else -> "Prepare chapters ahead of time"
        }
        audioChip.removeCallbacks(audioChipTick)
        if (making != null) audioChip.postDelayed(audioChipTick, 1000)
    }

    private var visible = false

    override fun onResume() {
        super.onResume()
        visible = true
        syncWordHighlight()
        startAfterBatteryPrompt?.let { startAfterBatteryPrompt = null; startReading(it) }
        // "Play" on an audio file
        RendersActivity.playRequest?.let { (id, pos) ->
            RendersActivity.playRequest = null
            if (id == bookId) { narrator.stop(); pickPosition(pos); startReading(pos) }
        }
        // paused from the lock screen / notification: show where the voice stopped (the page didn't follow while hidden)
        if (pausedWhileHidden && !narrator.isPlaying && heardPos >= 0 && pages.isNotEmpty()) {
            if (pageOf(heardPos) != page) showPage(pageOf(heardPos))
            scrollToOffset(heardPos, force = true)
        }
        pausedWhileHidden = false
        AppLog.i("Reader", "app visible · generation speed ${"%.2f".format(narrator.currentRtf)}x realtime cost")
        updateAudioChip()
        // back from a locked screen while reading: jump to where the voice is now
        if (narrator.isPlaying && !narrator.isPreviewing && follow && spoken.first >= 0 && pages.isNotEmpty()) {
            if (pageOf(spoken.first) != page) showPage(pageOf(spoken.first))
            highlightSpoken()
            pageView.post {
                val layout = pageView.layout ?: return@post
                val y = layout.getLineTop(layout.getLineForOffset((spoken.first - pages[page]).coerceIn(0, pageView.text.length)))
                scroll.scrollTo(0, (y + pageView.paddingTop - scroll.height / 3).coerceAtLeast(0))
            }
        }
        updateJumpBack()
    }

    private fun updateSpeedChip() {
        speedChip.text = "${"%.2f".format(java.util.Locale.US, narrator.speed).trimEnd('0').trimEnd('.')}×"
    }

    /** Speed sheet: big readout with −/+ steps, a 0.5×–2× slider and presets. Every change applies right away. */
    private fun pickSpeed() {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val ui = Ui(this, th)
        fun fmt(v: Float) = "${"%.2f".format(java.util.Locale.US, v).trimEnd('0').trimEnd('.')}×"
        fun snap(v: Float) = (Math.round(v * 20) / 20f).coerceIn(0.5f, 2.0f)
        var chosen = narrator.speed
        val readout = TextView(this).apply {
            textSize = 40f; gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD; setTextColor(th.accent)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        val slider = SeekBar(this).apply {
            max = 30                                    // (2.0 - 0.5) / 0.05
            minimumHeight = dp(40)
            progressTintList = ColorStateList.valueOf(th.accent)
            thumbTintList = ColorStateList.valueOf(th.accent)
            progressBackgroundTintList = ColorStateList.valueOf(th.dim)
            contentDescription = "Reading speed"
        }
        val presets = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false }
        fun show(v: Float, apply: Boolean) {
            chosen = snap(v)
            readout.text = fmt(chosen)
            readout.contentDescription = "Speed ${fmt(chosen)}"
            slider.progress = Math.round((chosen - 0.5f) / 0.05f)
            if (android.os.Build.VERSION.SDK_INT >= 30) slider.stateDescription = fmt(chosen)
            for (i in 0 until presets.childCount) (presets.getChildAt(i) as TextView).let { c ->
                val on = c.tag == chosen
                c.setTextColor(if (on) th.onAccent else th.text)
                c.background = android.graphics.drawable.RippleDrawable(ui.ripple(th.accent),
                    if (on) ui.rounded(th.accent, 20) else ui.rounded(null, 20, th.outline), ui.rounded(-1, 20))
                c.isSelected = on
            }
            if (apply) setSpeed(chosen)
        }
        var dragging = false
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                // while dragging only the readout moves; TalkBack's steps (no drag) apply at once
                if (fromUser) show(0.5f + p * 0.05f, apply = !dragging)
            }
            override fun onStartTrackingTouch(sb: SeekBar) { dragging = true }
            override fun onStopTrackingTouch(sb: SeekBar) { dragging = false; show(0.5f + sb.progress * 0.05f, apply = true) }
        })
        floatArrayOf(0.8f, 1.0f, 1.25f, 1.5f, 1.75f).forEachIndexed { i, v -> presets.addView(TextView(this).apply {
            // single line first: setSingleLine resets the minimum height to one line
            text = fmt(v); tag = v; textSize = 14f; gravity = Gravity.CENTER; isSingleLine = true; minHeight = dp(44)
            setOnClickListener { show(v, apply = true) }
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i < 4) marginEnd = dp(8) }) }
        val sheet = ui.sheet("Reading speed") {
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(ui.iconButton(R.drawable.ic_remove, "Slower", outlined = true) { show(chosen - 0.05f, apply = true) })
                addView(readout, LinearLayout.LayoutParams(0, -2, 1f))
                addView(ui.iconButton(R.drawable.ic_add, "Faster", outlined = true) { show(chosen + 0.05f, apply = true) })
            }, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(12), dp(4), dp(12), 0) })
            addView(slider, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(TextView(this@MainActivity).apply { text = "0.5×"; textSize = 11.5f; setTextColor(th.dim) }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(TextView(this@MainActivity).apply { text = "2×"; textSize = 11.5f; setTextColor(th.dim); gravity = Gravity.END }, LinearLayout.LayoutParams(0, -2, 1f))
            }, LinearLayout.LayoutParams(-1, -2).apply { marginStart = dp(16); marginEnd = dp(16) })
            addView(presets, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(8), dp(16), dp(8), 0) })
            addView(ui.hint(if (narrator.isPlayingFile) "Prepared audio keeps its voice and is sped up or slowed down to match."
                else "Changes apply right away, from the sentence being read.").apply {
                gravity = Gravity.CENTER; setPadding(dp(8), dp(14), dp(8), 0)
            })
        }
        show(chosen, apply = false)
        sheet.show()
    }

    /** Jump narration one sentence back / forward (starts reading if stopped). */
    private fun skipSentence(dir: Int, fromSaved: Boolean = false) {
        if (bookId == null || pages.isEmpty()) return
        val cur = when {
            fromSaved || narrator.isPlaying -> readPos
            heardPos in 0 until text.length -> heardPos   // paused: step from where the voice stopped
            readPos in visibleRange() -> readPos
            else -> topVisibleOffset()
        }
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
        val ui = Ui(this, th)
        val d = resources.displayMetrics.density
        Themes.applyChrome(this, th)
        findViewById<View>(R.id.root).background = Themes.pageBackground(this, th)
        pageView.setTextColor(th.text)
        pageView.highlightColor = th.selection
        // the player: bar color, rounded top, a hairline along its top edge (the side and bottom lines sit off-screen)
        val hair = maxOf(1, (1 * d).toInt())
        controls.background = android.graphics.drawable.InsetDrawable(GradientDrawable().apply {
            setColor(th.bar)
            val r = 22 * d
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            setStroke(hair, th.outline)
        }, -hair, 0, -hair, -hair)
        pageLabel.setTextColor(th.dim)
        for (chip in listOf(speedChip, audioChip)) {
            chip.setTextColor(th.text)
            chip.typeface = ui.medium
            chip.setPadding((12 * d).toInt(), 0, (12 * d).toInt(), 0)
            chip.background = android.graphics.drawable.RippleDrawable(ui.ripple(th.accent), ui.rounded(th.surface, 18, th.outline), ui.rounded(-1, 18))
        }
        speedChip.contentDescription = "Reading speed"
        updateAudioChip()
        for (id in intArrayOf(R.id.prev, R.id.next, R.id.back, R.id.fwd)) {
            findViewById<ImageButton>(id).apply {
                imageTintList = ColorStateList.valueOf(th.text)
                background = android.graphics.drawable.RippleDrawable(ui.ripple(th.text), null, ui.oval(-1))
            }
        }
        playBtn.background = android.graphics.drawable.RippleDrawable(ui.ripple(th.onAccent), ui.oval(th.accent), null)
        playBtn.imageTintList = ColorStateList.valueOf(th.onAccent)
        busy.indeterminateTintList = ColorStateList.valueOf(th.accent)
        jumpBack.background = android.graphics.drawable.RippleDrawable(ui.ripple(th.onAccent), ui.rounded(th.accent, 22), null)
        jumpBack.setTextColor(th.onAccent)
        jumpBack.typeface = ui.medium
        jumpBack.contentDescription = "Back to the sentence being read"
        bufferPill.background = ui.rounded(th.surface, 18, th.outline)
        bufferPill.setTextColor(th.text)
        Icons.leading(bufferPill, R.drawable.ic_hourglass, th.accent, sizeDp = 16f, gapDp = 6f)
        // spans carry their own color: rebuild them and re-render the page
        (pageView.text as? Spannable)?.let { it.removeSpan(highlight); it.removeSpan(wordMark); it.removeSpan(tapMark) }
        highlight = Mark(th.highlight, 2)
        wordMark = Mark(th.selection, 3)
        tapMark = Mark(th.selection, 1)
        if (bookId != null && pages.isNotEmpty()) {
            val y = scroll.scrollY
            pageView.setText(styledPage(page), TextView.BufferType.SPANNABLE)
            if (narrator.isPlaying && spoken.first in pages[page] until pageEnd(page)) highlightSpoken()
            scroll.post { scroll.scrollTo(0, y) }
        }
        val shownTitle = if (::topBar.isInitialized) topBar.title.text to topBar.subtitle.text else null
        buildTopBar()
        if (bookId != null) updateHeader() else shownTitle?.let { (t, sub) -> topBar.setTitle(t, sub) } ?: topBar.setTitle("bagueration2.0")
        if (bookId == null && emptyHolder.visibility == View.VISIBLE) showEmpty()
        updateJumpBack()
    }

    private fun applyTypography() {
        pageView.textSize = prefs.getInt("font_size", 19).toFloat()
        pageView.typeface = Ui.bookFont(this)
        pageView.setLineSpacing(0f, prefs.getFloat("spacing", 1.4f))
        pageView.justificationMode = if (prefs.getBoolean("justify", true))
            android.text.Layout.JUSTIFICATION_MODE_INTER_WORD else android.text.Layout.JUSTIFICATION_MODE_NONE
        layoutPage()
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
    private var searchMark = Mark(0, 1)

    /** Search this chapter or the whole book; tap a result to jump there. */
    private fun showSearch() {
        if (bookId == null) return
        val engine = search ?: BookSearch(text).also { search = it }
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val ui = Ui(this, th)
        lateinit var sheet: Ui.Sheet
        var hits: List<BookSearch.Hit> = emptyList()

        val input = android.widget.EditText(this).apply {
            setText(lastQuery); setSelection(lastQuery.length)
            hint = "Search the book"; setSingleLine(); textSize = 16f
            setTextColor(th.text); setHintTextColor(th.dim); highlightColor = th.selection
            background = null; setPadding(0, dp(8), 0, dp(8))
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
        }
        val clear = ui.iconButton(R.drawable.ic_close, "Clear", tint = th.dim) { input.setText("") }
        val field = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(52)
            setPadding(dp(16), 0, dp(4), 0)
            background = ui.rounded(th.surface, 26, th.outline)
            addView(android.widget.ImageView(this@MainActivity).apply { setImageDrawable(Icons.get(this@MainActivity, R.drawable.ic_search, th.dim, 20f)) },
                LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(12) })
            addView(input, LinearLayout.LayoutParams(0, -2, 1f))
            addView(clear)
        }
        val count = TextView(this).apply { textSize = 12.5f; setTextColor(th.dim); setPadding(dp(8), dp(10), dp(8), dp(4)) }
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
                    minimumHeight = dp(48)
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    if (searchWholeBook) book.chapters.getOrNull(chapterAt(hit.start))?.let { ch ->
                        addView(TextView(this@MainActivity).apply { text = ch.title; textSize = 11.5f; setTextColor(th.dim); maxLines = 1 })
                    }
                    addView(TextView(this@MainActivity).apply { text = sp; textSize = 14f; setTextColor(th.text); maxLines = 3 })
                }
            }
        }
        val results = android.widget.ListView(this).apply {
            this.adapter = adapter; divider = null
            selector = ui.pressable()
            overScrollMode = View.OVER_SCROLL_NEVER
            setOnItemClickListener { _, _, i, _ -> sheet.dismiss(); jumpToMatch(hits[i]) }
        }
        val run = Runnable {
            val q = input.text.toString()
            lastQuery = q
            clear.visibility = if (q.isEmpty()) View.INVISIBLE else View.VISIBLE
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
        input.setOnEditorActionListener { v, action, _ ->
            if (action != android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) return@setOnEditorActionListener false
            getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(v.windowToken, 0)
            input.removeCallbacks(run); run.run()
            true
        }
        val scope = ui.chips(listOf("This chapter" to "chapter", "Whole book" to "book"), if (searchWholeBook) "book" else "chapter") {
            searchWholeBook = it == "book"; run.run()
        }

        sheet = ui.sheet("Search", scroll = false, tall = true) {
            addView(field, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(4), 0, dp(4), 0) })
            addView(scope, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(4), dp(12), dp(4), 0) })
            addView(count)
            addView(results, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        @Suppress("DEPRECATION")
        sheet.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        sheet.show()
        input.requestFocus()
        run.run()
    }

    private fun jumpToMatch(hit: BookSearch.Hit) {
        if (narrator.isPlaying) follow = false   // keep reading; the chevron brings you back
        pickPosition(hit.start)
        val p = pageOf(hit.start)
        if (p != page) showPage(p)
        (pageView.text as? Spannable)?.let { sp ->
            sp.removeSpan(searchMark)
            searchMark = Mark(th.selection, 1)
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

    private fun saveProgress() {
        bookId?.let {
            // the chapter's name is kept for the library's "continue reading" card
            val chapter = if (pages.isNotEmpty()) book.chapters.getOrNull(chapterAt(readPos))?.title else null
            prefs.edit().putInt("pos_$it", readPos).putInt("heard_$it", heardPos).putString("chapter_$it", chapter)
                .putString("heardfile_$it", heardFile?.let { (id, ms) -> "$id:$ms" }).apply()
        }
    }

    /** Hides action bar, bottom controls and system bars. Only views change, so narration keeps going. */
    @Suppress("DEPRECATION")
    private fun setFullscreen(on: Boolean) {
        fullscreen = on
        barsHidden = on
        slideBars(on)
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let { c ->
                if (on) {
                    c.hide(android.view.WindowInsets.Type.systemBars())
                    c.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                } else c.show(android.view.WindowInsets.Type.systemBars())
            }
        } else {
            val hide = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            val v = window.decorView.systemUiVisibility
            window.decorView.systemUiVisibility = if (on) v or hide else v and hide.inv()
        }
        // say how to get out, the first time only
        if (on && !prefs.getBoolean("fullscreen_tip_seen", false)) {
            prefs.edit().putBoolean("fullscreen_tip_seen", true).apply()
            Toast.makeText(this, "Tap anywhere to show the controls", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        scroll.post { layoutPage(); updateProgressLine(); updateAudioChip() }
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
        val (pos, dur) = chapterProgress()
        lastMediaProgress = android.os.SystemClock.uptimeMillis()
        val svc = NarrationService.instance
        if (svc != null) svc.update(playing, bookTitle, chapterTitle, cover, pos, dur)
        else if (playing) startForegroundService(Intent(this, NarrationService::class.java)
            .putExtra(NarrationService.EXTRA_PLAYING, true)
            .putExtra(NarrationService.EXTRA_TITLE, bookTitle)
            .putExtra(NarrationService.EXTRA_CHAPTER, chapterTitle)
            .putExtra(NarrationService.EXTRA_COVER, cover)
            .putExtra(NarrationService.EXTRA_POSITION, pos)
            .putExtra(NarrationService.EXTRA_DURATION, dur))
    }

    private var lastMediaProgress = 0L

    /** Where the voice is in its chapter as listening time at this speed: (position, length) in ms. */
    private fun chapterProgress(): Pair<Long, Long> {
        val ci = chapterAt(readPos)
        val s = book.chapters.getOrNull(ci)?.start ?: 0
        val e = book.chapters.getOrNull(ci + 1)?.start ?: text.length
        val speed = narrator.speed; val pause = narrator.pauseMs
        return RenderManager.estimateMs((readPos - s).coerceAtLeast(0), speed, pause) to RenderManager.estimateMs((e - s).coerceAtLeast(1), speed, pause)
    }

    /** The media controls' progress bar: nudged every few seconds (the system extrapolates in between). */
    private fun tickMediaProgress() {
        if (!mediaActive) return
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastMediaProgress < 4000) return
        lastMediaProgress = now
        val (pos, dur) = chapterProgress()
        NarrationService.instance?.progress(pos, dur)
    }

    override fun remotePlay() = runOnUiThread {
        if (bookId != null && !narrator.isPlaying) {
            if (heardPos in 0 until text.length) startReading(heardPos, resume = true) else startReading(readPos)
        }
    }

    override fun remotePause() = runOnUiThread {
        if (narrator.isPlaying) {
            if (!visible) pausedWhileHidden = true
            pauseReading()
        }
    }

    /** Paused or just opened: keep preparing audio from where reading would resume. */
    private fun prefetchHere() {
        if (bookId != null && !narrator.isPlaying) narrator.prefetch(text, readPos)
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
        visible = false
        syncWordHighlight()
        AppLog.i("Reader", "app hidden (background / screen off) · generation speed ${"%.2f".format(narrator.currentRtf)}x realtime cost")
        saveProgress()
    }

    override fun onDestroy() {
        if (NarrationService.controller === this) NarrationService.controller = null
        releaseAudio()
        stopService(Intent(this, NarrationService::class.java))
        if (narrator.isRendering) narrator.stop() else narrator.release()
        super.onDestroy()
    }
}
