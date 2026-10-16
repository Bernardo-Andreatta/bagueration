package com.bernardo.ereader

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

/**
 * The library: a "continue reading" card for the book you're in, then every book as a cover grid. Returns
 * EXTRA_OPEN = book id to the reader (with EXTRA_PLAY when it should start reading aloud right away).
 */
class LibraryActivity : Activity() {

    companion object {
        const val EXTRA_OPEN = "open"
        const val EXTRA_PLAY = "play"
        const val EXTRA_CURRENT = "current"
        const val EXTRA_EXIT_ON_BACK = "exit_on_back"
        const val EXTRA_EXIT = "exit"
        private const val REQ_IMPORT = 1
        /** More books than this and a search button shows up. */
        private const val SEARCH_FROM = 8
    }

    private lateinit var ui: Ui
    private lateinit var th: ReaderTheme
    private lateinit var topBar: Ui.TopBar
    private lateinit var scroll: ScrollView
    private lateinit var list: LinearLayout
    private lateinit var importing: FrameLayout
    private lateinit var importText: TextView
    private lateinit var fab: ImageButton
    private var searchField: EditText? = null
    private var query = ""
    private var books = emptyList<BookStore.Entry>()
    private var bars = Ui.Bars(0, 0, 0, 0)
    private val d by lazy { resources.displayMetrics.density }
    private fun dp(v: Int) = (v * d).toInt()
    private fun dp(v: Float) = (v * d).toInt()
    private val prefs by lazy { BookStore.prefs(this) }
    private val covers = HashMap<String, Bitmap?>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        th = Themes.current(this)
        ui = Ui(this, th)
        Themes.applyChrome(this, th)
        Ui.edgeToEdge(this)

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll = ScrollView(this).apply { clipToPadding = false; addView(list) }
        topBar = ui.topBar("Library",
            nav = if (intent.getStringExtra(EXTRA_CURRENT) != null) Ui.Action(R.drawable.ic_chevron_left, "Back") { finish() } else null)

