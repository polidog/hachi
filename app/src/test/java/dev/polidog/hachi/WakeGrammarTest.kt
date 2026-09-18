package dev.polidog.hachi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The one thing between a name typed into Settings and Julius hearing it. */
class WakeGrammarTest {
    @Test fun `a name in either kana reads the same`() {
        assertEquals("m i r a", WakeGrammar.phones("ミラ"))
        assertEquals("m i r a", WakeGrammar.phones(WakeGrammar.katakana("みら")!!))
        assertEquals("h a ch i", WakeGrammar.phones("ハチ"))
    }

    @Test fun `the awkward readings`() {
        assertEquals("sh i", WakeGrammar.phones("シ"))          // not "s i"
        assertEquals("f u", WakeGrammar.phones("フ"))            // not "h u"
        assertEquals("ts u", WakeGrammar.phones("ツ"))
        assertEquals("j a N", WakeGrammar.phones("ジャン"))       // a digraph, then a moraic n
        assertEquals("ky o: t o", WakeGrammar.phones("キョート")) // a digraph, then a held vowel
        assertEquals("s a q k a:", WakeGrammar.phones("サッカー"))
    }

    @Test fun `a long mark with no vowel in front of it is not a reading`() {
        assertNull(WakeGrammar.phones("ーミラ"))
        assertNull(WakeGrammar.phones("ンー"))
    }

    @Test fun `a name it cannot pronounce has no grammar at all`() {
        assertNull(WakeGrammar.dict("Mira"))
        assertNull(WakeGrammar.dict("未来"))
        assertNull(WakeGrammar.dict(""))
        assertNull(WakeGrammar.dict("  "))
    }

    @Test fun `separators in a written name are not sounds`() {
        assertEquals("ミラ", WakeGrammar.katakana("み・ら"))
        assertEquals("ハーイ", WakeGrammar.katakana(" はーい "))
    }

    @Test fun `the dictionary is a greeting and then the name, in every category the automaton knows`() {
        val dict = WakeGrammar.dict("みら")!!
        val lines = dict.trim().lines()
        assertEquals("0\t[<s>]\tsilB", lines[0])
        assertEquals("1\t[</s>]\tsilE", lines[1])

        val wake = lines.filter { it.startsWith("2\t") }
        // Three ways of saying the greeting, each with the name held or not.
        assertEquals(6, wake.size)
        assertTrue(wake.contains("2\t[ハーイミラ]\th a: i m i r a"))
        assertTrue(wake.contains("2\t[ハーイミラ]\th e: i m i r a:"))
        // Every pronunciation answers to the one label, so recognising it is one comparison.
        assertTrue(wake.all { it.startsWith("2\t[${WakeGrammar.word("みら")}]\t") })
        // The name never stands alone: that is what keeps the room from waking it.
        assertTrue(wake.none { it.substringAfterLast('\t') == "m i r a" })

        assertEquals(40, lines.count { it.startsWith("3\t[<garbage>]\t") })
        assertEquals(2 + 6 + 40, lines.size)
    }

    @Test fun `a name already ending in a long vowel is not lengthened twice`() {
        val wake = WakeGrammar.dict("ミラー")!!.lines().filter { it.startsWith("2\t") }
        assertEquals(3, wake.size) // nothing left to hold, so one reading per greeting
        assertTrue(wake.contains("2\t[ハーイミラー]\th a: i m i r a:"))
    }
}
