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
    /** Fillers heard before the wake word; the rest came after it. */
    private var leading = 0

    /**
     * Whether the last block that woke it carried words after the name -- "はーいミラ、電気消して" in one
     * breath, a request rather than just a call. Read right after [feed] returns true.
     */
    var followed = false
        private set

    fun feed(line: String): Boolean {
        val trimmed = line.trim()
        when {
            // A new block starts: anything half-read from a malformed one is dropped, not carried over.
            trimmed.startsWith("<RECOGOUT") -> { inBlock = true; fillers = 0; leading = 0; hit = false }
            trimmed == "." -> {
                // A false wake is the name inside running speech, so it is what came *before* the name
                // that counts against it. A name that opens the utterance may be followed by a whole
                // request, and that request is not speech the name is buried in.
                val woken = inBlock && hit && (fillers <= maxFillers || leading <= MAX_LEADING)
                followed = woken && fillers - leading >= FOLLOWED_BY
                inBlock = false; fillers = 0; leading = 0; hit = false
                return woken
            }
            inBlock && trimmed.startsWith("<WHYPO") -> {
                val attrs = ATTR.findAll(trimmed).associate { it.groupValues[1] to it.groupValues[2] }
                if (attrs["WORD"] == "<garbage>") { fillers++; if (!hit) leading++ }
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

        /**
         * Fillers a name may carry in front of it and still open the utterance, and fillers after it
         * that make it a request. ponytail: guesses, not sweeps -- a held "ミラー" decodes as a filler
         * or two, so a request needs a few more than that. If the house wakes at sentences that happen
         * to start with the name, lower [MAX_LEADING] to 0 first.
         */
        const val MAX_LEADING = 2
        const val FOLLOWED_BY = 4
    }
}
