// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Grzegorz Olędzki

package pl.waw.oledzki.jmj

import org.junit.jupiter.api.Assertions.assertEquals
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
    fun `brigades sort numerically, zero-padded ones next to their value`() {
        assertEquals(
            listOf("01", "1", "2", "10", "A", "B1"),
            listOf("B1", "10", "2", "01", "A", "1").sortedWith(brigadeOrder),
        )
    }
}
