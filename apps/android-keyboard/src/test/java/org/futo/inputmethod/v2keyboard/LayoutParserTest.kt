package org.futo.inputmethod.v2keyboard

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class LayoutParserTest {
    @Test
    fun `bundled layouts with scalar keys and aliases still parse`() {
        val layouts = mapOf("Default/qwerty.yaml" to "QWERTY", "Special/number.yaml" to "Numbers")
        layouts.forEach { (path, name) ->
            val keyboard = parseKeyboardYamlString(File("java/assets/layouts/$path").readText())
            assertEquals(path, name, keyboard.name)
        }
    }

    @Test
    fun `custom layouts can still use more than one hundred aliases`() {
        val header = "name: Aliases\nattributes: &shared {width: Regular}\nrows:\n"
        val aliases = List(101) { "  - letters: a b c\n    attributes: *shared\n" }.joinToString("")
        val expanded = aliases.replace("*shared", "{width: Regular}")

        assertEquals(parseKeyboardYamlString(header + expanded), parseKeyboardYamlString(header + aliases))
    }
}
