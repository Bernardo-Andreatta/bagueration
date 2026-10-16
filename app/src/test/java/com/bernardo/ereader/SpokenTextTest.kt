package com.bernardo.ereader

import org.junit.Assert.assertEquals
import org.junit.Test

/** Same cases as tools/tts-timing/speak.py, whose rules were checked with Whisper transcripts of Kokoro. */
class SpokenTextTest {
    private fun check(lang: String, vararg cases: Pair<String, String>) {
        for ((input, expected) in cases) assertEquals(input, expected, SpokenText.normalise(input, lang))
    }

    @Test
    fun english() = check(
        "en",
        "CHAPTER IV" to
            "CHAPTER 4",
        "IV." to
            "4.",
        "IV. The Long Night" to
            "4. The Long Night",
        "V. Woolf wrote" to
            "V. Woolf wrote",
        "I am here." to
            "I am here.",
        "Part I’m sure, Part IV" to
            "Part I’m sure, Part 4",
        "The years 1914–1918 changed everything – absolutely everything." to
            "The years nineteen fourteen to nineteen eighteen changed everything – absolutely everything.",
        "In 1066 the king fell." to
            "In ten sixty-six the king fell.",
        "pp. 12–15 and 10-12 and 3-2 and 555-1234 and 2020-01-05" to
            "pages 12 to 15 and 10 to 12 and 3-2 and 555-1234 and 2020-01-05",
        "J. R. R. Tolkien met Mr Smith at 5 p.m. in the U.S. embassy." to
            "J.R.R. Tolkien met Mr Smith at 5 p.m. in the U.S. embassy.",
        "*Emphasis* was _placed_ on snake_case words." to
            "Emphasis was placed on snake_case words.",
        "Henry VIII and King John I and Henry V. Then I went. Malcolm X spoke. George I’ll be there. Elizabeth I, Charles I’s head." to
            "Henry the Eighth and King John the First and Henry the Fifth. Then I went. Malcolm X spoke. George I’ll be there. Elizabeth the First, Charles the First’s head.",
        "World War II and World War I." to
            "World War 2 and World War 1.",
        "in the 1960s and 1900s, by 2015, in 2005, 1914–18, from 1939-1945, on July 4, 1776, mid-1960s" to
            "in the nineteen sixties and nineteen hundreds, by twenty fifteen, in two thousand and five, nineteen fourteen to nineteen eighteen, from nineteen thirty-nine to nineteen forty-five, on July 4, seventeen seventy-six, mid-nineteen sixties",
        "£1500, 1,500, 1914.5, page 1234, 1999%, 1500 pounds" to
            "1500 pounds, 1,500, 1914.5, page 1234, 1999%, 1500 pounds",
        "i.e. from £1,200 to £1,242, \$3.50, £1, €2.5m, \$5 billion, £1.00, £20." to
            "i.e. from 1,200 pounds to 1,242 pounds, 3 dollars 50, 1 pound, 2.5 million euros, 5 billion dollars, 1 pound, 20 pounds.",
        "Pope Benedict XVI and Louis XIV’s court" to
            "Pope Benedict the Sixteenth and Louis the Fourteenth’s court",
        "Washington DC and MIX." to
            "Washington DC and MIX.",
        "# A heading" to
            "A heading",
    )

    @Test
    fun withoutOrdinalWords() {
        SpokenText.numberWords = null
        try {
            check("pt",
                "Capítulo IV: 1914–1918, J. R. R. Tolkien" to
                    "Capítulo 4: 1914 a 1918, J.R.R. Tolkien",
                // a small regnal number is left to the voice, a bigger one is plain digits either way
                "*Olá*, D. Pedro II e Luís XIV, 3º lugar" to
                    "Olá, D. Pedro II e Luís 14, 3º lugar",
            )
        } finally { SpokenText.numberWords = Ordinals }
    }

    @Test
    fun portuguese() = check(
        "pt",
        "D. Pedro II e Luís XIV, o Rei Sol. Dona Maria I reinou. Pedro I de Portugal. João VI. Bento XVI." to
            "D. Pedro segundo e Luís 14, o Rei Sol. Dona Maria primeira reinou. Pedro primeiro de Portugal. João sexto. Bento 16.",
        // "20° à sombra" is a temperature read as an ordinal: the degree sign is ambiguous, "20°C" and "20°." are safe
        "Ficou em 3º lugar, na 2.ª volta, no 1° andar, no século XIX. Fazia 20°C, 20°." to
            "Ficou em terceiro lugar, na segunda volta, no primeiro andar, no século 19. Fazia 20°C, 20°.",
        "Pedro I said nothing. Pedro II. O 25º aniversário." to
            "Pedro I said nothing. Pedro segundo. O vigésimo quinto aniversário.",
    )

    @Test
    fun spanish() = check(
        "es",
        "Felipe II y Luis XIV. Juan Pablo II. Isabel I de Castilla. El 1º de mayo, la 3ª vez, el siglo XX." to
            "Felipe segundo y Luis 14. Juan Pablo segundo. Isabel primera de Castilla. El primero de mayo, la tercera vez, el siglo 20.",
    )

    @Test
    fun french() = check(
        "fr",
        "Louis XIV et François Ier. Élisabeth Ire. Le XIXe siècle, la 1re fois, le 2e étage, 3ème, 21e." to
            "Louis 14 et François premier. Élisabeth première. Le dix-neuvième siècle, la première fois, le deuxième étage, troisième, vingt et unième.",
        "Le Me Dupont. Ce livre. Des choses." to
            "Le Me Dupont. Ce livre. Des choses.",
    )

    @Test
    fun italian() = check(
        "it",
        "Luigi XIV e Vittorio Emanuele II. Papa Giovanni XXIII. Il 1º maggio, la 2ª volta, nel secolo XIX." to
            "Luigi quattordicesimo e Vittorio Emanuele secondo. Papa Giovanni ventitreesimo. Il primo maggio, la seconda volta, nel secolo diciannovesimo.",
    )

    @Test
    fun german() = check(
        "de",
        "Ludwig XIV und Friedrich II von Preußen. Elisabeth I. Papst Benedikt XVI." to
            "Ludwig der Vierzehnte und Friedrich der Zweite von Preußen. Elisabeth die Erste. Papst Benedikt der Sechzehnte.",
    )
}