        fab = ImageButton(this).apply {
            setImageDrawable(Icons.get(this@LibraryActivity, R.drawable.ic_add, th.onAccent, 26f))
            scaleType = ImageView.ScaleType.CENTER
            background = RippleDrawable(ui.ripple(th.onAccent), ui.oval(th.accent), null)
            elevation = dp(6).toFloat()
            contentDescription = "Add book"
            tooltipText = "Add book"
            setOnClickListener { addBook() }
        }
        importText = TextView(this).apply {
            setTextColor(th.text); textSize = 14f; gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, 0); maxLines = 3; ellipsize = TextUtils.TruncateAt.MIDDLE
        }
        // blocking card while a book imports (big PDFs can take a while)
        importing = FrameLayout(this).apply {
            visibility = View.GONE
            isClickable = true
            setBackgroundColor(th.scrim)
            addView(LinearLayout(this@LibraryActivity).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                setPadding(dp(36), dp(24), dp(36), dp(24))
                background = ui.surface()
                elevation = dp(8).toFloat()
                addView(android.widget.ProgressBar(this@LibraryActivity).apply {
                    indeterminateTintList = android.content.res.ColorStateList.valueOf(th.accent)
                }, LinearLayout.LayoutParams(dp(44), dp(44)))
                addView(importText)
            }, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER).apply { leftMargin = dp(24); rightMargin = dp(24) })
        }

        setContentView(FrameLayout(this).apply {
            setBackgroundColor(th.bg)
            addView(LinearLayout(this@LibraryActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(topBar.view)
                addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
            })
            addView(fab, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.BOTTOM or Gravity.END))
            addView(importing, FrameLayout.LayoutParams(-1, -1))
            Ui.onInsets(this) { b ->
                bars = b
                topBar.view.setPadding(b.left, b.top, b.right, 0)
                scroll.setPadding(b.left, 0, b.right, b.bottom + dp(88))
                (fab.layoutParams as FrameLayout.LayoutParams).apply { rightMargin = b.right + dp(20); bottomMargin = b.bottom + dp(20) }
                fab.requestLayout()
            }
        })
        refresh()
    }

    override fun onResume() {
        super.onResume()
        if (::list.isInitialized && books.isNotEmpty()) render()   // audio files may have changed
    }

    private fun refresh() {
        books = BookStore.list(this)
        covers.keys.retainAll(books.map { it.id }.toSet())
        val actions = mutableListOf<Ui.Action>()
        if (books.size > SEARCH_FROM) actions += Ui.Action(R.drawable.ic_search, "Search") { toggleSearch() }
        if (books.size > 1) actions += Ui.Action(R.drawable.ic_sort, "Sort") { pickSort() }
        actions += Ui.Action(R.drawable.ic_more, "More") {
            ui.menuSheet(null, listOf(
                Ui.Action(R.drawable.ic_settings, "Settings") { startActivity(Intent(this, SettingsActivity::class.java)) },
                Ui.Action(R.drawable.ic_bug, "Send debug log") { AppLog.share(this) },
            ))
        }
        ui.setActions(topBar.actions, actions)
        topBar.setTitle("Library", when (books.size) { 0 -> null; 1 -> "1 book"; else -> "${books.size} books" })
        render()
    }

    // ---------- sorting and search ----------

    private val sort get() = prefs.getString("library_sort", "recent") ?: "recent"

    private fun sorted(): List<BookStore.Entry> {
        val q = query.trim()
        val shown = if (q.isEmpty()) books else books.filter { b ->
            b.title.contains(q, ignoreCase = true) || b.author?.contains(q, ignoreCase = true) == true
        }
        return when (sort) {
            "title" -> shown.sortedBy { it.title.lowercase() }
            "author" -> shown.sortedWith(compareBy({ it.author?.lowercase() ?: "￿" }, { it.title.lowercase() }))
            "progress" -> shown.sortedByDescending { it.progress }
            else -> shown   // BookStore lists the most recently opened first
        }
    }

    private fun pickSort() {
        lateinit var sheet: Ui.Sheet
        sheet = ui.sheet("Sort by") {
            for ((label, value) in listOf("Recently read" to "recent", "Title" to "title", "Author" to "author", "Progress" to "progress")) {
                addView(ui.choiceRow(label, when (value) {
                    "recent" -> "The book you opened last first"
                    "title" -> "A to Z"
                    "author" -> "A to Z by author"
                    else -> "Furthest along first"
                }, sort == value) {
                    prefs.edit().putString("library_sort", value).apply()
                    sheet.dismiss()
                    render()
                })
            }
        }
        sheet.show()
    }

    /** A search field drops down under the bar and filters the covers as you type. */
    private fun toggleSearch() {
        searchField?.let { f ->
            (f.parent as? View)?.let { topBar.view.removeView(it) }
            searchField = null
            query = ""
            getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(f.windowToken, 0)
            render()
            return
        }
        val field = EditText(this).apply {
            hint = "Title or author"; setSingleLine(); textSize = 15f
            setTextColor(th.text); setHintTextColor(th.dim); highlightColor = th.selection
            background = null; setPadding(0, dp(8), 0, dp(8))
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(e: android.text.Editable?) { query = e.toString(); render() }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
            setPadding(dp(16), 0, dp(4), 0)
            background = ui.rounded(th.surface, 24, th.outline)
            addView(ImageView(this@LibraryActivity).apply { setImageDrawable(Icons.get(this@LibraryActivity, R.drawable.ic_search, th.dim, 20f)) },
                LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(12) })
            addView(field, LinearLayout.LayoutParams(0, -2, 1f))
            addView(ui.iconButton(R.drawable.ic_close, "Close search", tint = th.dim) { toggleSearch() })
        }
        topBar.view.addView(box, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(16), 0, dp(16), dp(10)) })
        searchField = field
        field.requestFocus()
        getSystemService(android.view.inputmethod.InputMethodManager::class.java).showSoftInput(field, 0)
    }

    // ---------- the screen ----------

    private fun render() {
        list.removeAllViews()
        val pad = dp(16)
        list.setPadding(pad, dp(4), pad, dp(16))
        if (books.isEmpty()) {
            list.addView(ui.emptyState(R.drawable.ic_library, "Your library is empty",
                "EPUB, PDF, MOBI, AZW3, FB2, DOCX, HTML or TXT.\nOr open a book from your file manager with this app.",
                "Add a book") { addBook() })
            headerImage()?.let { list.addView(it, 0) }
            return
        }
        headerImage()?.let { list.addView(it) }
        val current = intent.getStringExtra(EXTRA_CURRENT) ?: prefs.getString("last_book", null)
        val hero = books.firstOrNull { it.id == current }
        if (hero != null && query.isBlank()) list.addView(heroCard(hero))

        val shown = sorted()
        list.addView(ui.header(if (query.isBlank()) "All books" else "${shown.size} found").apply { setPadding(dp(4), dp(22), dp(4), dp(10)) })
        if (shown.isEmpty()) {
            list.addView(ui.hint("No book matches “${query.trim()}”.").apply { setPadding(dp(4), 0, dp(4), 0) })
            return
        }
        // cover grid: as many ~130dp columns as fit
        val avail = resources.displayMetrics.widthPixels - bars.left - bars.right - 2 * pad
        val spacing = dp(14)
        val cols = maxOf(2, ((avail + spacing) / (dp(130) + spacing)))
        val colWidth = (avail - spacing * (cols - 1)) / cols
        shown.chunked(cols).forEach { rowBooks ->
            list.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                rowBooks.forEachIndexed { i, b ->
                    addView(bookTile(b, colWidth, b.id == current), LinearLayout.LayoutParams(colWidth, -2).apply {
                        if (i < cols - 1) marginEnd = spacing
                    })
                }
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(20) })
        }
    }

    /** Friend build only: Baguera on top of the library (the resource doesn't exist in the personal build). */
    private fun headerImage(): View? {
        val res = resources.getIdentifier("cat_header", "drawable", packageName)
        if (res == 0) return null
        return ImageView(this).apply {
            setImageResource(res)
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = "Baguera"
            outlineProvider = roundOutline(dp(20).toFloat())
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(-1, (resources.displayMetrics.widthPixels / 2.4f).toInt().coerceAtMost(dp(220)))
                .apply { topMargin = dp(8) }
        }
    }

    private fun roundOutline(r: Float) = object : ViewOutlineProvider() {
        override fun getOutline(v: View, o: Outline) = o.setRoundRect(0, 0, v.width, v.height, r)
    }

    /** "Continue reading": the book you're in, with Resume (reads aloud from where you left off) and Open. */
    private fun heroCard(b: BookStore.Entry) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = ui.surface()
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
        addView(cover(b, dp(96), dp(144), badge = false))
        addView(LinearLayout(this@LibraryActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(ui.header("Continue reading"))
            addView(TextView(this@LibraryActivity).apply {
                text = b.title; textSize = 17f; typeface = ui.medium; setTextColor(th.text)
                maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(6), 0, 0)
            })
            b.author?.let { a -> addView(TextView(this@LibraryActivity).apply {
                text = a; textSize = 13f; setTextColor(th.dim); maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(2), 0, 0)
            }) }
            val chapter = prefs.getString("chapter_${b.id}", null)
            addView(TextView(this@LibraryActivity).apply {
                text = listOfNotNull(chapter, "${b.progress}%").joinToString("  ·  ")
                textSize = 12.5f; setTextColor(th.dim); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(10), 0, dp(6))
            })
            addView(ui.progressBar(th.accent, 4).apply { progress = b.progress * 10 }, LinearLayout.LayoutParams(-1, dp(4)))
            addView(View(this@LibraryActivity), LinearLayout.LayoutParams(1, 0, 1f))
            addView(LinearLayout(this@LibraryActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(ui.pill("Resume", R.drawable.ic_play, filled = true) { open(b.id, play = true) }.apply {
                    contentDescription = "Resume reading aloud"
                }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
                addView(ui.pill("Open", null, filled = false) { open(b.id) })
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12) })
        }, LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = dp(16) })
    }

    private fun bookTile(b: BookStore.Entry, w: Int, reading: Boolean) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = ui.pressable(radiusDp = 12)
        setPadding(0, 0, 0, dp(4))
        addView(cover(b, w, (w * 1.5f).toInt(), badge = reading))
        addView(TextView(this@LibraryActivity).apply {
            text = b.title; setTextColor(th.text); textSize = 13f
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(2), dp(8), dp(2), 0)
        })
        addView(TextView(this@LibraryActivity).apply {
            text = listOfNotNull(b.author, if (b.progress > 0) "${b.progress}%" else "New").joinToString("  ·  ")
            setTextColor(th.dim); textSize = 11.5f
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(2), dp(2), dp(2), 0)
        })
        val audio = Renders.list(this@LibraryActivity, b.id).any { it.ready }
        contentDescription = listOfNotNull(b.title, b.author, if (b.progress > 0) "${b.progress} percent read" else "not started",
            if (reading) "reading now" else null, if (audio) "has an audio file" else null).joinToString(", ")
        setOnClickListener { open(b.id) }
        setOnLongClickListener { bookOptions(b); true }
    }

    /** A cover (the book's own, or one made from its title), rounded, with its marks: reading badge, audio, progress. */
    private fun cover(b: BookStore.Entry, w: Int, h: Int, badge: Boolean): View {
        val radius = dp(10).toFloat()
        val box = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(w, h)
            outlineProvider = roundOutline(radius)
            clipToOutline = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        val bmp = covers.getOrPut(b.id) { b.cover?.let { BitmapFactory.decodeFile(it.absolutePath) } }
        if (bmp != null) {
            box.addView(ImageView(this).apply { setImageBitmap(bmp); scaleType = ImageView.ScaleType.CENTER_CROP }, FrameLayout.LayoutParams(-1, -1))
        } else {
            // generated cover: a muted warm color from the title, and the title itself
            val hues = intArrayOf(0x8A5A3B, 0x5A6B4A, 0x6B4A5A, 0x4A5A6B, 0x7A6A3B, 0x4A6A66)
            val base = 0xFF000000.toInt() or hues[Math.floorMod(b.title.hashCode(), hues.size)]
            box.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Themes.mix(base, th.bg, 0.15f), Themes.mix(base, th.bg, 0.55f)))
            box.addView(TextView(this).apply {
                text = b.title
                setTextColor(0xFFF6EEE2.toInt())
                typeface = Ui.literata(this@LibraryActivity)
                textSize = if (w < dp(110)) 13f else 15f
                gravity = Gravity.CENTER
                maxLines = 5; ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(10), 0, dp(10), 0)
            }, FrameLayout.LayoutParams(-1, -1))
        }
        if (badge) box.addView(TextView(this).apply {
            text = "Reading"; textSize = 11f; typeface = ui.medium
            setTextColor(th.onAccent)
            background = ui.rounded(th.accent, 8)
            setPadding(dp(8), dp(3), dp(8), dp(3))
        }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(dp(6), dp(6), dp(6), 0) })
        if (Renders.list(this, b.id).any { it.ready }) box.addView(ImageView(this).apply {
            setImageDrawable(Icons.get(this@LibraryActivity, R.drawable.ic_headphones, th.accent, 13f))
            scaleType = ImageView.ScaleType.CENTER
            background = ui.oval(th.surface)
        }, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(6), 0, 0, dp(8)) })
        if (b.progress > 0) box.addView(View(this).apply { setBackgroundColor(th.accent) },
            FrameLayout.LayoutParams(w * b.progress / 100, dp(3), Gravity.BOTTOM))
        // light themes: a soft shadow; dark ones: a hairline (shadows don't show on dark)
        if (th.light) box.elevation = dp(2).toFloat()
        else box.foreground = ui.rounded(null, 10, th.outline)
        return box
    }

    private fun open(id: String, play: Boolean = false) {
        setResult(RESULT_OK, Intent().putExtra(EXTRA_OPEN, id).putExtra(EXTRA_PLAY, play))
        finish()
    }

    private fun bookOptions(b: BookStore.Entry) {
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(14), dp(12))
            addView(cover(b, dp(48), dp(72), badge = false))
            addView(LinearLayout(this@LibraryActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@LibraryActivity).apply {
                    text = b.title; textSize = 16f; typeface = ui.medium; setTextColor(th.text); maxLines = 2; ellipsize = TextUtils.TruncateAt.END
                })
                b.author?.let { a -> addView(TextView(this@LibraryActivity).apply { text = a; textSize = 13f; setTextColor(th.dim); maxLines = 1 }) }
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(14) })
        }
        ui.menuSheet(null, listOf(
            Ui.Action(R.drawable.ic_library, "Open") { open(b.id) },
            Ui.Action(R.drawable.ic_play, "Resume reading aloud") { open(b.id, play = true) },
            Ui.Action(R.drawable.ic_headphones, "Audio files") {
                startActivity(Intent(this, RendersActivity::class.java).putExtra(RendersActivity.EXTRA_BOOK, b.id))
            },
            Ui.Action(R.drawable.ic_delete, "Remove from library") { confirmDelete(b) },
        ), header = head)
    }

    private fun confirmDelete(b: BookStore.Entry) {
        Themes.dialog(this)
            .setTitle("Remove book?")
            .setMessage("\"${b.title}\" and its reading progress will be removed from the library.")
            .setPositiveButton("Remove") { _, _ ->
                BookStore.delete(this, b.id)
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addBook() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"   // format is detected from the file contents (mobi/fb2/azw3 are often mislabeled)
        }, REQ_IMPORT)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (searchField != null) return toggleSearch()
        if (intent.getBooleanExtra(EXTRA_EXIT_ON_BACK, false)) setResult(RESULT_CANCELED, Intent().putExtra(EXTRA_EXIT, true))
        super.onBackPressed()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode != REQ_IMPORT || resultCode != RESULT_OK || uri == null) return
        val name = contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "book"
        importText.text = "Importing\n$name"
        importing.visibility = View.VISIBLE
        thread {
            try {
                AppLog.i("Import", "importing $name")
                val id = BookStore.import(this, uri)
                AppLog.i("Import", "imported $name as $id")
                runOnUiThread { open(id) }
            } catch (t: Throwable) {
                runOnUiThread {
                    importing.visibility = View.GONE
                    AppLog.e("Import", "import of $name failed", t)
                    Toast.makeText(this, "Could not import: ${t.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
