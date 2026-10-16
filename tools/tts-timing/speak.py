"""Python mirror of Narrator.kt's text normalisation (`speakable` and helpers). Keep in step with the Kotlin."""
import re

SPACED_ELLIPSIS = re.compile(r"\.( \.){2,}")
FOOTNOTE_MARK = re.compile(r"(?<=[^\W\d_])([\"'”’»)]?)(\[\d{1,3}]|[¹²³⁴⁵⁶⁷⁸⁹⁰]+|\*+)(?=[\s\W]|$)")
DOUBLE_HYPHEN = re.compile(r"\s*--+\s*")
STRAY_MARKUP = re.compile(r"[*`\\~]+|(?<![^\W_])_+|_+(?![^\W_])|^#+\s*")
SPACED_INITIALS = re.compile(r"\b[A-Z]\.(?: [A-Z]\.)+")

ROMAN = r"(?=[MDCLXVI])M{0,3}(?:CM|CD|D?C{0,3})(?:XC|XL|L?X{0,3})(?:IX|IV|V?I{0,3})"
SMALL_ROMAN = r"(?=[XVI])X{0,3}(?:IX|IV|V?I{0,3})"
NUMBERED = ("Chapter|Part|Book|Volume|Vol\\.|Act|Scene|Section|Appendix|Canto|Stave|Letter|Episode|Article|Psalm|"
            "Phase|Stage|Type|Class|Level|Grade|War|"
            "Capítulo|Parte|Livro|Libro|Tomo|Volumen|Chapitre|Partie|Livre|Tome|Acte|Scène|Kapitel|Teil|Buch|Band|Akt|"
            "Capitolo|Atto|Scena")
# "Chapter IV", "CHAPTER IV", "World War II": the numeral is a plain number
CONTRACTION = r"(?![A-Za-z0-9])(?![’'](?:m|ll|d|ve|re)\b)"     # "Part I'm..." / "George I'll..." are the pronoun
NUMBERED_ROMAN = re.compile(rf"\b((?:{NUMBERED}|{NUMBERED.upper()})\s+)({ROMAN}){CONTRACTION}")
# a heading that is only a numeral: "IV", "IV.", "IV. The Long Night" (not "V. Woolf": one letter is an initial there)
HEADING_ROMAN = re.compile(rf"^({ROMAN})(\.?)$|^((?=[MDCLXVI]{{2}}){ROMAN})(\.)(?=\s+[A-Z])")
REGNAL_TITLES = "King|Queen|Pope|Emperor|Empress|Tsar|Czar|Tsarina|Kaiser|Pharaoh|Sultan|Shah|Prince|Princess|Duke|Duchess|Saint"
REGNAL_NAMES = ("Henry|Edward|George|William|Richard|Charles|James|Louis|Elizabeth|Mary|Anne|John|Philip|Peter|Pius|Leo|"
                "Gregory|Napoleon|Frederick|Ferdinand|Alexander|Nicholas|Catherine|Paul|Victor|Pedro|Carlos|Felipe|Juan")
# "Henry VIII" -> Henry the Eighth; one-letter numerals only after a title or a usual regnal name ("Malcolm X" stays)
# ("Henry I said" stays: after a bare name a one-letter numeral must be followed by punctuation, "of", "and" or "'s")
REGNAL = re.compile(rf"\b(?:{REGNAL_TITLES})\s+[A-Z][a-z]+\s+({SMALL_ROMAN}){CONTRACTION}"
                    rf"|\b(?:{REGNAL_NAMES})\s+([XVI])\b(?=\s*(?:[^\w\s’']|$|of\b|and\b|[’']s\b))"
                    rf"|\b[A-Z][a-z]+\s+((?=[XVI]{{2}}){SMALL_ROMAN})(?![A-Za-z0-9])")

RANGE = re.compile(r"(?<![\d.,–-])(\d{1,4})([–-])(\d{1,4})(?![\d–-]|[.,]\d)")
RANGE_WORD = {"en": "to", "pt": "a", "es": "a", "fr": "à", "de": "bis"}
YEAR = re.compile(r"(?<![\d.,£$€¥#])(?<!\d[–-])(\d{4})(s|’s|'s)?(?![\d%]|[.,]\d|\s+(?:pounds?|dollars?|euros?)\b)")
MONTHS = "January|February|March|April|May|June|July|August|September|October|November|December|Jan\\.|Feb\\.|Mar\\.|Apr\\.|Jun\\.|Jul\\.|Aug\\.|Sept?\\.|Oct\\.|Nov\\.|Dec\\."
YEAR_BEFORE = re.compile(rf"(?:\b(?:in|of|since|by|from|until|till|to|before|after|during|around|circa|c\.|year|summer|winter|spring|autumn|fall|early|late|mid|AD|A\.D\.)|(?:{MONTHS})(?: \d{{1,2}}(?:st|nd|rd|th)?,?)?)\s+$", re.I)
YEAR_AFTER = re.compile(r"^\s*(?:AD|BC|BCE|CE|A\.D\.|B\.C\.)\b")
NOT_YEAR_BEFORE = re.compile(r"\b(?:page|pages|p\.|pp\.|no\.|number|room|flight|line|lines|verse|item|code|version|model|bus|route)\s+$", re.I)

