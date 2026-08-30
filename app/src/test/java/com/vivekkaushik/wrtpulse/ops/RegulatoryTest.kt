package com.vivekkaushik.wrtpulse.ops

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun radio(
    section: String,
    channel: String = "auto",
    country: String = "",
    band: String = "2.4G",
) = WifiRadio(section, band, channel, "HE40", disabled = false, country = country)

class RegDomainListTest {

    @Test
    fun `the list comes from the JVM's ISO table, not a literal here`() {
        val codes = Regulatory.countries().map { it.code }
        assertTrue(codes.size > 200)
        assertTrue("IN" in codes)
        assertTrue("US" in codes)
        assertTrue("JP" in codes)
    }

    /** The safe fallback has to be reachable, and first, or nobody finds it. */
    @Test
    fun `the world domain leads the list`() {
        assertEquals(Regulatory.WORLD, Regulatory.countries().first().code)
    }

    @Test
    fun `codes resolve to readable names`() {
        assertEquals("India", Regulatory.nameOf("IN"))
        assertEquals("Japan", Regulatory.nameOf("JP"))
    }

    @Test
    fun `an unknown code falls back to itself rather than to blank`() {
        // ZZ is not a good example: CLDR knows it and calls it "Unknown Region".
        assertEquals("QQ", Regulatory.nameOf("QQ"))
    }

    /** A junk value stored on the router must not take the screen down with it. */
    @Test
    fun `a malformed code is named, not thrown`() {
        assertEquals("IND", Regulatory.nameOf("IND"))
        assertEquals("", Regulatory.nameOf(""))
    }

    @Test
    fun `the world domain never reaches the locale lookup, which rejects it`() {
        assertEquals("World (most restrictive)", Regulatory.nameOf(Regulatory.WORLD))
    }

    @Test
    fun `search matches both the name and the code`() {
        assertTrue(Regulatory.search("india").any { it.code == "IN" })
        assertTrue(Regulatory.search("in").any { it.code == "IN" })
        assertTrue(Regulatory.search("JP").any { it.code == "JP" })
        assertTrue(Regulatory.search("zzzzz").isEmpty())
    }

    @Test
    fun `an empty search is the whole list, not nothing`() {
        assertEquals(Regulatory.countries().size, Regulatory.search("   ").size)
    }

    /** uci takes two characters; anything else does not survive `wifi reload`. */
    @Test
    fun `only two-letter codes and the world domain are valid`() {
        assertTrue(Regulatory.isValidCode("IN"))
        assertTrue(Regulatory.isValidCode(Regulatory.WORLD))
        assertFalse(Regulatory.isValidCode("in"))
        assertFalse(Regulatory.isValidCode("IND"))
        assertFalse(Regulatory.isValidCode(""))
    }
}

class RadioDomainTest {

    @Test
    fun `radios that agree report one domain`() {
        assertEquals("IN", Regulatory.current(listOf(radio("radio0", country = "IN"), radio("radio1", country = "IN"))))
        assertFalse(Regulatory.disagree(listOf(radio("radio0", country = "IN"))))
    }

    /** One router stands in one country; radios disagreeing is worth surfacing. */
    @Test
    fun `radios that disagree report no single domain`() {
        val mixed = listOf(radio("radio0", country = "IN"), radio("radio1", country = "US"))
        assertNull(Regulatory.current(mixed))
        assertTrue(Regulatory.disagree(mixed))
    }

    @Test
    fun `an unset domain is not mistaken for a disagreement`() {
        val none = listOf(radio("radio0"), radio("radio1"))
        assertNull(Regulatory.current(none))
        assertFalse(Regulatory.disagree(none))
    }

    @Test
    fun `a blank alongside a set value still reads as that value`() {
        assertEquals("IN", Regulatory.current(listOf(radio("radio0", country = "IN"), radio("radio1"))))
    }

    /**
     * The reference router's actual state: radio0 IN, radio1 unset. Not a disagreement, but
     * the unset radio quietly runs on the world domain.
     */
    @Test
    fun `one radio set and one unset is called out as partial`() {
        val partial = listOf(radio("radio0", country = "IN"), radio("radio1"))
        assertTrue(Regulatory.partiallySet(partial))
        assertFalse(Regulatory.disagree(partial))
    }

    @Test
    fun `all set or none set is not partial`() {
        assertFalse(Regulatory.partiallySet(listOf(radio("radio0", country = "IN"), radio("radio1", country = "IN"))))
        assertFalse(Regulatory.partiallySet(listOf(radio("radio0"), radio("radio1"))))
        assertFalse(Regulatory.partiallySet(emptyList()))
    }
}

class RegWarningTest {

    @Test
    fun `a radio on auto is not warned about a channel it does not have`() {
        val warnings = Regulatory.warnings("US", listOf(radio("radio0", channel = "auto")))
        assertFalse(warnings.any { it.contains("pinned") })
    }

    /** Channels 12 and 13 are unusable in the US and Canada — one of the few certainties. */
    @Test
    fun `channel 13 under a US domain is named as disallowed, not merely doubted`() {
        val warning = Regulatory.warnings("US", listOf(radio("radio0", channel = "13")))
            .first { it.contains("radio0") }
        assertTrue(warning.contains("does not allow"))
        assertTrue(warning.contains("United States"))
    }

    @Test
    fun `the same channel elsewhere gets the softer, honest warning`() {
        val warning = Regulatory.warnings("IN", listOf(radio("radio0", channel = "13")))
            .first { it.contains("radio0") }
        assertTrue(warning.contains("If India does not allow it"))
    }

    @Test
    fun `channel 14 is called out everywhere except Japan`() {
        assertTrue(
            Regulatory.warnings("IN", listOf(radio("radio0", channel = "14")))
                .any { it.contains("only Japan") }
        )
        assertFalse(
            Regulatory.warnings("JP", listOf(radio("radio0", channel = "14")))
                .any { it.contains("only Japan") }
        )
    }

    @Test
    fun `the world domain says it is the restrictive one`() {
        assertTrue(
            Regulatory.warnings(Regulatory.WORLD, emptyList())
                .any { it.contains("most restrictive") }
        )
    }

    /**
     * The app has no channel-by-channel legal database, and saying so is the difference
     * between honest guidance and a table that looks authoritative while being wrong.
     */
    @Test
    fun `every domain change admits the router is the authority`() {
        listOf("IN", "US", "JP", Regulatory.WORLD).forEach { code ->
            val warnings = Regulatory.warnings(code, listOf(radio("radio0")))
            assertTrue(code, warnings.any { it.contains("does not carry a channel-by-channel") })
            assertTrue(code, warnings.any { it.contains("transmit power") })
        }
    }
}
