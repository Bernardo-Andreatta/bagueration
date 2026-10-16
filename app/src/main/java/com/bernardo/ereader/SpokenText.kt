package com.bernardo.ereader

/**
 * Book typography the voice would misread, rewritten as it should be said: Roman numerals ("CHAPTER IV" was read
 * "chapter roman four"), years and ranges ("1914–1918" was "nineteen hundred and fourteen, nineteen hundred and
 * eighteen"), money, spaced initials ("J. R. R." came out garbled) and stray markup ("*word" was "asterisk word").
 * English gets the full treatment; other languages get the numerals, initials, ranges and markup.
 * Mirrored in tools/tts-timing/speak.py, where each rule was checked by transcribing Kokoro with Whisper.
 */
object SpokenText {

    private val STRAY_MARKUP = Regex("[*`\\\\~]+|(?<![\\p{L}\\p{N}])_+|_+(?![\\p{L}\\p{N}])|^#+\\s*")
    private val SPACED_INITIALS = Regex("\\b\\p{Lu}\\.(?: \\p{Lu}\\.)+")

    private const val ROMAN = "(?=[MDCLXVI])M{0,3}(?:CM|CD|D?C{0,3})(?:XC|XL|L?X{0,3})(?:IX|IV|V?I{0,3})"
    private const val SMALL_ROMAN = "(?=[XVI])X{0,3}(?:IX|IV|V?I{0,3})"
    /** End of a numeral: no more letters, and not "I'm" / "I'll" (that's the pronoun). */
    private const val NUMERAL_END = "(?![A-Za-z0-9])(?![’'](?:m|ll|d|ve|re)\\b)"
    private const val NUMBERED = "Chapter|Part|Book|Volume|Vol\\.|Act|Scene|Section|Appendix|Canto|Stave|Letter|Episode|" +
        "Article|Psalm|Phase|Stage|Type|Class|Level|Grade|War|" +
        "Capítulo|Parte|Livro|Libro|Tomo|Volumen|Chapitre|Partie|Livre|Tome|Acte|Scène|Kapitel|Teil|Buch|Band|Akt|" +
        "Capitolo|Atto|Scena"
    /** "Chapter IV", "CHAPTER IV", "World War II": the numeral is a plain number. */
    private val NUMBERED_ROMAN = Regex("\\b((?:$NUMBERED|${NUMBERED.uppercase()})\\s+)($ROMAN)$NUMERAL_END")
    /** A heading that is only a numeral: "IV", "IV.", "IV. The Long Night" (not "V. Woolf": one letter is an initial there). */
    private val HEADING_ROMAN = Regex("^($ROMAN)(\\.?)$|^((?=[MDCLXVI]{2})$ROMAN)(\\.)(?=\\s+[A-Z])")
    private const val REGNAL_TITLES = "King|Queen|Pope|Emperor|Empress|Tsar|Czar|Tsarina|Kaiser|Pharaoh|Sultan|Shah|" +
        "Prince|Princess|Duke|Duchess|Saint"
    private const val REGNAL_NAMES = "Henry|Edward|George|William|Richard|Charles|James|Louis|Elizabeth|Mary|Anne|John|" +
        "Philip|Peter|Pius|Leo|Gregory|Napoleon|Frederick|Ferdinand|Alexander|Nicholas|Catherine|Paul|Victor|Pedro|Carlos|Felipe|Juan"
    /**
     * "Henry VIII" -> Henry the Eighth. A one-letter numeral only after a title or a usual regnal name ("Malcolm X"
     * stays), and after a bare name only before punctuation, "of", "and" or "'s" ("Henry I said" stays).
     */
    private val REGNAL = Regex(
        "\\b(?:$REGNAL_TITLES)\\s+[A-Z][a-z]+\\s+($SMALL_ROMAN)$NUMERAL_END" +
            "|\\b(?:$REGNAL_NAMES)\\s+([XVI])\\b(?=\\s*(?:[^\\w\\s’']|$|of\\b|and\\b|[’']s\\b))" +
            "|\\b[A-Z][a-z]+\\s+((?=[XVI]{2})$SMALL_ROMAN)(?![A-Za-z0-9])"
    )

