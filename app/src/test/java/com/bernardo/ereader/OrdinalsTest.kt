package com.bernardo.ereader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OrdinalsTest {
    private fun check(lang: String, vararg cases: Pair<Int, String>, feminine: Boolean = false) {
        for ((n, expected) in cases) assertEquals("$lang $n", expected, Ordinals.ordinal(n, lang, feminine))
    }

    @Test
    fun portuguese() {
        check("pt", 1 to "primeiro", 3 to "terceiro", 10 to "décimo", 11 to "décimo primeiro", 19 to "décimo nono",
            20 to "vigésimo", 21 to "vigésimo primeiro", 50 to "quinquagésimo", 99 to "nonagésimo nono", 100 to "centésimo")
        check("pt", 1 to "primeira", 2 to "segunda", 21 to "vigésima primeira", feminine = true)
    }

    @Test
    fun spanish() {
        check("es", 1 to "primero", 3 to "tercero", 11 to "undécimo", 12 to "duodécimo", 13 to "decimotercero", 18 to "decimoctavo",
            20 to "vigésimo", 23 to "vigésimo tercero", 40 to "cuadragésimo", 100 to "centésimo")
        check("es", 1 to "primera", 13 to "decimotercera", 22 to "vigésima segunda", feminine = true)
    }

    @Test
    fun french() {
        check("fr", 1 to "premier", 2 to "deuxième", 3 to "troisième", 4 to "quatrième", 6 to "sixième", 5 to "cinquième", 9 to "neuvième", 11 to "onzième",
            17 to "dix-septième", 19 to "dix-neuvième", 20 to "vingtième", 21 to "vingt et unième", 22 to "vingt-deuxième",
            30 to "trentième", 70 to "soixante-dixième", 71 to "soixante et onzième", 72 to "soixante-douzième",
            80 to "quatre-vingtième", 81 to "quatre-vingt-unième", 90 to "quatre-vingt-dixième", 91 to "quatre-vingt-onzième",
            99 to "quatre-vingt-dix-neuvième", 100 to "centième")
        check("fr", 1 to "première", 2 to "deuxième", feminine = true)
    }

    @Test
    fun italian() {
        check("it", 1 to "primo", 3 to "terzo", 10 to "decimo", 11 to "undicesimo", 14 to "quattordicesimo", 20 to "ventesimo",
            21 to "ventunesimo", 23 to "ventitreesimo", 26 to "ventiseiesimo", 28 to "ventottesimo", 33 to "trentatreesimo",
            90 to "novantesimo", 100 to "centesimo")
        check("it", 1 to "prima", 2 to "seconda", 20 to "ventesima", feminine = true)
    }

    @Test
    fun german() {
        check("de", 1 to "erste", 3 to "dritte", 7 to "siebte", 12 to "zwölfte", 14 to "vierzehnte", 20 to "zwanzigste",
            21 to "einundzwanzigste", 30 to "dreißigste", 99 to "neunundneunzigste", 100 to "hundertste")
    }

    @Test
    fun outOfRange() {
        assertNull(Ordinals.ordinal(0, "pt"))
        assertNull(Ordinals.ordinal(101, "fr"))
        assertNull(Ordinals.ordinal(3, "xx"))
    }
}
