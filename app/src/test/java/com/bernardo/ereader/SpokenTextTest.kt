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
    fun otherLanguages() = check(
        "pt",
        "Capítulo IV: 1914–1918, J. R. R. Tolkien" to
            "Capítulo 4: 1914 a 1918, J.R.R. Tolkien",
        "*Olá*, D. Pedro II" to
            "Olá, D. Pedro II",
    )
}