    private val RANGE = Regex("(?<![\\d.,–-])(\\d{1,4})([–-])(\\d{1,4})(?![\\d–-]|[.,]\\d)")
    private val EN_DASH_RANGE = Regex("(?<![\\d.,–-])(\\d+)–(\\d+)(?![\\d–]|[.,]\\d)")
    private val RANGE_WORD = mapOf("pt" to "a", "es" to "a", "fr" to "à", "de" to "bis")
    private val YEAR = Regex("(?<![\\d.,£\\$€¥#])(?<!\\d[–-])(\\d{4})(s|’s|'s)?(?![\\d%]|[.,]\\d|\\s+(?:pounds?|dollars?|euros?)\\b)")
    private const val MONTHS = "January|February|March|April|May|June|July|August|September|October|November|December|" +
        "Jan\\.|Feb\\.|Mar\\.|Apr\\.|Jun\\.|Jul\\.|Aug\\.|Sept?\\.|Oct\\.|Nov\\.|Dec\\."
    /** Words that make a number a year: "in 1066", "since 2015", "July 4, 1776". */
    private val YEAR_BEFORE = Regex(
        "(?:\\b(?:in|of|since|by|from|until|till|to|before|after|during|around|circa|c\\.|year|summer|winter|spring|" +
            "autumn|fall|early|late|mid|AD|A\\.D\\.)|(?:$MONTHS)(?: \\d{1,2}(?:st|nd|rd|th)?,?)?)\\s+$",
        RegexOption.IGNORE_CASE
    )
    private val YEAR_AFTER = Regex("^\\s*(?:AD|BC|BCE|CE|A\\.D\\.|B\\.C\\.)\\b")
    private val NOT_YEAR_BEFORE = Regex(
        "\\b(?:page|pages|p\\.|pp\\.|no\\.|number|room|flight|line|lines|verse|item|code|version|model|bus|route)\\s+$",
        RegexOption.IGNORE_CASE
    )

    private val CURRENCY = Regex("([£\\$€])(\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.(\\d+))?(?:\\s?(million|billion|thousand|m|bn)\\b)?")
    private val CURRENCY_NAMES = mapOf("£" to "pound", "$" to "dollar", "€" to "euro")
    private val SCALES = mapOf("m" to "million", "bn" to "billion")
    private val PAGE_ABBR = Regex("\\b(pp|p|vols?|chap|ch)\\.\\s*(?=\\d)", RegexOption.IGNORE_CASE)
    private val PAGE_WORDS = mapOf("pp" to "pages", "p" to "page", "vol" to "volume", "vols" to "volumes", "chap" to "chapter", "ch" to "chapter")

    // ---------- other languages: ordinal marks, regnal numbers ----------

    /** Spells an ordinal in a language ("3", "pt" -> "terceiro"), or null when it can't. See [Ordinals]. */
    interface NumberWords {
        fun ordinal(n: Int, lang: String, feminine: Boolean = false): String?
    }

    @Volatile var numberWords: NumberWords? = Ordinals

    /**
     * "3º", "3.ª" (pt, es, it): the mark says ordinal, ª says feminine. The degree sign is often used for º too
     * ("1° maggio"), but it's a temperature unless a word follows ("20° andar" yes, "20°C" and "fazia 20°." no).
     */
    private val ORDINAL_MARK = Regex("(?<![\\d,.])(\\d{1,4})\\s?\\.?\\s?(?:([ºª])(?![\\p{L}\\d])|(°)(?=\\s+\\p{L}))")
    /** "século XIX", "siglo XX", "secolo XIX": plain numbers in Portuguese and Spanish, ordinals in Italian. */
    private val CENTURY = Regex("\\b(séculos?|siglos?|secol[oi])\\s+($ROMAN)(?![\\p{L}\\d])", RegexOption.IGNORE_CASE)
    /** French: "1er", "1re", "2e", "3ème", "XIXe siècle". */
    private val ORDINAL_FR = Regex("(?<![\\d,.\\p{L}])(\\d{1,4}|$ROMAN)(ers?|res?|ères?|èmes?|e|es)(?![\\p{L}\\d])")

