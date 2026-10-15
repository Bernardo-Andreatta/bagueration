package com.bernardo.ereader

import android.content.Context
import java.io.File

/**
 * Every voice the app can use. Kokoro voices are built into the APK (one model, many languages);
 * Piper voices are downloaded on demand from sherpa-onnx's GitHub releases.
 */
class VoiceInfo(
    val id: String,          // "kokoro:3" or "piper:de_DE-thorsten-medium"
    val lang: String,        // language key, see Voices.LANGUAGES
    val name: String,
    val note: String,
    val kokoroSid: Int = -1,
    val piper: String? = null,
    val downloadMb: Int = 0,
) {
    val builtIn get() = piper == null
}

object Voices {

    /** Language key -> display name (in that language). */
    val LANGUAGES = linkedMapOf(
        "en" to "English", "pt" to "Português", "es" to "Español", "fr" to "Français", "it" to "Italiano",
        "de" to "Deutsch", "nl" to "Nederlands", "pl" to "Polski", "ru" to "Русский", "uk" to "Українська",
        "cs" to "Čeština", "sv" to "Svenska", "da" to "Dansk", "no" to "Norsk", "fi" to "Suomi", "hu" to "Magyar",
        "ro" to "Română", "el" to "Ελληνικά", "tr" to "Türkçe", "ca" to "Català", "hi" to "हिन्दी", "zh" to "中文",
        "vi" to "Tiếng Việt",
    )

    /** espeak-ng language code Kokoro needs for each language it speaks. */
    val KOKORO_LANG = mapOf("en" to "en-us", "pt" to "pt-BR", "es" to "es", "fr" to "fr", "it" to "it", "hi" to "hi")

    /** espeak voice for a Kokoro voice: British pronunciation for the UK English voices (b*_), as they were trained. */
    fun espeakLang(v: VoiceInfo): String =
        if (v.lang == "en" && v.note.startsWith("UK")) "en" else KOKORO_LANG[v.lang] ?: "en-us"

    private fun k(sid: Int, lang: String, name: String, note: String) = VoiceInfo("kokoro:$sid", lang, name, note, kokoroSid = sid)
    private fun p(model: String, name: String, note: String, mb: Int = 64) =
        VoiceInfo("piper:$model", model.substring(0, 2), name, note, piper = model, downloadMb = mb)

