package com.bernardo.ereader

/**
 * Ordinal numbers in words, 1-100, for the languages the voices speak: "terceiro", "vigésima primeira",
 * "dix-neuvième", "ventitreesimo", "einundzwanzigste". The voices read plain digits fine in their own language
 * but have no idea what "3º", "1re" or "XIXe" mean, so those are spelled out here. (Android's ICU has spell-out
 * rules for this, but they're not part of the public SDK.)
 */
object Ordinals : SpokenText.NumberWords {

    override fun ordinal(n: Int, lang: String, feminine: Boolean): String? {
        if (n !in 1..100) return null
        return when (lang) {
            "pt" -> pt(n, feminine)
            "es" -> es(n, feminine)
            "fr" -> fr(n, feminine)
            "it" -> it(n, feminine)
            "de" -> de(n)
            else -> null
        }
    }

    // ---------- Portuguese / Spanish: "vigésimo primeiro", each word takes the gender ----------

    private val PT_UNITS = "primeiro segundo terceiro quarto quinto sexto sétimo oitavo nono décimo".split(' ')
    private val PT_TENS = "_ décimo vigésimo trigésimo quadragésimo quinquagésimo sexagésimo septuagésimo octogésimo nonagésimo".split(' ')
    private val ES_UNITS = "primero segundo tercero cuarto quinto sexto séptimo octavo noveno décimo".split(' ')
    private val ES_TEENS = "undécimo duodécimo decimotercero decimocuarto decimoquinto decimosexto decimoséptimo decimoctavo decimonoveno".split(' ')
    private val ES_TENS = "_ décimo vigésimo trigésimo cuadragésimo quincuagésimo sexagésimo septuagésimo octogésimo nonagésimo".split(' ')

    private fun gender(words: String, feminine: Boolean) =
        if (!feminine) words else words.split(' ').joinToString(" ") { if (it.endsWith("o")) it.dropLast(1) + "a" else it }

    private fun pt(n: Int, fem: Boolean): String = gender(when {
        n == 100 -> "centésimo"
        n <= 10 -> PT_UNITS[n - 1]
        n % 10 == 0 -> PT_TENS[n / 10]
        else -> PT_TENS[n / 10] + " " + PT_UNITS[n % 10 - 1]
    }, fem)

    private fun es(n: Int, fem: Boolean): String = gender(when {
        n == 100 -> "centésimo"
        n <= 10 -> ES_UNITS[n - 1]
        n < 20 -> ES_TEENS[n - 11]
        n % 10 == 0 -> ES_TENS[n / 10]
        else -> ES_TENS[n / 10] + " " + ES_UNITS[n % 10 - 1]
    }, fem)

    // ---------- French: the cardinal, with its last word turned into an -ième ----------

    private val FR_CARDINALS = ("zéro un deux trois quatre cinq six sept huit neuf dix onze douze treize quatorze quinze seize " +
        "dix-sept dix-huit dix-neuf").split(' ')
    private val FR_TENS = "_ dix vingt trente quarante cinquante soixante soixante quatre-vingt quatre-vingt".split(' ')

    private fun frCardinal(n: Int): String {
        if (n < 20) return FR_CARDINALS[n]
        if (n == 100) return "cent"
        val t = n / 10; val u = n % 10
        val base = FR_TENS[t]
        // 70-79 and 90-99 count on from sixty and eighty: "soixante-douze", "quatre-vingt-onze"
        val rest = if (t == 7 || t == 9) 10 + u else u
        return when {
            rest == 0 -> if (t == 8) "quatre-vingts" else base
            rest == 1 && t < 8 -> "$base et ${FR_CARDINALS[1]}"
            rest == 11 && t == 7 -> "$base et onze"
            else -> "$base-${FR_CARDINALS[rest]}"
        }
    }

    private fun fr(n: Int, fem: Boolean): String {
        if (n == 1) return if (fem) "première" else "premier"
        val card = if (n == 80) "quatre-vingt" else frCardinal(n)   // quatre-vingts loses its s
        val cut = maxOf(card.lastIndexOf('-'), card.lastIndexOf(' ')) + 1
        val last = card.substring(cut)
        val stem = when {
            last == "cinq" -> "cinqu"
            last == "neuf" -> "neuv"
            last.endsWith("e") -> last.dropLast(1)
            else -> last
        }
        return card.substring(0, cut) + stem + "ième"
    }

    // ---------- Italian: 1-10 have their own words, above that the cardinal minus its vowel + -esimo ----------

    private val IT_UNITS = "primo secondo terzo quarto quinto sesto settimo ottavo nono decimo".split(' ')
    private val IT_CARDINALS = ("_ uno due tre quattro cinque sei sette otto nove dieci undici dodici tredici quattordici quindici " +
        "sedici diciassette diciotto diciannove").split(' ')
    private val IT_TENS = "_ dieci venti trenta quaranta cinquanta sessanta settanta ottanta novanta".split(' ')

    private fun itCardinal(n: Int): String {
        if (n < 20) return IT_CARDINALS[n]
        if (n == 100) return "cento"
        val t = n / 10; val u = n % 10
        val tens = IT_TENS[t]
        return when (u) {
            0 -> tens
            1, 8 -> tens.dropLast(1) + IT_CARDINALS[u]   // ventuno, ventotto
            else -> tens + IT_CARDINALS[u]
        }
    }

    private fun it(n: Int, fem: Boolean): String {
        val w = if (n <= 10) IT_UNITS[n - 1] else {
            val c = itCardinal(n)
            (if (c.endsWith("tre") || c.endsWith("sei")) c else c.dropLast(1)) + "esimo"   // ventitreesimo, ventiseiesimo
        }
        return if (fem) w.dropLast(1) + "a" else w
    }

    // ---------- German: -te up to nineteen, -ste from twenty; nominative ("der Vierzehnte") ----------

    private val DE_SMALL = ("_ erste zweite dritte vierte fünfte sechste siebte achte neunte zehnte elfte zwölfte dreizehnte " +
        "vierzehnte fünfzehnte sechzehnte siebzehnte achtzehnte neunzehnte").split(' ')
    private val DE_UNITS = "_ ein zwei drei vier fünf sechs sieben acht neun".split(' ')
    private val DE_TENS = "_ _ zwanzig dreißig vierzig fünfzig sechzig siebzig achtzig neunzig".split(' ')

    private fun de(n: Int): String = when {
        n < 20 -> DE_SMALL[n]
        n == 100 -> "hundertste"
        n % 10 == 0 -> DE_TENS[n / 10] + "ste"
        else -> DE_UNITS[n % 10] + "und" + DE_TENS[n / 10] + "ste"
    }
}
