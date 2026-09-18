package dev.polidog.hachi

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Judging a block of Julius's module output: confident enough, and not buried in running speech. */
class JuliusWakeTest {
    private fun parser() = JuliusWake("ハーイミラ", JuliusWake.THRESHOLD, JuliusWake.MAX_FILLERS)

    /** Feeds a whole block and reports what the terminator line said. */
    private fun block(vararg words: Pair<String, String>): Boolean {
        val p = parser()
        assertFalse(p.feed("<RECOGOUT>"))
        assertFalse(p.feed("""  <SHYPO RANK="1" SCORE="-4188.459473">"""))
        for ((word, cm) in words) {
            assertFalse(p.feed("""    <WHYPO WORD="$word" CLASSID="2" PHONE="h a: i" CM="$cm"/>"""))
        }
        assertFalse(p.feed("  </SHYPO>"))
        assertFalse(p.feed("</RECOGOUT>"))
        return p.feed(".")
    }

    private fun fillers(n: Int) = Array(n) { "<garbage>" to "0.234" }

    @Test fun `a confident, standalone hit wakes it`() {
        assertTrue(block("ハーイミラ" to "0.078"))
        assertTrue(block("ハーイミラ" to "0.078", *fillers(6)))
    }

    @Test fun `too unsure does not`() {
        assertFalse(block("ハーイミラ" to "0.01"))
    }

    /** Julius writes CM="-" when it did not score that word at all. */
    @Test fun `an unscored word does not`() {
        assertFalse(block("ハーイミラ" to "-"))
    }

    @Test fun `buried in a long run of speech does not`() {
        assertFalse(block("ハーイミラ" to "0.5", *fillers(11)))
    }

    @Test fun `some other word does not`() {
        assertFalse(block("ハーイハチ" to "0.9"))
        assertFalse(block(*fillers(2)))
    }

    @Test fun `status lines between blocks are not blocks`() {
        val p = parser()
        assertFalse(p.feed("""<INPUT STATUS="LISTEN" TIME="1758000000"/>"""))
        assertFalse(p.feed("."))
        assertFalse(p.feed("""    <WHYPO WORD="ハーイミラ" CLASSID="2" PHONE="h a: i" CM="0.9"/>"""))
        assertFalse(p.feed("."))
    }

    @Test fun `an unterminated block does not leak into the next one`() {
        val p = parser()
        p.feed("<RECOGOUT>")
        p.feed("""    <WHYPO WORD="ハーイミラ" CLASSID="2" PHONE="h a: i" CM="0.9"/>""")
        p.feed("<RECOGOUT>") // cut off; a new block starts
        p.feed("""    <WHYPO WORD="<garbage>" CLASSID="3" PHONE="a" CM="0.3"/>""")
        assertFalse(p.feed("."))
    }
}