    val all: List<VoiceInfo> = listOf(
        k(3, "en", "Heart", "US · female · most expressive"), k(0, "en", "Alloy", "US · female"), k(1, "en", "Aoede", "US · female"),
        k(2, "en", "Bella", "US · female"), k(4, "en", "Jessica", "US · female"), k(5, "en", "Kore", "US · female"),
        k(6, "en", "Nicole", "US · female · whisper"), k(7, "en", "Nova", "US · female"), k(8, "en", "River", "US · female"),
        k(9, "en", "Sarah", "US · female"), k(10, "en", "Sky", "US · female"),
        k(11, "en", "Adam", "US · male"), k(12, "en", "Echo", "US · male"), k(13, "en", "Eric", "US · male"),
        k(14, "en", "Fenrir", "US · male"), k(15, "en", "Liam", "US · male"), k(16, "en", "Michael", "US · male"),
        k(17, "en", "Onyx", "US · male"), k(18, "en", "Puck", "US · male"), k(19, "en", "Santa", "US · male"),
        k(20, "en", "Alice", "UK · female"), k(21, "en", "Emma", "UK · female"), k(22, "en", "Isabella", "UK · female"),
        k(23, "en", "Lily", "UK · female"), k(24, "en", "Daniel", "UK · male"), k(25, "en", "Fable", "UK · male"),
        k(26, "en", "George", "UK · male"), k(27, "en", "Lewis", "UK · male · most expressive"),
        k(28, "es", "Dora", "female"), k(29, "es", "Alex", "male"), k(53, "es", "Santa", "male"),
        k(30, "fr", "Siwis", "female"),
        k(35, "it", "Sara", "female"), k(36, "it", "Nicola", "male"),
        k(31, "hi", "Alpha", "female"), k(32, "hi", "Beta", "female"), k(33, "hi", "Omega", "male"), k(34, "hi", "Psi", "male"),

        // Brazilian-trained voices first: Kokoro's built-in Portuguese voices can sound European
        p("pt_BR-faber-medium", "Faber", "Brasil · male · Brazilian accent"), p("pt_BR-cadu-medium", "Cadu", "Brasil · male · Brazilian accent"),
        p("pt_BR-dii-high", "Dii", "Brasil · high quality"), p("pt_BR-jeff-medium", "Jeff", "Brasil · male"),
        p("pt_BR-miro-high", "Miro", "Brasil · high quality"),
        k(42, "pt", "Dora", "built-in · female · may sound European"), k(43, "pt", "Alex", "built-in · male · may sound European"),
        k(44, "pt", "Santa", "built-in · male · may sound European"),
        p("pt_PT-tugao-medium", "Tugão", "Portugal · male"),
        p("de_DE-thorsten-medium", "Thorsten", "male"), p("de_DE-kerstin-low", "Kerstin", "female", 63),
        p("nl_NL-pim-medium", "Pim", "Nederland · male"), p("nl_BE-nathalie-medium", "Nathalie", "België · female"),
        p("pl_PL-gosia-medium", "Gosia", "female"), p("pl_PL-darkman-medium", "Darkman", "male"),
        p("ru_RU-irina-medium", "Irina", "female"), p("ru_RU-dmitri-medium", "Dmitri", "male"),
        p("uk_UA-ukrainian_tts-medium", "Ukrainian TTS", "female", 76),
        p("cs_CZ-jirka-medium", "Jirka", "male"),
        p("sv_SE-lisa-medium", "Lisa", "female"), p("sv_SE-nst-medium", "NST", "male"),
        p("da_DK-talesyntese-medium", "Talesyntese", "male"),
        p("no_NO-talesyntese-medium", "Talesyntese", "male"),
        p("fi_FI-harri-medium", "Harri", "male"),
        p("hu_HU-anna-medium", "Anna", "female"),
        p("ro_RO-mihai-medium", "Mihai", "male"),
        p("el_GR-rapunzelina-low", "Rapunzelina", "female", 63),
        p("tr_TR-dfki-medium", "DFKI", "male"), p("tr_TR-fahrettin-medium", "Fahrettin", "male"),
        p("ca_ES-upc_ona-medium", "Ona", "female"),
        p("zh_CN-xiao_ya-medium", "Xiao Ya", "female", 57), p("zh_CN-chaowen-medium", "Chaowen", "male", 57),
        p("vi_VN-vais1000-medium", "VAIS1000", "female"),
    )

    const val DEFAULT_ID = "kokoro:3"

    /**
     * Pre-mixed blends baked into the voice file: each English voice (speaker 0-27) has a light (25%) and
     * half (50%) mix with the steadiest voice of the same accent and gender, at speakers 54 + 2*sid (+1).
     */
    private val BLEND_PARTNER = mapOf(0 to 4, 1 to 4, 2 to 4, 3 to 4, 4 to 3, 5 to 4, 6 to 4, 7 to 4, 8 to 4, 9 to 4, 10 to 4,
        11 to 13, 12 to 13, 13 to 15, 14 to 13, 15 to 13, 16 to 13, 17 to 13, 18 to 13, 19 to 13,
        20 to 23, 21 to 20, 22 to 20, 23 to 20, 24 to 25, 25 to 26, 26 to 25, 27 to 25)

    fun blendPartner(v: VoiceInfo): VoiceInfo? = BLEND_PARTNER[v.kokoroSid]?.let { byId("kokoro:$it") }
    fun blendLevel(ctx: Context, v: VoiceInfo) = if (blendPartner(v) == null) 0 else BookStore.prefs(ctx).getInt("blend_${v.id}", 0)
    fun setBlendLevel(ctx: Context, v: VoiceInfo, level: Int) = BookStore.prefs(ctx).edit().putInt("blend_${v.id}", level).apply()

    /** Speaker number to synthesize with, taking the blend setting into account. */
    fun effectiveSid(ctx: Context, v: VoiceInfo): Int {
        if (!v.builtIn) return 0
        val level = blendLevel(ctx, v)
        return if (level in 1..2) 54 + 2 * v.kokoroSid + (level - 1) else v.kokoroSid
    }

