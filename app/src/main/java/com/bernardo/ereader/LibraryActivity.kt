package com.bernardo.ereader

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

/** Grid of imported books with covers. Returns EXTRA_OPEN = book id to the reader. */
class LibraryActivity : Activity() {

    companion object {
        const val EXTRA_OPEN = "open"
        const val EXTRA_CURRENT = "current"
        const val EXTRA_EXIT_ON_BACK = "exit_on_back"
        const val EXTRA_EXIT = "exit"
        private const val REQ_IMPORT = 1
    }

    private lateinit var grid: GridView
    private lateinit var empty: TextView
    private lateinit var importing: FrameLayout
    private var books = emptyList<BookStore.Entry>()
    private var colWidth = 0
    private val d by lazy { resources.displayMetrics.density }
    private val th by lazy { Themes.current(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Themes.applyChrome(this, th)
        title = Themes.tint("Library", th.text)
        actionBar?.setDisplayHomeAsUpEnabled(intent.getStringExtra(EXTRA_CURRENT) != null)
        actionBar?.setHomeAsUpIndicator(getDrawable(R.drawable.ic_chevron_left)?.mutate()?.apply { setTint(th.text) })

        val pad = (12 * d).toInt()
        val spacing = (14 * d).toInt()
        val screenW = resources.displayMetrics.widthPixels
        val cols = maxOf(2, (screenW / d / 130).toInt())
        colWidth = (screenW - pad * 2 - spacing * (cols - 1)) / cols

        grid = GridView(this).apply {
            numColumns = cols
            horizontalSpacing = spacing
            verticalSpacing = (18 * d).toInt()
            setPadding(pad, pad, pad, pad)
            clipToPadding = false
            stretchMode = GridView.NO_STRETCH
            columnWidth = colWidth
            setOnItemClickListener { _, _, i, _ -> open(books[i].id) }
            setOnItemLongClickListener { _, _, i, _ -> bookOptions(books[i]); true }
        }
        empty = TextView(this).apply {
            text = "Your library is empty.\n\nTap + to add a book.\nEPUB, PDF, MOBI, AZW3, FB2, DOCX, HTML or TXT"
            gravity = Gravity.CENTER
            setTextColor(th.dim)
            textSize = 16f
            setPadding(pad * 3, 0, pad * 3, 0)
        }
        // full-screen blocking card while a book imports (big PDFs can take a while)
        importing = FrameLayout(this).apply {
            visibility = View.GONE
            isClickable = true
            setBackgroundColor((th.bg and 0x00FFFFFF) or (0xCC shl 24))
            addView(LinearLayout(this@LibraryActivity).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                setPadding(pad * 3, pad * 2, pad * 3, pad * 2)
                background = GradientDrawable().apply { cornerRadius = 20 * d; setColor(th.bar) }
                addView(android.widget.ProgressBar(this@LibraryActivity).apply {
                    indeterminateTintList = android.content.res.ColorStateList.valueOf(th.accent)
                }, LinearLayout.LayoutParams((48 * d).toInt(), (48 * d).toInt()))
                addView(TextView(this@LibraryActivity).apply {
                    id = R.id.importText
                    setTextColor(th.text); textSize = 14f; gravity = Gravity.CENTER
                    setPadding(0, pad, 0, 0); maxLines = 3; ellipsize = TextUtils.TruncateAt.MIDDLE
                })
            }, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER).apply { leftMargin = pad * 2; rightMargin = pad * 2 })
        }
        // friend build only: Baguera on top of the library (the resource doesn't exist in the personal build)
        val headerRes = resources.getIdentifier("cat_header", "drawable", packageName)
        val content: View = if (headerRes == 0) grid else LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(ImageView(this@LibraryActivity).apply {
                setImageResource(headerRes)
                scaleType = ImageView.ScaleType.CENTER_CROP
                contentDescription = "Baguera"
            }, LinearLayout.LayoutParams(-1, (resources.displayMetrics.widthPixels / 2.4f).toInt().coerceAtMost((220 * d).toInt())))
            addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(th.bg)
            addView(content)
            addView(empty, FrameLayout.LayoutParams(-1, -1))
            addView(importing, FrameLayout.LayoutParams(-1, -1))
        })
        refresh()
    }

    private fun refresh() {
        books = BookStore.list(this)
        empty.visibility = if (books.isEmpty()) View.VISIBLE else View.GONE
        actionBar?.subtitle = if (books.isEmpty()) null else Themes.tint("Tap to open · hold for options", th.dim)
        grid.adapter = Adapter()
    }

    private fun open(id: String) {
        setResult(RESULT_OK, Intent().putExtra(EXTRA_OPEN, id))
        finish()
    }

    private fun bookOptions(b: BookStore.Entry) {
        Themes.dialog(this).setTitle(b.title)
            .setItems(arrayOf("Open", "Audio files", "Remove from library")) { _, which ->
                when (which) {
                    0 -> open(b.id)
                    1 -> startActivity(Intent(this, RendersActivity::class.java).putExtra(RendersActivity.EXTRA_BOOK, b.id))
                    else -> confirmDelete(b)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
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

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 2, 1, "Send debug log")
        val add = menu.add(0, 1, 0, "Add book").setIcon(R.drawable.ic_add)
        add.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS or MenuItem.SHOW_AS_ACTION_WITH_TEXT)
        add.icon?.mutate()?.setTint(th.text)
        return true
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (intent.getBooleanExtra(EXTRA_EXIT_ON_BACK, false)) setResult(RESULT_CANCELED, Intent().putExtra(EXTRA_EXIT, true))
        super.onBackPressed()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            1 -> startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"   // format is detected from the file contents (mobi/fb2/azw3 are often mislabeled)
            }, REQ_IMPORT)
            android.R.id.home -> finish()
            2 -> AppLog.share(this)
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode != REQ_IMPORT || resultCode != RESULT_OK || uri == null) return
        val name = contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "book"
        importing.findViewById<TextView>(R.id.importText).text = "Importing\n$name"
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

    private inner class Adapter : BaseAdapter() {
        private val current = intent.getStringExtra(EXTRA_CURRENT)
        override fun getCount() = books.size
        override fun getItem(i: Int) = books[i]
        override fun getItemId(i: Int) = i.toLong()

        override fun getView(i: Int, convert: View?, parent: ViewGroup): View {
            val b = books[i]
            val coverH = (colWidth * 1.5f).toInt()
            val radius = 8 * d

            val cover = FrameLayout(this@LibraryActivity).apply {
                layoutParams = LinearLayout.LayoutParams(colWidth, coverH)
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(v: View, o: Outline) = o.setRoundRect(0, 0, v.width, v.height, radius)
                }
                clipToOutline = true
                elevation = 4 * d
            }
            val bmp = b.cover?.let { BitmapFactory.decodeFile(it.absolutePath) }
            if (bmp != null) {
                cover.addView(ImageView(this@LibraryActivity).apply {
                    setImageBitmap(bmp)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                }, FrameLayout.LayoutParams(-1, -1))
            } else {
                // generated cover: muted color from the title + the title itself
                val hues = intArrayOf(0x2E4A6B, 0x3B5A4A, 0x5A3B4E, 0x4A4A6B, 0x6B523B, 0x3B5560)
                cover.background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    intArrayOf(0xFF000000.toInt() or hues[Math.floorMod(b.title.hashCode(), hues.size)], th.bar))
                cover.addView(TextView(this@LibraryActivity).apply {
                    text = b.title
                    setTextColor(th.text)
                    typeface = Typeface.SERIF
                    textSize = 15f
                    gravity = Gravity.CENTER
                    setPadding((10 * d).toInt(), 0, (10 * d).toInt(), 0)
                }, FrameLayout.LayoutParams(-1, -1))
            }
            if (b.id == current) {
                cover.addView(TextView(this@LibraryActivity).apply {
                    text = "Reading"
                    textSize = 11f
                    setTextColor(th.onAccent)
                    background = GradientDrawable().apply { setColor(th.accent); cornerRadius = 6 * d }
                    setPadding((8 * d).toInt(), (3 * d).toInt(), (8 * d).toInt(), (3 * d).toInt())
                }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
                    setMargins((6 * d).toInt(), (6 * d).toInt(), (6 * d).toInt(), 0)
                })
            }
            // thin progress bar along the bottom of the cover
            if (b.progress > 0) {
                cover.addView(View(this@LibraryActivity).apply { setBackgroundColor(th.accent) },
                    FrameLayout.LayoutParams(colWidth * b.progress / 100, (3 * d).toInt(), Gravity.BOTTOM))
            }

            return LinearLayout(this@LibraryActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(cover)
                addView(TextView(this@LibraryActivity).apply {
                    text = b.title
                    setTextColor(th.text)
                    textSize = 13f
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(0, (6 * d).toInt(), 0, 0)
                })
                addView(TextView(this@LibraryActivity).apply {
                    text = listOfNotNull(b.author, if (b.progress > 0) "${b.progress}%" else "New").joinToString("  ·  ")
                    setTextColor(th.dim)
                    // has a ready audio file: a small headphones mark at the end of the line
                    if (Renders.list(this@LibraryActivity, b.id).any { it.ready }) {
                        Icons.trailing(this, R.drawable.ic_headphones, th.dim, sizeDp = 14f, gapDp = 4f)
                        contentDescription = "$text, has an audio file"
                    }
                    textSize = 11.5f
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                })
            }
        }
    }
}