CURRENCY = re.compile(r"([£$€])(\d{1,3}(?:,\d{3})+|\d+)(?:\.(\d+))?(?:\s?(million|billion|thousand|m|bn)\b)?")
CURRENCY_NAMES = {"£": "pound", "$": "dollar", "€": "euro"}
SCALES = {"m": "million", "bn": "billion"}
PAGE_ABBR = re.compile(r"\b(pp|p|vols?|chap|ch)\.\s*(?=\d)", re.I)
PAGE_WORDS = {"pp": "pages", "p": "page", "vol": "volume", "vols": "volumes", "chap": "chapter", "ch": "chapter"}

ONES = "zero one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen sixteen seventeen eighteen nineteen".split()
TENS = "_ _ twenty thirty forty fifty sixty seventy eighty ninety".split()
ORDINALS = ("_ First Second Third Fourth Fifth Sixth Seventh Eighth Ninth Tenth Eleventh Twelfth Thirteenth Fourteenth "
            "Fifteenth Sixteenth Seventeenth Eighteenth Nineteenth").split()
ORDINAL_TENS = "_ _ Twentieth Thirtieth".split()

def roman(s):
    v = {"I": 1, "V": 5, "X": 10, "L": 50, "C": 100, "D": 500, "M": 1000}; n = 0
    for k, c in enumerate(s):
        n += -v[c] if k + 1 < len(s) and v[c] < v[s[k + 1]] else v[c]
    return n

def ordinal(n):
    if n < 20: return ORDINALS[n]
    t, o = divmod(n, 10)
    return ORDINAL_TENS[t] if o == 0 else f"{TENS[t].capitalize()}-{ORDINALS[o]}"

def under100(n):
    if n < 20: return ONES[n]
    t, o = divmod(n, 10)
    return TENS[t] + ("" if o == 0 else "-" + ONES[o])

def year_words(n, plural=False):
    """1914 -> nineteen fourteen, 1905 -> nineteen oh five, 1900 -> nineteen hundred; 1960s -> nineteen sixties"""
    hi, lo = divmod(n, 100)
    if 2000 <= n < 2010: w = "two thousand" + ("" if lo == 0 else " and " + ONES[lo])
    elif lo == 0: w = under100(hi) + " hundred"
    elif lo < 10: w = under100(hi) + " oh " + ONES[lo]
    else: w = under100(hi) + " " + under100(lo)
    if plural:
        w = w[:-1] + "ies" if w.endswith("y") else w + "s"
    return w

def is_year(t, m):
    n = int(m.group(1)); before = t[:m.start()]
    if NOT_YEAR_BEFORE.search(before): return False
    if m.group(2) or YEAR_AFTER.match(t[m.end():]) or YEAR_BEFORE.search(before): return 1000 <= n <= 2099
    return 1100 <= n <= 1999

def money(m):
    """£1,200 -> 1,200 pounds; $3.50 -> 3 dollars 50; €2.5m -> 2.5 million euros"""
    sym, whole, dec, scale = m.groups(); unit = CURRENCY_NAMES[sym]
    if scale: return f"{whole}{'.' + dec if dec else ''} {SCALES.get(scale, scale)} {unit}s"
    unit = unit if whole == "1" else unit + "s"
    return f"{whole} {unit} {dec}" if dec and len(dec) == 2 and dec != "00" else f"{whole} {unit}"

def numbers_en(t):
    t = PAGE_ABBR.sub(lambda m: PAGE_WORDS[m.group(1).lower()] + " ", t)
    t = CURRENCY.sub(money, t)
    def rng(m):
        a, dash, b = m.group(1), m.group(2), m.group(3)
        if len(a) == 4 and len(b) == 2 and int(b) > int(a) % 100: b = a[:2] + b   # 1914–18
        if len(a) == 4 and len(b) == 4 and 1000 <= int(a) < int(b) <= 2099:
            return f"{year_words(int(a))} to {year_words(int(b))}"
        if dash == "-" and not (len(a) == len(b) and int(a) < int(b)): return m.group()
        return f"{a} to {b}"
    t = RANGE.sub(rng, t)
    return YEAR.sub(lambda m: year_words(int(m.group(1)), bool(m.group(2))) if is_year(t, m) else m.group(), t)

def normalise(t, lang):
    t = STRAY_MARKUP.sub("", t)
    t = SPACED_INITIALS.sub(lambda m: m.group().replace(" ", ""), t)
    t = NUMBERED_ROMAN.sub(lambda m: m.group(1) + str(roman(m.group(2))), t)
    t = HEADING_ROMAN.sub(lambda m: str(roman(m.group(1) or m.group(3))) + (m.group(2) or m.group(4)), t)
    if lang == "en":
        t = REGNAL.sub(lambda m: m.group().rsplit(None, 1)[0] + " the " + ordinal(roman(m.group(1) or m.group(2) or m.group(3))), t)
        t = numbers_en(t)
    elif lang in RANGE_WORD:
        t = re.sub(r"(?<![\d.,–-])(\d+)–(\d+)(?![\d–]|[.,]\d)", rf"\1 {RANGE_WORD[lang]} \2", t)
    return t

def speakable(t, lang="en", spoken=()):
    t = SPACED_ELLIPSIS.sub("…", t)
    t = FOOTNOTE_MARK.sub(r"\1", t)
    t = DOUBLE_HYPHEN.sub(" — ", t)
    for r, w in spoken: t = r.sub(w, t)
    return normalise(t, lang)