    fun byId(id: String?): VoiceInfo? = all.firstOrNull { it.id == id }

    fun dir(ctx: Context) = File(ctx.filesDir, "voices").apply { mkdirs() }
    fun piperDir(ctx: Context, v: VoiceInfo) = File(dir(ctx), v.piper!!)
    fun isInstalled(ctx: Context, v: VoiceInfo) = v.builtIn || File(piperDir(ctx, v), ".complete").exists()

    fun delete(ctx: Context, v: VoiceInfo) { if (!v.builtIn) piperDir(ctx, v).deleteRecursively() }

    /** Default voice for a language: first built-in, else first downloaded one, else null. */
    fun defaultFor(ctx: Context, lang: String): VoiceInfo? =
        all.firstOrNull { it.lang == lang && it.builtIn } ?: all.firstOrNull { it.lang == lang && isInstalled(ctx, it) }

    /**
     * Voice to use for a book: an explicit choice for that book, else the remembered voice for its
     * language, else that language's default, else the global default.
     */
    fun resolve(ctx: Context, bookId: String?, lang: String?): VoiceInfo {
        val p = BookStore.prefs(ctx)
        bookId?.let { byId(p.getString("voice_book_$it", null)) }?.takeIf { isInstalled(ctx, it) }?.let { return it }
        if (lang != null) {
            byId(p.getString("voice_lang_$lang", null))?.takeIf { isInstalled(ctx, it) }?.let { return it }
            defaultFor(ctx, lang)?.let { return it }
        }
        // migrate the old single "voice" setting (a Kokoro speaker number)
        val legacy = p.getInt("voice_v1", -1).takeIf { it >= 0 }?.let { byId("kokoro:$it") }
        return byId(p.getString("voice_global", null)) ?: legacy ?: byId(DEFAULT_ID)!!
    }

    fun remember(ctx: Context, bookId: String?, v: VoiceInfo) {
        BookStore.prefs(ctx).edit().apply {
            putString("voice_lang_${v.lang}", v.id)
            if (bookId != null) putString("voice_book_$bookId", v.id) else putString("voice_global", v.id)
        }.apply()
    }

    /** The standard preview line in each language: an exclamation, a question and a comma pause. */
    fun sample(lang: String): String = when (lang) {
        "pt" -> "Olá! Vamos continuar a leitura mais um pouco? Acho que o próximo capítulo, sem dúvida, é o melhor de todos."
        "es" -> "¡Hola! ¿Seguimos leyendo un poco más? Creo que el próximo capítulo, sin ninguna duda, es el mejor de todos."
        "fr" -> "Bonjour ! On continue la lecture encore un peu ? Je crois que le prochain chapitre, sans aucun doute, est le meilleur."
        "it" -> "Ciao! Continuiamo a leggere ancora un po'? Credo che il prossimo capitolo, senza alcun dubbio, sia il migliore."
        "de" -> "Hallo! Lesen wir noch ein bisschen weiter? Ich glaube, das nächste Kapitel ist ohne jeden Zweifel das beste."
        "nl" -> "Hallo! Lezen we verder? Ik denk dat het volgende hoofdstuk het beste is."
        "pl" -> "Cześć! Czytamy dalej? Myślę, że następny rozdział jest najlepszy."
        "ru" -> "Привет! Продолжим читать? Думаю, следующая глава — лучшая."
        "uk" -> "Привіт! Читаємо далі? Думаю, наступний розділ найкращий."
        "cs" -> "Ahoj! Budeme číst dál? Myslím, že další kapitola je nejlepší."
        "sv" -> "Hej! Ska vi läsa vidare? Jag tror att nästa kapitel är det bästa."
        "da" -> "Hej! Skal vi læse videre? Jeg tror, det næste kapitel er det bedste."
        "no" -> "Hei! Skal vi lese videre? Jeg tror neste kapittel er det beste."
        "fi" -> "Hei! Jatketaanko lukemista? Luulen, että seuraava luku on paras."
        "hu" -> "Szia! Olvassunk tovább? Szerintem a következő fejezet a legjobb."
        "ro" -> "Bună! Continuăm să citim? Cred că următorul capitol este cel mai bun."
        "el" -> "Γεια σου! Συνεχίζουμε το διάβασμα; Νομίζω ότι το επόμενο κεφάλαιο είναι το καλύτερο."
        "tr" -> "Merhaba! Okumaya devam edelim mi? Bence sonraki bölüm en iyisi."
        "ca" -> "Hola! Continuem llegint? Crec que el proper capítol és el millor."
        "hi" -> "नमस्ते! क्या हम पढ़ना जारी रखें? मुझे लगता है अगला अध्याय सबसे अच्छा है।"
        "zh" -> "你好！我们继续读吧？我觉得下一章是最好的。"
        "vi" -> "Xin chào! Chúng ta đọc tiếp nhé? Tôi nghĩ chương tiếp theo là hay nhất."
        else -> "Hello! Shall we keep reading for a while? I think the next chapter, without a doubt, is the best one."
    }
}

