package com.vivekkaushik.wrtpulse.data

import org.junit.Assert.assertEquals
import org.junit.Test

class FakeUciTest {

    /**
     * Lists and emptied options, as uci treats them. The expected text is what OpenWrt 25.12.5's
     * own `uci show` printed after the same batch against the same section.
     */
    @Test
    fun `lists and emptied options come out as uci shows them`() {
        val uci = FakeUci(
            "t",
            listOf(FakeUci.Section("a", "test", "a", mapOf("x" to "1", "s" to "one", "z" to "9"), mapOf("l2" to listOf("p", "q")))),
        )
        uci.run(
            listOf(
                "add_list t.a.s='two'", // onto a string: a list of the two, where the string was
                "add_list t.a.l='first'", // onto nothing: a new list at the end
                "add_list t.a.l='it'\\''s'",
                "set t.a.x=''", // set to nothing: gone
                "set t.a.l2='now a string'", // a list set to a string, in its place
            )
        )
        assertEquals(
            """
            t.a=test
            t.a.s='one' 'two'
            t.a.z='9'
            t.a.l2='now a string'
            t.a.l='first' 'it'\''s'
            """.trimIndent() + "\n",
            uci.show(),
        )
        val a = uci.sections.single()
        assertEquals(mapOf("z" to "9", "l2" to "now a string"), a.options)
        assertEquals(mapOf("s" to listOf("one", "two"), "l" to listOf("first", "it's")), a.lists)
    }
}
