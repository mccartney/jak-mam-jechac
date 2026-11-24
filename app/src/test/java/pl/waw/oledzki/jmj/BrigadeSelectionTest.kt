// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Grzegorz Olędzki

package pl.waw.oledzki.jmj

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

class BrigadeSelectionTest {

    @Test
    fun `day-type follows the day of the week`() {
        // Mon 16 Nov 2026 .. Sun 22 Nov 2026
        val codes = (16..22).map { ServiceDay.of(LocalDate.of(2026, 11, it)) }
        assertEquals(
            listOf(ServiceDay.PcS, ServiceDay.PcS, ServiceDay.PcS, ServiceDay.PcS, ServiceDay.PtS, ServiceDay.SbS, ServiceDay.NdS),
            codes,
        )
    }

    @Test
    fun `lines sort numerically first, then alphanumeric ones alphabetically`() {
        val lines = listOf("N01", "504", "10", "2", "E-1", "100").map { LineInfo(it, "", 3, emptyMap()) }
        assertEquals(listOf("2", "10", "100", "504", "E-1", "N01"), lines.sortedWith(lineOrder).map { it.line })
    }

    @Test
    fun `line search ignores dashes and spaces in line numbers`() {
        val l3 = LineInfo("L-3", "Dworzec - Centrum", 3, emptyMap())
        listOf("l3", "L-3", "l 3", "L-", "centrum").forEach { assertTrue(l3.matches(it), it) }
        listOf("l4", "3").forEach { assertFalse(l3.matches(it), it) }
        assertTrue(l3.matches("-"))   // a lone dash still matches names containing one
        assertFalse(LineInfo("504", "Wilanów", 3, emptyMap()).matches("-"))
    }

    @Test
    fun `line search ignores Polish letters both ways`() {
        val bialoleka = LineInfo("120", "Białołęka - Żerań", 3, emptyMap())
        listOf("bialo", "BIAŁO", "leka", "zeran", "Żerań").forEach { assertTrue(bialoleka.matches(it), it) }
        assertTrue(LineInfo("504", "Wilanow", 3, emptyMap()).matches("wilanów"))
    }

    @Test
    fun `match reports the hit ranges in the original text`() {
        val l3 = LineInfo("L-3", "Dworzec - Centrum", 3, emptyMap())
        assertEquals(LineMatch(0..2, emptyList()), l3.match("l3"))    // spans the dash
        assertEquals(LineMatch(0..0, emptyList()), l3.match("l"))
        assertEquals(LineMatch(null, listOf(10..12)), l3.match("cen"))
        assertEquals(LineMatch(null, listOf(0..4)), LineInfo("120", "Białołęka", 3, emptyMap()).match("biało"))
        assertEquals(null, l3.match("x"))
    }

    @Test
    fun `name search matches each word on its own, in any order`() {
        val pl = LineInfo("108", "Pl. Trzech Krzyży – Stegny", 3, emptyMap())
        listOf("pl trz", "trz pl", "Pl. Trzech", "trzech  krzyzy", "krzy steg").forEach { assertTrue(pl.matches(it), it) }
        listOf("pl wil", "trzechk").forEach { assertFalse(pl.matches(it), it) }
        assertEquals(LineMatch(null, listOf(0..1, 4..6)), pl.match("pl trz"))
    }

    @Test
    fun `full words find the abbreviations in names`() {
        val pl = LineInfo("108", "Pl. Trzech Krzyży – Stegny", 3, emptyMap())
        listOf("plac", "plac trzech", "pla trz").forEach { assertTrue(pl.matches(it), it) }
        assertEquals(LineMatch(null, listOf(0..2)), pl.match("plac"))
        val dw = LineInfo("127", "Metro Młociny – Dw. Zachodni (Tunelowa)", 3, emptyMap())
        listOf("dworzec zach", "dworzec", "metro dworz").forEach { assertTrue(dw.matches(it), it) }
        assertFalse(dw.matches("osiedle"))
        assertTrue(LineInfo("1", "Os. Górczewska", 3, emptyMap()).matches("osiedle gorcz"))
        assertTrue(LineInfo("1", "Al. Jerozolimskie", 3, emptyMap()).matches("aleje jerozolimskie"))
        assertFalse(LineInfo("1", "Kanał.", 3, emptyMap()).matches("aleja"))   // "ał." isn't a whole word
    }

    @Test
    fun `brigades sort numerically, zero-padded ones next to their value`() {
        assertEquals(
            listOf("01", "1", "2", "10", "A", "B1"),
            listOf("B1", "10", "2", "01", "A", "1").sortedWith(brigadeOrder),
        )
    }
}