/** Narration settings that suit a voice. */
data class VoicePreset(val speed: Float, val pauseMs: Int, val commaPauses: Boolean, val commaPauseMs: Int, val maxGapMs: Int,
                       val blend: Int = 0) {
    fun describe() = "${"%.2f".format(java.util.Locale.US, speed).trimEnd('0').trimEnd('.')}× · " +
        "${pauseMs} ms between sentences · " + (if (commaPauses) "comma pause ${commaPauseMs} ms · " else "no comma pauses · ") +
        (if (maxGapMs > 0) "trims stops to ${maxGapMs} ms" else "no trimming") +
        (when (blend) { 1 -> " · light blend"; 2 -> " · half blend"; else -> "" })

    fun apply(ctx: Context, n: Narrator?, v: VoiceInfo) {
        Voices.setBlendLevel(ctx, v, blend)
        BookStore.prefs(ctx).edit()
            .putFloat("speed", speed).putInt("pause", pauseMs).putBoolean("comma_pauses", commaPauses)
            .putInt("comma_pause", commaPauseMs).putInt("max_gap", maxGapMs).apply()
        n?.let { it.speed = speed; it.pauseMs = pauseMs; it.commaPauses = commaPauses; it.commaPauseMs = commaPauseMs; it.maxGapMs = maxGapMs }
    }

    companion object {
        fun current(ctx: Context, v: VoiceInfo) = BookStore.prefs(ctx).let {
            VoicePreset(it.getFloat("speed", 1f), it.getInt("pause", 400), it.getBoolean("comma_pauses", true),
                it.getInt("comma_pause", 180), it.getInt("max_gap", 90), Voices.blendLevel(ctx, v))
        }
        fun parse(s: String?) = s?.split('|')?.takeIf { it.size >= 5 }?.let {
            runCatching { VoicePreset(it[0].toFloat(), it[1].toInt(), it[2].toBoolean(), it[3].toInt(), it[4].toInt(),
                it.getOrNull(5)?.toInt() ?: 0) }.getOrNull()
        }
    }
    fun serialize() = "$speed|$pauseMs|$commaPauses|$commaPauseMs|$maxGapMs|$blend"
}

/**
 * Recommended settings per voice, derived from measuring each built-in voice: its speaking rate at 1x
 * (Kokoro talks fast, so most voices get slowed to a common narration pace) and how often it hesitates
 * between words (those get tighter trimming).
 */
object Presets {
    /** Words per minute at 1x on a reference passage (measured). */
    private val RATE = mapOf(0 to 249, 1 to 260, 2 to 260, 3 to 269, 4 to 273, 5 to 250, 6 to 201, 7 to 259, 8 to 275, 9 to 261, 10 to 263, 11 to 267, 12 to 253, 13 to 281, 14 to 252, 15 to 273, 16 to 248, 17 to 257, 18 to 266, 19 to 234, 20 to 244, 21 to 277, 22 to 268, 23 to 246, 24 to 251, 25 to 224, 26 to 234, 27 to 252, 28 to 214, 29 to 212, 30 to 220, 31 to 180, 32 to 199, 33 to 182, 34 to 189, 35 to 193, 36 to 181, 42 to 215, 43 to 212, 44 to 200, 53 to 207)
    /** Voices with frequent long stops between words (3+ gaps of 200 ms on the reference passage). */
    private val HESITANT = setOf(6, 9, 10, 11, 19, 27, 31, 32, 44)
    /** Target pace on the same passage: calibrated so Lewis lands on the chosen preset. */
    const val TARGET_RATE = 227f

