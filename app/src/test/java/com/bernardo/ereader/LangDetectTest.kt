package com.bernardo.ereader

import org.junit.Assert.assertEquals
import org.junit.Test

class LangDetectTest {
    private val samples = mapOf(
        "en" to "It was the best of times, it was the worst of times, it was the age of wisdom, and he said that she was not there with his friends.",
        "pt" to "No meio do caminho tinha uma pedra. Ele disse que não queria ir, mas ela insistiu muito e então os dois foram para a casa das tias.",
        "es" to "En un lugar de la Mancha, de cuyo nombre no quiero acordarme, no ha mucho tiempo que vivía un hidalgo de los de lanza en astillero, pero muy pobre.",
        "fr" to "Longtemps, je me suis couché de bonne heure. Parfois, à peine ma bougie éteinte, mes yeux se fermaient si vite que je n'avais pas le temps de me dire: je m'endors. Il est dans la chambre avec elle.",
        "it" to "Nel mezzo del cammin di nostra vita mi ritrovai per una selva oscura, che la diritta via era smarrita. Non è una cosa che lui sa, ma anche lei per questo.",
        "de" to "Als Gregor Samsa eines Morgens aus unruhigen Träumen erwachte, fand er sich in seinem Bett zu einem ungeheueren Ungeziefer verwandelt. Er ist nicht mit ihr auf die Reise gegangen und sie auch nicht.",
        "ru" to "Все счастливые семьи похожи друг на друга, каждая несчастливая семья несчастлива по-своему.",
        "uk" to "Як умру, то поховайте мене на могилі серед степу широкого, на Вкраїні милій. Їх було і є багато.",
        "zh" to "我们继续读吧，我觉得下一章是最好的。他说这本书非常有意思。",
    )

    @Test fun detectsFromText() {
        for ((lang, text) in samples) assertEquals("text in $lang", lang, LangDetect.detect(text.repeat(3)))
    }

    @Test fun tagUsedWhenTextIsInconclusive() {
        assertEquals("pt", LangDetect.detect("12 34 56 — 789", "pt-BR"))
        assertEquals("de", LangDetect.detect("Kapitel 1", "ger"))
    }

    @Test fun textOverridesClearlyWrongTag() {
        assertEquals("en", LangDetect.detect(samples.getValue("en").repeat(3), "pt"))
        assertEquals("pt", LangDetect.detect(samples.getValue("pt").repeat(3), "en_US"))
    }

    @Test fun tagKeptWhenTextAgrees() {
        assertEquals("pt", LangDetect.detect(samples.getValue("pt").repeat(3), "pt-BR"))
    }
}