    private const val REGNAL_NAMES_ALL = "Henrique|Henri|Enrique|Enrico|Heinrich|Eduardo|Édouard|Edoardo|Eduard|Jorge|Georges|" +
        "Giorgio|Georg|Guilherme|Guillaume|Guillermo|Guglielmo|Wilhelm|Ricardo|Richard|Riccardo|Carlos|Charles|Carlo|Karl|" +
        "Luís|Luiz|Luis|Louis|Luigi|Ludwig|Isabel|Élisabeth|Elisabetta|Elisabeth|Elizabeth|Maria|Marie|Ana|Anne|Anna|João|" +
        "Juan|Jean|Giovanni|Johann|Filipe|Felipe|Philippe|Filippo|Philipp|Pedro|Pierre|Pietro|Peter|Pio|Pie|Pius|Leão|León|" +
        "Léon|Leone|Leo|Gregório|Gregorio|Grégoire|Gregor|Napoleão|Napoleón|Napoléon|Napoleone|Napoleon|Frederico|Federico|" +
        "Frédéric|Friedrich|Fernando|Ferdinand|Ferdinando|Alexandre|Alejandro|Alessandro|Alexander|Nicolau|Nicolás|Nicolas|" +
        "Nicola|Nikolaus|Catarina|Catalina|Catherine|Caterina|Katharina|Paulo|Pablo|Paul|Paolo|Vítor|Víctor|Victor|Vittorio|" +
        "Viktor|Afonso|Alfonso|Alphonse|Sebastião|Bento|Benedicto|Benoît|Benedetto|Benedikt|Francisco|François|Francesco|" +
        "Franz|Umberto|Amadeu|Otto|Otão|Manuel|Dinis|Sancho|Duarte|Jaime|Jacques|Giacomo|Jakob|Fernão|Leonor|Beatriz|" +
        "Juana|Jeanne|Giovanna|Vitória|Victoria|Cristina|Margarida|Margarita|Marguerite|Margherita|Teresa|Urraca|Amélia|Carlota"
    private val FEMININE_NAMES = ("Isabel|Élisabeth|Elisabetta|Elisabeth|Elizabeth|Maria|Marie|Ana|Anne|Anna|Catarina|Catalina|" +
        "Catherine|Caterina|Katharina|Leonor|Beatriz|Juana|Jeanne|Giovanna|Vitória|Victoria|Cristina|Margarida|Margarita|" +
        "Marguerite|Margherita|Teresa|Urraca|Amélia|Carlota").split('|').toSet()

    private class RegnalStyle(titles: String, links: String) {
        /**
         * "Rei Pedro II", "D. Pedro II", "Pedro II." / "Pedro II de Portugal": like the English rule, a one-letter
         * numeral only after a title or a usual regnal name, and after a bare name only before punctuation or a link word.
         */
        val regex = Regex(
            "\\b(?:$titles)\\s+(\\p{Lu}\\p{L}+)\\s+($SMALL_ROMAN)(?![\\p{L}\\d])" +
                "|\\b($REGNAL_NAMES_ALL)\\s+([XVI])\\b(?=\\s*(?:[^\\w\\s’']|$|(?:$links)\\b))" +
                "|\\b(\\p{Lu}\\p{L}+)\\s+((?=[XVI]{2})$SMALL_ROMAN)(?![\\p{L}\\d])"
        )
    }

    private val REGNAL_BY_LANG = mapOf(
        "pt" to RegnalStyle("Rei|Rainha|Papa|Imperador|Imperatriz|Czar|Príncipe|Princesa|Duque|Duquesa|Dom|Dona|D\\.|São|Santa", "de|e|do|da|dos|das"),
        "es" to RegnalStyle("Rey|Reina|Papa|Emperador|Emperatriz|Zar|Príncipe|Princesa|Duque|Duquesa|Don|Doña|San|Santa", "de|y|el"),
        "fr" to RegnalStyle("Roi|Reine|Pape|Empereur|Impératrice|Tsar|Prince|Princesse|Duc|Duchesse|Saint|Sainte", "de|et|le|la|du"),
        "it" to RegnalStyle("Re|Regina|Papa|Imperatore|Imperatrice|Zar|Principe|Principessa|Duca|Duchessa|San|Santa", "di|e|il"),
        "de" to RegnalStyle("König|Königin|Papst|Kaiser|Kaiserin|Zar|Prinz|Prinzessin|Herzog|Herzogin|Fürst", "von|und|der|die"),
    )