    fun recommended(v: VoiceInfo): VoicePreset {
        val rate = RATE[v.kokoroSid]
        val speed = if (rate == null) 1f else (Math.round(TARGET_RATE / rate * 20) / 20f).coerceIn(0.8f, 1.05f)
        val hesitant = v.kokoroSid in HESITANT
        // hesitant voices also get a light blend with their steadiest sibling (smooths the stops, keeps the character)
        return VoicePreset(speed = speed, pauseMs = 380, commaPauses = true, commaPauseMs = 150, maxGapMs = if (hesitant) 60 else 90,
            blend = if (hesitant && Voices.blendPartner(v) != null) 1 else 0)
    }

    fun custom(ctx: Context, v: VoiceInfo) = VoicePreset.parse(BookStore.prefs(ctx).getString("preset_${v.id}", null))
    fun saveCustom(ctx: Context, v: VoiceInfo, p: VoicePreset) = BookStore.prefs(ctx).edit().putString("preset_${v.id}", p.serialize()).apply()
    fun deleteCustom(ctx: Context, v: VoiceInfo) = BookStore.prefs(ctx).edit().remove("preset_${v.id}").apply()
}

/**
 * Guesses a book's language. Looks at four slices from the middle of the book (the opening is often
 * front matter in another language), scores distinctive common words plus language-specific letters,
 * and only trusts the book's own language tag when the text doesn't clearly disagree.
 */
object LangDetect {

    /** Distinctive frequent words; no 1-2 letter words shared across languages ("a", "de", "se"...). */
    private val WORDS = mapOf(
        "en" to "the and that with was his her you have this which would from they were what said".split(' '),
        "pt" to "não uma com para você também então isso muito ele ela mas pelo pela são está dos das seu sua nós quando foi".split(' '),
        "es" to "el los del pero muy usted entonces eso había tenía como cuando sus porque fue señor".split(' '),
        "fr" to "les des une est pas qui dans avec elle mais pour sur vous très était nous cette comme".split(' '),
        "it" to "che della gli sono anche questo molto alla nella perché era loro così essere".split(' '),
        "de" to "und der die das nicht ist ich sie mit ein eine auch sich auf dem den war".split(' '),
        "nl" to "het een niet van dat zijn maar ook voor hij zij wat naar geen".split(' '),
        "pl" to "się nie jest że jak ale tak już jego tylko było przez".split(' '),
        "cs" to "že jsem není jako ale jsou který byl také když".split(' '),
        "sv" to "och att det som inte jag hon för med var har till".split(' '),
        "da" to "og ikke jeg hun der til har var mig havde".split(' '),
        "no" to "og ikke jeg hun til har var meg hadde ikkje".split(' '),
        "fi" to "että hän oli mutta kun ole ovat myös tämä".split(' '),
        "hu" to "és hogy nem egy volt van meg csak mint már ezt".split(' '),
        "ro" to "și în că este sunt pentru care sau dar fost".split(' '),
        "tr" to "bir için ile değil ama çok gibi daha".split(' '),
        "ca" to "els amb però més també això aquest molt són".split(' '),
        "vi" to "của và là có không một những được người".split(' '),
    )

    /** Letters that point strongly at one language. */
    private val LETTERS = mapOf(
        "pt" to "ãõ", "es" to "ñ¿¡", "fr" to "œùû", "de" to "ß", "hu" to "őű", "ro" to "șțăş",
        "pl" to "łąęśźżń", "cs" to "řěů", "tr" to "ğı", "ca" to "ŀ", "no" to "ø", "da" to "ø", "sv" to "å",
        "vi" to "ơưđạảấầẩậắặẹẻẽếềểễệỉịọỏốồổỗộớờởỡợụủứừửữự",
    )

