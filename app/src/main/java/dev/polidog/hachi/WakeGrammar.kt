package dev.polidog.hachi

/**
 * Turns the name Hachi has been given into the dictionary half of the Julius wake grammar.
 *
 * The automaton (`wake.dfa`, bundled) only knows about four word categories -- start silence, end
 * silence, the wake word, and a filler -- and never names any of them. Every phone sequence lives in
 * the dictionary, so the phrase can be rewritten at run time from the name in Settings without
 * rebuilding anything: [dict] is written over the unpacked `wake.dict` each time the listener starts.
 *
 * The phrase is a greeting and then the name, never the name alone. A two-mora name on its own is
 * shorter than most of the words a room says all day and wakes on half of them; butler learned the
 * same thing the expensive way with a short English phrase. Three pronunciations of the greeting are
 * accepted, because "はーい" said quickly is "はい" and said in English is "hey".
 *
 * No Android imports, so the whole thing is unit-testable on the JVM (see `WakeGrammarTest`).
 */
object WakeGrammar {
    /** "はーい" as Julius hears it: drawn out, clipped, and as the English "hey". */
    private val GREETINGS = listOf("h a: i" to "ハーイ", "h a i" to "ハーイ", "h e: i" to "ヘイ")

    /** Everything the phone loop is allowed to decode the rest of a sentence as. */
    private val FILLERS = (
        "a a: b by ch d dy e e: f g gy h hy i i: j k ky m my n N ny o o: p py q r ry s sh t ts " +
            "u u: w y z"
        ).split(" ")

    /**
     * What Julius will call the wake word in its output, e.g. "ハーイミラ".
     *
     * Every pronunciation shares the one label, so recognising the phrase is a single string
     * comparison however many ways of saying it the dictionary lists.
     */
    fun word(name: String): String? = katakana(name)?.let { "ハーイ$it" }

    /**
     * The whole `wake.dict`, or null when the name cannot be pronounced.
     *
     * A name written in kanji or in the Latin alphabet has no reading this can work out, and guessing
     * one would wake the house at the wrong words. The caller is expected to say so and stay asleep
     * rather than fall back to some other phrase.
     */
    fun dict(name: String): String? {
        val kana = katakana(name) ?: return null
        val phones = phones(kana) ?: return null
        if (phones.isEmpty()) return null
        val label = "ハーイ$kana"
        // Also allow the last vowel to be held -- "みらー" is how a name gets called across a room.
        val endings = listOf(phones, lengthened(phones)).distinct()
        val wake = GREETINGS.flatMap { (greeting, _) -> endings.map { "2\t[$label]\t$greeting $it" } }
        val fillers = FILLERS.map { "3\t[<garbage>]\t$it" }
        return (listOf("0\t[<s>]\tsilB", "1\t[</s>]\tsilE") + wake.distinct() + fillers)
            .joinToString("\n", postfix = "\n")
    }

    /** Holds the final vowel, if the reading ends in one: `m i r a` becomes `m i r a:`. */
    private fun lengthened(phones: String): String {
        val last = phones.substringAfterLast(' ')
        return if (last in VOWELS) "$phones:" else phones
    }

    /** Hiragana to katakana, dropping the separators a written name picks up. Null if nothing is left. */
    fun katakana(name: String): String? {
        val out = StringBuilder()
        for (c in name.trim()) {
            when {
                c in 'ぁ'..'ゖ' -> out.append(c + 0x60)
                c in 'ァ'..'ヺ' || c == 'ー' -> out.append(c)
                c == '、' || c == '・' || c == ' ' || c == '　' -> Unit
                else -> return null
            }
        }
        return out.toString().ifEmpty { null }
    }

    /**
     * Katakana to the Dictation Kit's phone set, or null at the first thing it cannot say.
     *
     * Two-character readings are tried before one, so ミ+ャ is one sound and not two.
     */
    fun phones(kana: String): String? {
        val out = mutableListOf<String>()
        var i = 0
        while (i < kana.length) {
            val pair = if (i + 1 < kana.length) kana.substring(i, i + 2) else null
            val two = pair?.let { DIGRAPHS[it] }
            when {
                two != null -> { out += two.split(" "); i += 2 }
                kana[i] == 'ー' -> {
                    // A long mark holds the vowel before it, and there has to be one.
                    val last = out.lastOrNull() ?: return null
                    if (last !in VOWELS) return null
                    out[out.size - 1] = "$last:"
                    i++
                }
                else -> {
                    val one = SINGLES[kana[i].toString()] ?: return null
                    out += one.split(" ")
                    i++
                }
            }
        }
        return out.joinToString(" ")
    }