    /** Masculine names that end in -a (Italian mostly). */
    private val MASCULINE_A = setOf("Luca", "Andrea", "Nicola", "Mattia", "Elia", "Tobia", "Isaia", "Zaccaria", "Garcia", "García")

    private fun feminineName(name: String) = name in FEMININE_NAMES || (name.endsWith("a") && name !in MASCULINE_A)

    /**
     * How each language says a regnal number: Portuguese and Spanish use ordinals up to ten and plain numbers above
     * ("Pedro segundo", "Luís catorze"); French only "premier"; Italian and German always ordinals ("Ludwig der
     * Vierzehnte"). Plain numbers are left as digits: the voice reads those in its own language.
     */
    private fun regnal(lang: String, name: String, roman: String): String {
        val n = roman(roman)
        val fem = feminineName(name)
        fun words() = numberWords?.ordinal(n, lang, fem)
        val said = when (lang) {
            "pt", "es" -> if (n <= 10) words() else n.toString()
            "fr" -> if (n == 1) words() else n.toString()
            "it" -> words()
            "de" -> words()?.let { (if (fem) "die " else "der ") + it.replaceFirstChar { c -> c.uppercase() } }
            else -> null
        } ?: return "$name $roman"
        return "$name $said"
    }

    private fun otherLanguage(t: String, lang: String): String {
        var out = t
        RANGE_WORD[lang]?.let { word -> out = out.replace(EN_DASH_RANGE, "$1 $word $2") }
        val style = REGNAL_BY_LANG[lang]
        if (style != null) out = out.replace(style.regex) { m ->
            val nameGroup = listOf(1, 3, 5).first { m.groups[it] != null }
            val name = m.groups[nameGroup]!!
            val roman = m.groups[nameGroup + 1]!!
            m.value.substring(0, name.range.first - m.range.first) + regnal(lang, name.value, roman.value)
        }
        if (lang == "pt" || lang == "es" || lang == "it") {
            out = out.replace(ORDINAL_MARK) { m ->
                numberWords?.ordinal(m.groupValues[1].toInt(), lang, feminine = m.groupValues[2] == "ª") ?: m.value
            }
            out = out.replace(CENTURY) { m ->
                val n = roman(m.groupValues[2])
                m.groupValues[1] + " " + (if (lang == "it") numberWords?.ordinal(n, lang) ?: m.groupValues[2] else n.toString())
            }
        }
        if (lang == "fr") out = out.replace(ORDINAL_FR) { m ->
            val (num, suffix) = m.destructured
            val digits = num[0].isDigit()
            // a Roman numeral needs two letters, or "Ier" / "Ire": "Le", "Me", "Ce" are words
            if (!digits && num.length < 2 && !(suffix.startsWith("er") || suffix.startsWith("re"))) return@replace m.value
            val n = if (digits) num.toInt() else roman(num)
            numberWords?.ordinal(n, lang, feminine = suffix.startsWith("re") || suffix.startsWith("ère"))
                ?: if (digits) m.value else "$n$suffix"
        }
        return out
    }

    private val ONES = ("zero one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen " +
        "sixteen seventeen eighteen nineteen").split(" ")
    private val TENS = "_ _ twenty thirty forty fifty sixty seventy eighty ninety".split(" ")
    private val ORDINALS = ("_ First Second Third Fourth Fifth Sixth Seventh Eighth Ninth Tenth Eleventh Twelfth " +
        "Thirteenth Fourteenth Fifteenth Sixteenth Seventeenth Eighteenth Nineteenth").split(" ")
    private val ORDINAL_TENS = "_ _ Twentieth Thirtieth".split(" ")

    fun normalise(t: String, lang: String): String {
        var out = t.replace(STRAY_MARKUP, "")
            .replace(SPACED_INITIALS) { it.value.replace(" ", "") }
            .replace(NUMBERED_ROMAN) { it.groupValues[1] + roman(it.groupValues[2]) }
            .replace(HEADING_ROMAN) { m ->
                val g = m.groupValues
                roman(g[1].ifEmpty { g[3] }).toString() + g[2].ifEmpty { g[4] }
            }
        if (lang == "en") {
            out = out.replace(REGNAL) { m ->
                val name = m.value.substring(0, m.value.indexOfLast { it.isWhitespace() }).trimEnd()
                val n = m.groupValues.drop(1).first { it.isNotEmpty() }
                "$name the ${ordinal(roman(n))}"
            }
            out = numbersEn(out)
        } else out = otherLanguage(out, lang)
        return out
    }

