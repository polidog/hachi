package dev.polidog.hachi

/**
 * Reads Julius's module output and decides whether the wake phrase was actually said.
 *
 * Julius writes one `<RECOGOUT>` block per segment of speech, terminated by a line holding a single
 * `.`, with one `<WHYPO .../>` line per decoded word:
 * ```
 * <RECOGOUT>
 *   <SHYPO RANK="1" SCORE="-4188.459473">
 *     <WHYPO WORD="ハーイミラ" CLASSID="2" PHONE="h a: i m i r a" CM="0.078"/>
 *     <WHYPO WORD="<garbage>" CLASSID="3" PHONE="a" CM="0.234"/>
 *   </SHYPO>
 * </RECOGOUT>
 * .
 * ```
 * The protocol's `<TAG attr="value"/>` lines are simple enough for a regex; no XML parser, no Android
 * imports, so this is unit-testable on the JVM (see `JuliusWakeTest`).
 *
 * A confidence score on the wake word alone does not separate real calls from false ones -- butler's
 * offline sweep found the two ranges overlap. Their *shape* does: a real call is a short standalone
 * utterance with few filler words around it, while a false one sits inside a running sentence with
 * many. So a whole block is judged at once, on the confidence of the wake word AND the number of
 * `<garbage>` words that came with it.
 *
 * Feed it every line from the module socket, in order. It returns true on the one line that closes a
 * hitting block. Not thread safe: one instance, one reader thread.
 */
class JuliusWake(
    private val wakeWord: String,
    private val threshold: Double,
    private val maxFillers: Int,
) {
    private var inBlock = false
    private var fillers = 0
    private var hit = false

    fun feed(line: String): Boolean {
        val trimmed = line.trim()
        when {
            // A new block starts: anything half-read from a malformed one is dropped, not carried over.
            trimmed.startsWith("<RECOGOUT") -> { inBlock = true; fillers = 0; hit = false }
            trimmed == "." -> {
                val woken = inBlock && hit && fillers <= maxFillers
                inBlock = false; fillers = 0; hit = false
                return woken
            }
            inBlock && trimmed.startsWith("<WHYPO") -> {
                val attrs = ATTR.findAll(trimmed).associate { it.groupValues[1] to it.groupValues[2] }
                if (attrs["WORD"] == "<garbage>") fillers++
                if (attrs["WORD"] == wakeWord) {
                    // Julius writes CM="-" when it did not compute a confidence for that word; that is
                    // not a confident hit, so a value that will not parse counts as no hit at all.
                    val cm = attrs["CM"]?.toDoubleOrNull()
                    if (cm != null && cm >= threshold) hit = true
                }
            }
        }
        return false
    }

    companion object {
        private val ATTR = Regex("""(\w+)="([^"]*)"""")

        /**
         * Confidence a wake word has to reach, and fillers it is allowed to bring.
         *
         * Both come from butler's offline sweeps against ~92 minutes of unrelated Japanese speech:
         * genuine hits score 0.04-0.14 and 0.05 (Julius's own -cmalpha default) separated them
         * cleanly, and every false wake seen carried 11 or more fillers while every genuine one
         * carried 6 or fewer. ponytail: these were tuned for butler's phrase, not this one -- if the
         * house starts waking at nothing, raise the threshold before touching anything else.
         */
        const val THRESHOLD = 0.05
        const val MAX_FILLERS = 10
    }
}