    /** Normalizes "pt-BR", "por", "en_US", "eng" ... to our language keys. */
    fun fromTag(tag: String?): String? {
        val t = tag?.trim()?.lowercase()?.replace('_', '-')?.substringBefore('-') ?: return null
        val three = mapOf("eng" to "en", "por" to "pt", "spa" to "es", "fre" to "fr", "fra" to "fr", "ita" to "it",
            "ger" to "de", "deu" to "de", "dut" to "nl", "nld" to "nl", "pol" to "pl", "rus" to "ru", "ukr" to "uk",
            "cze" to "cs", "ces" to "cs", "swe" to "sv", "dan" to "da", "nor" to "no", "nob" to "no", "nb" to "no",
            "nn" to "no", "fin" to "fi", "hun" to "hu", "rum" to "ro", "ron" to "ro", "gre" to "el", "ell" to "el",
            "tur" to "tr", "cat" to "ca", "hin" to "hi", "chi" to "zh", "zho" to "zh", "vie" to "vi")
        val key = three[t] ?: t
        return key.takeIf { it in Voices.LANGUAGES }
    }

    /** Four 8k-char slices from the middle of the book (or the whole text if it's short). */
    private fun sample(text: String): String {
        if (text.length < 40_000) return text
        return listOf(0.15, 0.4, 0.65, 0.9).joinToString("\n") { f ->
            val start = (text.length * f).toInt()
            text.substring(start, minOf(text.length, start + 8_000))
        }
    }

    /** Scores per language (higher = more likely). Script-based languages return a single winner. */
    fun scores(text: String): Map<String, Double> {
        val s = sample(text)
        var cyr = 0; var ukr = 0; var cjk = 0; var deva = 0; var greek = 0; var letters = 0
        val letterCounts = HashMap<Char, Int>()
        for (c in s) {
            if (!c.isLetter()) continue
            letters++
            val lc = c.lowercaseChar()
            letterCounts[lc] = (letterCounts[lc] ?: 0) + 1
            when (c) {
                in 'Ѐ'..'ӿ' -> { cyr++; if (c in "їєіґЇЄІҐ") ukr++ }
                in '一'..'鿿' -> cjk++
                in 'ऀ'..'ॿ' -> deva++
                in 'Ͱ'..'Ͽ' -> greek++
            }
        }
        if (letters > 0) {
            if (cjk > letters / 5) return mapOf("zh" to 1.0)
            if (deva > letters / 5) return mapOf("hi" to 1.0)
            if (greek > letters / 5) return mapOf("el" to 1.0)
            if (cyr > letters / 5) return mapOf((if (ukr > cyr / 50) "uk" else "ru") to 1.0)
        }
        val words = s.lowercase().split(Regex("[^\\p{L}]+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyMap()
        val counts = HashMap<String, Int>()
        for (w in words) counts[w] = (counts[w] ?: 0) + 1
        return WORDS.mapValues { (lang, list) ->
            val wordShare = list.sumOf { counts[it] ?: 0 }.toDouble() / words.size        // ~0.05-0.2 for the right language
            val letterShare = LETTERS[lang]?.sumOf { letterCounts[it] ?: 0 }?.toDouble()?.div(letters.coerceAtLeast(1)) ?: 0.0
            wordShare + letterShare * 3
        }
    }

    fun detect(text: String, metadata: String? = null): String {
        val sc = scores(text)
        val best = sc.maxByOrNull { it.value }
        val tagged = fromTag(metadata)
        if (tagged != null) {
            // trust the tag unless the text clearly says otherwise
            val taggedScore = sc[tagged] ?: if (sc.size == 1) 0.0 else best?.value ?: 0.0
            if (best == null || best.key == tagged || taggedScore >= best.value * 0.5) return tagged
        }
        return best?.takeIf { it.value > 0 }?.key ?: tagged ?: "en"
    }

    /** Debug: top scores. */
    fun explain(text: String) = scores(text).entries.sortedByDescending { it.value }.take(4)
        .joinToString { "${it.key}=${"%.3f".format(it.value)}" }
}