    private fun numbersEn(t: String): String {
        var out = t.replace(PAGE_ABBR) { PAGE_WORDS.getValue(it.groupValues[1].lowercase()) + " " }
            .replace(CURRENCY) { money(it) }
            .replace(RANGE) { m ->
                val (a, dash, b0) = m.destructured
                val b = if (a.length == 4 && b0.length == 2 && b0.toInt() > a.toInt() % 100) a.take(2) + b0 else b0   // 1914–18
                when {
                    a.length == 4 && b.length == 4 && a.toInt() in 1000 until b.toInt() && b.toInt() <= 2099 ->
                        "${yearWords(a.toInt())} to ${yearWords(b.toInt())}"
                    dash == "-" && !(a.length == b.length && a.toInt() < b.toInt()) -> m.value   // a score, a phone number
                    else -> "$a to $b"
                }
            }
        val s = out
        out = s.replace(YEAR) { m -> if (isYear(s, m)) yearWords(m.groupValues[1].toInt(), m.groupValues[2].isNotEmpty()) else m.value }
        return out
    }

    /** 1100-1999 are years unless the context says otherwise ("page 1234"); 1000-1099 and 2010-2099 only with a cue. */
    private fun isYear(t: String, m: MatchResult): Boolean {
        val n = m.groupValues[1].toInt()
        val before = t.substring(0, m.range.first)
        if (NOT_YEAR_BEFORE.containsMatchIn(before)) return false
        if (m.groupValues[2].isNotEmpty() || YEAR_AFTER.containsMatchIn(t.substring(m.range.last + 1)) ||
            YEAR_BEFORE.containsMatchIn(before)) return n in 1000..2099
        return n in 1100..1999
    }

    /** 1914 -> nineteen fourteen, 1905 -> nineteen oh five, 1900 -> nineteen hundred; plural: 1960s -> nineteen sixties. */
    private fun yearWords(n: Int, plural: Boolean = false): String {
        val hi = n / 100
        val lo = n % 100
        val w = when {
            n in 2000..2009 -> "two thousand" + if (lo == 0) "" else " and " + ONES[lo]
            lo == 0 -> under100(hi) + " hundred"
            lo < 10 -> under100(hi) + " oh " + ONES[lo]
            else -> under100(hi) + " " + under100(lo)
        }
        return if (!plural) w else if (w.endsWith("y")) w.dropLast(1) + "ies" else w + "s"
    }

    private fun under100(n: Int): String =
        if (n < 20) ONES[n] else TENS[n / 10] + if (n % 10 == 0) "" else "-" + ONES[n % 10]

    private fun ordinal(n: Int): String = when {
        n < 20 -> ORDINALS[n]
        n % 10 == 0 -> ORDINAL_TENS[n / 10]
        else -> TENS[n / 10].replaceFirstChar { it.uppercase() } + "-" + ORDINALS[n % 10]
    }

    private fun roman(s: String): Int {
        fun v(c: Char) = when (c) { 'I' -> 1; 'V' -> 5; 'X' -> 10; 'L' -> 50; 'C' -> 100; 'D' -> 500; else -> 1000 }
        var n = 0
        for (k in s.indices) n += if (k + 1 < s.length && v(s[k]) < v(s[k + 1])) -v(s[k]) else v(s[k])
        return n
    }

    /** £1,200 -> 1,200 pounds; $3.50 -> 3 dollars 50; €2.5m -> 2.5 million euros. */
    private fun money(m: MatchResult): String {
        val (sym, whole, dec, scale) = m.destructured
        val unit = CURRENCY_NAMES.getValue(sym)
        if (scale.isNotEmpty()) return "$whole${if (dec.isNotEmpty()) ".$dec" else ""} ${SCALES[scale] ?: scale} ${unit}s"
        val units = if (whole == "1") unit else unit + "s"
        return if (dec.length == 2 && dec != "00") "$whole $units $dec" else "$whole $units"
    }
}
