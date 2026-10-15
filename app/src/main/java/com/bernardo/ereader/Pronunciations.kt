package com.bernardo.ereader

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.text.InputType
import android.view.Gravity
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** One pronunciation fix: every whole-word [word] is spoken as [sayAs]. */
data class Pronunciation(val word: String, val sayAs: String, val allBooks: Boolean)

/**
 * Pronunciation fixes, per book or for all books. Applied to the text right before it's spoken
 * (the page itself is unchanged), so invented names like "Lumian" can be read the way you want.
 */
object Pronunciations {

    private const val FIELD = '\u0001'
    private const val ENTRY = '\u0002'

    private fun key(bookId: String?) = if (bookId == null) "pron_all" else "pron_$bookId"

    private fun read(ctx: Context, key: String, all: Boolean) =
        BookStore.prefs(ctx).getString(key, "").orEmpty().split(ENTRY).mapNotNull { e ->
            e.split(FIELD).takeIf { it.size == 2 && it[0].isNotBlank() }?.let { Pronunciation(it[0], it[1], all) }
        }

    private fun write(ctx: Context, key: String, list: List<Pronunciation>) =
        BookStore.prefs(ctx).edit().putString(key, list.joinToString(ENTRY.toString()) { "${it.word}$FIELD${it.sayAs}" }).apply()

    /** This book's fixes first (they win), then the ones for all books. */
    fun list(ctx: Context, bookId: String?): List<Pronunciation> =
        (bookId?.let { read(ctx, key(it), false) } ?: emptyList()) + read(ctx, key(null), true)

    fun save(ctx: Context, bookId: String?, p: Pronunciation, replacing: Pronunciation? = null) {
        replacing?.let { remove(ctx, bookId, it) }
        val k = key(if (p.allBooks) null else bookId)
        val cur = read(ctx, k, p.allBooks).filterNot { it.word.equals(p.word, ignoreCase = true) }
        write(ctx, k, cur + p)
    }

    fun remove(ctx: Context, bookId: String?, p: Pronunciation) {
        val k = key(if (p.allBooks) null else bookId)
        write(ctx, k, read(ctx, k, p.allBooks).filterNot { it.word.equals(p.word, ignoreCase = true) })
    }

    /** Whole-word, case-insensitive replacements, longest words first so "Lumian's" style overlaps behave. */
    fun rules(ctx: Context, bookId: String?): List<Pair<Regex, String>> =
        list(ctx, bookId).distinctBy { it.word.lowercase() }.sortedByDescending { it.word.length }.map {
            Regex("(?i)(?<![\\p{L}\\p{N}])${Regex.escape(it.word)}(?![\\p{L}\\p{N}])") to it.sayAs
        }

    /** Push the current book's rules to the narrator and re-render what's playing. */
    fun refreshNarrator(ctx: Context, bookId: String?) {
        Narrator.instance?.let { it.pronunciations = rules(ctx, bookId); it.applyToPlaying() }
    }

    /** Add / edit dialog with a test button. */
    fun edit(act: Activity, bookId: String?, word: String, existing: Pronunciation? = null, onSaved: () -> Unit = {}) {
        val th = Themes.current(act)
        val d = act.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        fun field(hint: String, value: String) = EditText(act).apply {
            setText(value); this.hint = hint; setSingleLine(); textSize = 16f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setTextColor(th.text); setHintTextColor(th.dim)
            backgroundTintList = ColorStateList.valueOf(th.accent)
        }
        val wordField = field("Word", existing?.word ?: word.trim().trim { !it.isLetterOrDigit() })
        val sayField = field("Say it as", existing?.sayAs ?: "")
        val all = CheckBox(act).apply {
            text = "Use in all books"; setTextColor(th.text); isChecked = existing?.allBooks ?: false
            buttonTintList = ColorStateList.valueOf(th.accent)
        }
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(8), dp(22), 0)
            addView(wordField)
            addView(sayField, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            addView(TextView(act).apply {
                text = "Write it the way it sounds, e.g. Lumian → Loo-mee-an. Hyphens split syllables."
                textSize = 12f; setTextColor(th.dim); setPadding(dp(4), dp(6), dp(4), dp(4))
            })
            addView(all)
            addView(TextView(act).apply {
                text = "▶  Test"; textSize = 14f; setTextColor(th.accent); gravity = Gravity.CENTER
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setOnClickListener {
                    val say = sayField.text.toString().ifBlank { wordField.text.toString() }
                    Narrator.instance?.preview(say)
                }
            })
        }
        Themes.dialog(act).setTitle(if (existing == null) "Fix pronunciation" else "Edit pronunciation")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val w = wordField.text.toString().trim()
                val s = sayField.text.toString().trim()
                if (w.isNotEmpty() && s.isNotEmpty()) {
                    save(act, bookId, Pronunciation(w, s, all.isChecked), existing)
                    refreshNarrator(act, bookId)
                    onSaved()
                }
            }
            .setNegativeButton("Cancel", null)
            .apply { if (existing != null) setNeutralButton("Delete") { _, _ -> remove(act, bookId, existing); refreshNarrator(act, bookId); onSaved() } }
            .show()
    }
}