    private val VOWELS = setOf("a", "i", "u", "e", "o")

    private val DIGRAPHS = mapOf(
        "キャ" to "ky a", "キュ" to "ky u", "キョ" to "ky o",
        "シャ" to "sh a", "シュ" to "sh u", "ショ" to "sh o", "シェ" to "sh e",
        "チャ" to "ch a", "チュ" to "ch u", "チョ" to "ch o", "チェ" to "ch e",
        "ニャ" to "ny a", "ニュ" to "ny u", "ニョ" to "ny o",
        "ヒャ" to "hy a", "ヒュ" to "hy u", "ヒョ" to "hy o",
        "ミャ" to "my a", "ミュ" to "my u", "ミョ" to "my o",
        "リャ" to "ry a", "リュ" to "ry u", "リョ" to "ry o",
        "ギャ" to "gy a", "ギュ" to "gy u", "ギョ" to "gy o",
        "ジャ" to "j a", "ジュ" to "j u", "ジョ" to "j o", "ジェ" to "j e",
        "ビャ" to "by a", "ビュ" to "by u", "ビョ" to "by o",
        "ピャ" to "py a", "ピュ" to "py u", "ピョ" to "py o",
        "ファ" to "f a", "フィ" to "f i", "フェ" to "f e", "フォ" to "f o",
        "ティ" to "t i", "トゥ" to "t u", "ディ" to "d i", "ドゥ" to "d u",
        "ウィ" to "w i", "ウェ" to "w e", "ウォ" to "w o",
        "ツァ" to "ts a", "ツィ" to "ts i", "ツェ" to "ts e", "ツォ" to "ts o",
        "ヴァ" to "b a", "ヴィ" to "b i", "ヴェ" to "b e", "ヴォ" to "b o",
    )

    private val SINGLES = mapOf(
        "ア" to "a", "イ" to "i", "ウ" to "u", "エ" to "e", "オ" to "o",
        "ァ" to "a", "ィ" to "i", "ゥ" to "u", "ェ" to "e", "ォ" to "o",
        "カ" to "k a", "キ" to "k i", "ク" to "k u", "ケ" to "k e", "コ" to "k o",
        "サ" to "s a", "シ" to "sh i", "ス" to "s u", "セ" to "s e", "ソ" to "s o",
        "タ" to "t a", "チ" to "ch i", "ツ" to "ts u", "テ" to "t e", "ト" to "t o",
        "ナ" to "n a", "ニ" to "n i", "ヌ" to "n u", "ネ" to "n e", "ノ" to "n o",
        "ハ" to "h a", "ヒ" to "h i", "フ" to "f u", "ヘ" to "h e", "ホ" to "h o",
        "マ" to "m a", "ミ" to "m i", "ム" to "m u", "メ" to "m e", "モ" to "m o",
        "ヤ" to "y a", "ユ" to "y u", "ヨ" to "y o",
        "ラ" to "r a", "リ" to "r i", "ル" to "r u", "レ" to "r e", "ロ" to "r o",
        "ワ" to "w a", "ヰ" to "i", "ヱ" to "e", "ヲ" to "o", "ン" to "N",
        "ガ" to "g a", "ギ" to "g i", "グ" to "g u", "ゲ" to "g e", "ゴ" to "g o",
        "ザ" to "z a", "ジ" to "j i", "ズ" to "z u", "ゼ" to "z e", "ゾ" to "z o",
        "ダ" to "d a", "ヂ" to "j i", "ヅ" to "z u", "デ" to "d e", "ド" to "d o",
        "バ" to "b a", "ビ" to "b i", "ブ" to "b u", "ベ" to "b e", "ボ" to "b o",
        "パ" to "p a", "ピ" to "p i", "プ" to "p u", "ペ" to "p e", "ポ" to "p o",
        "ヴ" to "b u", "ッ" to "q",
    )
}
