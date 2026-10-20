package org.futo.inputmethod.latin.openwispr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DictationSpliceTest {
    private val plain: (String, String) -> String = { _, _ -> "hello" }

    @Test
    fun insertsAtCursor() {
        val splice = spliceDictation("ab cd", 3, 3, "Hello", plain)!!
        assertEquals("ab hellocd", splice.newText)
        assertEquals(8, splice.cursor)
    }

    @Test
    fun replacesSelectionInEitherDirection() {
        assertEquals("a hello d", spliceDictation("a bc d", 4, 2, "x", plain)!!.newText)
    }

    @Test
    fun appendsWhenSelectionIsUnknownOrStale() {
        assertEquals("abhello", spliceDictation("ab", -1, -1, "x", plain)!!.newText)
        assertEquals("abhello", spliceDictation("ab", 1, 9, "x", plain)!!.newText)
    }

    @Test
    fun givesSanitizerTheTextAroundTheCursor() {
        var seen: Pair<String, String>? = null
        spliceDictation("one two", 3, 3, "x") { before, after -> seen = before to after; "y" }
        assertEquals("one" to " two", seen)
    }

    @Test
    fun skipsBlankResults() {
        assertNull(spliceDictation("ab", 0, 0, "  ", plain))
        assertNull(spliceDictation("ab", 0, 0, "x") { _, _ -> " " })
    }

    @Test
    fun undoRemovesDictationStillInPlace() {
        val splice = spliceDictation("ab cd", 3, 3, "x", plain)!!
        assertEquals("ab cd", removeDictation(splice.newText, splice.cursor - splice.inserted.length, splice.inserted))
    }

    @Test
    fun undoRefusesEditedOrMovedDictation() {
        assertNull(removeDictation("ab helLo cd", 3, "hello"))
        assertNull(removeDictation("hello", 2, "hello"))
        assertNull(removeDictation("hello", -1, "hello"))
        assertNull(removeDictation("hello", 0, ""))
    }
}
